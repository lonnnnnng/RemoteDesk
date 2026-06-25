package transport

import (
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"

	"remote_desk/apps/server/internal/auth"
	"remote_desk/apps/server/internal/config"
	"remote_desk/apps/server/internal/observability"
	"remote_desk/apps/server/internal/presence"
	"remote_desk/apps/server/internal/protocol"
	"remote_desk/apps/server/internal/session"
	"remote_desk/apps/server/internal/store"
)

type clientConn struct {
	conn *websocket.Conn
	mu   sync.Mutex
}

const (
	sessionMetricsRetentionWindow = 3 * time.Minute
	qualityHintFpsLowThreshold    = 10.0
	qualityHintStallFpsThreshold  = 16.0
	qualityHintBitrateLowKbps     = 350.0
	qualityHintCanvasHeavyPct     = 80.0
	qualityHintRttHighMs          = 220.0
	qualityHintFrameGapSpikeMs    = 1000.0
	qualityHintLowFpsStreakMs     = 6000.0
	qualityHintDroppedFrameSpike  = 12.0
)

var targetE2ERoutes = []struct {
	key   string
	label string
}{
	{key: "android_to_windows", label: "android->windows"},
	{key: "android_to_macos", label: "android->macos"},
	{key: "windows_to_windows", label: "windows->windows"},
	{key: "windows_to_macos", label: "windows->macos"},
}

var requiredRemoteInputCategories = []string{"click", "drag", "keyboard", "wheel"}

type endedSessionRecord struct {
	session store.Session
	endedAt time.Time
}

type sessionMetricsAggregate struct {
	controllerReport   map[string]any
	agentReport        map[string]any
	controllerDeviceID string
	agentDeviceID      string
	updatedAt          time.Time
}

type sessionE2EProofRecord struct {
	RouteKey            string    `json:"route_key"`
	Route               string    `json:"route"`
	TargetRoute         bool      `json:"target_route"`
	ProofStatus         string    `json:"proof_status"`
	VideoObserved       bool      `json:"video_observed"`
	InputObserved       bool      `json:"input_observed"`
	SessionID           string    `json:"session_id"`
	ControllerDeviceID  string    `json:"controller_device_id"`
	AgentDeviceID       string    `json:"agent_device_id"`
	ControllerPlatform  string    `json:"controller_platform,omitempty"`
	AgentPlatform       string    `json:"agent_platform,omitempty"`
	FirstFrameMS        float64   `json:"first_frame_ms,omitempty"`
	RenderedFrames      float64   `json:"rendered_frames,omitempty"`
	RemoteInputApplied  float64   `json:"remote_input_applied,omitempty"`
	RemoteInputTotal    float64   `json:"remote_input_total,omitempty"`
	RemoteInputExecutor string    `json:"remote_input_executor,omitempty"`
	RemoteInputStatus   string    `json:"remote_input_status,omitempty"`
	RemoteInputTraceID  string    `json:"remote_input_trace_id,omitempty"`
	RemoteInputCoverage []string  `json:"remote_input_coverage,omitempty"`
	SessionQualityHint  string    `json:"session_quality_hint,omitempty"`
	SessionProofSummary string    `json:"session_proof_summary,omitempty"`
	SessionPerfSummary  string    `json:"session_perf_summary,omitempty"`
	UpdatedAt           time.Time `json:"updated_at"`
}

type sessionE2EProofRouteState struct {
	RouteKey    string                 `json:"route_key"`
	Route       string                 `json:"route"`
	Status      string                 `json:"status"`
	Missing     []string               `json:"missing,omitempty"`
	NextAction  string                 `json:"next_action,omitempty"`
	Complete    bool                   `json:"complete"`
	Latest      *sessionE2EProofRecord `json:"latest,omitempty"`
	LastSuccess *sessionE2EProofRecord `json:"last_success,omitempty"`
}

type sessionE2EProofSnapshot struct {
	Event                string                      `json:"event"`
	Complete             bool                        `json:"complete"`
	TargetRoutesComplete int                         `json:"target_routes_complete"`
	TargetRoutesTotal    int                         `json:"target_routes_total"`
	Routes               []sessionE2EProofRouteState `json:"routes"`
}

type Hub struct {
	cfg      config.Config
	logger   *observability.Logger
	registry *presence.Registry
	sessions *store.Store

	mu      sync.RWMutex
	clients map[string]*clientConn

	metricsMu      sync.Mutex
	endedSessions  map[string]endedSessionRecord
	sessionMetrics map[string]*sessionMetricsAggregate
	e2eLatest      map[string]sessionE2EProofRecord
	e2eSuccess     map[string]sessionE2EProofRecord
}

func NewHub(cfg config.Config, logger *observability.Logger, registry *presence.Registry, sessions *store.Store) *Hub {
	return &Hub{
		cfg:            cfg,
		logger:         logger,
		registry:       registry,
		sessions:       sessions,
		clients:        make(map[string]*clientConn),
		endedSessions:  make(map[string]endedSessionRecord),
		sessionMetrics: make(map[string]*sessionMetricsAggregate),
		e2eLatest:      make(map[string]sessionE2EProofRecord),
		e2eSuccess:     make(map[string]sessionE2EProofRecord),
	}
}

func (h *Hub) HandleE2EProof(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Allow", "GET, POST, DELETE, OPTIONS")
	if origin := r.Header.Get("Origin"); origin != "" && config.IsOriginAllowed(r, h.cfg.AllowedOrigins) {
		w.Header().Set("Access-Control-Allow-Origin", origin)
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
		w.Header().Set("Vary", "Origin")
	}
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if r.Method != http.MethodGet && r.Method != http.MethodDelete && r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	if r.Method == http.MethodDelete || (r.Method == http.MethodPost && r.URL.Query().Get("reset") == "1") {
		h.resetE2EProof()
	}
	_ = json.NewEncoder(w).Encode(h.e2eProofSnapshot())
}

func (h *Hub) HandleWS(w http.ResponseWriter, r *http.Request) {
	upgrader := websocket.Upgrader{
		CheckOrigin: func(r *http.Request) bool {
			return config.IsOriginAllowed(r, h.cfg.AllowedOrigins)
		},
	}

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		h.logger.Error(map[string]any{"event": "ws.upgrade_failed", "error": err.Error()})
		return
	}
	client := &clientConn{conn: conn}
	var registeredDeviceID string
	defer func() {
		if registeredDeviceID != "" && h.unregisterClient(registeredDeviceID, client) {
			if _, changed := h.registry.MarkOffline(registeredDeviceID); changed {
				h.broadcastPresenceSnapshot("socket_disconnected", registeredDeviceID)
			}
		}
		conn.Close()
	}()

	h.logger.Info(map[string]any{"event": "ws.connected", "remote_addr": r.RemoteAddr})

	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			h.logger.Info(map[string]any{"event": "ws.disconnected", "error": err.Error(), "device_id": registeredDeviceID})
			return
		}

		msg, err := protocol.Decode(data)
		if err != nil {
			h.writeError(client, "", "", 9001, "INTERNAL_ERROR", "invalid json", "")
			continue
		}
		if !h.validateSocketIdentity(client, &registeredDeviceID, msg) {
			continue
		}

		h.logger.Info(map[string]any{
			"event":      "ws.message_received",
			"type":       msg.Type,
			"trace_id":   msg.TraceID,
			"msg_id":     msg.MessageID,
			"session_id": msg.SessionID,
			"device_id":  msg.From.DeviceID,
			"role":       msg.From.Role,
		})

		switch msg.Type {
		case "hello":
			h.writeEnvelope(client, protocol.Envelope{
				Version:   h.cfg.ProtocolVersion,
				MessageID: fmt.Sprintf("hello-%d", time.Now().UnixMilli()),
				Type:      "hello.rsp",
				Timestamp: time.Now().UnixMilli(),
				TraceID:   msg.TraceID,
				From:      protocol.From{DeviceID: "server", Role: "server"},
				Payload: map[string]any{
					"message": "remote_desk server ready",
				},
			})
		case "device.register.req":
			if !h.validateRegister(msg) {
				h.writeError(client, msg.TraceID, msg.MessageID, 4001, "INPUT_INVALID_PAYLOAD", "invalid device registration payload", msg.From.DeviceID)
				continue
			}
			userID, _ := msg.Payload["user_id"].(string)
			platform, _ := msg.Payload["platform"].(string)
			deviceName, _ := msg.Payload["device_name"].(string)
			capabilities := parseDeviceCapabilities(msg.Payload["capabilities"])
			token := fmt.Sprintf("stub-token-%s", msg.From.DeviceID)
			_, presenceChanged := h.registry.Upsert(presence.Device{
				DeviceID:     msg.From.DeviceID,
				DeviceName:   deviceName,
				UserID:       userID,
				Platform:     platform,
				Role:         msg.From.Role,
				Status:       "online",
				Capabilities: capabilities,
				ClientToken:  token,
			})
			registeredDeviceID = msg.From.DeviceID
			h.registerClient(msg.From.DeviceID, client)
			h.logger.Info(map[string]any{
				"event":             "device.registered",
				"device_id":         msg.From.DeviceID,
				"device_name":       deviceName,
				"platform":          platform,
				"role":              msg.From.Role,
				"user_id":           userID,
				"can_control":       capabilities.CanControl,
				"can_be_controlled": capabilities.CanBeControlled,
			})
			h.writeEnvelope(client, protocol.Envelope{
				Version:   h.cfg.ProtocolVersion,
				MessageID: fmt.Sprintf("register-%d", time.Now().UnixMilli()),
				Type:      "device.register.rsp",
				Timestamp: time.Now().UnixMilli(),
				TraceID:   msg.TraceID,
				From:      protocol.From{DeviceID: "server", Role: "server"},
				Payload: map[string]any{
					"result":      "ok",
					"token":       token,
					"expires_in":  86400,
					"server_time": time.Now().UnixMilli(),
					"device_name": deviceName,
					"error":       nil,
				},
			})
			if presenceChanged {
				h.broadcastPresenceSnapshot("device_registered", msg.From.DeviceID)
			}
		case "presence.heartbeat.req":
			status, ok := msg.Payload["status"].(string)
			if !ok || !isAllowedStatus(status) {
				h.writeError(client, msg.TraceID, msg.MessageID, 4001, "INPUT_INVALID_PAYLOAD", "missing or invalid heartbeat status", msg.From.DeviceID)
				continue
			}
			token, _ := msg.Payload["token"].(string)
			if !auth.ValidateToken(h.registry, msg.From.DeviceID, token) {
				h.writeError(client, msg.TraceID, msg.MessageID, 1001, "AUTH_INVALID_TOKEN", "invalid or expired token", msg.From.DeviceID)
				continue
			}
			device, ok, statusChanged := h.registry.Heartbeat(msg.From.DeviceID, status)
			if !ok {
				h.writeError(client, msg.TraceID, msg.MessageID, 2001, "DEVICE_NOT_FOUND", "device is not registered", msg.From.DeviceID)
				continue
			}
			h.logger.Info(map[string]any{
				"event":     "presence.heartbeat",
				"device_id": msg.From.DeviceID,
				"status":    device.Status,
			})
			h.writeEnvelope(client, protocol.Envelope{
				Version:   h.cfg.ProtocolVersion,
				MessageID: fmt.Sprintf("heartbeat-%d", time.Now().UnixMilli()),
				Type:      "presence.heartbeat.rsp",
				Timestamp: time.Now().UnixMilli(),
				TraceID:   msg.TraceID,
				From:      protocol.From{DeviceID: "server", Role: "server"},
				Payload: map[string]any{
					"result":           "ok",
					"next_interval_ms": 10000,
					"device_status":    device.Status,
				},
			})
			if statusChanged {
				h.broadcastPresenceSnapshot("heartbeat_status_changed", msg.From.DeviceID)
			}
		case "session.request.req":
			h.handleSessionRequest(client, msg)
		case "session.end.req":
			h.handleSessionEnd(client, msg)
		case "session.metrics.report":
			h.handleSessionMetricsReport(client, msg)
		case "input.mouse.move", "input.mouse.button", "input.keyboard.key", "input.wheel.scroll":
			h.handleInput(client, msg)
		case "input.result.push":
			h.handleInputResult(client, msg)
		case "webrtc.offer", "webrtc.answer", "webrtc.ice_candidate", "webrtc.restart_ice":
			h.handleWebRTCSignal(client, msg)
		default:
			h.writeError(client, msg.TraceID, msg.MessageID, 9001, "INTERNAL_ERROR", "unsupported message type", msg.From.DeviceID)
		}
	}
}

func (h *Hub) handleSessionRequest(client *clientConn, msg protocol.Envelope) {
	if !h.registry.HasDevice(msg.From.DeviceID) {
		h.writeError(client, msg.TraceID, msg.MessageID, 2001, "DEVICE_NOT_FOUND", "requesting device is not registered", msg.From.DeviceID)
		return
	}
	controllerDevice, ok := h.registry.Get(msg.From.DeviceID)
	if !ok || !controllerDevice.Capabilities.CanControl {
		h.writeError(client, msg.TraceID, msg.MessageID, 3004, "SESSION_STATE_INVALID", "requesting device is not allowed to control remote devices", msg.From.DeviceID)
		return
	}
	requestID, ok := msg.Payload["request_id"].(string)
	if !ok || requestID == "" {
		h.writeError(client, msg.TraceID, msg.MessageID, 4001, "INPUT_INVALID_PAYLOAD", "missing request_id", msg.From.DeviceID)
		return
	}
	targetDeviceID, ok := msg.Payload["target_device_id"].(string)
	if !ok || targetDeviceID == "" {
		h.writeError(client, msg.TraceID, msg.MessageID, 4001, "INPUT_INVALID_PAYLOAD", "missing target_device_id", msg.From.DeviceID)
		return
	}
	if targetDeviceID == msg.From.DeviceID {
		h.writeError(client, msg.TraceID, msg.MessageID, 4001, "INPUT_INVALID_PAYLOAD", "target_device_id must reference another device", msg.From.DeviceID)
		return
	}
	if !h.registry.HasDevice(targetDeviceID) {
		h.writeError(client, msg.TraceID, msg.MessageID, 2001, "DEVICE_NOT_FOUND", "target device is not registered", msg.From.DeviceID)
		return
	}
	targetDevice, ok := h.registry.Get(targetDeviceID)
	if !ok || !targetDevice.Capabilities.CanBeControlled {
		h.writeError(client, msg.TraceID, msg.MessageID, 3004, "SESSION_STATE_INVALID", "target device is not allowed to be controlled", msg.From.DeviceID)
		return
	}
	if !h.hasConnectedClient(targetDeviceID) {
		h.writeError(client, msg.TraceID, msg.MessageID, 2002, "DEVICE_OFFLINE", "target device is offline", msg.From.DeviceID)
		return
	}
	controllerProfile := "standard"
	if rawProfile, ok := msg.Payload["controller_profile"].(string); ok {
		normalizedProfile := strings.ToLower(strings.TrimSpace(rawProfile))
		switch normalizedProfile {
		case "emulator", "standard", "android_phone":
			controllerProfile = normalizedProfile
		}
	}
	h.logger.Info(map[string]any{
		"event":              "session.request.accepted",
		"request_id":         requestID,
		"controller_id":      msg.From.DeviceID,
		"agent_id":           targetDeviceID,
		"controller_profile": controllerProfile,
		"trace_id":           msg.TraceID,
		"msg_id":             msg.MessageID,
	})
	current := h.sessions.Create(msg.From.DeviceID, targetDeviceID, controllerProfile)
	h.logger.Info(map[string]any{
		"event":      "session.created",
		"session_id": current.SessionID,
		"controller": current.ControllerDeviceID,
		"agent":      current.AgentDeviceID,
		"trace_id":   msg.TraceID,
	})
	approved := session.BuildApprovedResult(msg, current.SessionID)
	start := session.BuildStart(current, msg.TraceID, h.cfg.PublicWSURL)
	if !h.writeToDevice(targetDeviceID, start) {
		h.sessions.Delete(current.SessionID)
		h.writeError(client, msg.TraceID, msg.MessageID, 2002, "DEVICE_OFFLINE", "target device disconnected before session start", msg.From.DeviceID)
		return
	}
	if !h.writeEnvelope(client, approved) {
		h.sessions.Delete(current.SessionID)
		h.writeToDevice(targetDeviceID, session.BuildEnd(current.SessionID, msg.TraceID, "server_terminate"))
		return
	}
	if !h.writeEnvelope(client, start) {
		h.sessions.Delete(current.SessionID)
		h.writeToDevice(targetDeviceID, session.BuildEnd(current.SessionID, msg.TraceID, "server_terminate"))
		return
	}
	h.logger.Info(map[string]any{
		"event":      "session.start.dispatched",
		"session_id": current.SessionID,
		"controller": current.ControllerDeviceID,
		"agent":      current.AgentDeviceID,
	})
}

func (h *Hub) handleSessionEnd(client *clientConn, msg protocol.Envelope) {
	payloadSessionID, _ := msg.Payload["session_id"].(string)
	if msg.SessionID == "" || payloadSessionID == "" || msg.SessionID != payloadSessionID {
		h.writeError(client, msg.TraceID, msg.MessageID, 4001, "INPUT_INVALID_PAYLOAD", "session_id is required and must match envelope", msg.From.DeviceID)
		return
	}
	current, ok := h.sessions.Get(msg.SessionID)
	if !ok {
		h.writeError(client, msg.TraceID, msg.MessageID, 3003, "SESSION_NOT_FOUND", "session not found", msg.From.DeviceID)
		return
	}
	if current.ControllerDeviceID != msg.From.DeviceID && current.AgentDeviceID != msg.From.DeviceID {
		h.writeError(client, msg.TraceID, msg.MessageID, 3004, "SESSION_STATE_INVALID", "device is not a participant of this session", msg.From.DeviceID)
		return
	}
	end := session.BuildEnd(msg.SessionID, msg.TraceID, session.NormalizeEndReason(msg.Payload["reason"]))
	h.writeToDevice(current.ControllerDeviceID, end)
	if current.AgentDeviceID != current.ControllerDeviceID {
		h.writeToDevice(current.AgentDeviceID, end)
	}
	h.rememberEndedSession(current)
	h.sessions.Delete(msg.SessionID)
	h.logger.Info(map[string]any{
		"event":      "session.ended",
		"session_id": msg.SessionID,
		"reason":     session.NormalizeEndReason(msg.Payload["reason"]),
		"by_device":  msg.From.DeviceID,
		"trace_id":   msg.TraceID,
	})
}

func (h *Hub) handleSessionMetricsReport(client *clientConn, msg protocol.Envelope) {
	payloadSessionID, _ := msg.Payload["session_id"].(string)
	if msg.SessionID == "" || payloadSessionID == "" || msg.SessionID != payloadSessionID {
		h.writeError(client, msg.TraceID, msg.MessageID, 4001, "INPUT_INVALID_PAYLOAD", "session_id is required and must match envelope", msg.From.DeviceID)
		return
	}

	current, ok := h.resolveSessionForMetrics(msg.SessionID)
	if !ok {
		h.writeError(client, msg.TraceID, msg.MessageID, 3003, "SESSION_NOT_FOUND", "session not found", msg.From.DeviceID)
		return
	}

	role := ""
	switch msg.From.DeviceID {
	case current.ControllerDeviceID:
		role = "controller"
	case current.AgentDeviceID:
		role = "agent"
	default:
		h.writeError(client, msg.TraceID, msg.MessageID, 3004, "SESSION_STATE_INVALID", "device is not a participant of this session", msg.From.DeviceID)
		return
	}

	reportPayload := clonePayload(msg.Payload)
	delete(reportPayload, "session_id")
	if len(reportPayload) == 0 {
		h.writeError(client, msg.TraceID, msg.MessageID, 4001, "INPUT_INVALID_PAYLOAD", "metrics report payload is empty", msg.From.DeviceID)
		return
	}
	reportPayload["source_role"] = role
	reportPayload["source_device_id"] = msg.From.DeviceID

	combined := h.upsertSessionMetrics(msg.SessionID, current, role, reportPayload)
	h.logger.Info(map[string]any{
		"event":        "session.metrics.reported",
		"session_id":   msg.SessionID,
		"from_device":  msg.From.DeviceID,
		"source_role":  role,
		"trace_id":     msg.TraceID,
		"msg_id":       msg.MessageID,
		"payload_keys": payloadKeys(reportPayload),
	})
	if combined != nil {
		h.logger.Info(combined)
	}
}

func (h *Hub) handleInput(client *clientConn, msg protocol.Envelope) {
	if msg.SessionID == "" {
		h.writeError(client, msg.TraceID, msg.MessageID, 4002, "INPUT_NOT_ALLOWED", "session_id is required for input events", msg.From.DeviceID)
		return
	}
	if !h.sessions.IsController(msg.SessionID, msg.From.DeviceID) {
		h.writeError(client, msg.TraceID, msg.MessageID, 4002, "INPUT_NOT_ALLOWED", "only the controller can send input events", msg.From.DeviceID)
		return
	}
	peerDeviceID, ok := h.sessions.PeerDeviceID(msg.SessionID, msg.From.DeviceID)
	if !ok {
		h.writeError(client, msg.TraceID, msg.MessageID, 3003, "SESSION_NOT_FOUND", "session not found", msg.From.DeviceID)
		return
	}
	if !h.writeToDevice(peerDeviceID, msg) {
		h.writeError(client, msg.TraceID, msg.MessageID, 2002, "DEVICE_OFFLINE", "session peer is offline", msg.From.DeviceID)
		return
	}
	h.logger.Info(map[string]any{
		"event":        "input.forwarded",
		"type":         msg.Type,
		"session_id":   msg.SessionID,
		"from_device":  msg.From.DeviceID,
		"peer_device":  peerDeviceID,
		"trace_id":     msg.TraceID,
		"message_id":   msg.MessageID,
		"payload_keys": payloadKeys(msg.Payload),
	})
	h.writeEnvelope(client, session.BuildInputAck(msg.SessionID, msg.TraceID, msg.Type))
}

func (h *Hub) handleInputResult(client *clientConn, msg protocol.Envelope) {
	if msg.SessionID == "" {
		h.writeError(client, msg.TraceID, msg.MessageID, 4002, "INPUT_NOT_ALLOWED", "session_id is required for input result events", msg.From.DeviceID)
		return
	}
	current, ok := h.sessions.Get(msg.SessionID)
	if !ok {
		h.writeError(client, msg.TraceID, msg.MessageID, 3003, "SESSION_NOT_FOUND", "session not found", msg.From.DeviceID)
		return
	}
	if current.AgentDeviceID != msg.From.DeviceID {
		h.writeError(client, msg.TraceID, msg.MessageID, 4002, "INPUT_NOT_ALLOWED", "only the target agent can send input result events", msg.From.DeviceID)
		return
	}
	if !h.validateInputResult(msg) {
		h.writeError(client, msg.TraceID, msg.MessageID, 4001, "INPUT_INVALID_PAYLOAD", "invalid input result payload", msg.From.DeviceID)
		return
	}
	if !h.writeToDevice(current.ControllerDeviceID, msg) {
		h.writeError(client, msg.TraceID, msg.MessageID, 2002, "DEVICE_OFFLINE", "session controller is offline", msg.From.DeviceID)
		return
	}
	h.logger.Info(map[string]any{
		"event":        "input.result.forwarded",
		"session_id":   msg.SessionID,
		"from_device":  msg.From.DeviceID,
		"peer_device":  current.ControllerDeviceID,
		"trace_id":     msg.TraceID,
		"message_id":   msg.MessageID,
		"payload_keys": payloadKeys(msg.Payload),
	})
}

func (h *Hub) handleWebRTCSignal(client *clientConn, msg protocol.Envelope) {
	if msg.SessionID == "" {
		h.writeError(client, msg.TraceID, msg.MessageID, 5001, "MEDIA_NEGOTIATION_FAILED", "session_id is required for webrtc signaling", msg.From.DeviceID)
		return
	}
	if !h.sessions.HasParticipant(msg.SessionID, msg.From.DeviceID) {
		h.writeError(client, msg.TraceID, msg.MessageID, 5001, "MEDIA_NEGOTIATION_FAILED", "only session participants can exchange webrtc signaling", msg.From.DeviceID)
		return
	}
	if !h.validateWebRTCSignal(msg) {
		h.writeError(client, msg.TraceID, msg.MessageID, 5001, "MEDIA_NEGOTIATION_FAILED", "invalid webrtc signaling payload", msg.From.DeviceID)
		return
	}
	summary := summarizeWebRTCSignal(msg)
	summary["event"] = "webrtc.signal.accepted"
	summary["from_device"] = msg.From.DeviceID
	summary["session_id"] = msg.SessionID
	summary["trace_id"] = msg.TraceID
	summary["msg_id"] = msg.MessageID
	h.logger.Info(summary)
	peerDeviceID, ok := h.sessions.PeerDeviceID(msg.SessionID, msg.From.DeviceID)
	if !ok {
		h.writeError(client, msg.TraceID, msg.MessageID, 3003, "SESSION_NOT_FOUND", "session not found", msg.From.DeviceID)
		return
	}
	if !h.writeToDevice(peerDeviceID, msg) {
		h.writeError(client, msg.TraceID, msg.MessageID, 2002, "DEVICE_OFFLINE", "session peer is offline", msg.From.DeviceID)
		return
	}
	h.logger.Info(map[string]any{
		"event":        "webrtc.signal.forwarded",
		"type":         msg.Type,
		"session_id":   msg.SessionID,
		"from_device":  msg.From.DeviceID,
		"peer_device":  peerDeviceID,
		"trace_id":     msg.TraceID,
		"message_id":   msg.MessageID,
		"payload_keys": payloadKeys(msg.Payload),
	})
}

func (h *Hub) validateSocketIdentity(client *clientConn, registeredDeviceID *string, msg protocol.Envelope) bool {
	if msg.Type == "hello" {
		return true
	}
	if msg.Type == "device.register.req" {
		if *registeredDeviceID == "" {
			return true
		}
		if msg.From.DeviceID == *registeredDeviceID {
			return true
		}
		h.writeError(client, msg.TraceID, msg.MessageID, 1002, "AUTH_FORBIDDEN_DEVICE", "socket is already bound to another device", msg.From.DeviceID)
		return false
	}
	if *registeredDeviceID == "" {
		h.writeError(client, msg.TraceID, msg.MessageID, 1002, "AUTH_FORBIDDEN_DEVICE", "device must register before sending this message", msg.From.DeviceID)
		return false
	}
	if msg.From.DeviceID != *registeredDeviceID {
		h.writeError(client, msg.TraceID, msg.MessageID, 1002, "AUTH_FORBIDDEN_DEVICE", "message device_id does not match registered socket identity", msg.From.DeviceID)
		return false
	}
	if !h.isAuthorizedClient(*registeredDeviceID, client) {
		h.writeError(client, msg.TraceID, msg.MessageID, 1002, "AUTH_FORBIDDEN_DEVICE", "socket is no longer the active connection for this device", msg.From.DeviceID)
		return false
	}
	return true
}

func (h *Hub) validateRegister(msg protocol.Envelope) bool {
	payloadDeviceID, ok := msg.Payload["device_id"].(string)
	if !ok || payloadDeviceID == "" {
		return false
	}
	if payloadDeviceID != msg.From.DeviceID {
		return false
	}
	userID, ok := msg.Payload["user_id"].(string)
	if !ok || userID == "" {
		return false
	}
	platform, ok := msg.Payload["platform"].(string)
	if !ok || platform == "" {
		return false
	}
	clientVersion, ok := msg.Payload["client_version"].(string)
	if !ok || clientVersion == "" {
		return false
	}
	capabilities, ok := msg.Payload["capabilities"].(map[string]any)
	if !ok {
		return false
	}
	if _, ok := capabilities["can_control"].(bool); !ok {
		return false
	}
	if _, ok := capabilities["can_be_controlled"].(bool); !ok {
		return false
	}
	return msg.From.DeviceID != "" && msg.From.Role != ""
}

func parseDeviceCapabilities(value any) presence.DeviceCapabilities {
	capabilities, _ := value.(map[string]any)
	canControl, _ := capabilities["can_control"].(bool)
	canBeControlled, _ := capabilities["can_be_controlled"].(bool)
	return presence.DeviceCapabilities{
		CanControl:      canControl,
		CanBeControlled: canBeControlled,
	}
}

func (h *Hub) validateInputResult(msg protocol.Envelope) bool {
	if msg.Payload == nil {
		return false
	}
	inputType, ok := msg.Payload["input_type"].(string)
	if !ok || !isInputMessageType(inputType) {
		return false
	}
	if _, ok := msg.Payload["applied"].(bool); !ok {
		return false
	}
	if traceID, exists := msg.Payload["input_trace_id"]; exists {
		value, ok := traceID.(string)
		if !ok || strings.TrimSpace(value) == "" {
			return false
		}
	}
	for _, key := range []string{"executor", "status_code", "status_detail", "error_code", "error_detail", "summary"} {
		if value, exists := msg.Payload[key]; exists {
			if _, ok := value.(string); !ok {
				return false
			}
		}
	}
	if count, exists := msg.Payload["input_count"]; exists {
		value, ok := count.(float64)
		if !ok || value < 0 || math.Trunc(value) != value {
			return false
		}
	}
	return true
}

func isInputMessageType(messageType string) bool {
	switch strings.TrimSpace(messageType) {
	case "input.mouse.move", "input.mouse.button", "input.keyboard.key", "input.wheel.scroll":
		return true
	default:
		return false
	}
}

func (h *Hub) validateWebRTCSignal(msg protocol.Envelope) bool {
	switch msg.Type {
	case "webrtc.offer":
		sdpType, ok := msg.Payload["sdp_type"].(string)
		if !ok || strings.TrimSpace(sdpType) != "offer" {
			return false
		}
		sdp, ok := msg.Payload["sdp"].(string)
		return ok && strings.TrimSpace(sdp) != ""
	case "webrtc.answer":
		sdpType, ok := msg.Payload["sdp_type"].(string)
		if !ok || strings.TrimSpace(sdpType) != "answer" {
			return false
		}
		sdp, ok := msg.Payload["sdp"].(string)
		return ok && strings.TrimSpace(sdp) != ""
	case "webrtc.ice_candidate":
		candidate, ok := msg.Payload["candidate"].(string)
		if !ok || strings.TrimSpace(candidate) == "" {
			return false
		}
		if mid, exists := msg.Payload["sdp_mid"]; exists {
			sdpMid, ok := mid.(string)
			if !ok || strings.TrimSpace(sdpMid) == "" {
				return false
			}
		}
		if index, exists := msg.Payload["sdp_mline_index"]; exists {
			mlineIndex, ok := index.(float64)
			if !ok || mlineIndex < 0 || math.Trunc(mlineIndex) != mlineIndex {
				return false
			}
		}
		return true
	case "webrtc.restart_ice":
		if reason, exists := msg.Payload["reason"]; exists {
			if _, ok := reason.(string); !ok {
				return false
			}
		}
		return true
	default:
		return false
	}
}

func clonePayload(payload map[string]any) map[string]any {
	if len(payload) == 0 {
		return map[string]any{}
	}
	cloned := make(map[string]any, len(payload))
	for key, value := range payload {
		cloned[key] = value
	}
	return cloned
}

func (h *Hub) rememberEndedSession(current store.Session) {
	now := time.Now()
	h.metricsMu.Lock()
	defer h.metricsMu.Unlock()
	h.cleanupMetricsStateLocked(now)
	h.endedSessions[current.SessionID] = endedSessionRecord{
		session: current,
		endedAt: now,
	}
}

func (h *Hub) resolveSessionForMetrics(sessionID string) (store.Session, bool) {
	current, ok := h.sessions.Get(sessionID)
	if ok {
		return current, true
	}
	now := time.Now()
	h.metricsMu.Lock()
	defer h.metricsMu.Unlock()
	h.cleanupMetricsStateLocked(now)
	record, exists := h.endedSessions[sessionID]
	if !exists {
		return store.Session{}, false
	}
	return record.session, true
}

func (h *Hub) upsertSessionMetrics(sessionID string, current store.Session, role string, report map[string]any) map[string]any {
	now := time.Now()
	h.metricsMu.Lock()
	defer h.metricsMu.Unlock()
	h.cleanupMetricsStateLocked(now)

	aggregate, ok := h.sessionMetrics[sessionID]
	if !ok || aggregate == nil {
		aggregate = &sessionMetricsAggregate{
			controllerDeviceID: current.ControllerDeviceID,
			agentDeviceID:      current.AgentDeviceID,
			updatedAt:          now,
		}
		h.sessionMetrics[sessionID] = aggregate
	}
	aggregate.updatedAt = now
	switch role {
	case "controller":
		aggregate.controllerReport = report
	case "agent":
		aggregate.agentReport = report
	default:
		return nil
	}
	if aggregate.controllerReport == nil || aggregate.agentReport == nil {
		return nil
	}
	combined := map[string]any{
		"event":                "session.metrics.combined",
		"session_id":           sessionID,
		"controller_device_id": aggregate.controllerDeviceID,
		"agent_device_id":      aggregate.agentDeviceID,
		"controller":           aggregate.controllerReport,
		"agent":                aggregate.agentReport,
	}
	for key, value := range extractBridgeCombinedSummary(
		aggregate.controllerReport,
		aggregate.agentReport,
	) {
		combined[key] = value
	}
	for key, value := range extractSessionCombinedSummary(
		aggregate.controllerReport,
		aggregate.agentReport,
	) {
		combined[key] = value
	}
	h.recordSessionE2EProofLocked(now, combined)
	return combined
}

func (h *Hub) recordSessionE2EProofLocked(now time.Time, combined map[string]any) {
	routeKey, routeKeyOK := asTrimmedString(combined["session_e2e_route_key"])
	if !routeKeyOK || !isTargetE2ERoute(routeKey) {
		return
	}
	route, _ := asTrimmedString(combined["session_e2e_route"])
	proofStatus, proofStatusOK := asTrimmedString(combined["session_e2e_proof_status"])
	if !proofStatusOK {
		proofStatus = "missing"
	}
	videoObserved, _ := asBool(combined["session_e2e_video_observed"])
	inputObserved, _ := asBool(combined["session_e2e_input_observed"])
	targetRoute, _ := asBool(combined["session_e2e_target_route"])
	inputCoverage := remoteInputCoverageFromCombined(combined)
	if inputObserved && !remoteInputCoverageComplete(inputCoverage) {
		if videoObserved {
			proofStatus = "video_and_partial_input_observed"
		} else {
			proofStatus = "partial_input_only"
		}
	}
	record := sessionE2EProofRecord{
		RouteKey:           routeKey,
		Route:              route,
		TargetRoute:        targetRoute,
		ProofStatus:        proofStatus,
		VideoObserved:      videoObserved,
		InputObserved:      inputObserved,
		UpdatedAt:          now.UTC(),
		SessionID:          stringFromCombined(combined, "session_id"),
		ControllerDeviceID: stringFromCombined(combined, "controller_device_id"),
		AgentDeviceID:      stringFromCombined(combined, "agent_device_id"),
	}
	if value, ok := asTrimmedString(combined["session_controller_platform"]); ok {
		record.ControllerPlatform = value
	}
	if value, ok := asTrimmedString(combined["session_agent_platform"]); ok {
		record.AgentPlatform = value
	}
	if value, ok := asNumber(combined["first_frame_ms"]); ok {
		record.FirstFrameMS = value
	}
	if value, ok := asNumber(combined["rendered_frames"]); ok {
		record.RenderedFrames = value
	}
	if value, ok := asNumber(combined["remote_input_result_applied_count"]); ok {
		record.RemoteInputApplied = value
	}
	if value, ok := asNumber(combined["remote_input_result_count"]); ok {
		record.RemoteInputTotal = value
	}
	if value, ok := asTrimmedString(combined["remote_input_last_executor"]); ok {
		record.RemoteInputExecutor = value
	}
	if value, ok := asTrimmedString(combined["remote_input_last_status_code"]); ok {
		record.RemoteInputStatus = value
	}
	if value, ok := asTrimmedString(combined["remote_input_last_trace_id"]); ok {
		record.RemoteInputTraceID = value
	}
	record.RemoteInputCoverage = inputCoverage
	if value, ok := asTrimmedString(combined["session_quality_hint"]); ok {
		record.SessionQualityHint = value
	}
	if value, ok := asTrimmedString(combined["session_e2e_proof_summary"]); ok {
		record.SessionProofSummary = value
	}
	if value, ok := asTrimmedString(combined["session_perf_summary"]); ok {
		record.SessionPerfSummary = value
	}
	if h.e2eLatest == nil {
		h.e2eLatest = make(map[string]sessionE2EProofRecord)
	}
	if h.e2eSuccess == nil {
		h.e2eSuccess = make(map[string]sessionE2EProofRecord)
	}
	h.e2eLatest[routeKey] = record
	if record.ProofStatus == "video_and_input_observed" && remoteInputCoverageComplete(record.RemoteInputCoverage) {
		h.e2eSuccess[routeKey] = record
	}
}

func stringFromCombined(combined map[string]any, key string) string {
	value, _ := combined[key].(string)
	return strings.TrimSpace(value)
}

func (h *Hub) resetE2EProof() {
	h.metricsMu.Lock()
	defer h.metricsMu.Unlock()
	h.e2eLatest = make(map[string]sessionE2EProofRecord)
	h.e2eSuccess = make(map[string]sessionE2EProofRecord)
}

func (h *Hub) e2eProofSnapshot() sessionE2EProofSnapshot {
	h.metricsMu.Lock()
	defer h.metricsMu.Unlock()
	h.cleanupMetricsStateLocked(time.Now())

	snapshot := sessionE2EProofSnapshot{
		Event:             "session.e2e_proof.snapshot",
		TargetRoutesTotal: len(targetE2ERoutes),
		Routes:            make([]sessionE2EProofRouteState, 0, len(targetE2ERoutes)),
	}
	for _, route := range targetE2ERoutes {
		state := sessionE2EProofRouteState{
			RouteKey: route.key,
			Route:    route.label,
			Status:   "not_observed",
			Missing:  []string{"video", "input"},
		}
		if latest, ok := h.e2eLatest[route.key]; ok {
			latestCopy := latest
			state.Latest = &latestCopy
			state.Status = latest.ProofStatus
			state.Missing = missingE2EProofSignals(latest)
		}
		if success, ok := h.e2eSuccess[route.key]; ok {
			successCopy := success
			state.LastSuccess = &successCopy
			state.Complete = true
			state.Status = "complete"
			state.Missing = nil
			snapshot.TargetRoutesComplete += 1
		}
		state.NextAction = nextE2EProofAction(state)
		snapshot.Routes = append(snapshot.Routes, state)
	}
	snapshot.Complete = snapshot.TargetRoutesComplete == snapshot.TargetRoutesTotal
	return snapshot
}

func missingE2EProofSignals(record sessionE2EProofRecord) []string {
	missing := []string{}
	if !record.VideoObserved {
		missing = append(missing, "video")
	}
	if !record.InputObserved {
		missing = append(missing, "input")
		return missing
	}
	for _, category := range missingRemoteInputCategories(record.RemoteInputCoverage) {
		missing = append(missing, "input:"+category)
	}
	return missing
}

func nextE2EProofAction(state sessionE2EProofRouteState) string {
	if state.Complete {
		return "done"
	}
	if state.Latest == nil {
		return "run route and wait for live session.metrics.report from controller and target"
	}
	if len(state.Missing) == 0 {
		return "wait for successful proof aggregation"
	}
	if hasMissingSignal(state.Missing, "video") {
		return "verify controller renders remote video and sends first_frame/rendered_frames metrics"
	}
	switch strings.Join(state.Missing, ",") {
	case "video":
		return "verify controller renders remote video and sends first_frame/rendered_frames metrics"
	case "input":
		return "send tap/click, drag, keyboard, and wheel input; verify target input.result.push applied=true"
	default:
		if inputMissing := missingInputSubtypes(state.Missing); len(inputMissing) > 0 {
			return "send missing input types (" + strings.Join(inputMissing, ",") + "); verify target input.result.push applied=true for each"
		}
		return "verify both controller video metrics and target input.result.push applied=true"
	}
}

func hasMissingSignal(missing []string, signal string) bool {
	for _, value := range missing {
		if value == signal {
			return true
		}
	}
	return false
}

func missingInputSubtypes(missing []string) []string {
	subtypes := []string{}
	for _, signal := range missing {
		if subtype, ok := strings.CutPrefix(signal, "input:"); ok && subtype != "" {
			subtypes = append(subtypes, subtype)
		}
	}
	return subtypes
}

func remoteInputCoverageFromCombined(combined map[string]any) []string {
	coverage := map[string]bool{}
	for _, category := range requiredRemoteInputCategories {
		key := "remote_input_applied_" + category
		if applied, ok := asBool(combined[key]); ok && applied {
			coverage[category] = true
		}
	}
	if value, ok := asTrimmedString(combined["remote_input_applied_categories"]); ok {
		for _, rawCategory := range strings.Split(value, ",") {
			if category := normalizeRemoteInputCategory(rawCategory); category != "" {
				coverage[category] = true
			}
		}
	}
	if category, ok := asTrimmedString(combined["remote_input_last_category"]); ok {
		if lastApplied, lastAppliedOK := asBool(combined["remote_input_last_applied"]); lastAppliedOK && lastApplied {
			if normalized := normalizeRemoteInputCategory(category); normalized != "" {
				coverage[normalized] = true
			}
		}
	}
	result := make([]string, 0, len(requiredRemoteInputCategories))
	for _, category := range requiredRemoteInputCategories {
		if coverage[category] {
			result = append(result, category)
		}
	}
	return result
}

func remoteInputCoverageComplete(coverage []string) bool {
	return len(missingRemoteInputCategories(coverage)) == 0
}

func missingRemoteInputCategories(coverage []string) []string {
	seen := map[string]bool{}
	for _, category := range coverage {
		if normalized := normalizeRemoteInputCategory(category); normalized != "" {
			seen[normalized] = true
		}
	}
	missing := []string{}
	for _, category := range requiredRemoteInputCategories {
		if !seen[category] {
			missing = append(missing, category)
		}
	}
	return missing
}

func normalizeRemoteInputCategory(raw string) string {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "tap", "pointer", "click":
		return "click"
	case "drag":
		return "drag"
	case "key", "keyboard":
		return "keyboard"
	case "scroll", "wheel":
		return "wheel"
	default:
		return ""
	}
}

func extractBridgeCombinedSummary(controllerReport, agentReport map[string]any) map[string]any {
	candidateReports := []struct {
		role   string
		report map[string]any
	}{
		{role: "agent", report: agentReport},
		{role: "controller", report: controllerReport},
	}
	for _, candidate := range candidateReports {
		if candidate.report == nil {
			continue
		}
		pipeline, ok := asTrimmedString(candidate.report["bridge_pipeline"])
		if !ok {
			continue
		}
		summary := map[string]any{
			"bridge_source_role": candidate.role,
			"bridge_pipeline":    pipeline,
		}
		if value, ok := asTrimmedString(candidate.report["bridge_pipeline_ratios"]); ok {
			summary["bridge_pipeline_ratios"] = value
		}
		if value, ok := asTrimmedString(candidate.report["bridge_fetch_skips"]); ok {
			summary["bridge_fetch_skips"] = value
		}
		if value, ok := asTrimmedString(candidate.report["bridge_modes"]); ok {
			summary["bridge_modes"] = value
		}
		if value, ok := asNumber(candidate.report["bridge_total"]); ok {
			summary["bridge_total"] = value
		}
		if value, ok := asNumber(candidate.report["bridge_fetch_skip_total"]); ok {
			summary["bridge_fetch_skip_total"] = value
		}
		if value, ok := asNumber(candidate.report["bridge_canvas_hits"]); ok {
			summary["bridge_canvas_hits"] = value
		}
		if value, ok := asNumber(candidate.report["bridge_canvas_share_pct"]); ok {
			summary["bridge_canvas_share_pct"] = value
		}
		if value, ok := asNumber(candidate.report["bridge_raw_success_rate_pct"]); ok {
			summary["bridge_raw_success_rate_pct"] = value
		}
		if value, ok := asNumber(candidate.report["bridge_jpeg_retry_success_rate_pct"]); ok {
			summary["bridge_jpeg_retry_success_rate_pct"] = value
		}
		if value, ok := asTrimmedString(candidate.report["bridge_capability_tier"]); ok {
			summary["bridge_capability_tier"] = value
		}
		if value, ok := asNumber(candidate.report["runtime_signature_version"]); ok {
			summary["runtime_signature_version"] = value
		}
		if value, ok := asTrimmedString(candidate.report["runtime_kernel"]); ok {
			summary["runtime_kernel"] = value
		}
		if value, ok := asTrimmedString(candidate.report["runtime_capability_signature"]); ok {
			summary["runtime_capability_signature"] = value
		}
		if value, ok := asTrimmedString(candidate.report["runtime_engine"]); ok {
			summary["runtime_engine"] = value
		}
		if value, ok := asTrimmedString(candidate.report["runtime_shell_platform"]); ok {
			summary["runtime_shell_platform"] = value
		}
		if value, ok := asTrimmedString(candidate.report["runtime_browser_platform"]); ok {
			summary["runtime_browser_platform"] = value
		}
		if value, ok := asTrimmedString(candidate.report["runtime_user_agent"]); ok {
			summary["runtime_user_agent"] = value
		}
		if value, ok := asBool(candidate.report["runtime_cap_fetch"]); ok {
			summary["runtime_cap_fetch"] = value
		}
		if value, ok := asBool(candidate.report["runtime_cap_media_stream"]); ok {
			summary["runtime_cap_media_stream"] = value
		}
		if value, ok := asBool(candidate.report["runtime_cap_video_frame"]); ok {
			summary["runtime_cap_video_frame"] = value
		}
		if value, ok := asBool(candidate.report["runtime_cap_track_generator"]); ok {
			summary["runtime_cap_track_generator"] = value
		}
		if value, ok := asBool(candidate.report["runtime_cap_display_media"]); ok {
			summary["runtime_cap_display_media"] = value
		}
		if value, ok := asBool(candidate.report["runtime_cap_native_sender"]); ok {
			summary["runtime_cap_native_sender"] = value
		}
		if value, ok := asTrimmedString(candidate.report["runtime_native_sender_support_level"]); ok {
			summary["runtime_native_sender_support_level"] = value
		}
		if value, ok := asTrimmedString(candidate.report["runtime_native_sender_blocker"]); ok {
			summary["runtime_native_sender_blocker"] = value
		}
		return summary
	}
	return nil
}

func extractSessionCombinedSummary(controllerReport, agentReport map[string]any) map[string]any {
	summary := map[string]any{}
	controller := controllerReport
	agent := agentReport

	controllerPlatform, controllerPlatformOK := resolveSessionReportPlatform(controller)
	agentPlatform, agentPlatformOK := resolveSessionReportPlatform(agent)
	sessionRouteKey, sessionRouteLabel, sessionRouteTarget, sessionRouteOK := deriveSessionE2ERoute(
		controllerPlatform,
		controllerPlatformOK,
		agentPlatform,
		agentPlatformOK,
	)

	firstFrameMs, firstFrameOk := asNumber(controller["first_frame_ms"])
	renderFpsAvg, renderFpsOk := asNumber(controller["render_fps_avg"])
	recvKbpsAvg, recvKbpsOk := asNumber(controller["recv_kbps_avg"])
	renderedFrames, renderedFramesOk := asNumber(controller["rendered_frames"])
	rttMsAvg, rttMsAvgOk := asNumber(controller["rtt_ms_avg"])
	icePolicyRestarts, icePolicyRestartsOk := asNumber(controller["ice_policy_restarts"])
	renderLongestFrameGapMs, renderLongestFrameGapMsOK := asNumber(controller["render_longest_frame_gap_ms"])
	renderFrameGapSpikeCount, renderFrameGapSpikeCountOK := asNumber(controller["render_frame_gap_spike_count"])
	renderLowFpsSampleCount, renderLowFpsSampleCountOK := asNumber(controller["render_low_fps_sample_count"])
	renderLongestLowFpsStreakMs, renderLongestLowFpsStreakMsOK := asNumber(controller["render_longest_low_fps_streak_ms"])
	framesDroppedLast, framesDroppedLastOK := asNumber(controller["frames_dropped_last"])
	framesDroppedSpikeMax, framesDroppedSpikeMaxOK := asNumber(controller["frames_dropped_spike_max"])
	controllerQualityHint, controllerQualityHintOK := asTrimmedString(controller["controller_quality_hint"])
	remoteInputResultCount, remoteInputResultCountOK := asNumber(controller["remote_input_result_count"])
	remoteInputResultAppliedCount, remoteInputResultAppliedCountOK := asNumber(controller["remote_input_result_applied_count"])
	remoteInputResultFailedCount, remoteInputResultFailedCountOK := asNumber(controller["remote_input_result_failed_count"])
	remoteInputAppliedClick, remoteInputAppliedClickOK := asBool(controller["remote_input_applied_click"])
	remoteInputAppliedDrag, remoteInputAppliedDragOK := asBool(controller["remote_input_applied_drag"])
	remoteInputAppliedKeyboard, remoteInputAppliedKeyboardOK := asBool(controller["remote_input_applied_keyboard"])
	remoteInputAppliedWheel, remoteInputAppliedWheelOK := asBool(controller["remote_input_applied_wheel"])
	remoteInputCoverageCompleteValue, remoteInputCoverageCompleteOK := asBool(controller["remote_input_required_coverage_complete"])
	remoteInputAppliedCategories, remoteInputAppliedCategoriesOK := asTrimmedString(controller["remote_input_applied_categories"])
	remoteInputLastType, remoteInputLastTypeOK := asTrimmedString(controller["remote_input_last_type"])
	remoteInputLastCategory, remoteInputLastCategoryOK := asTrimmedString(controller["remote_input_last_category"])
	remoteInputLastTraceID, remoteInputLastTraceIDOK := asTrimmedString(controller["remote_input_last_trace_id"])
	remoteInputLastApplied, remoteInputLastAppliedOK := asBool(controller["remote_input_last_applied"])
	remoteInputLastExecutor, remoteInputLastExecutorOK := asTrimmedString(controller["remote_input_last_executor"])
	remoteInputLastStatusCode, remoteInputLastStatusCodeOK := asTrimmedString(controller["remote_input_last_status_code"])
	remoteInputLastStatusDetail, remoteInputLastStatusDetailOK := asTrimmedString(controller["remote_input_last_status_detail"])
	remoteInputLastErrorCode, remoteInputLastErrorCodeOK := asTrimmedString(controller["remote_input_last_error_code"])
	remoteInputLastErrorDetail, remoteInputLastErrorDetailOK := asTrimmedString(controller["remote_input_last_error_detail"])
	remoteInputLastSummary, remoteInputLastSummaryOK := asTrimmedString(controller["remote_input_last_summary"])
	remoteInputLastCount, remoteInputLastCountOK := asNumber(controller["remote_input_last_count"])
	remoteInputLastTargetDeviceID, remoteInputLastTargetDeviceIDOK := asTrimmedString(controller["remote_input_last_target_device_id"])

	sendFps, sendFpsOk := asNumber(agent["send_fps"])
	sendKbps, sendKbpsOk := asNumber(agent["send_kbps"])
	captureFps, captureFpsOk := asNumber(agent["capture_fps"])
	agentRttMs, agentRttMsOk := asNumber(agent["rtt_ms"])
	canvasSharePct, canvasShareOk := asNumber(agent["bridge_canvas_share_pct"])
	bridgeCapabilityTier, bridgeCapabilityTierOk := asTrimmedString(agent["bridge_capability_tier"])

	candidatePairLast, candidatePairLastOk := asTrimmedString(controller["candidate_pair_last"])
	candidateTierLast, candidateTierLastOk := asTrimmedString(controller["candidate_tier_last"])
	candidatePath, candidatePathOk := asTrimmedString(agent["candidate_path"])
	candidateTier, candidateTierOk := asTrimmedString(agent["candidate_tier"])
	qualityLimit, qualityLimitOk := asTrimmedString(agent["quality_limit"])
	adaptiveProfile, adaptiveProfileOk := asTrimmedString(agent["adaptive_profile"])
	adaptiveDecision, adaptiveDecisionOk := asTrimmedString(agent["adaptive_decision"])
	nativeSenderLifecycle, nativeSenderLifecycleOK := asTrimmedString(agent["native_sender_lifecycle"])
	nativeSenderSignalingState, nativeSenderSignalingStateOK := asTrimmedString(agent["native_sender_signaling_state"])
	nativeSenderDryRun, nativeSenderDryRunOK := asBool(agent["native_sender_dry_run"])
	nativeSenderSignalCount, nativeSenderSignalCountOK := asNumber(agent["native_sender_signal_count"])
	nativeSenderLastSignalType, nativeSenderLastSignalTypeOK := asTrimmedString(agent["native_sender_last_signal_type"])
	nativeSenderLastSignalDirection, nativeSenderLastSignalDirectionOK := asTrimmedString(agent["native_sender_last_signal_direction"])
	nativeSenderLastSignalTraceID, nativeSenderLastSignalTraceIDOK := asTrimmedString(agent["native_sender_last_signal_trace_id"])
	nativeSenderLastSignalPayloadBytes, nativeSenderLastSignalPayloadBytesOK := asNumber(agent["native_sender_last_signal_payload_bytes"])
	nativeSenderInboundSignalCount, nativeSenderInboundSignalCountOK := asNumber(agent["native_sender_inbound_signal_count"])
	nativeSenderOutboundSignalCount, nativeSenderOutboundSignalCountOK := asNumber(agent["native_sender_outbound_signal_count"])
	nativeSenderLocalOfferCount, nativeSenderLocalOfferCountOK := asNumber(agent["native_sender_local_offer_count"])
	nativeSenderLocalAnswerCount, nativeSenderLocalAnswerCountOK := asNumber(agent["native_sender_local_answer_count"])
	nativeSenderLocalCandidateCount, nativeSenderLocalCandidateCountOK := asNumber(agent["native_sender_local_candidate_count"])
	nativeSenderRemoteOfferCount, nativeSenderRemoteOfferCountOK := asNumber(agent["native_sender_remote_offer_count"])
	nativeSenderRemoteAnswerCount, nativeSenderRemoteAnswerCountOK := asNumber(agent["native_sender_remote_answer_count"])
	nativeSenderRemoteCandidateCount, nativeSenderRemoteCandidateCountOK := asNumber(agent["native_sender_remote_candidate_count"])
	nativeSenderRestartIceCount, nativeSenderRestartIceCountOK := asNumber(agent["native_sender_restart_ice_count"])
	nativeSenderLocalRestartIceCount, nativeSenderLocalRestartIceCountOK := asNumber(agent["native_sender_local_restart_ice_count"])
	nativeSenderRemoteRestartIceCount, nativeSenderRemoteRestartIceCountOK := asNumber(agent["native_sender_remote_restart_ice_count"])
	nativeSenderRemoteAnswerSdpLen, nativeSenderRemoteAnswerSdpLenOK := asNumber(agent["native_sender_remote_answer_sdp_len"])
	nativeSenderRemoteOfferSdpLen, nativeSenderRemoteOfferSdpLenOK := asNumber(agent["native_sender_remote_offer_sdp_len"])
	nativeSenderLastRemoteCandidateType, nativeSenderLastRemoteCandidateTypeOK := asTrimmedString(agent["native_sender_last_remote_candidate_type"])
	nativeSenderProbeRunning, nativeSenderProbeRunningOK := asBool(agent["native_sender_probe_running"])
	nativeSenderProbeFps, nativeSenderProbeFpsOK := asNumber(agent["native_sender_probe_fps"])
	nativeSenderProbeKbps, nativeSenderProbeKbpsOK := asNumber(agent["native_sender_probe_kbps"])
	nativeSenderProbeFrameCount, nativeSenderProbeFrameCountOK := asNumber(agent["native_sender_probe_frame_count"])
	nativeSenderProbeTotalBytes, nativeSenderProbeTotalBytesOK := asNumber(agent["native_sender_probe_total_bytes"])
	nativeSenderProbeFrameWidth, nativeSenderProbeFrameWidthOK := asNumber(agent["native_sender_probe_frame_width"])
	nativeSenderProbeFrameHeight, nativeSenderProbeFrameHeightOK := asNumber(agent["native_sender_probe_frame_height"])
	nativeSenderProbeLastFrameTsMs, nativeSenderProbeLastFrameTsMsOK := asNumber(agent["native_sender_probe_last_frame_ts_ms"])
	nativeSenderShadowRuntimeReady, nativeSenderShadowRuntimeReadyOK := asBool(agent["native_sender_shadow_runtime_ready"])
	nativeSenderShadowTrackBound, nativeSenderShadowTrackBoundOK := asBool(agent["native_sender_shadow_track_bound"])
	nativeSenderShadowLastApplyAction, nativeSenderShadowLastApplyActionOK := asTrimmedString(agent["native_sender_shadow_last_apply_action"])
	nativeSenderLastErrorCode, nativeSenderLastErrorCodeOK := asTrimmedString(agent["native_sender_last_error_code"])
	runtimeSignatureVersion, runtimeSignatureVersionOK := asNumber(agent["runtime_signature_version"])
	runtimeKernel, runtimeKernelOK := asTrimmedString(agent["runtime_kernel"])
	runtimeCapabilitySignature, runtimeCapabilitySignatureOK := asTrimmedString(agent["runtime_capability_signature"])
	runtimeEngine, runtimeEngineOK := asTrimmedString(agent["runtime_engine"])
	runtimeShellPlatform, runtimeShellPlatformOK := asTrimmedString(agent["runtime_shell_platform"])
	runtimeBrowserPlatform, runtimeBrowserPlatformOK := asTrimmedString(agent["runtime_browser_platform"])
	runtimeUserAgent, runtimeUserAgentOK := asTrimmedString(agent["runtime_user_agent"])
	runtimeCapFetch, runtimeCapFetchOK := asBool(agent["runtime_cap_fetch"])
	runtimeCapMediaStream, runtimeCapMediaStreamOK := asBool(agent["runtime_cap_media_stream"])
	runtimeCapVideoFrame, runtimeCapVideoFrameOK := asBool(agent["runtime_cap_video_frame"])
	runtimeCapTrackGenerator, runtimeCapTrackGeneratorOK := asBool(agent["runtime_cap_track_generator"])
	runtimeCapDisplayMedia, runtimeCapDisplayMediaOK := asBool(agent["runtime_cap_display_media"])
	runtimeCapNativeSender, runtimeCapNativeSenderOK := asBool(agent["runtime_cap_native_sender"])
	runtimeNativeSenderSupportLevel, runtimeNativeSenderSupportLevelOK := asTrimmedString(agent["runtime_native_sender_support_level"])
	runtimeNativeSenderBlocker, runtimeNativeSenderBlockerOK := asTrimmedString(agent["runtime_native_sender_blocker"])

	if firstFrameOk {
		summary["first_frame_ms"] = firstFrameMs
	}
	if renderFpsOk {
		summary["render_fps_avg"] = renderFpsAvg
	}
	if recvKbpsOk {
		summary["recv_kbps_avg"] = recvKbpsAvg
	}
	if renderedFramesOk {
		summary["rendered_frames"] = renderedFrames
	}
	if rttMsAvgOk {
		summary["rtt_ms_avg"] = rttMsAvg
	}
	if icePolicyRestartsOk {
		summary["ice_policy_restarts"] = icePolicyRestarts
	}
	if renderLongestFrameGapMsOK {
		summary["render_longest_frame_gap_ms"] = renderLongestFrameGapMs
	}
	if renderFrameGapSpikeCountOK {
		summary["render_frame_gap_spike_count"] = renderFrameGapSpikeCount
	}
	if renderLowFpsSampleCountOK {
		summary["render_low_fps_sample_count"] = renderLowFpsSampleCount
	}
	if renderLongestLowFpsStreakMsOK {
		summary["render_longest_low_fps_streak_ms"] = renderLongestLowFpsStreakMs
	}
	if framesDroppedLastOK {
		summary["frames_dropped_last"] = framesDroppedLast
	}
	if framesDroppedSpikeMaxOK {
		summary["frames_dropped_spike_max"] = framesDroppedSpikeMax
	}
	if controllerQualityHintOK {
		summary["controller_quality_hint"] = controllerQualityHint
	}
	if remoteInputResultCountOK {
		summary["remote_input_result_count"] = remoteInputResultCount
	}
	if remoteInputResultAppliedCountOK {
		summary["remote_input_result_applied_count"] = remoteInputResultAppliedCount
	}
	if remoteInputResultFailedCountOK {
		summary["remote_input_result_failed_count"] = remoteInputResultFailedCount
	}
	if remoteInputAppliedClickOK {
		summary["remote_input_applied_click"] = remoteInputAppliedClick
	}
	if remoteInputAppliedDragOK {
		summary["remote_input_applied_drag"] = remoteInputAppliedDrag
	}
	if remoteInputAppliedKeyboardOK {
		summary["remote_input_applied_keyboard"] = remoteInputAppliedKeyboard
	}
	if remoteInputAppliedWheelOK {
		summary["remote_input_applied_wheel"] = remoteInputAppliedWheel
	}
	if remoteInputCoverageCompleteOK {
		summary["remote_input_required_coverage_complete"] = remoteInputCoverageCompleteValue
	}
	if remoteInputAppliedCategoriesOK {
		summary["remote_input_applied_categories"] = remoteInputAppliedCategories
	}
	if remoteInputLastTypeOK {
		summary["remote_input_last_type"] = remoteInputLastType
	}
	if remoteInputLastCategoryOK {
		summary["remote_input_last_category"] = remoteInputLastCategory
	}
	if remoteInputLastTraceIDOK {
		summary["remote_input_last_trace_id"] = remoteInputLastTraceID
	}
	if remoteInputLastAppliedOK {
		summary["remote_input_last_applied"] = remoteInputLastApplied
	}
	if remoteInputLastExecutorOK {
		summary["remote_input_last_executor"] = remoteInputLastExecutor
	}
	if remoteInputLastStatusCodeOK {
		summary["remote_input_last_status_code"] = remoteInputLastStatusCode
	}
	if remoteInputLastStatusDetailOK {
		summary["remote_input_last_status_detail"] = remoteInputLastStatusDetail
	}
	if remoteInputLastErrorCodeOK {
		summary["remote_input_last_error_code"] = remoteInputLastErrorCode
	}
	if remoteInputLastErrorDetailOK {
		summary["remote_input_last_error_detail"] = remoteInputLastErrorDetail
	}
	if remoteInputLastSummaryOK {
		summary["remote_input_last_summary"] = remoteInputLastSummary
	}
	if remoteInputLastCountOK {
		summary["remote_input_last_count"] = remoteInputLastCount
	}
	if remoteInputLastTargetDeviceIDOK {
		summary["remote_input_last_target_device_id"] = remoteInputLastTargetDeviceID
	}
	if candidatePairLastOk {
		summary["candidate_pair_last"] = candidatePairLast
	}
	if candidateTierLastOk {
		summary["candidate_tier_last"] = candidateTierLast
	}
	if sendFpsOk {
		summary["send_fps"] = sendFps
	}
	if sendKbpsOk {
		summary["send_kbps"] = sendKbps
	}
	if captureFpsOk {
		summary["capture_fps"] = captureFps
	}
	if agentRttMsOk {
		summary["agent_rtt_ms"] = agentRttMs
	}
	if candidatePathOk {
		summary["candidate_path"] = candidatePath
	}
	if candidateTierOk {
		summary["candidate_tier"] = candidateTier
	}
	if qualityLimitOk {
		summary["quality_limit"] = qualityLimit
	}
	if adaptiveProfileOk {
		summary["adaptive_profile"] = adaptiveProfile
	}
	if adaptiveDecisionOk {
		summary["adaptive_decision"] = adaptiveDecision
	}
	if nativeSenderLifecycleOK {
		summary["native_sender_lifecycle"] = nativeSenderLifecycle
	}
	if nativeSenderSignalingStateOK {
		summary["native_sender_signaling_state"] = nativeSenderSignalingState
	}
	if nativeSenderDryRunOK {
		summary["native_sender_dry_run"] = nativeSenderDryRun
	}
	if nativeSenderSignalCountOK {
		summary["native_sender_signal_count"] = nativeSenderSignalCount
	}
	if nativeSenderLastSignalTypeOK {
		summary["native_sender_last_signal_type"] = nativeSenderLastSignalType
	}
	if nativeSenderLastSignalDirectionOK {
		summary["native_sender_last_signal_direction"] = nativeSenderLastSignalDirection
	}
	if nativeSenderLastSignalTraceIDOK {
		summary["native_sender_last_signal_trace_id"] = nativeSenderLastSignalTraceID
	}
	if nativeSenderLastSignalPayloadBytesOK {
		summary["native_sender_last_signal_payload_bytes"] = nativeSenderLastSignalPayloadBytes
	}
	if nativeSenderInboundSignalCountOK {
		summary["native_sender_inbound_signal_count"] = nativeSenderInboundSignalCount
	}
	if nativeSenderOutboundSignalCountOK {
		summary["native_sender_outbound_signal_count"] = nativeSenderOutboundSignalCount
	}
	if nativeSenderLocalOfferCountOK {
		summary["native_sender_local_offer_count"] = nativeSenderLocalOfferCount
	}
	if nativeSenderLocalAnswerCountOK {
		summary["native_sender_local_answer_count"] = nativeSenderLocalAnswerCount
	}
	if nativeSenderLocalCandidateCountOK {
		summary["native_sender_local_candidate_count"] = nativeSenderLocalCandidateCount
	}
	if nativeSenderRemoteOfferCountOK {
		summary["native_sender_remote_offer_count"] = nativeSenderRemoteOfferCount
	}
	if nativeSenderRemoteAnswerCountOK {
		summary["native_sender_remote_answer_count"] = nativeSenderRemoteAnswerCount
	}
	if nativeSenderRemoteCandidateCountOK {
		summary["native_sender_remote_candidate_count"] = nativeSenderRemoteCandidateCount
	}
	if nativeSenderRestartIceCountOK {
		summary["native_sender_restart_ice_count"] = nativeSenderRestartIceCount
	}
	if nativeSenderLocalRestartIceCountOK {
		summary["native_sender_local_restart_ice_count"] = nativeSenderLocalRestartIceCount
	}
	if nativeSenderRemoteRestartIceCountOK {
		summary["native_sender_remote_restart_ice_count"] = nativeSenderRemoteRestartIceCount
	}
	if nativeSenderRemoteAnswerSdpLenOK {
		summary["native_sender_remote_answer_sdp_len"] = nativeSenderRemoteAnswerSdpLen
	}
	if nativeSenderRemoteOfferSdpLenOK {
		summary["native_sender_remote_offer_sdp_len"] = nativeSenderRemoteOfferSdpLen
	}
	if nativeSenderLastRemoteCandidateTypeOK {
		summary["native_sender_last_remote_candidate_type"] = nativeSenderLastRemoteCandidateType
	}
	if nativeSenderProbeRunningOK {
		summary["native_sender_probe_running"] = nativeSenderProbeRunning
	}
	if nativeSenderProbeFpsOK {
		summary["native_sender_probe_fps"] = nativeSenderProbeFps
	}
	if nativeSenderProbeKbpsOK {
		summary["native_sender_probe_kbps"] = nativeSenderProbeKbps
	}
	if nativeSenderProbeFrameCountOK {
		summary["native_sender_probe_frame_count"] = nativeSenderProbeFrameCount
	}
	if nativeSenderProbeTotalBytesOK {
		summary["native_sender_probe_total_bytes"] = nativeSenderProbeTotalBytes
	}
	if nativeSenderProbeFrameWidthOK {
		summary["native_sender_probe_frame_width"] = nativeSenderProbeFrameWidth
	}
	if nativeSenderProbeFrameHeightOK {
		summary["native_sender_probe_frame_height"] = nativeSenderProbeFrameHeight
	}
	if nativeSenderProbeLastFrameTsMsOK {
		summary["native_sender_probe_last_frame_ts_ms"] = nativeSenderProbeLastFrameTsMs
	}
	if nativeSenderShadowRuntimeReadyOK {
		summary["native_sender_shadow_runtime_ready"] = nativeSenderShadowRuntimeReady
	}
	if nativeSenderShadowTrackBoundOK {
		summary["native_sender_shadow_track_bound"] = nativeSenderShadowTrackBound
	}
	if nativeSenderShadowLastApplyActionOK {
		summary["native_sender_shadow_last_apply_action"] = nativeSenderShadowLastApplyAction
	}
	if nativeSenderLastErrorCodeOK {
		summary["native_sender_last_error_code"] = nativeSenderLastErrorCode
	}
	if canvasShareOk {
		summary["bridge_canvas_share_pct"] = canvasSharePct
	}
	if bridgeCapabilityTierOk {
		summary["bridge_capability_tier"] = bridgeCapabilityTier
	}
	if runtimeSignatureVersionOK {
		summary["runtime_signature_version"] = runtimeSignatureVersion
	}
	if runtimeKernelOK {
		summary["runtime_kernel"] = runtimeKernel
	}
	if runtimeCapabilitySignatureOK {
		summary["runtime_capability_signature"] = runtimeCapabilitySignature
	}
	if runtimeEngineOK {
		summary["runtime_engine"] = runtimeEngine
	}
	if runtimeShellPlatformOK {
		summary["runtime_shell_platform"] = runtimeShellPlatform
	}
	if runtimeBrowserPlatformOK {
		summary["runtime_browser_platform"] = runtimeBrowserPlatform
	}
	if runtimeUserAgentOK {
		summary["runtime_user_agent"] = runtimeUserAgent
	}
	if runtimeCapFetchOK {
		summary["runtime_cap_fetch"] = runtimeCapFetch
	}
	if runtimeCapMediaStreamOK {
		summary["runtime_cap_media_stream"] = runtimeCapMediaStream
	}
	if runtimeCapVideoFrameOK {
		summary["runtime_cap_video_frame"] = runtimeCapVideoFrame
	}
	if runtimeCapTrackGeneratorOK {
		summary["runtime_cap_track_generator"] = runtimeCapTrackGenerator
	}
	if runtimeCapDisplayMediaOK {
		summary["runtime_cap_display_media"] = runtimeCapDisplayMedia
	}
	if runtimeCapNativeSenderOK {
		summary["runtime_cap_native_sender"] = runtimeCapNativeSender
	}
	if runtimeNativeSenderSupportLevelOK {
		summary["runtime_native_sender_support_level"] = runtimeNativeSenderSupportLevel
	}
	if runtimeNativeSenderBlockerOK {
		summary["runtime_native_sender_blocker"] = runtimeNativeSenderBlocker
	}
	if controllerPlatformOK {
		summary["session_controller_platform"] = controllerPlatform
	}
	if agentPlatformOK {
		summary["session_agent_platform"] = agentPlatform
	}
	if sessionRouteOK {
		summary["session_e2e_route_key"] = sessionRouteKey
		summary["session_e2e_route"] = sessionRouteLabel
		summary["session_e2e_target_route"] = sessionRouteTarget
	}

	if len(summary) == 0 {
		return nil
	}
	remoteInputCoverage := remoteInputCoverageFromCombined(summary)
	remoteInputCoverageOK := remoteInputCoverageComplete(remoteInputCoverage)
	remoteInputCoverageText := strings.Join(remoteInputCoverage, ",")
	if len(remoteInputCoverage) > 0 {
		summary["remote_input_coverage"] = remoteInputCoverageText
	}

	sessionE2EVideoObserved, sessionE2EInputObserved, sessionE2EProofStatus := deriveSessionE2EProofStatus(
		firstFrameMs, firstFrameOk,
		renderedFrames, renderedFramesOk,
		remoteInputResultAppliedCount, remoteInputResultAppliedCountOK,
		remoteInputLastApplied, remoteInputLastAppliedOK,
	)
	if sessionE2EInputObserved && !remoteInputCoverageOK {
		if sessionE2EVideoObserved {
			sessionE2EProofStatus = "video_and_partial_input_observed"
		} else {
			sessionE2EProofStatus = "partial_input_only"
		}
	}
	summary["session_e2e_video_observed"] = sessionE2EVideoObserved
	summary["session_e2e_input_observed"] = sessionE2EInputObserved
	summary["session_e2e_proof_status"] = sessionE2EProofStatus

	resolvedTier := candidateTierLast
	resolvedTierOK := candidateTierLastOk
	if !resolvedTierOK && candidateTierOk {
		resolvedTier = candidateTier
		resolvedTierOK = true
		summary["candidate_tier_last"] = candidateTier
	}
	resolvedPath := candidatePath
	resolvedPathOK := candidatePathOk
	if !resolvedPathOK && candidatePairLastOk {
		resolvedPath = candidatePairLast
		resolvedPathOK = true
	}
	if !candidatePairLastOk && candidatePathOk {
		summary["candidate_pair_last"] = candidatePath
	}

	summary["session_quality_hint"] = inferSessionQualityHint(
		renderFpsAvg, renderFpsOk,
		recvKbpsAvg, recvKbpsOk,
		sendFps, sendFpsOk,
		rttMsAvg, rttMsAvgOk,
		renderLongestFrameGapMs, renderLongestFrameGapMsOK,
		renderLongestLowFpsStreakMs, renderLongestLowFpsStreakMsOK,
		framesDroppedSpikeMax, framesDroppedSpikeMaxOK,
		resolvedTier, resolvedTierOK,
		canvasSharePct, canvasShareOk,
		bridgeCapabilityTier, bridgeCapabilityTierOk,
	)
	summary["session_perf_summary"] = fmt.Sprintf(
		"route=%s first_frame_ms=%s render_fps_avg=%s recv_kbps_avg=%s stutter_gap_ms=%s low_fps_streak_ms=%s drop_spike=%s send_fps=%s send_kbps=%s path=%s tier=%s canvas_share=%s remote_input_applied=%s/%s input_coverage=%s last_executor=%s last_status=%s",
		formatSummaryString(sessionRouteLabel, sessionRouteOK),
		formatSummaryNumber(firstFrameMs, firstFrameOk, 0),
		formatSummaryNumber(renderFpsAvg, renderFpsOk, 2),
		formatSummaryNumber(recvKbpsAvg, recvKbpsOk, 2),
		formatSummaryNumber(renderLongestFrameGapMs, renderLongestFrameGapMsOK, 0),
		formatSummaryNumber(renderLongestLowFpsStreakMs, renderLongestLowFpsStreakMsOK, 0),
		formatSummaryNumber(framesDroppedSpikeMax, framesDroppedSpikeMaxOK, 0),
		formatSummaryNumber(sendFps, sendFpsOk, 2),
		formatSummaryNumber(sendKbps, sendKbpsOk, 2),
		formatSummaryString(resolvedPath, resolvedPathOK),
		formatSummaryString(resolvedTier, resolvedTierOK),
		formatSummaryPercent(canvasSharePct, canvasShareOk),
		formatSummaryNumber(remoteInputResultAppliedCount, remoteInputResultAppliedCountOK, 0),
		formatSummaryNumber(remoteInputResultCount, remoteInputResultCountOK, 0),
		formatSummaryString(remoteInputCoverageText, len(remoteInputCoverage) > 0),
		formatSummaryString(remoteInputLastExecutor, remoteInputLastExecutorOK),
		formatSummaryString(remoteInputLastStatusCode, remoteInputLastStatusCodeOK),
	)
	summary["session_e2e_proof_summary"] = fmt.Sprintf(
		"session_e2e_proof_status=%s route_key=%s route=%s target_route=%t video_observed=%t input_observed=%t input_coverage=%s first_frame_ms=%s rendered_frames=%s remote_input_applied=%s/%s last_executor=%s last_status=%s",
		sessionE2EProofStatus,
		formatSummaryString(sessionRouteKey, sessionRouteOK),
		formatSummaryString(sessionRouteLabel, sessionRouteOK),
		sessionRouteTarget,
		sessionE2EVideoObserved,
		sessionE2EInputObserved,
		formatSummaryString(remoteInputCoverageText, len(remoteInputCoverage) > 0),
		formatSummaryNumber(firstFrameMs, firstFrameOk, 0),
		formatSummaryNumber(renderedFrames, renderedFramesOk, 0),
		formatSummaryNumber(remoteInputResultAppliedCount, remoteInputResultAppliedCountOK, 0),
		formatSummaryNumber(remoteInputResultCount, remoteInputResultCountOK, 0),
		formatSummaryString(remoteInputLastExecutor, remoteInputLastExecutorOK),
		formatSummaryString(remoteInputLastStatusCode, remoteInputLastStatusCodeOK),
	)
	return summary
}

func deriveSessionE2EProofStatus(
	firstFrameMs float64,
	firstFrameOK bool,
	renderedFrames float64,
	renderedFramesOK bool,
	remoteInputAppliedCount float64,
	remoteInputAppliedCountOK bool,
	remoteInputLastApplied bool,
	remoteInputLastAppliedOK bool,
) (bool, bool, string) {
	videoObserved := (firstFrameOK && firstFrameMs >= 0) || (renderedFramesOK && renderedFrames > 0)
	inputObserved := (remoteInputAppliedCountOK && remoteInputAppliedCount > 0) || (remoteInputLastAppliedOK && remoteInputLastApplied)
	switch {
	case videoObserved && inputObserved:
		return videoObserved, inputObserved, "video_and_input_observed"
	case videoObserved:
		return videoObserved, inputObserved, "video_only"
	case inputObserved:
		return videoObserved, inputObserved, "input_only"
	default:
		return videoObserved, inputObserved, "missing"
	}
}

func resolveSessionReportPlatform(report map[string]any) (string, bool) {
	if value, ok := asTrimmedString(report["source_platform"]); ok {
		if normalized, normalizedOK := normalizeSessionRoutePlatform(value); normalizedOK && normalized != "desktop" {
			return normalized, true
		}
	}
	if value, ok := asTrimmedString(report["runtime_shell_platform"]); ok {
		return normalizeSessionRoutePlatform(value)
	}
	if value, ok := asTrimmedString(report["runtime_kernel"]); ok {
		for _, part := range strings.Split(value, "|") {
			if normalized, ok := normalizeKnownSessionRoutePlatform(part); ok {
				return normalized, true
			}
		}
	}
	if value, ok := asTrimmedString(report["source_platform"]); ok {
		return normalizeSessionRoutePlatform(value)
	}
	return "", false
}

func normalizeKnownSessionRoutePlatform(value string) (string, bool) {
	normalized, ok := normalizeSessionRoutePlatform(value)
	if !ok {
		return "", false
	}
	switch normalized {
	case "android", "windows", "macos":
		return normalized, true
	default:
		return "", false
	}
}

func normalizeSessionRoutePlatform(value string) (string, bool) {
	normalized := strings.TrimSpace(strings.ToLower(value))
	if normalized == "" || normalized == "-" {
		return "", false
	}
	switch {
	case normalized == "android" || strings.Contains(normalized, "android"):
		return "android", true
	case normalized == "windows" || normalized == "win32" || strings.Contains(normalized, "windows"):
		return "windows", true
	case normalized == "macos" || normalized == "darwin" || normalized == "mac" || strings.Contains(normalized, "macos"):
		return "macos", true
	default:
		return normalized, true
	}
}

func deriveSessionE2ERoute(
	controllerPlatform string,
	controllerPlatformOK bool,
	agentPlatform string,
	agentPlatformOK bool,
) (string, string, bool, bool) {
	if !controllerPlatformOK || !agentPlatformOK {
		return "", "", false, false
	}
	routeKey := fmt.Sprintf("%s_to_%s", controllerPlatform, agentPlatform)
	routeLabel := fmt.Sprintf("%s->%s", controllerPlatform, agentPlatform)
	return routeKey, routeLabel, isTargetE2ERoute(routeKey), true
}

func isTargetE2ERoute(routeKey string) bool {
	switch routeKey {
	case "android_to_windows", "android_to_macos", "windows_to_windows", "windows_to_macos":
		return true
	default:
		return false
	}
}

func inferSessionQualityHint(
	renderFps float64,
	renderFpsOK bool,
	recvKbps float64,
	recvKbpsOK bool,
	sendFps float64,
	sendFpsOK bool,
	rttMs float64,
	rttMsOK bool,
	renderLongestFrameGapMs float64,
	renderLongestFrameGapMsOK bool,
	renderLongestLowFpsStreakMs float64,
	renderLongestLowFpsStreakMsOK bool,
	framesDroppedSpikeMax float64,
	framesDroppedSpikeMaxOK bool,
	candidateTier string,
	candidateTierOK bool,
	canvasSharePct float64,
	canvasShareOK bool,
	bridgeCapabilityTier string,
	bridgeCapabilityTierOK bool,
) string {
	if bridgeCapabilityTierOK && strings.EqualFold(strings.TrimSpace(bridgeCapabilityTier), "capability_blocked") {
		return "capability_blocked"
	}
	if candidateTierOK {
		tier := strings.TrimSpace(strings.ToLower(candidateTier))
		if tier == "relay_tcp" || tier == "p2p_tcp" || tier == "relay_udp_high_rtt" {
			return fmt.Sprintf("path_%s", tier)
		}
	}
	if rttMsOK && rttMs >= qualityHintRttHighMs {
		return "rtt_high"
	}
	if canvasShareOK && canvasSharePct >= qualityHintCanvasHeavyPct {
		return "canvas_fallback_heavy"
	}
	if renderLongestFrameGapMsOK && renderLongestFrameGapMs >= qualityHintFrameGapSpikeMs {
		return "render_frame_stutter"
	}
	if renderLongestLowFpsStreakMsOK && renderLongestLowFpsStreakMs >= qualityHintLowFpsStreakMs {
		return "render_fps_streak"
	}
	if framesDroppedSpikeMaxOK && framesDroppedSpikeMax >= qualityHintDroppedFrameSpike {
		return "frames_dropped_spike"
	}
	if renderFpsOK && renderFps > 0 && renderFps < qualityHintFpsLowThreshold {
		return "render_fps_low"
	}
	if recvKbpsOK && recvKbps >= 0 {
		likelyStall := renderFpsOK && renderFps >= 0 && renderFps < qualityHintStallFpsThreshold
		if likelyStall && recvKbps < qualityHintBitrateLowKbps {
			return "recv_bitrate_low"
		}
	}
	if sendFpsOK && sendFps > 0 && sendFps < qualityHintFpsLowThreshold {
		return "send_fps_low"
	}
	return "stable"
}

func formatSummaryNumber(value float64, ok bool, digits int) string {
	if !ok {
		return "-"
	}
	pattern := fmt.Sprintf("%%.%df", digits)
	return fmt.Sprintf(pattern, value)
}

func formatSummaryPercent(value float64, ok bool) string {
	if !ok {
		return "-"
	}
	return fmt.Sprintf("%.2f%%", value)
}

func formatSummaryString(value string, ok bool) string {
	if !ok {
		return "-"
	}
	return value
}

func asTrimmedString(raw any) (string, bool) {
	value, ok := raw.(string)
	if !ok {
		return "", false
	}
	value = strings.TrimSpace(value)
	if value == "" || value == "-" {
		return "", false
	}
	return value, true
}

func asNumber(raw any) (float64, bool) {
	switch value := raw.(type) {
	case float64:
		if math.IsNaN(value) || math.IsInf(value, 0) {
			return 0, false
		}
		return value, true
	case int:
		return float64(value), true
	case int64:
		return float64(value), true
	case uint64:
		return float64(value), true
	default:
		return 0, false
	}
}

func asBool(raw any) (bool, bool) {
	value, ok := raw.(bool)
	if !ok {
		return false, false
	}
	return value, true
}

func (h *Hub) cleanupMetricsStateLocked(now time.Time) {
	for sessionID, record := range h.endedSessions {
		if now.Sub(record.endedAt) > sessionMetricsRetentionWindow {
			delete(h.endedSessions, sessionID)
		}
	}
	for sessionID, aggregate := range h.sessionMetrics {
		if aggregate == nil || now.Sub(aggregate.updatedAt) > sessionMetricsRetentionWindow {
			delete(h.sessionMetrics, sessionID)
		}
	}
}

func (h *Hub) registerClient(deviceID string, client *clientConn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.clients[deviceID] = client
}

func (h *Hub) unregisterClient(deviceID string, client *clientConn) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	current, ok := h.clients[deviceID]
	if ok && current == client {
		delete(h.clients, deviceID)
		return true
	}
	return false
}

func (h *Hub) hasConnectedClient(deviceID string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	_, ok := h.clients[deviceID]
	return ok
}

func (h *Hub) isAuthorizedClient(deviceID string, client *clientConn) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	current, ok := h.clients[deviceID]
	return ok && current == client
}

func (h *Hub) getClient(deviceID string) (*clientConn, bool) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	client, ok := h.clients[deviceID]
	return client, ok
}

func (h *Hub) snapshotClients() map[string]*clientConn {
	h.mu.RLock()
	defer h.mu.RUnlock()
	snapshot := make(map[string]*clientConn, len(h.clients))
	for deviceID, client := range h.clients {
		snapshot[deviceID] = client
	}
	return snapshot
}

func (h *Hub) broadcastPresenceSnapshot(reason string, changedDeviceID string) {
	clients := h.snapshotClients()
	if len(clients) == 0 {
		return
	}
	message := protocol.Envelope{
		Version:   h.cfg.ProtocolVersion,
		MessageID: fmt.Sprintf("presence-push-%d", time.Now().UnixMilli()),
		Type:      "device.presence.push",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "",
		From:      protocol.From{DeviceID: "server", Role: "server"},
		Payload: map[string]any{
			"reason":            reason,
			"changed_device_id": changedDeviceID,
			"server_time":       time.Now().UnixMilli(),
			"devices":           h.registry.PublicList(),
		},
	}

	successCount := 0
	failedCount := 0
	for deviceID, client := range clients {
		if h.writeEnvelope(client, message) {
			successCount += 1
			continue
		}
		failedCount += 1
		if h.unregisterClient(deviceID, client) {
			if _, changed := h.registry.MarkOffline(deviceID); changed {
				h.logger.Info(map[string]any{
					"event":     "device.offline_on_presence_push_failure",
					"device_id": deviceID,
					"reason":    reason,
				})
			}
		}
		_ = client.conn.Close()
	}

	h.logger.Info(map[string]any{
		"event":               "device.presence.push.broadcasted",
		"reason":              reason,
		"changed_device_id":   changedDeviceID,
		"devices_count":       len(message.Payload["devices"].([]presence.PublicDevice)),
		"clients_total":       len(clients),
		"clients_success":     successCount,
		"clients_failed":      failedCount,
		"push_message_type":   message.Type,
		"push_message_id":     message.MessageID,
		"push_timestamp_unix": message.Timestamp,
	})
}

func (h *Hub) writeToDevice(deviceID string, msg protocol.Envelope) bool {
	client, ok := h.getClient(deviceID)
	if !ok {
		h.logger.Info(map[string]any{
			"event":      "ws.forward.miss",
			"to_device":  deviceID,
			"type":       msg.Type,
			"session_id": msg.SessionID,
			"trace_id":   msg.TraceID,
			"msg_id":     msg.MessageID,
		})
		return false
	}
	h.logger.Info(map[string]any{
		"event":       "ws.forward",
		"to_device":   deviceID,
		"from_device": msg.From.DeviceID,
		"type":        msg.Type,
		"session_id":  msg.SessionID,
		"trace_id":    msg.TraceID,
		"msg_id":      msg.MessageID,
		"payload":     summarizeSignalPayload(msg),
	})
	if h.writeEnvelope(client, msg) {
		return true
	}
	h.logger.Error(map[string]any{
		"event":      "ws.forward.failed",
		"to_device":  deviceID,
		"type":       msg.Type,
		"session_id": msg.SessionID,
		"trace_id":   msg.TraceID,
		"msg_id":     msg.MessageID,
	})
	if h.unregisterClient(deviceID, client) {
		if _, changed := h.registry.MarkOffline(deviceID); changed {
			h.broadcastPresenceSnapshot("forward_write_failed", deviceID)
		}
	}
	_ = client.conn.Close()
	return false
}

func (h *Hub) writeEnvelope(client *clientConn, msg protocol.Envelope) bool {
	data, err := protocol.Encode(msg)
	if err != nil {
		h.logger.Error(map[string]any{"event": "ws.encode_failed", "error": err.Error()})
		return false
	}
	client.mu.Lock()
	defer client.mu.Unlock()
	if err := client.conn.WriteMessage(websocket.TextMessage, data); err != nil {
		h.logger.Error(map[string]any{"event": "ws.write_failed", "error": err.Error()})
		return false
	}
	return true
}

func (h *Hub) writeError(client *clientConn, traceID, requestID string, code int, name, message, deviceID string) {
	h.writeEnvelope(client, protocol.Envelope{
		Version:   h.cfg.ProtocolVersion,
		MessageID: fmt.Sprintf("error-%d", time.Now().UnixMilli()),
		Type:      "error.rsp",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   traceID,
		From:      protocol.From{DeviceID: "server", Role: "server"},
		Payload: map[string]any{
			"code":           code,
			"name":           name,
			"message":        message,
			"request_msg_id": requestID,
			"retryable":      false,
			"device_id":      deviceID,
		},
	})
}

func isAllowedStatus(status string) bool {
	return status == "online" || status == "busy"
}

func summarizeWebRTCSignal(msg protocol.Envelope) map[string]any {
	summary := map[string]any{
		"type": msg.Type,
	}
	for key, value := range summarizeSignalPayload(msg) {
		summary[key] = value
	}
	return summary
}

func summarizeSignalPayload(msg protocol.Envelope) map[string]any {
	switch msg.Type {
	case "webrtc.offer", "webrtc.answer":
		sdp, _ := msg.Payload["sdp"].(string)
		return map[string]any{
			"sdp_len":           len(strings.TrimSpace(sdp)),
			"sdp_has_video":     strings.Contains(sdp, "m=video"),
			"sdp_has_candidate": strings.Contains(sdp, "a=candidate:"),
		}
	case "webrtc.ice_candidate":
		candidate, _ := msg.Payload["candidate"].(string)
		sdpMid, _ := msg.Payload["sdp_mid"].(string)
		return map[string]any{
			"candidate_type": extractIceCandidateType(candidate),
			"candidate_len":  len(strings.TrimSpace(candidate)),
			"sdp_mid":        strings.TrimSpace(sdpMid),
			"sdp_mline":      extractMLineIndex(msg.Payload),
		}
	case "webrtc.restart_ice":
		reason, _ := msg.Payload["reason"].(string)
		return map[string]any{
			"reason": strings.TrimSpace(reason),
		}
	default:
		return map[string]any{
			"payload_keys": payloadKeys(msg.Payload),
		}
	}
}

func extractIceCandidateType(candidate string) string {
	marker := " typ "
	index := strings.Index(candidate, marker)
	if index < 0 {
		return "unknown"
	}
	start := index + len(marker)
	if start >= len(candidate) {
		return "unknown"
	}
	rest := candidate[start:]
	end := strings.Index(rest, " ")
	if end < 0 {
		return strings.TrimSpace(rest)
	}
	return strings.TrimSpace(rest[:end])
}

func extractMLineIndex(payload map[string]any) int {
	raw, ok := payload["sdp_mline_index"]
	if !ok {
		return -1
	}
	switch value := raw.(type) {
	case float64:
		return int(value)
	case int:
		return value
	default:
		return -1
	}
}

func payloadKeys(payload map[string]any) string {
	if len(payload) == 0 {
		return "-"
	}
	keys := make([]string, 0, len(payload))
	for key := range payload {
		keys = append(keys, key)
	}
	return strings.Join(keys, ",")
}
