package config

import (
	"net/http"
	"os"
	"strings"
)

type Config struct {
	HTTPAddr        string
	ProtocolVersion string
	LogLevel        string
	PublicWSURL     string
	AllowedOrigins  []string
}

func Load() Config {
	allowedOrigins := splitCSV(envOrDefault("RD_ALLOWED_ORIGINS", "http://localhost:5173,http://127.0.0.1:5173,http://localhost:4173,http://127.0.0.1:4173,tauri://localhost,http://tauri.localhost,https://tauri.localhost"))
	return Config{
		HTTPAddr:        envOrDefault("RD_HTTP_ADDR", ":18081"),
		ProtocolVersion: envOrDefault("RD_PROTOCOL_VERSION", "1.0"),
		LogLevel:        envOrDefault("RD_LOG_LEVEL", "debug"),
		PublicWSURL:     envOrDefault("RD_WS_PUBLIC_URL", "ws://localhost:18081/ws"),
		AllowedOrigins:  allowedOrigins,
	}
}

func envOrDefault(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func splitCSV(value string) []string {
	parts := strings.Split(value, ",")
	result := make([]string, 0, len(parts))
	for _, part := range parts {
		trimmed := strings.TrimSpace(part)
		if trimmed != "" {
			result = append(result, trimmed)
		}
	}
	return result
}

func IsOriginAllowed(r *http.Request, allowedOrigins []string) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true
	}
	for _, allowed := range allowedOrigins {
		if origin == allowed {
			return true
		}
	}
	return false
}
