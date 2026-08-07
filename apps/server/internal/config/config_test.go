package config

import (
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestLoadIncludesLocalDevOrigins(t *testing.T) {
	t.Setenv("RD_ALLOWED_ORIGINS", "")

	cfg, err := Load("")
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	want := []string{
		"http://localhost:5173",
		"http://127.0.0.1:5173",
		"http://localhost:4173",
		"http://127.0.0.1:4173",
		"tauri://localhost",
		"http://tauri.localhost",
		"https://tauri.localhost",
	}

	assertOrigins(t, cfg.AllowedOrigins, want)
}

func TestLoadHonorsExplicitAllowedOrigins(t *testing.T) {
	t.Setenv("RD_ALLOWED_ORIGINS", "https://desk.example, https://controller.example")

	cfg, err := Load("")
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	want := []string{
		"https://desk.example",
		"https://controller.example",
	}

	assertOrigins(t, cfg.AllowedOrigins, want)
}

func TestLoadReadsSharedRelayAndTurnConfig(t *testing.T) {
	path := filepath.Join(t.TempDir(), "remote-desk.json")
	content := `{
  "http_addr": "0.0.0.0:19081",
  "public_ws_url": "wss://relay.example/ws",
  "allowed_origins": ["https://desk.example"],
  "turn_bind_addr": "0.0.0.0:4478",
  "turn_public_ip": "203.0.113.10",
  "turn_public_host": "turn.example",
  "turn_port": 4478,
  "turn_realm": "relay.example",
  "turn_username": "relay-user",
  "turn_password": "relay-password",
  "stun_urls": ["stun:stun.example:3478"],
  "ice_mode": "relay_tcp",
  "ice_turn_transport": "tcp",
  "ice_relay_udp_high_rtt_ms": 180,
  "ice_degrade_streak_samples": 4
}`
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load(%q) error = %v", path, err)
	}
	if cfg.HTTPAddr != "0.0.0.0:19081" || cfg.TurnBindAddr != "0.0.0.0:4478" || cfg.TurnPort != 4478 {
		t.Fatalf("unexpected listen config: %#v", cfg)
	}
	if cfg.TurnUsername != "relay-user" || cfg.TurnPassword != "relay-password" {
		t.Fatalf("unexpected TURN credentials")
	}
	if cfg.ICEMode != "relay_tcp" || cfg.ICETurnTransport != "tcp" || cfg.ICEDegradeStreakSamples != 4 {
		t.Fatalf("unexpected ICE config: %#v", cfg)
	}
}

func TestLoadUsesTurnPublicIPAsAdvertisedHost(t *testing.T) {
	path := filepath.Join(t.TempDir(), "remote-desk.json")
	if err := os.WriteFile(path, []byte(`{"turn_public_ip":"203.0.113.20"}`), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load(%q) error = %v", path, err)
	}
	if cfg.TurnPublicHost != "203.0.113.20" {
		t.Fatalf("expected advertised TURN host to inherit public IP, got %q", cfg.TurnPublicHost)
	}
}

func TestLoadRejectsTurnPublicHostWithPort(t *testing.T) {
	path := filepath.Join(t.TempDir(), "remote-desk.json")
	if err := os.WriteFile(path, []byte(`{"turn_public_host":"turn.example:3478"}`), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}
	if _, err := Load(path); err == nil {
		t.Fatal("expected turn_public_host with port to fail")
	}
}

func TestLoadRejectsUnknownConfigField(t *testing.T) {
	path := filepath.Join(t.TempDir(), "remote-desk.json")
	if err := os.WriteFile(path, []byte(`{"unknown": true}`), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}
	if _, err := Load(path); err == nil {
		t.Fatal("expected unknown config field to fail")
	}
}

func TestLoadUsesConfigFileFromEnvironmentAndAppliesOverrides(t *testing.T) {
	path := filepath.Join(t.TempDir(), "remote-desk.json")
	content := `{
  "public_ws_url": "wss://relay.example/ws",
  "turn_username": "file-user",
  "turn_password": "file-password"
}`
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}
	t.Setenv("RD_CONFIG_FILE", path)
	t.Setenv("RD_TURN_USERNAME", "environment-user")

	cfg, err := Load("")
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.PublicWSURL != "wss://relay.example/ws" {
		t.Fatalf("unexpected public websocket URL: %q", cfg.PublicWSURL)
	}
	if cfg.TurnUsername != "environment-user" || cfg.TurnPassword != "file-password" {
		t.Fatalf("unexpected TURN credentials after override")
	}
}

func TestIsOriginAllowed(t *testing.T) {
	allowed := []string{
		"http://localhost:5173",
		"http://127.0.0.1:5173",
		"http://localhost:4173",
		"http://127.0.0.1:4173",
		"tauri://localhost",
		"http://tauri.localhost",
		"https://tauri.localhost",
	}

	tests := []struct {
		name   string
		origin string
		want   bool
	}{
		{name: "missing origin", origin: "", want: true},
		{name: "vite dev localhost", origin: "http://localhost:5173", want: true},
		{name: "vite dev loopback", origin: "http://127.0.0.1:5173", want: true},
		{name: "vite preview localhost", origin: "http://localhost:4173", want: true},
		{name: "vite preview loopback", origin: "http://127.0.0.1:4173", want: true},
		{name: "tauri protocol", origin: "tauri://localhost", want: true},
		{name: "tauri http localhost", origin: "http://tauri.localhost", want: true},
		{name: "tauri https localhost", origin: "https://tauri.localhost", want: true},
		{name: "wrong preview port", origin: "http://localhost:4174", want: false},
		{name: "wrong tauri host", origin: "https://desktop.localhost", want: false},
		{name: "blocked host", origin: "http://evil.com", want: false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := httptest.NewRequest("GET", "http://server.test/ws", nil)
			if tt.origin != "" {
				req.Header.Set("Origin", tt.origin)
			}

			if got := IsOriginAllowed(req, allowed); got != tt.want {
				t.Fatalf("IsOriginAllowed(%q) = %v, want %v", tt.origin, got, tt.want)
			}
		})
	}
}

func assertOrigins(t *testing.T, got []string, want []string) {
	t.Helper()

	if len(got) != len(want) {
		t.Fatalf("expected %d allowed origins, got %d: %#v", len(want), len(got), got)
	}
	for i, origin := range want {
		if got[i] != origin {
			t.Fatalf("expected allowed origin %q at index %d, got %q", origin, i, got[i])
		}
	}
}
