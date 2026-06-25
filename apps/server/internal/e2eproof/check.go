package e2eproof

import (
	"encoding/json"
	"fmt"
	"io"
	"net/url"
	"sort"
	"strings"
)

var (
	requiredRoutes          = []string{"android_to_windows", "windows_to_windows", "windows_to_macos"}
	requiredInputCategories = []string{"click", "drag", "keyboard", "wheel"}
)

type Snapshot struct {
	Event                string       `json:"event"`
	Complete             bool         `json:"complete"`
	TargetRoutesComplete int          `json:"target_routes_complete"`
	TargetRoutesTotal    int          `json:"target_routes_total"`
	Routes               []RouteState `json:"routes"`
}

type RouteState struct {
	RouteKey    string       `json:"route_key"`
	Route       string       `json:"route"`
	Status      string       `json:"status"`
	Missing     []string     `json:"missing,omitempty"`
	NextAction  string       `json:"next_action,omitempty"`
	Complete    bool         `json:"complete"`
	Latest      *ProofRecord `json:"latest,omitempty"`
	LastSuccess *ProofRecord `json:"last_success,omitempty"`
}

type ProofRecord struct {
	RouteKey            string   `json:"route_key"`
	Route               string   `json:"route"`
	TargetRoute         bool     `json:"target_route"`
	ProofStatus         string   `json:"proof_status"`
	VideoObserved       bool     `json:"video_observed"`
	InputObserved       bool     `json:"input_observed"`
	SessionID           string   `json:"session_id"`
	ControllerDeviceID  string   `json:"controller_device_id"`
	AgentDeviceID       string   `json:"agent_device_id"`
	RemoteInputCoverage []string `json:"remote_input_coverage,omitempty"`
}

type Result struct {
	Complete bool
	Lines    []string
}

func DecodeSnapshot(r io.Reader) (Snapshot, error) {
	var snapshot Snapshot
	decoder := json.NewDecoder(r)
	if err := decoder.Decode(&snapshot); err != nil {
		return Snapshot{}, err
	}
	return snapshot, nil
}

func CheckSnapshot(snapshot Snapshot) Result {
	lines := []string{}
	ok := true

	if snapshot.Event != "" && snapshot.Event != "session.e2e_proof.snapshot" {
		ok = false
		lines = append(lines, fmt.Sprintf("unexpected event %q", snapshot.Event))
	}
	if !snapshot.Complete {
		ok = false
		lines = append(lines, fmt.Sprintf("snapshot complete=false (%d/%d target routes)", snapshot.TargetRoutesComplete, snapshot.TargetRoutesTotal))
	}
	if snapshot.TargetRoutesTotal != len(requiredRoutes) {
		ok = false
		lines = append(lines, fmt.Sprintf("target_routes_total=%d, want %d", snapshot.TargetRoutesTotal, len(requiredRoutes)))
	}

	routeByKey := map[string]RouteState{}
	for _, route := range snapshot.Routes {
		routeByKey[route.RouteKey] = route
	}
	for _, key := range requiredRoutes {
		route, found := routeByKey[key]
		if !found {
			ok = false
			lines = append(lines, fmt.Sprintf("%s: missing from snapshot", key))
			continue
		}
		routeOK, routeLines := checkRoute(key, route)
		if !routeOK {
			ok = false
		}
		lines = append(lines, routeLines...)
	}
	if ok {
		lines = append(lines, fmt.Sprintf("E2E proof complete: %d/%d routes", snapshot.TargetRoutesComplete, snapshot.TargetRoutesTotal))
	}
	return Result{Complete: ok, Lines: lines}
}

func CheckResetSnapshot(snapshot Snapshot) Result {
	lines := []string{}
	ok := true

	if snapshot.Event != "" && snapshot.Event != "session.e2e_proof.snapshot" {
		ok = false
		lines = append(lines, fmt.Sprintf("unexpected event %q", snapshot.Event))
	}
	if snapshot.Complete {
		ok = false
		lines = append(lines, "reset snapshot complete=true")
	}
	if snapshot.TargetRoutesComplete != 0 {
		ok = false
		lines = append(lines, fmt.Sprintf("reset snapshot target_routes_complete=%d, want 0", snapshot.TargetRoutesComplete))
	}
	if snapshot.TargetRoutesTotal != len(requiredRoutes) {
		ok = false
		lines = append(lines, fmt.Sprintf("target_routes_total=%d, want %d", snapshot.TargetRoutesTotal, len(requiredRoutes)))
	}

	routeByKey := map[string]RouteState{}
	for _, route := range snapshot.Routes {
		routeByKey[route.RouteKey] = route
	}
	for _, key := range requiredRoutes {
		route, found := routeByKey[key]
		if !found {
			ok = false
			lines = append(lines, fmt.Sprintf("%s: missing from reset snapshot", key))
			continue
		}
		if route.Complete {
			ok = false
			lines = append(lines, fmt.Sprintf("%s: reset route complete=true", key))
		}
		if route.Latest != nil {
			ok = false
			lines = append(lines, fmt.Sprintf("%s: reset route still has latest proof", key))
		}
		if route.LastSuccess != nil {
			ok = false
			lines = append(lines, fmt.Sprintf("%s: reset route still has last_success proof", key))
		}
	}
	if ok {
		lines = append(lines, "E2E proof reset confirmed")
	}
	return Result{Complete: ok, Lines: lines}
}

func Summary(snapshot Snapshot) string {
	parts := []string{fmt.Sprintf("%d/%d routes", snapshot.TargetRoutesComplete, snapshot.TargetRoutesTotal)}
	routeByKey := map[string]RouteState{}
	for _, route := range snapshot.Routes {
		routeByKey[route.RouteKey] = route
	}
	for _, key := range requiredRoutes {
		route, ok := routeByKey[key]
		if !ok {
			parts = append(parts, key+"=missing")
			continue
		}
		if route.Complete {
			parts = append(parts, key+"=complete")
			continue
		}
		missing := strings.Join(route.Missing, ",")
		if missing == "" {
			missing = route.Status
		}
		if missing == "" {
			missing = "incomplete"
		}
		parts = append(parts, key+"="+missing)
	}
	return strings.Join(parts, "; ")
}

func NormalizeProofURL(raw string) (string, error) {
	value := strings.TrimSpace(raw)
	if value == "" {
		return "", fmt.Errorf("proof URL is required")
	}
	if !strings.Contains(value, "://") {
		value = "http://" + value
	}
	parsed, err := url.Parse(value)
	if err != nil {
		return "", err
	}
	switch strings.ToLower(parsed.Scheme) {
	case "ws":
		parsed.Scheme = "http"
	case "wss":
		parsed.Scheme = "https"
	case "http", "https":
	default:
		return "", fmt.Errorf("unsupported URL scheme %q", parsed.Scheme)
	}
	if parsed.Host == "" {
		return "", fmt.Errorf("proof URL is missing a host")
	}
	path := parsed.EscapedPath()
	switch {
	case path == "" || path == "/":
		parsed.Path = "/e2e-proof"
	case path == "/ws":
		parsed.Path = "/e2e-proof"
	case strings.HasSuffix(path, "/ws"):
		parsed.Path = strings.TrimSuffix(path, "/ws") + "/e2e-proof"
	case strings.HasSuffix(path, "/e2e-proof"):
	default:
		parsed.Path = strings.TrimRight(parsed.Path, "/") + "/e2e-proof"
	}
	parsed.RawQuery = ""
	parsed.Fragment = ""
	return parsed.String(), nil
}

func RequiredRoutes() []string {
	return append([]string(nil), requiredRoutes...)
}

func RequiredInputCategories() []string {
	return append([]string(nil), requiredInputCategories...)
}

func checkRoute(key string, route RouteState) (bool, []string) {
	lines := []string{}
	ok := true
	if !route.Complete {
		ok = false
		lines = append(lines, fmt.Sprintf("%s: route complete=false status=%q missing=%s next_action=%q", key, route.Status, joinOrDash(route.Missing), route.NextAction))
	}
	if route.LastSuccess == nil {
		ok = false
		lines = append(lines, fmt.Sprintf("%s: missing last_success proof", key))
		return ok, lines
	}
	proof := route.LastSuccess
	if proof.RouteKey != "" && proof.RouteKey != key {
		ok = false
		lines = append(lines, fmt.Sprintf("%s: last_success route_key=%q", key, proof.RouteKey))
	}
	if !proof.TargetRoute {
		ok = false
		lines = append(lines, fmt.Sprintf("%s: last_success target_route=false", key))
	}
	if proof.ProofStatus != "video_and_input_observed" {
		ok = false
		lines = append(lines, fmt.Sprintf("%s: proof_status=%q", key, proof.ProofStatus))
	}
	if !proof.VideoObserved {
		ok = false
		lines = append(lines, fmt.Sprintf("%s: video_observed=false", key))
	}
	if !proof.InputObserved {
		ok = false
		lines = append(lines, fmt.Sprintf("%s: input_observed=false", key))
	}
	missingCoverage := missingInputCoverage(proof.RemoteInputCoverage)
	if len(missingCoverage) > 0 {
		ok = false
		lines = append(lines, fmt.Sprintf("%s: missing remote_input_coverage=%s", key, strings.Join(missingCoverage, ",")))
	}
	if ok {
		lines = append(lines, fmt.Sprintf("%s: complete session=%s controller=%s agent=%s coverage=%s", key, dash(proof.SessionID), dash(proof.ControllerDeviceID), dash(proof.AgentDeviceID), joinOrDash(proof.RemoteInputCoverage)))
	}
	return ok, lines
}

func missingInputCoverage(coverage []string) []string {
	present := map[string]bool{}
	for _, raw := range coverage {
		category := strings.ToLower(strings.TrimSpace(raw))
		if category != "" {
			present[category] = true
		}
	}
	missing := []string{}
	for _, category := range requiredInputCategories {
		if !present[category] {
			missing = append(missing, category)
		}
	}
	return missing
}

func joinOrDash(values []string) string {
	cleaned := []string{}
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value != "" {
			cleaned = append(cleaned, value)
		}
	}
	if len(cleaned) == 0 {
		return "-"
	}
	sort.Strings(cleaned)
	return strings.Join(cleaned, ",")
}

func dash(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return "-"
	}
	return value
}
