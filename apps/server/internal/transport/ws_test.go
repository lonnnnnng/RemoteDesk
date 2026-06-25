package transport

import (
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"remote_desk/apps/server/internal/config"
	"remote_desk/apps/server/internal/observability"
	"remote_desk/apps/server/internal/presence"
	"remote_desk/apps/server/internal/protocol"
	"remote_desk/apps/server/internal/store"
)

const allowedOrigin = "http://allowed.test"

func TestWSFlowRelayAndHTTPVisibility(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	controller := dialWS(t, server, allowedOrigin)
	defer controller.Close()

	agent := dialWS(t, server, allowedOrigin)
	defer agent.Close()

	controllerToken := registerDevice(t, controller, "controller-01", "controller")
	agentToken := registerDevice(t, agent, "agent-01", "agent")

	assertHealthz(t, server)
	assertDevicesListRedacted(t, server)

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "heartbeat-invalid",
		Type:      "presence.heartbeat.req",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "trace-heartbeat-invalid",
		From:      protocol.From{DeviceID: "controller-01", Role: "controller"},
		Payload: map[string]any{
			"token":             "bad-token",
			"status":            "online",
			"active_session_id": nil,
		},
	})
	invalidHeartbeat := readEnvelopeOfType(t, controller, "error.rsp")
	if invalidHeartbeat.Type != "error.rsp" {
		t.Fatalf("expected error.rsp, got %s", invalidHeartbeat.Type)
	}
	if code := asInt(t, invalidHeartbeat.Payload["code"]); code != 1001 {
		t.Fatalf("expected AUTH_INVALID_TOKEN code 1001, got %d", code)
	}

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "heartbeat-valid",
		Type:      "presence.heartbeat.req",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "trace-heartbeat-valid",
		From:      protocol.From{DeviceID: "controller-01", Role: "controller"},
		Payload: map[string]any{
			"token":             controllerToken,
			"status":            "online",
			"active_session_id": nil,
		},
	})
	validHeartbeat := readEnvelopeOfType(t, controller, "presence.heartbeat.rsp")
	if validHeartbeat.Type != "presence.heartbeat.rsp" {
		t.Fatalf("expected presence.heartbeat.rsp, got %s", validHeartbeat.Type)
	}

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "request-1",
		Type:      "session.request.req",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "trace-request-1",
		From:      protocol.From{DeviceID: "controller-01", Role: "controller"},
		Payload: map[string]any{
			"target_device_id": "agent-01",
			"request_id":       "req-1",
			"auth_mode":        "consent_required",
		},
	})

	requestResult := readEnvelopeOfType(t, controller, "session.request.result.push")
	if requestResult.Type != "session.request.result.push" {
		t.Fatalf("expected session.request.result.push, got %s", requestResult.Type)
	}
	sessionID, _ := requestResult.Payload["session_id"].(string)
	if sessionID == "" {
		t.Fatal("expected session_id in request result")
	}
	controllerStart := readEnvelopeOfType(t, controller, "session.start.push")
	if controllerStart.Type != "session.start.push" {
		t.Fatalf("expected controller session.start.push, got %s", controllerStart.Type)
	}
	agentStart := readEnvelopeOfType(t, agent, "session.start.push")
	if agentStart.Type != "session.start.push" {
		t.Fatalf("expected agent session.start.push, got %s", agentStart.Type)
	}
	if controllerStart.SessionID != sessionID || agentStart.SessionID != sessionID {
		t.Fatalf("expected shared session id %s, got controller=%s agent=%s", sessionID, controllerStart.SessionID, agentStart.SessionID)
	}
	transport, ok := controllerStart.Payload["transport"].(map[string]any)
	if !ok {
		t.Fatalf("expected session.start transport payload, got %#v", controllerStart.Payload["transport"])
	}
	if mode, _ := transport["mode"].(string); mode != "webrtc" {
		t.Fatalf("expected transport.mode webrtc, got %q", mode)
	}
	if signalURL, _ := transport["signal_url"].(string); signalURL == "" {
		t.Fatalf("expected non-empty transport.signal_url, got %q", signalURL)
	}

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "input-1",
		Type:      "input.mouse.move",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-input-1",
		From:      protocol.From{DeviceID: "controller-01", Role: "controller"},
		Payload: map[string]any{
			"x": 0.25,
			"y": 0.75,
		},
	})
	forwardedInput := readEnvelopeOfType(t, agent, "input.mouse.move")
	if forwardedInput.Type != "input.mouse.move" {
		t.Fatalf("expected forwarded input.mouse.move, got %s", forwardedInput.Type)
	}
	inputAck := readEnvelopeOfType(t, controller, "input.ack")
	if inputAck.Type != "input.ack" {
		t.Fatalf("expected input.ack, got %s", inputAck.Type)
	}

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "offer-1",
		Type:      "webrtc.offer",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-offer-1",
		From:      protocol.From{DeviceID: "controller-01", Role: "controller"},
		Payload: map[string]any{
			"sdp_type": "offer",
			"sdp":      "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=remote_desk\r\n",
		},
	})
	forwardedOffer := readEnvelopeOfType(t, agent, "webrtc.offer")
	if forwardedOffer.Type != "webrtc.offer" {
		t.Fatalf("expected webrtc.offer, got %s", forwardedOffer.Type)
	}
	if got, _ := forwardedOffer.Payload["sdp_type"].(string); got != "offer" {
		t.Fatalf("unexpected offer payload sdp_type: %q", got)
	}

	sendEnvelope(t, agent, protocol.Envelope{
		Version:   "1.0",
		MessageID: "answer-1",
		Type:      "webrtc.answer",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-answer-1",
		From:      protocol.From{DeviceID: "agent-01", Role: "agent"},
		Payload: map[string]any{
			"sdp_type": "answer",
			"sdp":      "v=0\r\no=- 3 4 IN IP4 127.0.0.1\r\ns=remote_desk\r\n",
		},
	})
	forwardedAnswer := readEnvelopeOfType(t, controller, "webrtc.answer")
	if forwardedAnswer.Type != "webrtc.answer" {
		t.Fatalf("expected webrtc.answer, got %s", forwardedAnswer.Type)
	}

	sendEnvelope(t, agent, protocol.Envelope{
		Version:   "1.0",
		MessageID: "ice-1",
		Type:      "webrtc.ice_candidate",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-ice-1",
		From:      protocol.From{DeviceID: "agent-01", Role: "agent"},
		Payload: map[string]any{
			"candidate":       "candidate:0 1 UDP 2122252543 192.168.1.2 54321 typ host",
			"sdp_mid":         "0",
			"sdp_mline_index": 0,
		},
	})
	forwardedCandidate := readEnvelopeOfType(t, controller, "webrtc.ice_candidate")
	if forwardedCandidate.Type != "webrtc.ice_candidate" {
		t.Fatalf("expected webrtc.ice_candidate, got %s", forwardedCandidate.Type)
	}

	sendEnvelope(t, agent, protocol.Envelope{
		Version:   "1.0",
		MessageID: "ice-native-defaults",
		Type:      "webrtc.ice_candidate",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-ice-native-defaults",
		From:      protocol.From{DeviceID: "agent-01", Role: "agent"},
		Payload: map[string]any{
			"candidate": "candidate:1 1 UDP 2122252543 192.168.1.2 54322 typ host",
		},
	})
	forwardedDefaultCandidate := readEnvelopeOfType(t, controller, "webrtc.ice_candidate")
	if forwardedDefaultCandidate.Type != "webrtc.ice_candidate" {
		t.Fatalf("expected native-style webrtc.ice_candidate to be forwarded, got %s", forwardedDefaultCandidate.Type)
	}

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "end-1",
		Type:      "session.end.req",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-end-1",
		From:      protocol.From{DeviceID: "controller-01", Role: "controller"},
		Payload: map[string]any{
			"session_id": sessionID,
			"reason":     "user_end",
		},
	})
	controllerEnd := readEnvelopeOfType(t, controller, "session.end.push")
	if controllerEnd.Type != "session.end.push" {
		t.Fatalf("expected controller session.end.push, got %s", controllerEnd.Type)
	}
	agentEnd := readEnvelopeOfType(t, agent, "session.end.push")
	if agentEnd.Type != "session.end.push" {
		t.Fatalf("expected agent session.end.push, got %s", agentEnd.Type)
	}

	sendEnvelope(t, agent, protocol.Envelope{
		Version:   "1.0",
		MessageID: "heartbeat-agent",
		Type:      "presence.heartbeat.req",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "trace-heartbeat-agent",
		From:      protocol.From{DeviceID: "agent-01", Role: "agent"},
		Payload: map[string]any{
			"token":             agentToken,
			"status":            "online",
			"active_session_id": nil,
		},
	})
	agentHeartbeat := readEnvelopeOfType(t, agent, "presence.heartbeat.rsp")
	if agentHeartbeat.Type != "presence.heartbeat.rsp" {
		t.Fatalf("expected agent presence.heartbeat.rsp, got %s", agentHeartbeat.Type)
	}
}

func TestWSPresencePushOnRegisterHeartbeatAndDisconnect(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	controller := dialWS(t, server, allowedOrigin)
	defer controller.Close()

	agent := dialWS(t, server, allowedOrigin)
	defer agent.Close()

	_ = registerDevice(t, controller, "controller-01", "controller")
	controllerRegisterPush := readEnvelopeOfType(t, controller, "device.presence.push")
	assertPresencePushSnapshot(t, controllerRegisterPush, "device_registered", "controller-01", 1)
	assertPresencePushCapabilities(t, controllerRegisterPush, "controller-01", true, false)

	agentToken := registerDevice(t, agent, "agent-01", "agent")
	controllerAgentRegisterPush := readEnvelopeOfType(t, controller, "device.presence.push")
	agentRegisterPush := readEnvelopeOfType(t, agent, "device.presence.push")
	assertPresencePushSnapshot(t, controllerAgentRegisterPush, "device_registered", "agent-01", 2)
	assertPresencePushCapabilities(t, controllerAgentRegisterPush, "controller-01", true, false)
	assertPresencePushCapabilities(t, controllerAgentRegisterPush, "agent-01", false, true)
	assertPresencePushSnapshot(t, agentRegisterPush, "device_registered", "agent-01", 2)
	assertPresencePushCapabilities(t, agentRegisterPush, "controller-01", true, false)
	assertPresencePushCapabilities(t, agentRegisterPush, "agent-01", false, true)

	sendEnvelope(t, agent, protocol.Envelope{
		Version:   "1.0",
		MessageID: "heartbeat-agent-busy",
		Type:      "presence.heartbeat.req",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "trace-heartbeat-agent-busy",
		From:      protocol.From{DeviceID: "agent-01", Role: "agent"},
		Payload: map[string]any{
			"token":             agentToken,
			"status":            "busy",
			"active_session_id": nil,
		},
	})
	if heartbeat := readEnvelopeOfType(t, agent, "presence.heartbeat.rsp"); heartbeat.Type != "presence.heartbeat.rsp" {
		t.Fatalf("expected presence.heartbeat.rsp, got %s", heartbeat.Type)
	}
	controllerHeartbeatPush := readEnvelopeOfType(t, controller, "device.presence.push")
	assertPresencePushSnapshot(t, controllerHeartbeatPush, "heartbeat_status_changed", "agent-01", 2)

	if err := agent.Close(); err != nil {
		t.Fatalf("agent close failed: %v", err)
	}
	controllerDisconnectPush := readEnvelopeOfType(t, controller, "device.presence.push")
	assertPresencePushSnapshot(t, controllerDisconnectPush, "socket_disconnected", "agent-01", 2)
	assertPresenceStatus(t, controllerDisconnectPush, "agent-01", "offline")
}

func TestWSRejectsInvalidWebRTCSignalPayload(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	controller := dialWS(t, server, allowedOrigin)
	defer controller.Close()

	agent := dialWS(t, server, allowedOrigin)
	defer agent.Close()

	controllerToken := registerDevice(t, controller, "controller-01", "controller")
	_ = registerDevice(t, agent, "agent-01", "agent")

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "heartbeat-valid",
		Type:      "presence.heartbeat.req",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "trace-heartbeat-valid",
		From:      protocol.From{DeviceID: "controller-01", Role: "controller"},
		Payload: map[string]any{
			"token":             controllerToken,
			"status":            "online",
			"active_session_id": nil,
		},
	})
	heartbeat := readEnvelopeOfType(t, controller, "presence.heartbeat.rsp")
	if heartbeat.Type != "presence.heartbeat.rsp" {
		t.Fatalf("expected presence.heartbeat.rsp, got %s", heartbeat.Type)
	}

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "request-1",
		Type:      "session.request.req",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "trace-request-1",
		From:      protocol.From{DeviceID: "controller-01", Role: "controller"},
		Payload: map[string]any{
			"target_device_id": "agent-01",
			"request_id":       "req-1",
			"auth_mode":        "consent_required",
		},
	})
	requestResult := readEnvelopeOfType(t, controller, "session.request.result.push")
	if requestResult.Type != "session.request.result.push" {
		t.Fatalf("expected session.request.result.push, got %s", requestResult.Type)
	}
	sessionID, _ := requestResult.Payload["session_id"].(string)
	if sessionID == "" {
		t.Fatal("expected session_id in request result")
	}
	if start := readEnvelopeOfType(t, controller, "session.start.push"); start.Type != "session.start.push" {
		t.Fatalf("expected controller session.start.push, got %s", start.Type)
	}
	if start := readEnvelopeOfType(t, agent, "session.start.push"); start.Type != "session.start.push" {
		t.Fatalf("expected agent session.start.push, got %s", start.Type)
	}

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "offer-invalid",
		Type:      "webrtc.offer",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-offer-invalid",
		From:      protocol.From{DeviceID: "controller-01", Role: "controller"},
		Payload: map[string]any{
			"sdp_type": "answer",
			"sdp":      "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=remote_desk\r\n",
		},
	})
	invalidOffer := readEnvelopeOfType(t, controller, "error.rsp")
	if invalidOffer.Type != "error.rsp" {
		t.Fatalf("expected error.rsp, got %s", invalidOffer.Type)
	}
	if code := asInt(t, invalidOffer.Payload["code"]); code != 5001 {
		t.Fatalf("expected MEDIA_NEGOTIATION_FAILED code 5001, got %d", code)
	}

	sendEnvelope(t, agent, protocol.Envelope{
		Version:   "1.0",
		MessageID: "ice-invalid",
		Type:      "webrtc.ice_candidate",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-ice-invalid",
		From:      protocol.From{DeviceID: "agent-01", Role: "agent"},
		Payload: map[string]any{
			"candidate":       "",
			"sdp_mid":         "0",
			"sdp_mline_index": 0,
		},
	})
	invalidCandidate := readEnvelopeOfType(t, agent, "error.rsp")
	if invalidCandidate.Type != "error.rsp" {
		t.Fatalf("expected error.rsp, got %s", invalidCandidate.Type)
	}
	if code := asInt(t, invalidCandidate.Payload["code"]); code != 5001 {
		t.Fatalf("expected MEDIA_NEGOTIATION_FAILED code 5001, got %d", code)
	}
}

func TestWSRejectsDisallowedOrigin(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	dialer := websocket.Dialer{}
	conn, resp, err := dialer.Dial(toWSURL(server.URL)+"/ws", http.Header{"Origin": []string{"http://blocked.test"}})
	if err == nil {
		conn.Close()
		t.Fatal("expected websocket dial to fail for blocked origin")
	}
	if resp == nil {
		t.Fatal("expected HTTP response for blocked origin")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("expected 403, got %d", resp.StatusCode)
	}
}

func TestWSRequiresRegistrationBeforeHeartbeat(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	conn := dialWS(t, server, allowedOrigin)
	defer conn.Close()

	sendEnvelope(t, conn, protocol.Envelope{
		Version:   "1.0",
		MessageID: "heartbeat-no-register",
		Type:      "presence.heartbeat.req",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "trace-heartbeat-no-register",
		From:      protocol.From{DeviceID: "controller-01", Role: "controller"},
		Payload: map[string]any{
			"token":             "stub-token-controller-01",
			"status":            "online",
			"active_session_id": nil,
		},
	})

	msg := readEnvelopeOfType(t, conn, "error.rsp")
	if msg.Type != "error.rsp" {
		t.Fatalf("expected error.rsp, got %s", msg.Type)
	}
	if code := asInt(t, msg.Payload["code"]); code != 1002 {
		t.Fatalf("expected AUTH_FORBIDDEN_DEVICE code 1002, got %d", code)
	}
}

func TestWSRejectsImpersonationOnRegisteredSocket(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	conn := dialWS(t, server, allowedOrigin)
	defer conn.Close()

	_ = registerDevice(t, conn, "controller-01", "controller")

	sendEnvelope(t, conn, protocol.Envelope{
		Version:   "1.0",
		MessageID: "heartbeat-impersonation",
		Type:      "presence.heartbeat.req",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "trace-heartbeat-impersonation",
		From:      protocol.From{DeviceID: "agent-01", Role: "agent"},
		Payload: map[string]any{
			"token":             "stub-token-agent-01",
			"status":            "online",
			"active_session_id": nil,
		},
	})

	msg := readEnvelopeOfType(t, conn, "error.rsp")
	if msg.Type != "error.rsp" {
		t.Fatalf("expected error.rsp, got %s", msg.Type)
	}
	if code := asInt(t, msg.Payload["code"]); code != 1002 {
		t.Fatalf("expected AUTH_FORBIDDEN_DEVICE code 1002, got %d", code)
	}
}

func TestWSSessionRequestUsesRegisteredCapabilities(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	controller := dialWS(t, server, allowedOrigin)
	defer controller.Close()
	target := dialWS(t, server, allowedOrigin)
	defer target.Close()

	_ = registerDeviceWithCapabilities(t, controller, "windows-controller-01", "agent", true, true)
	_ = registerDeviceWithCapabilities(t, target, "windows-target-01", "agent", true, true)

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "request-windows-to-windows",
		Type:      "session.request.req",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "trace-request-windows-to-windows",
		From:      protocol.From{DeviceID: "windows-controller-01", Role: "agent"},
		Payload: map[string]any{
			"target_device_id": "windows-target-01",
			"request_id":       "req-windows-to-windows",
			"auth_mode":        "consent_required",
		},
	})

	requestResult := readEnvelopeOfType(t, controller, "session.request.result.push")
	if requestResult.Type != "session.request.result.push" {
		t.Fatalf("expected session.request.result.push, got %s", requestResult.Type)
	}
	if sessionID, _ := requestResult.Payload["session_id"].(string); sessionID == "" {
		t.Fatal("expected session_id in request result")
	}
	if start := readEnvelopeOfType(t, controller, "session.start.push"); start.Type != "session.start.push" {
		t.Fatalf("expected controller session.start.push, got %s", start.Type)
	}
	if start := readEnvelopeOfType(t, target, "session.start.push"); start.Type != "session.start.push" {
		t.Fatalf("expected target session.start.push, got %s", start.Type)
	}
}

func TestWSSessionRequestAllowsControllerRoleTargetWhenCapabilityAllowsControl(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	controller := dialWS(t, server, allowedOrigin)
	defer controller.Close()
	target := dialWS(t, server, allowedOrigin)
	defer target.Close()

	_ = registerDeviceWithCapabilities(t, controller, "windows-controller-01", "controller", true, true)
	_ = registerDeviceWithCapabilities(t, target, "windows-target-01", "controller", true, true)

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "request-controller-role-target",
		Type:      "session.request.req",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "trace-request-controller-role-target",
		From:      protocol.From{DeviceID: "windows-controller-01", Role: "controller"},
		Payload: map[string]any{
			"target_device_id": "windows-target-01",
			"request_id":       "req-controller-role-target",
			"auth_mode":        "consent_required",
		},
	})

	requestResult := readEnvelopeOfType(t, controller, "session.request.result.push")
	if requestResult.Type != "session.request.result.push" {
		t.Fatalf("expected session.request.result.push, got %s", requestResult.Type)
	}
	if sessionID, _ := requestResult.Payload["session_id"].(string); sessionID == "" {
		t.Fatal("expected session_id in request result")
	}

	targetStart := readEnvelopeOfType(t, target, "session.start.push")
	if targetStart.Type != "session.start.push" {
		t.Fatalf("expected target session.start.push, got %s", targetStart.Type)
	}
	if controllerID, _ := targetStart.Payload["controller_device_id"].(string); controllerID != "windows-controller-01" {
		t.Fatalf("expected controller_device_id windows-controller-01, got %q", controllerID)
	}
	if agentID, _ := targetStart.Payload["agent_device_id"].(string); agentID != "windows-target-01" {
		t.Fatalf("expected agent_device_id windows-target-01, got %q", agentID)
	}

	controllerStart := readEnvelopeOfType(t, controller, "session.start.push")
	if controllerStart.Type != "session.start.push" {
		t.Fatalf("expected controller session.start.push, got %s", controllerStart.Type)
	}
}

func TestWSInputForwardingUsesSessionControllerDeviceNotDeclaredRole(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	controller := dialWS(t, server, allowedOrigin)
	defer controller.Close()
	target := dialWS(t, server, allowedOrigin)
	defer target.Close()

	_ = registerDeviceWithCapabilities(t, controller, "windows-controller-01", "agent", true, true)
	_ = registerDeviceWithCapabilities(t, target, "windows-target-01", "agent", true, true)

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "request-agent-role-controller",
		Type:      "session.request.req",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "trace-request-agent-role-controller",
		From:      protocol.From{DeviceID: "windows-controller-01", Role: "agent"},
		Payload: map[string]any{
			"target_device_id": "windows-target-01",
			"request_id":       "req-agent-role-controller",
			"auth_mode":        "consent_required",
		},
	})

	requestResult := readEnvelopeOfType(t, controller, "session.request.result.push")
	sessionID, _ := requestResult.Payload["session_id"].(string)
	if sessionID == "" {
		t.Fatal("expected session_id in request result")
	}
	if start := readEnvelopeOfType(t, target, "session.start.push"); start.Type != "session.start.push" {
		t.Fatalf("expected target session.start.push, got %s", start.Type)
	}
	if start := readEnvelopeOfType(t, controller, "session.start.push"); start.Type != "session.start.push" {
		t.Fatalf("expected controller session.start.push, got %s", start.Type)
	}

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "keyboard-from-agent-role-controller",
		Type:      "input.keyboard.key",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-keyboard-from-agent-role-controller",
		From:      protocol.From{DeviceID: "windows-controller-01", Role: "agent"},
		Payload: map[string]any{
			"key_code":  "KeyA",
			"action":    "down",
			"modifiers": []any{"ShiftLeft", "ControlLeft"},
		},
	})

	forwardedKeyboard := readEnvelopeOfType(t, target, "input.keyboard.key")
	if forwardedKeyboard.SessionID != sessionID {
		t.Fatalf("expected forwarded keyboard session %s, got %s", sessionID, forwardedKeyboard.SessionID)
	}
	if keyCode, _ := forwardedKeyboard.Payload["key_code"].(string); keyCode != "KeyA" {
		t.Fatalf("expected forwarded key_code KeyA, got %q", keyCode)
	}
	modifiers, ok := forwardedKeyboard.Payload["modifiers"].([]any)
	if !ok || len(modifiers) != 2 || modifiers[0] != "ShiftLeft" || modifiers[1] != "ControlLeft" {
		t.Fatalf("expected forwarded keyboard modifiers to be preserved, got %#v", forwardedKeyboard.Payload["modifiers"])
	}
	keyboardAck := readEnvelopeOfType(t, controller, "input.ack")
	if echoType, _ := keyboardAck.Payload["echo_type"].(string); echoType != "input.keyboard.key" {
		t.Fatalf("expected keyboard input ack echo_type, got %q", echoType)
	}

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "wheel-from-agent-role-controller",
		Type:      "input.wheel.scroll",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-wheel-from-agent-role-controller",
		From:      protocol.From{DeviceID: "windows-controller-01", Role: "agent"},
		Payload: map[string]any{
			"delta_x": 0,
			"delta_y": -120,
		},
	})

	forwardedWheel := readEnvelopeOfType(t, target, "input.wheel.scroll")
	if deltaY := asInt(t, forwardedWheel.Payload["delta_y"]); deltaY != -120 {
		t.Fatalf("expected forwarded wheel delta_y -120, got %d", deltaY)
	}
	wheelAck := readEnvelopeOfType(t, controller, "input.ack")
	if echoType, _ := wheelAck.Payload["echo_type"].(string); echoType != "input.wheel.scroll" {
		t.Fatalf("expected wheel input ack echo_type, got %q", echoType)
	}

	sendEnvelope(t, target, protocol.Envelope{
		Version:   "1.0",
		MessageID: "input-result-from-target",
		Type:      "input.result.push",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-input-result-from-target",
		From:      protocol.From{DeviceID: "windows-target-01", Role: "agent"},
		Payload: map[string]any{
			"input_type":     "input.wheel.scroll",
			"input_trace_id": "trace-wheel-from-agent-role-controller",
			"applied":        true,
			"executor":       "windows.send_input",
			"status_code":    "applied",
			"summary":        "滚轮 dx=0.00 dy=-120.00",
			"input_count":    2,
		},
	})

	forwardedResult := readEnvelopeOfType(t, controller, "input.result.push")
	if forwardedResult.SessionID != sessionID {
		t.Fatalf("expected forwarded input result session %s, got %s", sessionID, forwardedResult.SessionID)
	}
	if inputType, _ := forwardedResult.Payload["input_type"].(string); inputType != "input.wheel.scroll" {
		t.Fatalf("expected input result type to be preserved, got %q", inputType)
	}
	if applied, _ := forwardedResult.Payload["applied"].(bool); !applied {
		t.Fatalf("expected input result applied=true, got %#v", forwardedResult.Payload["applied"])
	}

	sendEnvelope(t, target, protocol.Envelope{
		Version:   "1.0",
		MessageID: "keyboard-from-target",
		Type:      "input.keyboard.key",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-keyboard-from-target",
		From:      protocol.From{DeviceID: "windows-target-01", Role: "agent"},
		Payload: map[string]any{
			"key_code": "KeyB",
			"action":   "down",
		},
	})

	rejected := readEnvelopeOfType(t, target, "error.rsp")
	if code := asInt(t, rejected.Payload["code"]); code != 4002 {
		t.Fatalf("expected non-controller input to be rejected with 4002, got %d", code)
	}
}

func TestWSSessionRequestRejectsMissingCapabilities(t *testing.T) {
	t.Run("requesting device cannot control", func(t *testing.T) {
		server := newTestServer(t)
		defer server.Close()

		controller := dialWS(t, server, allowedOrigin)
		defer controller.Close()
		target := dialWS(t, server, allowedOrigin)
		defer target.Close()

		_ = registerDeviceWithCapabilities(t, controller, "viewer-01", "controller", false, false)
		_ = registerDeviceWithCapabilities(t, target, "target-01", "agent", false, true)

		sendEnvelope(t, controller, protocol.Envelope{
			Version:   "1.0",
			MessageID: "request-controller-denied",
			Type:      "session.request.req",
			Timestamp: time.Now().UnixMilli(),
			TraceID:   "trace-request-controller-denied",
			From:      protocol.From{DeviceID: "viewer-01", Role: "controller"},
			Payload: map[string]any{
				"target_device_id": "target-01",
				"request_id":       "req-controller-denied",
				"auth_mode":        "consent_required",
			},
		})

		msg := readEnvelopeOfType(t, controller, "error.rsp")
		if code := asInt(t, msg.Payload["code"]); code != 3004 {
			t.Fatalf("expected SESSION_STATE_INVALID code 3004, got %d", code)
		}
	})

	t.Run("target device cannot be controlled", func(t *testing.T) {
		server := newTestServer(t)
		defer server.Close()

		controller := dialWS(t, server, allowedOrigin)
		defer controller.Close()
		target := dialWS(t, server, allowedOrigin)
		defer target.Close()

		_ = registerDeviceWithCapabilities(t, controller, "controller-01", "controller", true, false)
		_ = registerDeviceWithCapabilities(t, target, "viewer-01", "controller", true, false)

		sendEnvelope(t, controller, protocol.Envelope{
			Version:   "1.0",
			MessageID: "request-target-denied",
			Type:      "session.request.req",
			Timestamp: time.Now().UnixMilli(),
			TraceID:   "trace-request-target-denied",
			From:      protocol.From{DeviceID: "controller-01", Role: "controller"},
			Payload: map[string]any{
				"target_device_id": "viewer-01",
				"request_id":       "req-target-denied",
				"auth_mode":        "consent_required",
			},
		})

		msg := readEnvelopeOfType(t, controller, "error.rsp")
		if code := asInt(t, msg.Payload["code"]); code != 3004 {
			t.Fatalf("expected SESSION_STATE_INVALID code 3004, got %d", code)
		}
	})
}

func TestWSInputResultRequiresTargetAgentAndValidPayload(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	controller := dialWS(t, server, allowedOrigin)
	defer controller.Close()
	target := dialWS(t, server, allowedOrigin)
	defer target.Close()

	_ = registerDeviceWithCapabilities(t, controller, "controller-input-result-01", "controller", true, false)
	_ = registerDeviceWithCapabilities(t, target, "target-input-result-01", "agent", false, true)

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "request-input-result-session",
		Type:      "session.request.req",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "trace-request-input-result-session",
		From:      protocol.From{DeviceID: "controller-input-result-01", Role: "controller"},
		Payload: map[string]any{
			"target_device_id": "target-input-result-01",
			"request_id":       "req-input-result-session",
			"auth_mode":        "consent_required",
		},
	})

	requestResult := readEnvelopeOfType(t, controller, "session.request.result.push")
	sessionID, _ := requestResult.Payload["session_id"].(string)
	if sessionID == "" {
		t.Fatal("expected session_id in request result")
	}
	if start := readEnvelopeOfType(t, controller, "session.start.push"); start.Type != "session.start.push" {
		t.Fatalf("expected controller session.start.push, got %s", start.Type)
	}
	if start := readEnvelopeOfType(t, target, "session.start.push"); start.Type != "session.start.push" {
		t.Fatalf("expected target session.start.push, got %s", start.Type)
	}

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "forged-input-result-from-controller",
		Type:      "input.result.push",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-forged-input-result-from-controller",
		From:      protocol.From{DeviceID: "controller-input-result-01", Role: "controller"},
		Payload: map[string]any{
			"input_type":  "input.mouse.move",
			"applied":     true,
			"executor":    "windows.send_input",
			"status_code": "applied",
		},
	})
	rejectedControllerResult := readEnvelopeOfType(t, controller, "error.rsp")
	if code := asInt(t, rejectedControllerResult.Payload["code"]); code != 4002 {
		t.Fatalf("expected controller input result to be rejected with 4002, got %d", code)
	}

	sendEnvelope(t, target, protocol.Envelope{
		Version:   "1.0",
		MessageID: "invalid-input-result-from-target",
		Type:      "input.result.push",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-invalid-input-result-from-target",
		From:      protocol.From{DeviceID: "target-input-result-01", Role: "agent"},
		Payload: map[string]any{
			"input_type": "input.mouse.move",
		},
	})
	rejectedInvalidPayload := readEnvelopeOfType(t, target, "error.rsp")
	if code := asInt(t, rejectedInvalidPayload.Payload["code"]); code != 4001 {
		t.Fatalf("expected invalid input result payload to be rejected with 4001, got %d", code)
	}
}

func TestWSSessionMetricsReportAcceptedAfterSessionEnd(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	controller := dialWS(t, server, allowedOrigin)
	defer controller.Close()
	agent := dialWS(t, server, allowedOrigin)
	defer agent.Close()

	controllerToken := registerDevice(t, controller, "controller-01", "controller")
	_ = registerDevice(t, agent, "agent-01", "agent")

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "heartbeat-valid",
		Type:      "presence.heartbeat.req",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "trace-heartbeat-valid",
		From:      protocol.From{DeviceID: "controller-01", Role: "controller"},
		Payload: map[string]any{
			"token":             controllerToken,
			"status":            "online",
			"active_session_id": nil,
		},
	})
	if heartbeat := readEnvelopeOfType(t, controller, "presence.heartbeat.rsp"); heartbeat.Type != "presence.heartbeat.rsp" {
		t.Fatalf("expected presence.heartbeat.rsp, got %s", heartbeat.Type)
	}

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "request-1",
		Type:      "session.request.req",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "trace-request-1",
		From:      protocol.From{DeviceID: "controller-01", Role: "controller"},
		Payload: map[string]any{
			"target_device_id": "agent-01",
			"request_id":       "req-1",
			"auth_mode":        "consent_required",
		},
	})
	requestResult := readEnvelopeOfType(t, controller, "session.request.result.push")
	if requestResult.Type != "session.request.result.push" {
		t.Fatalf("expected session.request.result.push, got %s", requestResult.Type)
	}
	sessionID, _ := requestResult.Payload["session_id"].(string)
	if sessionID == "" {
		t.Fatal("expected session_id in request result")
	}
	if start := readEnvelopeOfType(t, controller, "session.start.push"); start.Type != "session.start.push" {
		t.Fatalf("expected controller session.start.push, got %s", start.Type)
	}
	if start := readEnvelopeOfType(t, agent, "session.start.push"); start.Type != "session.start.push" {
		t.Fatalf("expected agent session.start.push, got %s", start.Type)
	}

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "end-1",
		Type:      "session.end.req",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-end-1",
		From:      protocol.From{DeviceID: "controller-01", Role: "controller"},
		Payload: map[string]any{
			"session_id": sessionID,
			"reason":     "user_end",
		},
	})
	if controllerEnd := readEnvelopeOfType(t, controller, "session.end.push"); controllerEnd.Type != "session.end.push" {
		t.Fatalf("expected controller session.end.push, got %s", controllerEnd.Type)
	}
	if agentEnd := readEnvelopeOfType(t, agent, "session.end.push"); agentEnd.Type != "session.end.push" {
		t.Fatalf("expected agent session.end.push, got %s", agentEnd.Type)
	}

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "metrics-controller-1",
		Type:      "session.metrics.report",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-metrics-controller-1",
		From:      protocol.From{DeviceID: "controller-01", Role: "controller"},
		Payload: map[string]any{
			"session_id":      sessionID,
			"report_version":  1,
			"source_client":   "android",
			"source_platform": "android",
			"reason":          "session_end",
			"duration_ms":     1234,
		},
	})

	sendEnvelope(t, agent, protocol.Envelope{
		Version:   "1.0",
		MessageID: "metrics-agent-1",
		Type:      "session.metrics.report",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-metrics-agent-1",
		From:      protocol.From{DeviceID: "agent-01", Role: "agent"},
		Payload: map[string]any{
			"session_id":      sessionID,
			"report_version":  1,
			"source_client":   "desktop",
			"source_platform": "macos",
			"reason":          "session_end",
			"send_fps":        23.5,
		},
	})

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "heartbeat-after-metrics",
		Type:      "presence.heartbeat.req",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "trace-heartbeat-after-metrics",
		From:      protocol.From{DeviceID: "controller-01", Role: "controller"},
		Payload: map[string]any{
			"token":             controllerToken,
			"status":            "online",
			"active_session_id": nil,
		},
	})

	for attempt := 0; attempt < 3; attempt += 1 {
		msg, ok, err := readEnvelopeWithTimeout(controller, 500*time.Millisecond)
		if err != nil {
			t.Fatalf("read failed after metrics report: %v", err)
		}
		if !ok {
			t.Fatal("expected presence.heartbeat.rsp after metrics report")
		}
		if msg.Type == "presence.heartbeat.rsp" {
			return
		}
		if msg.Type == "error.rsp" {
			t.Fatalf("unexpected error after metrics report: %#v", msg.Payload)
		}
	}
	t.Fatal("did not receive presence.heartbeat.rsp after metrics report")
}

func TestUpsertSessionMetricsIncludesBridgeSummary(t *testing.T) {
	hub := &Hub{
		sessionMetrics: make(map[string]*sessionMetricsAggregate),
		endedSessions:  make(map[string]endedSessionRecord),
	}
	current := store.Session{
		SessionID:          "sess-bridge-1",
		ControllerDeviceID: "controller-01",
		AgentDeviceID:      "agent-01",
	}

	controllerCombined := hub.upsertSessionMetrics(
		current.SessionID,
		current,
		"controller",
		map[string]any{
			"session_id":                         current.SessionID,
			"source_client":                      "android",
			"source_platform":                    "android",
			"source_role":                        "controller",
			"bridge_pipeline":                    "-",
			"first_frame_ms":                     1200.0,
			"render_fps_avg":                     22.5,
			"recv_kbps_avg":                      130.0,
			"candidate_pair_last":                "srflx/srflx/udp",
			"candidate_tier_last":                "p2p_udp",
			"controller_quality_hint":            "stable",
			"remote_input_result_count":          4.0,
			"remote_input_result_applied_count":  4.0,
			"remote_input_result_failed_count":   1.0,
			"remote_input_applied_click":         true,
			"remote_input_applied_drag":          true,
			"remote_input_applied_keyboard":      true,
			"remote_input_applied_wheel":         true,
			"remote_input_applied_categories":    "click,drag,keyboard,wheel",
			"remote_input_last_type":             "input.wheel.scroll",
			"remote_input_last_category":         "wheel",
			"remote_input_last_trace_id":         "trace-input-4",
			"remote_input_last_applied":          true,
			"remote_input_last_executor":         "windows.send_input",
			"remote_input_last_status_code":      "applied",
			"remote_input_last_summary":          "wheel dy=-120",
			"remote_input_last_count":            4.0,
			"remote_input_last_target_device_id": "agent-01",
		},
	)
	if controllerCombined != nil {
		t.Fatal("expected nil combined while waiting for agent report")
	}

	combined := hub.upsertSessionMetrics(
		current.SessionID,
		current,
		"agent",
		map[string]any{
			"session_id":                               current.SessionID,
			"source_client":                            "desktop",
			"source_platform":                          "desktop",
			"source_role":                              "agent",
			"bridge_pipeline":                          "raw_attempts:1, raw_success:1, raw_failed:0, jpeg_retry_attempted:0, jpeg_retry_success:0, jpeg_retry_failed:0",
			"bridge_pipeline_ratios":                   "raw_success_rate=100%, jpeg_retry_success_rate=-, canvas_share=0%",
			"bridge_fetch_skips":                       "unsupported_track_generator:1",
			"bridge_modes":                             "mjpeg_stream_fetch_generator:1",
			"bridge_total":                             1.0,
			"bridge_fetch_skip_total":                  1.0,
			"bridge_canvas_hits":                       0.0,
			"bridge_canvas_share_pct":                  0.0,
			"bridge_raw_success_rate_pct":              100.0,
			"bridge_jpeg_retry_success_rate_pct":       -1.0,
			"send_fps":                                 23.5,
			"send_kbps":                                140.0,
			"capture_fps":                              24.0,
			"candidate_path":                           "srflx/srflx/udp",
			"candidate_tier":                           "p2p_udp",
			"native_sender_lifecycle":                  "running",
			"native_sender_signaling_state":            "ice_syncing",
			"native_sender_dry_run":                    true,
			"native_sender_signal_count":               4.0,
			"native_sender_last_signal_type":           "webrtc.ice_candidate",
			"native_sender_last_signal_direction":      "inbound",
			"native_sender_last_signal_trace_id":       "trace-native-123",
			"native_sender_last_signal_payload_bytes":  420.0,
			"native_sender_inbound_signal_count":       3.0,
			"native_sender_outbound_signal_count":      1.0,
			"native_sender_local_offer_count":          1.0,
			"native_sender_local_answer_count":         0.0,
			"native_sender_local_candidate_count":      1.0,
			"native_sender_remote_offer_count":         0.0,
			"native_sender_remote_answer_count":        1.0,
			"native_sender_remote_candidate_count":     3.0,
			"native_sender_restart_ice_count":          0.0,
			"native_sender_local_restart_ice_count":    0.0,
			"native_sender_remote_restart_ice_count":   0.0,
			"native_sender_remote_answer_sdp_len":      1961.0,
			"native_sender_remote_offer_sdp_len":       0.0,
			"native_sender_last_remote_candidate_type": "srflx",
			"native_sender_probe_running":              true,
			"native_sender_probe_fps":                  17.5,
			"native_sender_probe_kbps":                 4012.4,
			"native_sender_probe_frame_count":          72.0,
			"native_sender_probe_total_bytes":          2012345.0,
			"native_sender_probe_frame_width":          556.0,
			"native_sender_probe_frame_height":         360.0,
			"native_sender_probe_last_frame_ts_ms":     1777378096230.0,
			"native_sender_shadow_runtime_ready":       true,
			"native_sender_shadow_track_bound":         true,
			"native_sender_shadow_last_apply_action":   "add_ice_candidate",
			"native_sender_last_error_code":            "-",
			"runtime_signature_version":                1.0,
			"runtime_kernel":                           "tauri|macos|webkit/619.1.0",
			"runtime_capability_signature":             "fetch:1|media_stream:1|video_frame:1|track_generator:0",
			"runtime_engine":                           "tauri",
			"runtime_shell_platform":                   "macos",
			"runtime_browser_platform":                 "MacIntel",
			"runtime_cap_fetch":                        true,
			"runtime_cap_media_stream":                 true,
			"runtime_cap_video_frame":                  true,
			"runtime_cap_track_generator":              false,
			"runtime_cap_display_media":                true,
			"runtime_cap_native_sender":                false,
			"runtime_native_sender_support_level":      "track_sample_pump_experimental",
			"runtime_native_sender_blocker":            "native_sender.shadow_signaling_ownership_missing",
		},
	)
	if combined == nil {
		t.Fatal("expected combined metrics after both reports")
	}
	if sourceRole, _ := combined["bridge_source_role"].(string); sourceRole != "agent" {
		t.Fatalf("expected bridge_source_role=agent, got %#v", combined["bridge_source_role"])
	}
	if pipeline, _ := combined["bridge_pipeline"].(string); !strings.Contains(pipeline, "raw_attempts:1") {
		t.Fatalf("expected bridge_pipeline summary, got %#v", combined["bridge_pipeline"])
	}
	if ratios, _ := combined["bridge_pipeline_ratios"].(string); !strings.Contains(ratios, "raw_success_rate=100%") {
		t.Fatalf("expected bridge_pipeline_ratios summary, got %#v", combined["bridge_pipeline_ratios"])
	}
	if fetchSkips, _ := combined["bridge_fetch_skips"].(string); !strings.Contains(fetchSkips, "unsupported_track_generator:1") {
		t.Fatalf("expected bridge_fetch_skips summary, got %#v", combined["bridge_fetch_skips"])
	}
	if fetchSkipTotal, ok := combined["bridge_fetch_skip_total"].(float64); !ok || fetchSkipTotal != 1 {
		t.Fatalf("expected bridge_fetch_skip_total=1, got %#v", combined["bridge_fetch_skip_total"])
	}
	if total, ok := combined["bridge_total"].(float64); !ok || total != 1 {
		t.Fatalf("expected bridge_total=1, got %#v", combined["bridge_total"])
	}
	if firstFrame, ok := combined["first_frame_ms"].(float64); !ok || firstFrame != 1200 {
		t.Fatalf("expected first_frame_ms=1200, got %#v", combined["first_frame_ms"])
	}
	if renderFps, ok := combined["render_fps_avg"].(float64); !ok || renderFps != 22.5 {
		t.Fatalf("expected render_fps_avg=22.5, got %#v", combined["render_fps_avg"])
	}
	if sendFps, ok := combined["send_fps"].(float64); !ok || sendFps != 23.5 {
		t.Fatalf("expected send_fps=23.5, got %#v", combined["send_fps"])
	}
	if remoteInputCount, ok := combined["remote_input_result_count"].(float64); !ok || remoteInputCount != 4 {
		t.Fatalf("expected remote_input_result_count=4, got %#v", combined["remote_input_result_count"])
	}
	if remoteInputAppliedCount, ok := combined["remote_input_result_applied_count"].(float64); !ok || remoteInputAppliedCount != 4 {
		t.Fatalf("expected remote_input_result_applied_count=4, got %#v", combined["remote_input_result_applied_count"])
	}
	if remoteInputCoverage, _ := combined["remote_input_coverage"].(string); remoteInputCoverage != "click,drag,keyboard,wheel" {
		t.Fatalf("expected complete remote_input_coverage, got %#v", combined["remote_input_coverage"])
	}
	if remoteInputApplied, ok := combined["remote_input_last_applied"].(bool); !ok || !remoteInputApplied {
		t.Fatalf("expected remote_input_last_applied=true, got %#v", combined["remote_input_last_applied"])
	}
	if remoteInputExecutor, _ := combined["remote_input_last_executor"].(string); remoteInputExecutor != "windows.send_input" {
		t.Fatalf("expected remote_input_last_executor=windows.send_input, got %#v", combined["remote_input_last_executor"])
	}
	if remoteInputStatus, _ := combined["remote_input_last_status_code"].(string); remoteInputStatus != "applied" {
		t.Fatalf("expected remote_input_last_status_code=applied, got %#v", combined["remote_input_last_status_code"])
	}
	if videoObserved, ok := combined["session_e2e_video_observed"].(bool); !ok || !videoObserved {
		t.Fatalf("expected session_e2e_video_observed=true, got %#v", combined["session_e2e_video_observed"])
	}
	if inputObserved, ok := combined["session_e2e_input_observed"].(bool); !ok || !inputObserved {
		t.Fatalf("expected session_e2e_input_observed=true, got %#v", combined["session_e2e_input_observed"])
	}
	if proofStatus, _ := combined["session_e2e_proof_status"].(string); proofStatus != "video_and_input_observed" {
		t.Fatalf("expected session_e2e_proof_status=video_and_input_observed, got %#v", combined["session_e2e_proof_status"])
	}
	if proofSummary, _ := combined["session_e2e_proof_summary"].(string); !strings.Contains(proofSummary, "session_e2e_proof_status=video_and_input_observed") {
		t.Fatalf("expected session_e2e_proof_summary with proof status, got %#v", combined["session_e2e_proof_summary"])
	}
	if routeKey, _ := combined["session_e2e_route_key"].(string); routeKey != "android_to_macos" {
		t.Fatalf("expected session_e2e_route_key=android_to_macos, got %#v", combined["session_e2e_route_key"])
	}
	if route, _ := combined["session_e2e_route"].(string); route != "android->macos" {
		t.Fatalf("expected session_e2e_route=android->macos, got %#v", combined["session_e2e_route"])
	}
	if targetRoute, ok := combined["session_e2e_target_route"].(bool); !ok || targetRoute {
		t.Fatalf("expected session_e2e_target_route=false, got %#v", combined["session_e2e_target_route"])
	}
	if proofSummary, _ := combined["session_e2e_proof_summary"].(string); !strings.Contains(proofSummary, "route_key=android_to_macos") {
		t.Fatalf("expected session_e2e_proof_summary with route key, got %#v", combined["session_e2e_proof_summary"])
	}
	if perfSummary, _ := combined["session_perf_summary"].(string); !strings.Contains(perfSummary, "first_frame_ms=1200") {
		t.Fatalf("expected session_perf_summary with first_frame_ms, got %#v", combined["session_perf_summary"])
	}
	if perfSummary, _ := combined["session_perf_summary"].(string); !strings.Contains(perfSummary, "remote_input_applied=4/4") {
		t.Fatalf("expected session_perf_summary with remote_input_applied=4/4, got %#v", combined["session_perf_summary"])
	}
	if perfSummary, _ := combined["session_perf_summary"].(string); !strings.Contains(perfSummary, "input_coverage=click,drag,keyboard,wheel") {
		t.Fatalf("expected session_perf_summary with complete input coverage, got %#v", combined["session_perf_summary"])
	}
	if qualityHint, _ := combined["session_quality_hint"].(string); qualityHint == "" {
		t.Fatalf("expected session_quality_hint to be present, got %#v", combined["session_quality_hint"])
	}
	if controllerQualityHint, _ := combined["controller_quality_hint"].(string); controllerQualityHint != "stable" {
		t.Fatalf("expected controller_quality_hint=stable, got %#v", combined["controller_quality_hint"])
	}
	if runtimeKernel, _ := combined["runtime_kernel"].(string); !strings.Contains(runtimeKernel, "tauri|macos") {
		t.Fatalf("expected runtime_kernel summary, got %#v", combined["runtime_kernel"])
	}
	if runtimeCapSig, _ := combined["runtime_capability_signature"].(string); !strings.Contains(runtimeCapSig, "fetch:1") {
		t.Fatalf("expected runtime_capability_signature summary, got %#v", combined["runtime_capability_signature"])
	}
	if runtimeCapTrackGenerator, ok := combined["runtime_cap_track_generator"].(bool); !ok || runtimeCapTrackGenerator {
		t.Fatalf("expected runtime_cap_track_generator=false, got %#v", combined["runtime_cap_track_generator"])
	}
	if runtimeCapNativeSender, ok := combined["runtime_cap_native_sender"].(bool); !ok || runtimeCapNativeSender {
		t.Fatalf("expected runtime_cap_native_sender=false, got %#v", combined["runtime_cap_native_sender"])
	}
	if nativeSenderSupportLevel, _ := combined["runtime_native_sender_support_level"].(string); nativeSenderSupportLevel != "track_sample_pump_experimental" {
		t.Fatalf("expected runtime_native_sender_support_level=track_sample_pump_experimental, got %#v", combined["runtime_native_sender_support_level"])
	}
	if nativeSenderBlocker, _ := combined["runtime_native_sender_blocker"].(string); !strings.Contains(nativeSenderBlocker, "shadow_signaling_ownership_missing") {
		t.Fatalf("expected runtime_native_sender_blocker summary, got %#v", combined["runtime_native_sender_blocker"])
	}
	if probeFps, ok := combined["native_sender_probe_fps"].(float64); !ok || probeFps <= 0 {
		t.Fatalf("expected native_sender_probe_fps to be present, got %#v", combined["native_sender_probe_fps"])
	}
	if lifecycle, _ := combined["native_sender_lifecycle"].(string); lifecycle != "running" {
		t.Fatalf("expected native_sender_lifecycle=running, got %#v", combined["native_sender_lifecycle"])
	}
	if signalingState, _ := combined["native_sender_signaling_state"].(string); signalingState != "ice_syncing" {
		t.Fatalf("expected native_sender_signaling_state=ice_syncing, got %#v", combined["native_sender_signaling_state"])
	}
	if lastDirection, _ := combined["native_sender_last_signal_direction"].(string); lastDirection != "inbound" {
		t.Fatalf("expected native_sender_last_signal_direction=inbound, got %#v", combined["native_sender_last_signal_direction"])
	}
	if shadowTrackBound, ok := combined["native_sender_shadow_track_bound"].(bool); !ok || !shadowTrackBound {
		t.Fatalf("expected native_sender_shadow_track_bound=true, got %#v", combined["native_sender_shadow_track_bound"])
	}
}

func TestUpsertSessionMetricsKeepsRollingReportsForLiveProof(t *testing.T) {
	hub := &Hub{
		sessionMetrics: make(map[string]*sessionMetricsAggregate),
		endedSessions:  make(map[string]endedSessionRecord),
		e2eLatest:      make(map[string]sessionE2EProofRecord),
		e2eSuccess:     make(map[string]sessionE2EProofRecord),
	}
	current := store.Session{
		SessionID:          "sess-rolling-proof",
		ControllerDeviceID: "windows-controller",
		AgentDeviceID:      "macos-agent",
	}

	controllerVideoOnly := map[string]any{
		"source_client":                     "desktop",
		"source_platform":                   "windows",
		"source_role":                       "controller",
		"first_frame_ms":                    750.0,
		"rendered_frames":                   6.0,
		"remote_input_result_count":         0.0,
		"remote_input_result_applied_count": 0.0,
		"remote_input_last_applied":         false,
	}
	if combined := hub.upsertSessionMetrics(current.SessionID, current, "controller", controllerVideoOnly); combined != nil {
		t.Fatalf("expected nil combined while waiting for agent report, got %#v", combined)
	}
	agentReport := map[string]any{
		"source_client":           "desktop",
		"source_platform":         "desktop",
		"source_role":             "agent",
		"runtime_shell_platform":  "macos",
		"send_fps":                24.0,
		"send_kbps":               900.0,
		"capture_fps":             24.0,
		"native_sender_lifecycle": "running",
	}
	combined := hub.upsertSessionMetrics(current.SessionID, current, "agent", agentReport)
	if combined == nil {
		t.Fatal("expected video-only combined metrics after agent report")
	}
	if proofStatus, _ := combined["session_e2e_proof_status"].(string); proofStatus != "video_only" {
		t.Fatalf("expected video_only proof status, got %#v", combined["session_e2e_proof_status"])
	}
	snapshot := hub.e2eProofSnapshot()
	route := findProofRouteState(t, snapshot, "windows_to_macos")
	if route.Complete || route.Latest == nil || route.Latest.ProofStatus != "video_only" {
		t.Fatalf("expected rolling proof to start as video_only, got %#v", route)
	}

	controllerFullInput := map[string]any{
		"source_client":                      "desktop",
		"source_platform":                    "windows",
		"source_role":                        "controller",
		"first_frame_ms":                     750.0,
		"rendered_frames":                    7.0,
		"remote_input_result_count":          4.0,
		"remote_input_result_applied_count":  4.0,
		"remote_input_result_failed_count":   0.0,
		"remote_input_applied_click":         true,
		"remote_input_applied_drag":          true,
		"remote_input_applied_keyboard":      true,
		"remote_input_applied_wheel":         true,
		"remote_input_applied_categories":    "click,drag,keyboard,wheel",
		"remote_input_last_type":             "input.wheel.scroll",
		"remote_input_last_category":         "wheel",
		"remote_input_last_trace_id":         "trace-wheel",
		"remote_input_last_applied":          true,
		"remote_input_last_executor":         "macos.cg_event",
		"remote_input_last_status_code":      "applied",
		"remote_input_last_target_device_id": current.AgentDeviceID,
	}
	combined = hub.upsertSessionMetrics(current.SessionID, current, "controller", controllerFullInput)
	if combined == nil {
		t.Fatal("expected controller update to combine with retained agent report")
	}
	if proofStatus, _ := combined["session_e2e_proof_status"].(string); proofStatus != "video_and_input_observed" {
		t.Fatalf("expected complete proof status, got %#v", combined["session_e2e_proof_status"])
	}

	snapshot = hub.e2eProofSnapshot()
	route = findProofRouteState(t, snapshot, "windows_to_macos")
	if !route.Complete || route.LastSuccess == nil {
		t.Fatalf("expected windows_to_macos route complete after rolling controller update, got %#v", route)
	}
	if !sameStrings(route.LastSuccess.RemoteInputCoverage, []string{"click", "drag", "keyboard", "wheel"}) {
		t.Fatalf("expected full input coverage, got %#v", route.LastSuccess.RemoteInputCoverage)
	}
}

func TestDeriveSessionE2EProofStatus(t *testing.T) {
	tests := []struct {
		name              string
		firstFrameMs      float64
		firstFrameOK      bool
		renderedFrames    float64
		renderedFramesOK  bool
		appliedCount      float64
		appliedCountOK    bool
		lastApplied       bool
		lastAppliedOK     bool
		wantVideoObserved bool
		wantInputObserved bool
		wantProofStatus   string
	}{
		{
			name:              "video and raw input",
			firstFrameMs:      1200,
			firstFrameOK:      true,
			appliedCount:      1,
			appliedCountOK:    true,
			wantVideoObserved: true,
			wantInputObserved: true,
			wantProofStatus:   "video_and_input_observed",
		},
		{
			name:              "video by rendered frames only",
			firstFrameMs:      -1,
			firstFrameOK:      true,
			renderedFrames:    3,
			renderedFramesOK:  true,
			wantVideoObserved: true,
			wantProofStatus:   "video_only",
		},
		{
			name:              "input by last applied only",
			firstFrameMs:      -1,
			firstFrameOK:      true,
			lastApplied:       true,
			lastAppliedOK:     true,
			wantInputObserved: true,
			wantProofStatus:   "input_only",
		},
		{
			name:            "missing",
			firstFrameMs:    -1,
			firstFrameOK:    true,
			appliedCount:    0,
			appliedCountOK:  true,
			lastApplied:     false,
			lastAppliedOK:   true,
			wantProofStatus: "missing",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			videoObserved, inputObserved, proofStatus := deriveSessionE2EProofStatus(
				tt.firstFrameMs,
				tt.firstFrameOK,
				tt.renderedFrames,
				tt.renderedFramesOK,
				tt.appliedCount,
				tt.appliedCountOK,
				tt.lastApplied,
				tt.lastAppliedOK,
			)
			if videoObserved != tt.wantVideoObserved {
				t.Fatalf("expected videoObserved=%t, got %t", tt.wantVideoObserved, videoObserved)
			}
			if inputObserved != tt.wantInputObserved {
				t.Fatalf("expected inputObserved=%t, got %t", tt.wantInputObserved, inputObserved)
			}
			if proofStatus != tt.wantProofStatus {
				t.Fatalf("expected proofStatus=%q, got %q", tt.wantProofStatus, proofStatus)
			}
		})
	}
}

func TestRemoteInputCoverageFromCombined(t *testing.T) {
	coverage := remoteInputCoverageFromCombined(map[string]any{
		"remote_input_applied_click":      true,
		"remote_input_applied_drag":       true,
		"remote_input_applied_categories": "keyboard,wheel",
	})
	if !sameStrings(coverage, []string{"click", "drag", "keyboard", "wheel"}) {
		t.Fatalf("expected complete coverage, got %#v", coverage)
	}
	if !remoteInputCoverageComplete(coverage) {
		t.Fatalf("expected coverage to be complete")
	}

	partial := remoteInputCoverageFromCombined(map[string]any{
		"remote_input_applied_categories": "click",
		"remote_input_last_category":      "wheel",
		"remote_input_last_applied":       true,
	})
	if !sameStrings(partial, []string{"click", "wheel"}) {
		t.Fatalf("expected partial coverage from categories and last result, got %#v", partial)
	}
	if missing := missingRemoteInputCategories(partial); !sameStrings(missing, []string{"drag", "keyboard"}) {
		t.Fatalf("expected missing drag and keyboard, got %#v", missing)
	}
}

func TestDeriveSessionE2ERoute(t *testing.T) {
	tests := []struct {
		name                 string
		controllerReport     map[string]any
		agentReport          map[string]any
		wantController       string
		wantAgent            string
		wantRouteKey         string
		wantRoute            string
		wantTargetRoute      bool
		wantRouteAvailable   bool
		wantControllerSource bool
		wantAgentSource      bool
	}{
		{
			name: "android to windows target",
			controllerReport: map[string]any{
				"source_platform": "android",
			},
			agentReport: map[string]any{
				"source_platform": "windows",
			},
			wantController:       "android",
			wantAgent:            "windows",
			wantRouteKey:         "android_to_windows",
			wantRoute:            "android->windows",
			wantTargetRoute:      true,
			wantRouteAvailable:   true,
			wantControllerSource: true,
			wantAgentSource:      true,
		},
		{
			name: "windows to windows target",
			controllerReport: map[string]any{
				"source_platform": "windows",
			},
			agentReport: map[string]any{
				"runtime_shell_platform": "win32",
			},
			wantController:       "windows",
			wantAgent:            "windows",
			wantRouteKey:         "windows_to_windows",
			wantRoute:            "windows->windows",
			wantTargetRoute:      true,
			wantRouteAvailable:   true,
			wantControllerSource: true,
			wantAgentSource:      true,
		},
		{
			name: "windows to macos target from runtime kernel",
			controllerReport: map[string]any{
				"source_platform": "windows",
			},
			agentReport: map[string]any{
				"runtime_kernel": "tauri|macos|webkit/619.1.0",
			},
			wantController:       "windows",
			wantAgent:            "macos",
			wantRouteKey:         "windows_to_macos",
			wantRoute:            "windows->macos",
			wantTargetRoute:      true,
			wantRouteAvailable:   true,
			wantControllerSource: true,
			wantAgentSource:      true,
		},
		{
			name: "android to macos non-target",
			controllerReport: map[string]any{
				"source_platform": "android",
			},
			agentReport: map[string]any{
				"runtime_shell_platform": "macos",
			},
			wantController:       "android",
			wantAgent:            "macos",
			wantRouteKey:         "android_to_macos",
			wantRoute:            "android->macos",
			wantRouteAvailable:   true,
			wantControllerSource: true,
			wantAgentSource:      true,
		},
		{
			name: "missing agent platform",
			controllerReport: map[string]any{
				"source_platform": "android",
			},
			agentReport:          map[string]any{},
			wantController:       "android",
			wantControllerSource: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			controllerPlatform, controllerOK := resolveSessionReportPlatform(tt.controllerReport)
			if controllerPlatform != tt.wantController || controllerOK != tt.wantControllerSource {
				t.Fatalf("expected controller platform %q ok=%t, got %q ok=%t", tt.wantController, tt.wantControllerSource, controllerPlatform, controllerOK)
			}
			agentPlatform, agentOK := resolveSessionReportPlatform(tt.agentReport)
			if agentPlatform != tt.wantAgent || agentOK != tt.wantAgentSource {
				t.Fatalf("expected agent platform %q ok=%t, got %q ok=%t", tt.wantAgent, tt.wantAgentSource, agentPlatform, agentOK)
			}
			routeKey, route, targetRoute, routeOK := deriveSessionE2ERoute(controllerPlatform, controllerOK, agentPlatform, agentOK)
			if routeOK != tt.wantRouteAvailable {
				t.Fatalf("expected routeOK=%t, got %t", tt.wantRouteAvailable, routeOK)
			}
			if routeKey != tt.wantRouteKey {
				t.Fatalf("expected routeKey=%q, got %q", tt.wantRouteKey, routeKey)
			}
			if route != tt.wantRoute {
				t.Fatalf("expected route=%q, got %q", tt.wantRoute, route)
			}
			if targetRoute != tt.wantTargetRoute {
				t.Fatalf("expected targetRoute=%t, got %t", tt.wantTargetRoute, targetRoute)
			}
		})
	}
}

func TestHubE2EProofSnapshotTracksTargetRoutes(t *testing.T) {
	hub := &Hub{
		sessionMetrics: make(map[string]*sessionMetricsAggregate),
		endedSessions:  make(map[string]endedSessionRecord),
		e2eLatest:      make(map[string]sessionE2EProofRecord),
		e2eSuccess:     make(map[string]sessionE2EProofRecord),
	}

	seedCombinedMetricsForRoute(t, hub, "sess-android-windows-ok", "android", "windows", "windows.send_input", true)
	seedCombinedMetricsForRoute(t, hub, "sess-windows-windows-ok", "windows", "windows", "windows.send_input", true)
	seedCombinedMetricsForRoute(t, hub, "sess-windows-macos-ok", "windows", "macos", "macos.cg_event", true)

	snapshot := hub.e2eProofSnapshot()
	if !snapshot.Complete {
		t.Fatalf("expected proof snapshot complete, got %#v", snapshot)
	}
	if snapshot.TargetRoutesComplete != 3 || snapshot.TargetRoutesTotal != 3 {
		t.Fatalf("expected target routes 3/3, got %d/%d", snapshot.TargetRoutesComplete, snapshot.TargetRoutesTotal)
	}
	for _, routeKey := range []string{"android_to_windows", "windows_to_windows", "windows_to_macos"} {
		state := findProofRouteState(t, snapshot, routeKey)
		if !state.Complete {
			t.Fatalf("expected route %s complete, got %#v", routeKey, state)
		}
		if state.Status != "complete" || state.NextAction != "done" || len(state.Missing) != 0 {
			t.Fatalf("expected route %s complete diagnostics, got status=%q next=%q missing=%#v", routeKey, state.Status, state.NextAction, state.Missing)
		}
		if state.Latest == nil || state.Latest.ProofStatus != "video_and_input_observed" {
			t.Fatalf("expected route %s latest success, got %#v", routeKey, state.Latest)
		}
		if state.LastSuccess == nil || state.LastSuccess.ProofStatus != "video_and_input_observed" {
			t.Fatalf("expected route %s last_success success, got %#v", routeKey, state.LastSuccess)
		}
	}

	seedCombinedMetricsForRoute(t, hub, "sess-android-windows-missing", "android", "windows", "windows.send_input", false)
	snapshot = hub.e2eProofSnapshot()
	androidWindows := findProofRouteState(t, snapshot, "android_to_windows")
	if androidWindows.Latest == nil || androidWindows.Latest.ProofStatus != "missing" {
		t.Fatalf("expected latest android_to_windows proof to reflect missing retry, got %#v", androidWindows.Latest)
	}
	if androidWindows.LastSuccess == nil || androidWindows.LastSuccess.SessionID != "sess-android-windows-ok" {
		t.Fatalf("expected last_success android_to_windows to be preserved, got %#v", androidWindows.LastSuccess)
	}
	if androidWindows.Status != "complete" || androidWindows.NextAction != "done" || len(androidWindows.Missing) != 0 {
		t.Fatalf("expected completed android_to_windows to keep done diagnostics after failed retry, got status=%q next=%q missing=%#v", androidWindows.Status, androidWindows.NextAction, androidWindows.Missing)
	}
	if !snapshot.Complete || snapshot.TargetRoutesComplete != 3 {
		t.Fatalf("expected snapshot to remain complete after failed retry, got %#v", snapshot)
	}
}

func TestHubE2EProofSnapshotDiagnostics(t *testing.T) {
	hub := &Hub{
		sessionMetrics: make(map[string]*sessionMetricsAggregate),
		endedSessions:  make(map[string]endedSessionRecord),
		e2eLatest:      make(map[string]sessionE2EProofRecord),
		e2eSuccess:     make(map[string]sessionE2EProofRecord),
	}

	snapshot := hub.e2eProofSnapshot()
	empty := findProofRouteState(t, snapshot, "android_to_windows")
	if empty.Status != "not_observed" || !sameStrings(empty.Missing, []string{"video", "input"}) {
		t.Fatalf("expected not_observed diagnostics, got status=%q missing=%#v", empty.Status, empty.Missing)
	}
	if !strings.Contains(empty.NextAction, "run route") {
		t.Fatalf("expected run route next action, got %q", empty.NextAction)
	}

	hub.recordSessionE2EProofLocked(time.Now(), map[string]any{
		"session_id":                        "sess-video-only",
		"controller_device_id":              "windows-controller",
		"agent_device_id":                   "windows-target",
		"session_e2e_route_key":             "windows_to_windows",
		"session_e2e_route":                 "windows->windows",
		"session_e2e_target_route":          true,
		"session_e2e_proof_status":          "video_only",
		"session_e2e_video_observed":        true,
		"session_e2e_input_observed":        false,
		"first_frame_ms":                    900.0,
		"rendered_frames":                   5.0,
		"remote_input_result_applied_count": 0.0,
		"remote_input_result_count":         0.0,
		"session_e2e_proof_summary":         "session_e2e_proof_status=video_only route_key=windows_to_windows",
		"session_perf_summary":              "route=windows->windows first_frame_ms=900",
		"session_controller_platform":       "windows",
		"session_agent_platform":            "windows",
	})
	snapshot = hub.e2eProofSnapshot()
	videoOnly := findProofRouteState(t, snapshot, "windows_to_windows")
	if videoOnly.Status != "video_only" || !sameStrings(videoOnly.Missing, []string{"input"}) {
		t.Fatalf("expected video_only diagnostics, got status=%q missing=%#v", videoOnly.Status, videoOnly.Missing)
	}
	if !strings.Contains(videoOnly.NextAction, "input.result.push") {
		t.Fatalf("expected input next action, got %q", videoOnly.NextAction)
	}

	hub.recordSessionE2EProofLocked(time.Now(), map[string]any{
		"session_id":                        "sess-input-only",
		"controller_device_id":              "windows-controller",
		"agent_device_id":                   "macos-target",
		"session_e2e_route_key":             "windows_to_macos",
		"session_e2e_route":                 "windows->macos",
		"session_e2e_target_route":          true,
		"session_e2e_proof_status":          "input_only",
		"session_e2e_video_observed":        false,
		"session_e2e_input_observed":        true,
		"remote_input_result_applied_count": 1.0,
		"remote_input_result_count":         1.0,
		"remote_input_applied_click":        true,
		"remote_input_last_category":        "click",
		"remote_input_last_applied":         true,
		"remote_input_last_executor":        "macos.cg_event",
		"remote_input_last_status_code":     "applied",
		"remote_input_last_trace_id":        "trace-input-only",
		"session_e2e_proof_summary":         "session_e2e_proof_status=input_only route_key=windows_to_macos",
		"session_perf_summary":              "route=windows->macos remote_input_applied=1/1",
		"session_controller_platform":       "windows",
		"session_agent_platform":            "macos",
	})
	snapshot = hub.e2eProofSnapshot()
	inputOnly := findProofRouteState(t, snapshot, "windows_to_macos")
	if inputOnly.Status != "partial_input_only" || !sameStrings(inputOnly.Missing, []string{"video", "input:drag", "input:keyboard", "input:wheel"}) {
		t.Fatalf("expected input_only diagnostics, got status=%q missing=%#v", inputOnly.Status, inputOnly.Missing)
	}
	if !strings.Contains(inputOnly.NextAction, "renders remote video") {
		t.Fatalf("expected video next action, got %q", inputOnly.NextAction)
	}

	hub.recordSessionE2EProofLocked(time.Now(), map[string]any{
		"session_id":                        "sess-video-partial-input",
		"controller_device_id":              "windows-controller",
		"agent_device_id":                   "macos-target",
		"session_e2e_route_key":             "windows_to_macos",
		"session_e2e_route":                 "windows->macos",
		"session_e2e_target_route":          true,
		"session_e2e_proof_status":          "video_and_partial_input_observed",
		"session_e2e_video_observed":        true,
		"session_e2e_input_observed":        true,
		"first_frame_ms":                    800.0,
		"rendered_frames":                   6.0,
		"remote_input_result_applied_count": 2.0,
		"remote_input_result_count":         2.0,
		"remote_input_applied_click":        true,
		"remote_input_applied_keyboard":     true,
		"remote_input_applied_categories":   "click,keyboard",
		"remote_input_last_applied":         true,
		"remote_input_last_executor":        "macos.cg_event",
		"remote_input_last_status_code":     "applied",
		"remote_input_last_trace_id":        "trace-partial-input",
		"session_controller_platform":       "windows",
		"session_agent_platform":            "macos",
	})
	snapshot = hub.e2eProofSnapshot()
	partialInput := findProofRouteState(t, snapshot, "windows_to_macos")
	if partialInput.Status != "video_and_partial_input_observed" || !sameStrings(partialInput.Missing, []string{"input:drag", "input:wheel"}) {
		t.Fatalf("expected partial input diagnostics, got status=%q missing=%#v", partialInput.Status, partialInput.Missing)
	}
	if !strings.Contains(partialInput.NextAction, "drag,wheel") {
		t.Fatalf("expected missing subtype next action, got %q", partialInput.NextAction)
	}
}

func TestHubE2EProofEndpoint(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	resp, err := http.Get(server.URL + "/e2e-proof")
	if err != nil {
		t.Fatalf("e2e-proof request failed: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected e2e-proof status 200, got %d", resp.StatusCode)
	}
	var snapshot sessionE2EProofSnapshot
	if err := json.NewDecoder(resp.Body).Decode(&snapshot); err != nil {
		t.Fatalf("decode e2e-proof failed: %v", err)
	}
	if snapshot.Event != "session.e2e_proof.snapshot" {
		t.Fatalf("expected e2e proof snapshot event, got %q", snapshot.Event)
	}
	if snapshot.Complete {
		t.Fatalf("expected empty proof snapshot incomplete, got %#v", snapshot)
	}
	if snapshot.TargetRoutesTotal != 3 || len(snapshot.Routes) != 3 {
		t.Fatalf("expected 3 target routes in empty snapshot, got total=%d len=%d", snapshot.TargetRoutesTotal, len(snapshot.Routes))
	}
}

func TestHubE2EProofEndpointCORSPreflight(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	req, err := http.NewRequest(http.MethodOptions, server.URL+"/e2e-proof", nil)
	if err != nil {
		t.Fatalf("new preflight request failed: %v", err)
	}
	req.Header.Set("Origin", allowedOrigin)
	req.Header.Set("Access-Control-Request-Method", http.MethodDelete)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("e2e-proof preflight failed: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("expected preflight status 204, got %d", resp.StatusCode)
	}
	if got := resp.Header.Get("Access-Control-Allow-Origin"); got != allowedOrigin {
		t.Fatalf("expected Access-Control-Allow-Origin %q, got %q", allowedOrigin, got)
	}
	if got := resp.Header.Get("Access-Control-Allow-Methods"); !strings.Contains(got, http.MethodDelete) {
		t.Fatalf("expected DELETE in Access-Control-Allow-Methods, got %q", got)
	}
	if got := resp.Header.Get("Allow"); !strings.Contains(got, http.MethodOptions) {
		t.Fatalf("expected OPTIONS in Allow, got %q", got)
	}
}

func TestHubE2EProofEndpointRecordsWebSocketMetricsRoute(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	proveRouteThroughWebSocketMetrics(t, server, "android_to_windows", "android", "windows", "windows.send_input")
	proveRouteThroughWebSocketMetrics(t, server, "windows_to_windows", "windows", "windows", "windows.send_input")
	proveRouteThroughWebSocketMetrics(t, server, "windows_to_macos", "windows", "macos", "macos.cg_event")

	resp, err := http.Get(server.URL + "/e2e-proof")
	if err != nil {
		t.Fatalf("e2e-proof request failed: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected e2e-proof status 200, got %d", resp.StatusCode)
	}
	var snapshot sessionE2EProofSnapshot
	if err := json.NewDecoder(resp.Body).Decode(&snapshot); err != nil {
		t.Fatalf("decode e2e-proof failed: %v", err)
	}
	if !snapshot.Complete || snapshot.TargetRoutesComplete != 3 {
		t.Fatalf("expected complete proof snapshot, got %#v", snapshot)
	}
	for _, route := range []struct {
		key                string
		controllerDeviceID string
		agentDeviceID      string
	}{
		{"android_to_windows", "android-controller", "windows-agent-android"},
		{"windows_to_windows", "windows-controller", "windows-agent-windows"},
		{"windows_to_macos", "windows-controller-macos", "macos-agent"},
	} {
		state := findProofRouteState(t, snapshot, route.key)
		if !state.Complete || state.LastSuccess == nil {
			t.Fatalf("expected %s proof complete, got %#v", route.key, state)
		}
		if state.LastSuccess.ProofStatus != "video_and_input_observed" {
			t.Fatalf("expected %s proof_status video_and_input_observed, got %#v", route.key, state.LastSuccess)
		}
		if !sameStrings(state.LastSuccess.RemoteInputCoverage, []string{"click", "drag", "keyboard", "wheel"}) {
			t.Fatalf("expected %s full input coverage, got %#v", route.key, state.LastSuccess.RemoteInputCoverage)
		}
		if state.LastSuccess.ControllerDeviceID != route.controllerDeviceID || state.LastSuccess.AgentDeviceID != route.agentDeviceID {
			t.Fatalf("unexpected %s proof participants: %#v", route.key, state.LastSuccess)
		}
	}
}

func proveRouteThroughWebSocketMetrics(t *testing.T, server *httptest.Server, routeKey string, controllerPlatform string, agentPlatform string, executor string) {
	t.Helper()

	controllerDeviceID := controllerPlatform + "-controller"
	agentDeviceID := agentPlatform + "-agent-" + controllerPlatform
	if routeKey == "windows_to_macos" {
		controllerDeviceID = "windows-controller-macos"
		agentDeviceID = "macos-agent"
	}

	controller := dialWS(t, server, allowedOrigin)
	defer controller.Close()
	agent := dialWS(t, server, allowedOrigin)
	defer agent.Close()

	controllerRolePlatform := controllerPlatform
	controllerSourceClient := "desktop"
	if controllerPlatform == "android" {
		controllerSourceClient = "android"
	}
	registerDeviceWithPlatformAndCapabilities(t, controller, controllerDeviceID, "controller", controllerRolePlatform, true, false)
	registerDeviceWithPlatformAndCapabilities(t, agent, agentDeviceID, "agent", agentPlatform, false, true)

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "request-" + routeKey,
		Type:      "session.request.req",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "trace-request-" + routeKey,
		From:      protocol.From{DeviceID: controllerDeviceID, Role: "controller"},
		Payload: map[string]any{
			"target_device_id": agentDeviceID,
			"request_id":       "req-" + routeKey,
			"auth_mode":        "consent_required",
		},
	})
	requestResult := readEnvelopeOfType(t, controller, "session.request.result.push")
	sessionID, _ := requestResult.Payload["session_id"].(string)
	if sessionID == "" {
		t.Fatalf("expected session_id in %s request result", routeKey)
	}
	readEnvelopeOfType(t, controller, "session.start.push")
	readEnvelopeOfType(t, agent, "session.start.push")

	for _, category := range []struct {
		messageType string
		category    string
	}{
		{"input.mouse.button", "click"},
		{"input.mouse.button", "drag"},
		{"input.keyboard.key", "keyboard"},
		{"input.wheel.scroll", "wheel"},
	} {
		sendEnvelope(t, agent, protocol.Envelope{
			Version:   "1.0",
			MessageID: "input-result-" + routeKey + "-" + category.category,
			Type:      "input.result.push",
			Timestamp: time.Now().UnixMilli(),
			SessionID: sessionID,
			TraceID:   "trace-input-result-" + routeKey + "-" + category.category,
			From:      protocol.From{DeviceID: agentDeviceID, Role: "agent"},
			Payload: map[string]any{
				"input_type":     category.messageType,
				"input_category": category.category,
				"input_trace_id": "trace-input-" + routeKey + "-" + category.category,
				"applied":        true,
				"executor":       executor,
				"status_code":    "applied",
				"status_detail":  category.category + " applied",
				"summary":        category.category + " proof input",
				"input_count":    1.0,
			},
		})
		readEnvelopeOfType(t, controller, "input.result.push")
	}

	sendEnvelope(t, controller, protocol.Envelope{
		Version:   "1.0",
		MessageID: "metrics-controller-" + routeKey,
		Type:      "session.metrics.report",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-metrics-controller-" + routeKey,
		From:      protocol.From{DeviceID: controllerDeviceID, Role: "controller"},
		Payload: map[string]any{
			"session_id":                              sessionID,
			"report_version":                          1,
			"source_client":                           controllerSourceClient,
			"source_platform":                         controllerPlatform,
			"reason":                                  "live_controller_e2e_proof_sequence",
			"first_frame_ms":                          320.0,
			"rendered_frames":                         12.0,
			"remote_input_result_count":               4.0,
			"remote_input_result_applied_count":       4.0,
			"remote_input_result_failed_count":        0.0,
			"remote_input_applied_click":              true,
			"remote_input_applied_drag":               true,
			"remote_input_applied_keyboard":           true,
			"remote_input_applied_wheel":              true,
			"remote_input_applied_categories":         "click,drag,keyboard,wheel",
			"remote_input_required_coverage_complete": true,
			"remote_input_last_type":                  "input.wheel.scroll",
			"remote_input_last_category":              "wheel",
			"remote_input_last_trace_id":              "trace-input-" + routeKey + "-wheel",
			"remote_input_last_applied":               true,
			"remote_input_last_executor":              executor,
			"remote_input_last_status_code":           "applied",
			"remote_input_last_target_device_id":      agentDeviceID,
			"controller_quality_hint":                 "stable",
		},
	})
	sendEnvelope(t, agent, protocol.Envelope{
		Version:   "1.0",
		MessageID: "metrics-agent-" + routeKey,
		Type:      "session.metrics.report",
		Timestamp: time.Now().UnixMilli(),
		SessionID: sessionID,
		TraceID:   "trace-metrics-agent-" + routeKey,
		From:      protocol.From{DeviceID: agentDeviceID, Role: "agent"},
		Payload: map[string]any{
			"session_id":                      sessionID,
			"report_version":                  1,
			"source_client":                   "desktop",
			"source_platform":                 "desktop",
			"runtime_shell_platform":          agentPlatform,
			"reason":                          "live_agent_e2e_proof_sequence",
			"send_fps":                        24.0,
			"send_kbps":                       1200.0,
			"capture_fps":                     24.0,
			"native_sender_lifecycle":         "running",
			"native_sender_probe_frame_count": 4.0,
		},
	})
}

func TestHubE2EProofEndpointReset(t *testing.T) {
	hub := &Hub{
		sessionMetrics: make(map[string]*sessionMetricsAggregate),
		endedSessions:  make(map[string]endedSessionRecord),
		e2eLatest:      make(map[string]sessionE2EProofRecord),
		e2eSuccess:     make(map[string]sessionE2EProofRecord),
	}
	seedCombinedMetricsForRoute(t, hub, "sess-reset-ok", "windows", "windows", "windows.send_input", true)

	server := httptest.NewServer(http.HandlerFunc(hub.HandleE2EProof))
	defer server.Close()

	req, err := http.NewRequest(http.MethodDelete, server.URL, nil)
	if err != nil {
		t.Fatalf("new reset request failed: %v", err)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("reset e2e-proof request failed: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected reset status 200, got %d", resp.StatusCode)
	}
	var snapshot sessionE2EProofSnapshot
	if err := json.NewDecoder(resp.Body).Decode(&snapshot); err != nil {
		t.Fatalf("decode reset proof snapshot failed: %v", err)
	}
	if snapshot.Complete || snapshot.TargetRoutesComplete != 0 {
		t.Fatalf("expected reset snapshot incomplete, got %#v", snapshot)
	}
	windowsWindows := findProofRouteState(t, snapshot, "windows_to_windows")
	if windowsWindows.Latest != nil || windowsWindows.LastSuccess != nil || windowsWindows.Status != "not_observed" {
		t.Fatalf("expected reset windows route empty, got %#v", windowsWindows)
	}
}

func TestInferSessionQualityHintRelayThresholds(t *testing.T) {
	lowRecvWithoutStall := inferSessionQualityHint(
		26.0, true,
		120.0, true,
		26.0, true,
		30.0, true,
		"p2p_udp", true,
		0.0, true,
		"no_canvas_ready", true,
	)
	if lowRecvWithoutStall != "stable" {
		t.Fatalf("expected stable for static low bitrate without stall, got %q", lowRecvWithoutStall)
	}

	relayHighRtt := inferSessionQualityHint(
		23.0, true,
		500.0, true,
		23.0, true,
		260.0, true,
		"relay_udp_high_rtt", true,
		0.0, true,
		"no_canvas_ready", true,
	)
	if relayHighRtt != "path_relay_udp_high_rtt" {
		t.Fatalf("expected path_relay_udp_high_rtt, got %q", relayHighRtt)
	}

	highRtt := inferSessionQualityHint(
		23.0, true,
		500.0, true,
		23.0, true,
		250.0, true,
		"p2p_udp", true,
		0.0, true,
		"no_canvas_ready", true,
	)
	if highRtt != "rtt_high" {
		t.Fatalf("expected rtt_high, got %q", highRtt)
	}
}

func newTestServer(t *testing.T) *httptest.Server {
	t.Helper()

	cfg := config.Config{
		HTTPAddr:        ":0",
		ProtocolVersion: "1.0",
		LogLevel:        "debug",
		PublicWSURL:     "ws://public.example/ws",
		AllowedOrigins:  []string{allowedOrigin},
	}
	logger := observability.New()
	registry := presence.NewRegistry()
	sessions := store.New()
	hub := NewHub(cfg, logger, registry, sessions)

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"status":           "ok",
			"service":          "remote_desk-api-server",
			"protocol_version": cfg.ProtocolVersion,
		})
	})
	mux.HandleFunc("/devices", func(w http.ResponseWriter, r *http.Request) {
		if origin := r.Header.Get("Origin"); origin != "" && config.IsOriginAllowed(r, cfg.AllowedOrigins) {
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Vary", "Origin")
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"devices": registry.PublicList(),
		})
	})
	mux.HandleFunc("/e2e-proof", hub.HandleE2EProof)
	mux.HandleFunc("/ws", hub.HandleWS)
	return httptest.NewServer(mux)
}

func dialWS(t *testing.T, server *httptest.Server, origin string) *websocket.Conn {
	t.Helper()
	dialer := websocket.Dialer{}
	conn, resp, err := dialer.Dial(toWSURL(server.URL)+"/ws", http.Header{"Origin": []string{origin}})
	if err != nil {
		if resp != nil {
			defer resp.Body.Close()
			body, _ := io.ReadAll(resp.Body)
			t.Fatalf("websocket dial failed: %v status=%d body=%s", err, resp.StatusCode, string(body))
		}
		t.Fatalf("websocket dial failed: %v", err)
	}
	return conn
}

func registerDevice(t *testing.T, conn *websocket.Conn, deviceID string, role string) string {
	t.Helper()
	return registerDeviceWithCapabilities(t, conn, deviceID, role, role == "controller", role == "agent")
}

func registerDeviceWithCapabilities(t *testing.T, conn *websocket.Conn, deviceID string, role string, canControl bool, canBeControlled bool) string {
	t.Helper()
	return registerDeviceWithPlatformAndCapabilities(t, conn, deviceID, role, "macos", canControl, canBeControlled)
}

func registerDeviceWithPlatformAndCapabilities(t *testing.T, conn *websocket.Conn, deviceID string, role string, platform string, canControl bool, canBeControlled bool) string {
	t.Helper()
	sendEnvelope(t, conn, protocol.Envelope{
		Version:   "1.0",
		MessageID: "register-" + deviceID,
		Type:      "device.register.req",
		Timestamp: time.Now().UnixMilli(),
		TraceID:   "trace-register-" + deviceID,
		From:      protocol.From{DeviceID: deviceID, Role: role},
		Payload: map[string]any{
			"device_id":      deviceID,
			"user_id":        "user-" + deviceID,
			"platform":       platform,
			"client_version": "0.1.0",
			"device_name":    strings.ToUpper(deviceID),
			"capabilities": map[string]any{
				"can_control":       canControl,
				"can_be_controlled": canBeControlled,
			},
		},
	})
	msg := readEnvelopeOfType(t, conn, "device.register.rsp")
	if msg.Type != "device.register.rsp" {
		t.Fatalf("expected device.register.rsp, got %s", msg.Type)
	}
	token, _ := msg.Payload["token"].(string)
	if token == "" {
		t.Fatalf("expected token in register response for %s", deviceID)
	}
	return token
}

func seedCombinedMetricsForRoute(t *testing.T, hub *Hub, sessionID string, controllerPlatform string, agentPlatform string, executor string, success bool) {
	t.Helper()
	current := store.Session{
		SessionID:          sessionID,
		ControllerDeviceID: "controller-" + strings.ReplaceAll(controllerPlatform, "_", "-"),
		AgentDeviceID:      "agent-" + strings.ReplaceAll(agentPlatform, "_", "-"),
	}
	firstFrame := 1200.0
	renderedFrames := 8.0
	appliedCount := 4.0
	totalCount := 4.0
	lastApplied := true
	statusCode := "applied"
	coverage := map[string]any{
		"remote_input_applied_click":      true,
		"remote_input_applied_drag":       true,
		"remote_input_applied_keyboard":   true,
		"remote_input_applied_wheel":      true,
		"remote_input_applied_categories": "click,drag,keyboard,wheel",
		"remote_input_last_category":      "wheel",
	}
	if !success {
		firstFrame = -1
		renderedFrames = 0
		appliedCount = 0
		totalCount = 0
		lastApplied = false
		statusCode = "missing"
		coverage = map[string]any{}
	}
	controllerReport := map[string]any{
		"source_client":                      "desktop",
		"source_platform":                    controllerPlatform,
		"source_role":                        "controller",
		"first_frame_ms":                     firstFrame,
		"rendered_frames":                    renderedFrames,
		"remote_input_result_count":          totalCount,
		"remote_input_result_applied_count":  appliedCount,
		"remote_input_last_applied":          lastApplied,
		"remote_input_last_executor":         executor,
		"remote_input_last_status_code":      statusCode,
		"remote_input_last_trace_id":         "trace-" + sessionID,
		"remote_input_last_target_device_id": current.AgentDeviceID,
	}
	for key, value := range coverage {
		controllerReport[key] = value
	}
	controllerCombined := hub.upsertSessionMetrics(
		sessionID,
		current,
		"controller",
		controllerReport,
	)
	if controllerCombined != nil {
		t.Fatalf("expected nil combined while waiting for agent report for %s", sessionID)
	}
	combined := hub.upsertSessionMetrics(
		sessionID,
		current,
		"agent",
		map[string]any{
			"source_client":           "desktop",
			"source_platform":         "desktop",
			"source_role":             "agent",
			"runtime_shell_platform":  agentPlatform,
			"send_fps":                24.0,
			"send_kbps":               1200.0,
			"capture_fps":             24.0,
			"native_sender_lifecycle": "running",
		},
	)
	if combined == nil {
		t.Fatalf("expected combined metrics for %s", sessionID)
	}
}

func findProofRouteState(t *testing.T, snapshot sessionE2EProofSnapshot, routeKey string) sessionE2EProofRouteState {
	t.Helper()
	for _, state := range snapshot.Routes {
		if state.RouteKey == routeKey {
			return state
		}
	}
	t.Fatalf("route %s not found in proof snapshot %#v", routeKey, snapshot)
	return sessionE2EProofRouteState{}
}

func sameStrings(left []string, right []string) bool {
	if len(left) != len(right) {
		return false
	}
	for index := range left {
		if left[index] != right[index] {
			return false
		}
	}
	return true
}

func sendEnvelope(t *testing.T, conn *websocket.Conn, msg protocol.Envelope) {
	t.Helper()
	data, err := protocol.Encode(msg)
	if err != nil {
		t.Fatalf("encode failed: %v", err)
	}
	if err := conn.WriteMessage(websocket.TextMessage, data); err != nil {
		t.Fatalf("write failed: %v", err)
	}
}

func readEnvelope(t *testing.T, conn *websocket.Conn) protocol.Envelope {
	t.Helper()
	if err := conn.SetReadDeadline(time.Now().Add(3 * time.Second)); err != nil {
		t.Fatalf("set deadline failed: %v", err)
	}
	_, data, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("read failed: %v", err)
	}
	msg, err := protocol.Decode(data)
	if err != nil {
		t.Fatalf("decode failed: %v", err)
	}
	return msg
}

func readEnvelopeOfType(t *testing.T, conn *websocket.Conn, expectedType string) protocol.Envelope {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for {
		timeout := time.Until(deadline)
		if timeout <= 0 {
			t.Fatalf("timeout waiting for %s", expectedType)
		}
		msg, ok, err := readEnvelopeWithTimeout(conn, timeout)
		if err != nil {
			t.Fatalf("read failed while waiting for %s: %v", expectedType, err)
		}
		if !ok {
			t.Fatalf("timeout waiting for %s", expectedType)
		}
		if msg.Type == expectedType {
			return msg
		}
		if msg.Type == "device.presence.push" {
			continue
		}
		t.Fatalf("expected %s, got %s", expectedType, msg.Type)
	}
}

func readEnvelopeWithTimeout(conn *websocket.Conn, timeout time.Duration) (protocol.Envelope, bool, error) {
	if err := conn.SetReadDeadline(time.Now().Add(timeout)); err != nil {
		return protocol.Envelope{}, false, err
	}
	_, data, err := conn.ReadMessage()
	if err != nil {
		if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
			return protocol.Envelope{}, false, nil
		}
		return protocol.Envelope{}, false, err
	}
	msg, err := protocol.Decode(data)
	if err != nil {
		return protocol.Envelope{}, false, err
	}
	return msg, true, nil
}

func assertHealthz(t *testing.T, server *httptest.Server) {
	t.Helper()
	resp, err := http.Get(server.URL + "/healthz")
	if err != nil {
		t.Fatalf("healthz request failed: %v", err)
	}
	defer resp.Body.Close()
	var payload map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		t.Fatalf("decode healthz failed: %v", err)
	}
	if payload["status"] != "ok" {
		t.Fatalf("expected healthz status ok, got %#v", payload["status"])
	}
	if payload["protocol_version"] != "1.0" {
		t.Fatalf("expected protocol_version 1.0, got %#v", payload["protocol_version"])
	}
}

func assertDevicesListRedacted(t *testing.T, server *httptest.Server) {
	t.Helper()
	request, err := http.NewRequest(http.MethodGet, server.URL+"/devices", nil)
	if err != nil {
		t.Fatalf("build devices request failed: %v", err)
	}
	request.Header.Set("Origin", allowedOrigin)
	resp, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("devices request failed: %v", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read devices body failed: %v", err)
	}
	if got := resp.Header.Get("Access-Control-Allow-Origin"); got != allowedOrigin {
		t.Fatalf("expected Access-Control-Allow-Origin %q, got %q", allowedOrigin, got)
	}
	if strings.Contains(string(body), "client_token") {
		t.Fatalf("devices endpoint leaked client_token: %s", string(body))
	}
	var payload struct {
		Devices []map[string]any `json:"devices"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		t.Fatalf("decode devices failed: %v", err)
	}
	if len(payload.Devices) != 2 {
		t.Fatalf("expected 2 devices, got %d", len(payload.Devices))
	}
	for _, device := range payload.Devices {
		name, _ := device["device_name"].(string)
		if name == "" {
			t.Fatalf("expected device_name in public devices payload: %#v", device)
		}
	}
	assertPublicDeviceCapabilities(t, payload.Devices, "controller-01", true, false)
	assertPublicDeviceCapabilities(t, payload.Devices, "agent-01", false, true)
}

func assertPresencePushSnapshot(t *testing.T, message protocol.Envelope, expectedReason string, expectedChangedDeviceID string, expectedDeviceCount int) {
	t.Helper()
	if message.Type != "device.presence.push" {
		t.Fatalf("expected device.presence.push, got %s", message.Type)
	}
	reason, _ := message.Payload["reason"].(string)
	if reason != expectedReason {
		t.Fatalf("expected push reason %s, got %s", expectedReason, reason)
	}
	changedDeviceID, _ := message.Payload["changed_device_id"].(string)
	if changedDeviceID != expectedChangedDeviceID {
		t.Fatalf("expected changed_device_id %s, got %s", expectedChangedDeviceID, changedDeviceID)
	}
	devices, ok := message.Payload["devices"].([]any)
	if !ok {
		t.Fatalf("expected devices array in presence push payload, got %#v", message.Payload["devices"])
	}
	if len(devices) != expectedDeviceCount {
		t.Fatalf("expected %d devices in presence push payload, got %d", expectedDeviceCount, len(devices))
	}
}

func assertPresencePushCapabilities(t *testing.T, message protocol.Envelope, deviceID string, expectedCanControl bool, expectedCanBeControlled bool) {
	t.Helper()
	devices, ok := message.Payload["devices"].([]any)
	if !ok {
		t.Fatalf("expected devices array in presence push payload, got %#v", message.Payload["devices"])
	}
	for _, entry := range devices {
		item, ok := entry.(map[string]any)
		if !ok {
			continue
		}
		currentDeviceID, _ := item["device_id"].(string)
		if currentDeviceID != deviceID {
			continue
		}
		assertDeviceCapabilities(t, item, expectedCanControl, expectedCanBeControlled)
		return
	}
	t.Fatalf("device %s not found in presence push payload", deviceID)
}

func assertPresenceStatus(t *testing.T, message protocol.Envelope, deviceID string, expectedStatus string) {
	t.Helper()
	devices, ok := message.Payload["devices"].([]any)
	if !ok {
		t.Fatalf("expected devices array in presence push payload, got %#v", message.Payload["devices"])
	}
	for _, entry := range devices {
		item, ok := entry.(map[string]any)
		if !ok {
			continue
		}
		currentDeviceID, _ := item["device_id"].(string)
		if currentDeviceID != deviceID {
			continue
		}
		status, _ := item["status"].(string)
		if status != expectedStatus {
			t.Fatalf("expected device %s status %s, got %s", deviceID, expectedStatus, status)
		}
		return
	}
	t.Fatalf("device %s not found in presence push payload", deviceID)
}

func assertPublicDeviceCapabilities(t *testing.T, devices []map[string]any, deviceID string, expectedCanControl bool, expectedCanBeControlled bool) {
	t.Helper()
	for _, device := range devices {
		currentDeviceID, _ := device["device_id"].(string)
		if currentDeviceID != deviceID {
			continue
		}
		assertDeviceCapabilities(t, device, expectedCanControl, expectedCanBeControlled)
		return
	}
	t.Fatalf("device %s not found in public devices payload", deviceID)
}

func assertDeviceCapabilities(t *testing.T, device map[string]any, expectedCanControl bool, expectedCanBeControlled bool) {
	t.Helper()
	capabilities, ok := device["capabilities"].(map[string]any)
	if !ok {
		t.Fatalf("expected capabilities in device payload: %#v", device)
	}
	canControl, ok := capabilities["can_control"].(bool)
	if !ok {
		t.Fatalf("expected capabilities.can_control bool in device payload: %#v", device)
	}
	canBeControlled, ok := capabilities["can_be_controlled"].(bool)
	if !ok {
		t.Fatalf("expected capabilities.can_be_controlled bool in device payload: %#v", device)
	}
	if canControl != expectedCanControl || canBeControlled != expectedCanBeControlled {
		t.Fatalf(
			"unexpected capabilities for device %v: can_control=%v can_be_controlled=%v",
			device["device_id"],
			canControl,
			canBeControlled,
		)
	}
}

func toWSURL(httpURL string) string {
	return "ws" + strings.TrimPrefix(httpURL, "http")
}

func asInt(t *testing.T, value any) int {
	t.Helper()
	floatValue, ok := value.(float64)
	if !ok {
		t.Fatalf("expected numeric value, got %#v", value)
	}
	return int(floatValue)
}
