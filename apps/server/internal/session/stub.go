package session

import (
	"fmt"
	"net"
	"net/url"
	"sort"
	"strings"
	"time"

	"remote_desk/apps/server/internal/config"
	"remote_desk/apps/server/internal/protocol"
	"remote_desk/apps/server/internal/store"
)

type iceServerPolicy struct {
	mode          string
	includeStun   bool
	turnTransport string
}

func BuildApprovedResult(req protocol.Envelope, sessionID string) protocol.Envelope {
	now := time.Now().UnixMilli()
	requestID, _ := req.Payload["request_id"].(string)
	return protocol.Envelope{
		Version:   "1.0",
		MessageID: fmt.Sprintf("result-%d", now),
		Type:      "session.request.result.push",
		Timestamp: now,
		TraceID:   req.TraceID,
		From: protocol.From{
			DeviceID: "server",
			Role:     "server",
		},
		Payload: map[string]any{
			"request_id": requestID,
			"result":     "approved",
			"reason":     nil,
			"session_id": sessionID,
		},
	}
}

func BuildStart(current store.Session, traceID string, cfg config.Config) protocol.Envelope {
	now := time.Now().UnixMilli()
	// 作者: long；TURN 地址与凭据必须来自服务端共享配置，确保下发给客户端的 ICE 参数和实际 TURN 认证完全一致。
	turnHosts := resolveTurnHostsForControllerProfile(cfg.PublicWSURL, cfg.TurnPublicHost, current.ControllerProfile)
	icePolicy := resolveIceServerPolicy(cfg)
	iceServers := make([]map[string]any, 0, 2)
	if stunURLs := buildStunURLs(cfg.StunURLs, icePolicy.includeStun); len(stunURLs) > 0 {
		iceServers = append(iceServers, map[string]any{
			"urls": stunURLs,
		})
	}
	if turnURLs := buildTurnURLs(turnHosts, cfg.TurnPort, icePolicy.turnTransport); len(turnURLs) > 0 {
		iceServers = append(iceServers, map[string]any{
			"urls":       turnURLs,
			"username":   cfg.TurnUsername,
			"credential": cfg.TurnPassword,
		})
	}
	return protocol.Envelope{
		Version:   "1.0",
		MessageID: fmt.Sprintf("start-%d", now),
		Type:      "session.start.push",
		Timestamp: now,
		SessionID: current.SessionID,
		TraceID:   traceID,
		From: protocol.From{
			DeviceID: "server",
			Role:     "server",
		},
		Payload: map[string]any{
			"session_id":           current.SessionID,
			"controller_device_id": current.ControllerDeviceID,
			"agent_device_id":      current.AgentDeviceID,
			"transport": map[string]any{
				"mode":       "webrtc",
				"signal_url": cfg.PublicWSURL,
			},
			"webrtc": map[string]any{
				"ice_servers":        iceServers,
				"controller_profile": current.ControllerProfile,
				"ice_policy": map[string]any{
					"mode":                   icePolicy.mode,
					"stun_enabled":           icePolicy.includeStun,
					"turn_transport":         icePolicy.turnTransport,
					"relay_udp_high_rtt_ms":  cfg.ICERelayUDPHighRTTMS,
					"degrade_streak_samples": cfg.ICEDegradeStreakSamples,
				},
			},
			"start_deadline_ms": 15000,
		},
	}
}

func resolveTurnHostsForControllerProfile(publicWSURL string, explicitTurnHost string, controllerProfile string) []string {
	return filterTurnHostsForControllerProfile(
		resolveLocalTurnHosts(publicWSURL, explicitTurnHost),
		controllerProfile,
	)
}

func filterTurnHostsForControllerProfile(hosts []string, controllerProfile string) []string {
	physicalAndroid := strings.EqualFold(strings.TrimSpace(controllerProfile), "android_phone")
	result := make([]string, 0, len(hosts))
	for _, host := range hosts {
		normalized := strings.TrimSpace(strings.ToLower(host))
		if normalized == "" {
			continue
		}
		if physicalAndroid && normalized == "10.0.2.2" {
			continue
		}
		result = append(result, normalized)
	}
	sort.SliceStable(result, func(i, j int) bool {
		return turnHostPriority(result[i], physicalAndroid) < turnHostPriority(result[j], physicalAndroid)
	})
	return result
}

func turnHostPriority(host string, physicalAndroid bool) int {
	if !physicalAndroid {
		return 0
	}
	switch strings.TrimSpace(strings.ToLower(host)) {
	case "127.0.0.1", "localhost", "::1":
		return 2
	default:
		return 0
	}
}

func resolveLocalTurnHosts(publicWSURL string, explicitTurnHost string) []string {
	if normalized := normalizeTurnHost(explicitTurnHost); normalized != "" {
		return []string{normalized}
	}
	hosts := []string{"127.0.0.1", "10.0.2.2"}
	if host := hostFromURL(publicWSURL); host != "" {
		hosts = append(hosts, host)
	}

	ifaces, err := net.Interfaces()
	if err == nil {
		for _, iface := range ifaces {
			if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
				continue
			}
			addrs, err := iface.Addrs()
			if err != nil {
				continue
			}
			for _, addr := range addrs {
				var ip net.IP
				switch value := addr.(type) {
				case *net.IPNet:
					ip = value.IP
				case *net.IPAddr:
					ip = value.IP
				}
				if ip == nil {
					continue
				}
				v4 := ip.To4()
				if v4 == nil || !v4.IsPrivate() {
					continue
				}
				hosts = append(hosts, v4.String())
			}
		}
	}

	uniq := make(map[string]struct{}, len(hosts))
	result := make([]string, 0, len(hosts))
	for _, host := range hosts {
		normalized := strings.TrimSpace(strings.ToLower(host))
		switch normalized {
		case "", "localhost", "0.0.0.0":
			continue
		}
		if _, exists := uniq[normalized]; exists {
			continue
		}
		uniq[normalized] = struct{}{}
		result = append(result, normalized)
	}
	sort.Strings(result)
	return result
}

func normalizeTurnHost(value string) string {
	normalized := strings.TrimSpace(value)
	if normalized == "" {
		return ""
	}
	if parsed, err := url.Parse(normalized); err == nil && parsed.Hostname() != "" {
		return strings.ToLower(parsed.Hostname())
	}
	return strings.ToLower(strings.Trim(normalized, "[]"))
}

func hostFromURL(raw string) string {
	parsed, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || parsed == nil {
		return ""
	}
	return parsed.Hostname()
}

func buildStunURLs(configured []string, enabled bool) []string {
	if !enabled {
		return nil
	}
	return append([]string(nil), configured...)
}

func buildTurnURLs(hosts []string, port int, transport string) []string {
	normalizedTransport := strings.ToLower(strings.TrimSpace(transport))
	switch normalizedTransport {
	case "udp", "tcp":
	default:
		normalizedTransport = "all"
	}
	urls := make([]string, 0, len(hosts)*3)
	for _, host := range hosts {
		switch normalizedTransport {
		case "udp":
			urls = append(urls, fmt.Sprintf("turn:%s:%d?transport=udp", host, port))
		case "tcp":
			urls = append(urls, fmt.Sprintf("turn:%s:%d?transport=tcp", host, port))
		default:
			urls = append(urls, fmt.Sprintf("turn:%s:%d", host, port))
			urls = append(urls, fmt.Sprintf("turn:%s:%d?transport=udp", host, port))
			urls = append(urls, fmt.Sprintf("turn:%s:%d?transport=tcp", host, port))
		}
	}
	return urls
}

func resolveIceServerPolicy(cfg config.Config) iceServerPolicy {
	policy := iceServerPolicy{
		mode:          cfg.ICEMode,
		includeStun:   len(cfg.StunURLs) > 0,
		turnTransport: cfg.ICETurnTransport,
	}

	switch cfg.ICEMode {
	case "relay_only":
		policy.mode = "relay_only"
		policy.includeStun = false
	case "relay_udp":
		policy.mode = "relay_udp"
		policy.includeStun = false
		policy.turnTransport = "udp"
	case "relay_tcp":
		policy.mode = "relay_tcp"
		policy.includeStun = false
		policy.turnTransport = "tcp"
	}

	switch cfg.ICETurnTransport {
	case "udp":
		policy.turnTransport = "udp"
	case "tcp":
		policy.turnTransport = "tcp"
	case "all":
		policy.turnTransport = "all"
	}

	return policy
}

const (
	EndReasonUserEnd          = "user_end"
	EndReasonPeerDisconnected = "peer_disconnected"
	EndReasonTimeout          = "timeout"
	EndReasonServerTerminate  = "server_terminate"
)

func NormalizeEndReason(reason any) string {
	switch value := reason.(type) {
	case string:
		switch value {
		case EndReasonUserEnd, EndReasonPeerDisconnected, EndReasonTimeout, EndReasonServerTerminate:
			return value
		case "network_lost":
			return EndReasonPeerDisconnected
		case "error":
			return EndReasonServerTerminate
		}
	}
	return EndReasonServerTerminate
}

func BuildEnd(sessionID string, traceID string, reason string) protocol.Envelope {
	now := time.Now().UnixMilli()
	if reason == "" {
		reason = EndReasonServerTerminate
	}
	return protocol.Envelope{
		Version:   "1.0",
		MessageID: fmt.Sprintf("end-%d", now),
		Type:      "session.end.push",
		Timestamp: now,
		SessionID: sessionID,
		TraceID:   traceID,
		From: protocol.From{
			DeviceID: "server",
			Role:     "server",
		},
		Payload: map[string]any{
			"session_id": sessionID,
			"reason":     reason,
			"ended_at":   now,
		},
	}
}

func BuildInputAck(sessionID string, traceID string, echoType string) protocol.Envelope {
	now := time.Now().UnixMilli()
	return protocol.Envelope{
		Version:   "1.0",
		MessageID: fmt.Sprintf("ack-%d", now),
		Type:      "input.ack",
		Timestamp: now,
		SessionID: sessionID,
		TraceID:   traceID,
		From: protocol.From{
			DeviceID: "server",
			Role:     "server",
		},
		Payload: map[string]any{
			"accepted":  true,
			"echo_type": echoType,
		},
	}
}
