package main

import (
	"encoding/json"
	"net/http"

	"remote_desk/apps/server/internal/config"
	"remote_desk/apps/server/internal/observability"
	"remote_desk/apps/server/internal/presence"
	"remote_desk/apps/server/internal/store"
	"remote_desk/apps/server/internal/transport"
)

func main() {
	cfg := config.Load()
	logger := observability.New()
	registry := presence.NewRegistry()
	sessions := store.New()
	hub := transport.NewHub(cfg, logger, registry, sessions)

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

	logger.Info(map[string]any{
		"event":            "server.starting",
		"http_addr":        cfg.HTTPAddr,
		"protocol_version": cfg.ProtocolVersion,
		"public_ws_url":    cfg.PublicWSURL,
	})

	if err := http.ListenAndServe(cfg.HTTPAddr, mux); err != nil {
		logger.Error(map[string]any{"event": "server.stopped", "error": err.Error()})
	}
}
