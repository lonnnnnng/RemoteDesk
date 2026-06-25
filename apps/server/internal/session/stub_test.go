package session

import (
	"strings"
	"testing"

	"remote_desk/apps/server/internal/store"
)

func TestResolveIceServerPolicy_Default(t *testing.T) {
	t.Setenv("RD_ICE_MODE", "")
	t.Setenv("RD_ICE_DISABLE_STUN", "")
	t.Setenv("RD_ICE_TURN_TRANSPORT", "")

	policy := resolveIceServerPolicy()
	if policy.mode != "default" {
		t.Fatalf("expected mode=default, got %q", policy.mode)
	}
	if !policy.includeStun {
		t.Fatalf("expected includeStun=true")
	}
	if policy.turnTransport != "all" {
		t.Fatalf("expected turnTransport=all, got %q", policy.turnTransport)
	}
}

func TestResolveIceServerPolicy_RelayTCPMode(t *testing.T) {
	t.Setenv("RD_ICE_MODE", "relay_tcp")
	t.Setenv("RD_ICE_DISABLE_STUN", "")
	t.Setenv("RD_ICE_TURN_TRANSPORT", "")

	policy := resolveIceServerPolicy()
	if policy.mode != "relay_tcp" {
		t.Fatalf("expected mode=relay_tcp, got %q", policy.mode)
	}
	if policy.includeStun {
		t.Fatalf("expected includeStun=false in relay_tcp mode")
	}
	if policy.turnTransport != "tcp" {
		t.Fatalf("expected turnTransport=tcp, got %q", policy.turnTransport)
	}
}

func TestResolveIceServerPolicy_ExplicitOverride(t *testing.T) {
	t.Setenv("RD_ICE_MODE", "relay_tcp")
	t.Setenv("RD_ICE_DISABLE_STUN", "false")
	t.Setenv("RD_ICE_TURN_TRANSPORT", "udp")

	policy := resolveIceServerPolicy()
	if policy.includeStun {
		t.Fatalf("expected includeStun=false because relay_tcp mode defaults to relay-only")
	}
	if policy.turnTransport != "udp" {
		t.Fatalf("expected turnTransport=udp override, got %q", policy.turnTransport)
	}
}

func TestBuildStart_RelayTCPOnlyPolicy(t *testing.T) {
	t.Setenv("RD_ICE_MODE", "relay_tcp")
	t.Setenv("RD_ICE_DISABLE_STUN", "")
	t.Setenv("RD_ICE_TURN_TRANSPORT", "")
	t.Setenv("RD_TURN_PORT", "3478")

	msg := BuildStart(store.Session{
		SessionID:          "sess-test-1",
		ControllerDeviceID: "controller-1",
		AgentDeviceID:      "agent-1",
	}, "trace-1", "ws://localhost:18081/ws")

	payload, ok := msg.Payload["webrtc"].(map[string]any)
	if !ok {
		t.Fatalf("webrtc payload missing")
	}
	rawServers, ok := payload["ice_servers"].([]map[string]any)
	if !ok {
		t.Fatalf("ice_servers malformed")
	}
	if len(rawServers) != 1 {
		t.Fatalf("expected only TURN server when relay_tcp mode is enabled, got %d", len(rawServers))
	}
	urlsAny, ok := rawServers[0]["urls"].([]string)
	if !ok {
		t.Fatalf("turn urls malformed")
	}
	if len(urlsAny) == 0 {
		t.Fatalf("expected turn urls")
	}
	for _, url := range urlsAny {
		if strings.Contains(url, "transport=udp") || !strings.Contains(url, "transport=tcp") {
			t.Fatalf("expected tcp-only turn url, got %q", url)
		}
	}
}

func TestBuildTurnURLsTransportFilter(t *testing.T) {
	hosts := []string{"127.0.0.1"}
	tcpURLs := buildTurnURLs(hosts, 3478, "tcp")
	if len(tcpURLs) != 1 || !strings.Contains(tcpURLs[0], "transport=tcp") {
		t.Fatalf("expected one tcp url, got %#v", tcpURLs)
	}
	udpURLs := buildTurnURLs(hosts, 3478, "udp")
	if len(udpURLs) != 1 || !strings.Contains(udpURLs[0], "transport=udp") {
		t.Fatalf("expected one udp url, got %#v", udpURLs)
	}
	allURLs := buildTurnURLs(hosts, 3478, "all")
	if len(allURLs) != 3 {
		t.Fatalf("expected three urls for all transport, got %#v", allURLs)
	}
}

func TestResolveRelayUdpHighRttMsDefault(t *testing.T) {
	t.Setenv("RD_ICE_POLICY_RELAY_UDP_HIGH_RTT_MS", "")
	value := resolveRelayUdpHighRttMs()
	if value != 220 {
		t.Fatalf("expected default relay_udp_high_rtt_ms=220, got %v", value)
	}
}
