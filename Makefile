SHELL := /bin/bash
ROOT := $(CURDIR)
SERVER_DIR := $(ROOT)/apps/server
LOCAL_SERVER_CONFIG := $(ROOT)/remote-desk.json
SERVER_CONFIG ?= $(if $(wildcard $(LOCAL_SERVER_CONFIG)),$(LOCAL_SERVER_CONFIG),$(ROOT)/infra/remote-desk.example.json)

ifneq (,$(wildcard .env))
include .env
export
endif

.PHONY: init dev proto-check server-run turn-run server-test lint test android-build desktop-check

init:
	@cp -n .env.example .env 2>/dev/null || true
	@cp -n infra/remote-desk.example.json $(LOCAL_SERVER_CONFIG) 2>/dev/null || true
	@echo "Initialized local env and server config files if missing."

dev:
	docker compose -f infra/compose/docker-compose.dev.yml up -d postgres
	$(MAKE) server-run

proto-check:
	python3 scripts/check_protocol.py

server-run:
	cd $(SERVER_DIR) && go run ./cmd/api-server -config "$(SERVER_CONFIG)"

turn-run:
	cd $(SERVER_DIR) && go run ./cmd/turn-server -config "$(SERVER_CONFIG)"

server-test:
	cd $(SERVER_DIR) && go test ./...

android-build:
	cd apps/android && ./gradlew assembleDebug

desktop-check:
	cd apps/desktop/src-tauri && cargo check

lint:
	$(MAKE) proto-check
	cd $(SERVER_DIR) && go test ./...
	cd apps/desktop/src-tauri && cargo fmt --check && cargo clippy -- -D warnings

test:
	$(MAKE) proto-check
	$(MAKE) server-test
	cd apps/desktop/src-tauri && cargo test
