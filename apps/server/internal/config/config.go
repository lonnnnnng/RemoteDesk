package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
)

type Config struct {
	HTTPAddr                string   `json:"http_addr"`
	ProtocolVersion         string   `json:"protocol_version"`
	LogLevel                string   `json:"log_level"`
	PublicWSURL             string   `json:"public_ws_url"`
	AllowedOrigins          []string `json:"allowed_origins"`
	TurnBindAddr            string   `json:"turn_bind_addr"`
	TurnPublicIP            string   `json:"turn_public_ip"`
	TurnPublicHost          string   `json:"turn_public_host"`
	TurnPort                int      `json:"turn_port"`
	TurnRealm               string   `json:"turn_realm"`
	TurnUsername            string   `json:"turn_username"`
	TurnPassword            string   `json:"turn_password"`
	StunURLs                []string `json:"stun_urls"`
	ICEMode                 string   `json:"ice_mode"`
	ICETurnTransport        string   `json:"ice_turn_transport"`
	ICERelayUDPHighRTTMS    float64  `json:"ice_relay_udp_high_rtt_ms"`
	ICEDegradeStreakSamples int      `json:"ice_degrade_streak_samples"`
}

func Default() Config {
	return Config{
		HTTPAddr:                ":18081",
		ProtocolVersion:         "1.0",
		LogLevel:                "debug",
		PublicWSURL:             "ws://localhost:18081/ws",
		AllowedOrigins:          splitCSV("http://localhost:5173,http://127.0.0.1:5173,http://localhost:4173,http://127.0.0.1:4173,tauri://localhost,http://tauri.localhost,https://tauri.localhost"),
		TurnBindAddr:            "0.0.0.0:3478",
		TurnPort:                3478,
		TurnRealm:               "remote.desk",
		TurnUsername:            "rd",
		TurnPassword:            "rdpass",
		StunURLs:                []string{"stun:stun.l.google.com:19302"},
		ICEMode:                 "default",
		ICETurnTransport:        "all",
		ICERelayUDPHighRTTMS:    220,
		ICEDegradeStreakSamples: 3,
	}
}

// 作者: long；relay 和 TURN 必须读取同一份配置，避免服务监听参数已经改变、会话下发仍保留旧凭据。
func Load(path string) (Config, error) {
	cfg := Default()
	resolvedPath := strings.TrimSpace(path)
	if resolvedPath == "" {
		resolvedPath = strings.TrimSpace(os.Getenv("RD_CONFIG_FILE"))
	}
	if resolvedPath != "" {
		if err := loadJSONFile(resolvedPath, &cfg); err != nil {
			return Config{}, err
		}
	}
	if err := applyEnvironmentOverrides(&cfg); err != nil {
		return Config{}, err
	}
	normalize(&cfg)
	if err := Validate(cfg); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

func loadJSONFile(path string, cfg *Config) error {
	file, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("open config file %q: %w", path, err)
	}
	defer file.Close()

	decoder := json.NewDecoder(file)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(cfg); err != nil {
		return fmt.Errorf("decode config file %q: %w", path, err)
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		if err == nil {
			return fmt.Errorf("decode config file %q: multiple JSON values are not allowed", path)
		}
		return fmt.Errorf("decode config file %q: %w", path, err)
	}
	return nil
}

func applyEnvironmentOverrides(cfg *Config) error {
	// 作者: long；保留环境变量覆盖仅用于兼容现有部署脚本，显式 JSON 文件仍是生产部署的主配置来源。
	stringOverrides := []struct {
		key    string
		target *string
	}{
		{key: "RD_HTTP_ADDR", target: &cfg.HTTPAddr},
		{key: "RD_PROTOCOL_VERSION", target: &cfg.ProtocolVersion},
		{key: "RD_LOG_LEVEL", target: &cfg.LogLevel},
		{key: "RD_WS_PUBLIC_URL", target: &cfg.PublicWSURL},
		{key: "RD_TURN_BIND_ADDR", target: &cfg.TurnBindAddr},
		{key: "RD_TURN_PUBLIC_IP", target: &cfg.TurnPublicIP},
		{key: "RD_TURN_PUBLIC_HOST", target: &cfg.TurnPublicHost},
		{key: "RD_TURN_REALM", target: &cfg.TurnRealm},
		{key: "RD_TURN_USERNAME", target: &cfg.TurnUsername},
		{key: "RD_TURN_PASSWORD", target: &cfg.TurnPassword},
		{key: "RD_ICE_MODE", target: &cfg.ICEMode},
		{key: "RD_ICE_TURN_TRANSPORT", target: &cfg.ICETurnTransport},
	}
	for _, override := range stringOverrides {
		if value, ok := os.LookupEnv(override.key); ok && strings.TrimSpace(value) != "" {
			*override.target = value
		}
	}

	if value, ok := os.LookupEnv("RD_ALLOWED_ORIGINS"); ok && strings.TrimSpace(value) != "" {
		cfg.AllowedOrigins = splitCSV(value)
	}
	if value, ok := os.LookupEnv("RD_STUN_URLS"); ok {
		cfg.StunURLs = splitCSV(value)
	}
	if disabled, ok, err := envBool("RD_ICE_DISABLE_STUN"); err != nil {
		return err
	} else if ok && disabled {
		cfg.StunURLs = nil
	}
	if err := overrideInt("RD_TURN_PORT", &cfg.TurnPort); err != nil {
		return err
	}
	if err := overrideInt("RD_ICE_DEGRADE_STREAK_SAMPLES", &cfg.ICEDegradeStreakSamples); err != nil {
		return err
	}
	if err := overrideFloat("RD_ICE_POLICY_RELAY_UDP_HIGH_RTT_MS", &cfg.ICERelayUDPHighRTTMS); err != nil {
		return err
	}
	return nil
}

func overrideInt(key string, target *int) error {
	raw, ok := os.LookupEnv(key)
	if !ok || strings.TrimSpace(raw) == "" {
		return nil
	}
	value, err := strconv.Atoi(strings.TrimSpace(raw))
	if err != nil {
		return fmt.Errorf("%s must be an integer: %w", key, err)
	}
	*target = value
	return nil
}

func overrideFloat(key string, target *float64) error {
	raw, ok := os.LookupEnv(key)
	if !ok || strings.TrimSpace(raw) == "" {
		return nil
	}
	value, err := strconv.ParseFloat(strings.TrimSpace(raw), 64)
	if err != nil {
		return fmt.Errorf("%s must be a number: %w", key, err)
	}
	*target = value
	return nil
}

func envBool(key string) (bool, bool, error) {
	raw, ok := os.LookupEnv(key)
	if !ok || strings.TrimSpace(raw) == "" {
		return false, false, nil
	}
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "1", "true", "yes", "on":
		return true, true, nil
	case "0", "false", "no", "off":
		return false, true, nil
	default:
		return false, true, fmt.Errorf("%s must be true or false", key)
	}
}

func normalize(cfg *Config) {
	cfg.HTTPAddr = strings.TrimSpace(cfg.HTTPAddr)
	cfg.ProtocolVersion = strings.TrimSpace(cfg.ProtocolVersion)
	cfg.LogLevel = strings.TrimSpace(cfg.LogLevel)
	cfg.PublicWSURL = strings.TrimSpace(cfg.PublicWSURL)
	cfg.TurnBindAddr = strings.TrimSpace(cfg.TurnBindAddr)
	cfg.TurnPublicIP = strings.TrimSpace(cfg.TurnPublicIP)
	cfg.TurnPublicHost = strings.TrimSpace(cfg.TurnPublicHost)
	// 作者: long；常规部署只配置一个公网 IPv4 即可，只有 TURN 域名与 relay IP 不同时才需要单独填写 public host。
	if cfg.TurnPublicHost == "" && cfg.TurnPublicIP != "" {
		cfg.TurnPublicHost = cfg.TurnPublicIP
	}
	cfg.TurnRealm = strings.TrimSpace(cfg.TurnRealm)
	cfg.TurnUsername = strings.TrimSpace(cfg.TurnUsername)
	cfg.TurnPassword = strings.TrimSpace(cfg.TurnPassword)
	cfg.ICEMode = strings.ToLower(strings.TrimSpace(cfg.ICEMode))
	cfg.ICETurnTransport = strings.ToLower(strings.TrimSpace(cfg.ICETurnTransport))
	cfg.AllowedOrigins = compactStrings(cfg.AllowedOrigins)
	cfg.StunURLs = compactStrings(cfg.StunURLs)
}

func Validate(cfg Config) error {
	if err := validateListenAddr("http_addr", cfg.HTTPAddr); err != nil {
		return err
	}
	if err := validateListenAddr("turn_bind_addr", cfg.TurnBindAddr); err != nil {
		return err
	}
	parsedWSURL, err := url.Parse(cfg.PublicWSURL)
	if err != nil || parsedWSURL.Hostname() == "" || (parsedWSURL.Scheme != "ws" && parsedWSURL.Scheme != "wss") {
		return fmt.Errorf("public_ws_url must be a valid ws:// or wss:// URL")
	}
	if cfg.TurnPort < 1 || cfg.TurnPort > 65535 {
		return fmt.Errorf("turn_port must be between 1 and 65535")
	}
	if cfg.TurnPublicIP != "" {
		parsedIP := net.ParseIP(cfg.TurnPublicIP)
		if parsedIP == nil || parsedIP.To4() == nil {
			return fmt.Errorf("turn_public_ip must be an IPv4 address")
		}
	}
	if cfg.TurnPublicHost != "" {
		parsedHost, err := url.Parse("//" + cfg.TurnPublicHost)
		normalizedHost := strings.Trim(cfg.TurnPublicHost, "[]")
		if err != nil || parsedHost.Hostname() == "" || parsedHost.Port() != "" || parsedHost.Hostname() != normalizedHost {
			return fmt.Errorf("turn_public_host must be a hostname or IP address without scheme or port")
		}
	}
	if cfg.ProtocolVersion == "" || cfg.TurnRealm == "" || cfg.TurnUsername == "" || cfg.TurnPassword == "" {
		return fmt.Errorf("protocol_version, turn_realm, turn_username, and turn_password must not be empty")
	}
	if cfg.ICEMode != "default" && cfg.ICEMode != "relay_only" && cfg.ICEMode != "relay_udp" && cfg.ICEMode != "relay_tcp" {
		return fmt.Errorf("ice_mode must be default, relay_only, relay_udp, or relay_tcp")
	}
	if cfg.ICETurnTransport != "all" && cfg.ICETurnTransport != "udp" && cfg.ICETurnTransport != "tcp" {
		return fmt.Errorf("ice_turn_transport must be all, udp, or tcp")
	}
	if cfg.ICERelayUDPHighRTTMS < 0 {
		return fmt.Errorf("ice_relay_udp_high_rtt_ms must be greater than or equal to 0")
	}
	if cfg.ICEDegradeStreakSamples < 1 {
		return fmt.Errorf("ice_degrade_streak_samples must be greater than or equal to 1")
	}
	for _, stunURL := range cfg.StunURLs {
		lower := strings.ToLower(stunURL)
		if !strings.HasPrefix(lower, "stun:") && !strings.HasPrefix(lower, "stuns:") {
			return fmt.Errorf("stun_urls entry %q must use stun: or stuns:", stunURL)
		}
	}
	return nil
}

func validateListenAddr(field string, value string) error {
	_, rawPort, err := net.SplitHostPort(value)
	if err != nil {
		return fmt.Errorf("%s must use host:port format: %w", field, err)
	}
	port, err := strconv.Atoi(rawPort)
	if err != nil || port < 1 || port > 65535 {
		return fmt.Errorf("%s port must be between 1 and 65535", field)
	}
	return nil
}

func compactStrings(values []string) []string {
	result := make([]string, 0, len(values))
	seen := make(map[string]struct{}, len(values))
	for _, value := range values {
		trimmed := strings.TrimSpace(value)
		if trimmed == "" {
			continue
		}
		if _, exists := seen[trimmed]; exists {
			continue
		}
		seen[trimmed] = struct{}{}
		result = append(result, trimmed)
	}
	return result
}

func splitCSV(value string) []string {
	return compactStrings(strings.Split(value, ","))
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
