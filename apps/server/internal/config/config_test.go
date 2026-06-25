package config

import (
	"net/http/httptest"
	"testing"
)

func TestLoadIncludesLocalDevOrigins(t *testing.T) {
	t.Setenv("RD_ALLOWED_ORIGINS", "")

	cfg := Load()
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

	cfg := Load()
	want := []string{
		"https://desk.example",
		"https://controller.example",
	}

	assertOrigins(t, cfg.AllowedOrigins, want)
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
