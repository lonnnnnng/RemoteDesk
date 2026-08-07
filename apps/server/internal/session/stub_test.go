package session

import (
	"strings"
	"testing"

	"remote_desk/apps/server/internal/config"
	"remote_desk/apps/server/internal/store"
)

func TestResolveIceServerPolicy_Default(t *testing.T) {
	policy := resolveIceServerPolicy(config.Default())
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
	cfg := config.Default()
	cfg.ICEMode = "relay_tcp"
	cfg.ICETurnTransport = "tcp"
	policy := resolveIceServerPolicy(cfg)
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
	cfg := config.Default()
	cfg.ICEMode = "relay_tcp"
	cfg.ICETurnTransport = "udp"
	policy := resolveIceServerPolicy(cfg)
	if policy.includeStun {
		t.Fatalf("expected includeStun=false because relay_tcp mode defaults to relay-only")
	}
	if policy.turnTransport != "udp" {
		t.Fatalf("expected turnTransport=udp override, got %q", policy.turnTransport)
	}
}

func TestBuildStart_RelayTCPOnlyPolicy(t *testing.T) {
	cfg := config.Default()
	cfg.PublicWSURL = "ws://localhost:18081/ws"
	cfg.TurnPublicHost = "turn.example.com"
	cfg.TurnUsername = "relay-user"
	cfg.TurnPassword = "relay-password"
	cfg.ICEMode = "relay_tcp"
	cfg.ICETurnTransport = "tcp"

	msg := BuildStart(store.Session{
		SessionID:          "sess-test-1",
		ControllerDeviceID: "controller-1",
		AgentDeviceID:      "agent-1",
	}, "trace-1", cfg)

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
	if rawServers[0]["username"] != "relay-user" || rawServers[0]["credential"] != "relay-password" {
		t.Fatalf("expected configured TURN credentials, got %#v", rawServers[0])
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

func TestFilterTurnHostsForAndroidPhone(t *testing.T) {
	hosts := []string{"127.0.0.1", "10.0.2.2", "192.168.1.20", "10.93.137.12"}
	filtered := filterTurnHostsForControllerProfile(hosts, "android_phone")
	joined := strings.Join(filtered, ",")
	if strings.Contains(joined, "10.0.2.2") {
		t.Fatalf("android_phone turn hosts should not include emulator gateway: %#v", filtered)
	}
	if len(filtered) != 3 {
		t.Fatalf("expected three filtered hosts, got %#v", filtered)
	}
	if filtered[len(filtered)-1] != "127.0.0.1" {
		t.Fatalf("expected loopback host to be last for android_phone, got %#v", filtered)
	}
}

func TestFilterTurnHostsKeepsEmulatorGatewayForEmulator(t *testing.T) {
	hosts := []string{"127.0.0.1", "10.0.2.2"}
	filtered := filterTurnHostsForControllerProfile(hosts, "emulator")
	joined := strings.Join(filtered, ",")
	if !strings.Contains(joined, "10.0.2.2") {
		t.Fatalf("emulator turn hosts should keep emulator gateway, got %#v", filtered)
	}
}

func TestResolveRelayUdpHighRttMsDefault(t *testing.T) {
	value := config.Default().ICERelayUDPHighRTTMS
	if value != 220 {
		t.Fatalf("expected default relay_udp_high_rtt_ms=220, got %v", value)
	}
}
