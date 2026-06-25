package e2eproof

import (
	"bytes"
	"strings"
	"testing"
)

func TestCheckSnapshotComplete(t *testing.T) {
	snapshot := Snapshot{
		Event:                "session.e2e_proof.snapshot",
		Complete:             true,
		TargetRoutesComplete: 3,
		TargetRoutesTotal:    3,
	}
	for _, key := range RequiredRoutes() {
		snapshot.Routes = append(snapshot.Routes, RouteState{
			RouteKey: key,
			Complete: true,
			Status:   "complete",
			LastSuccess: &ProofRecord{
				RouteKey:            key,
				TargetRoute:         true,
				ProofStatus:         "video_and_input_observed",
				VideoObserved:       true,
				InputObserved:       true,
				SessionID:           "sess-" + key,
				ControllerDeviceID:  "controller-" + key,
				AgentDeviceID:       "agent-" + key,
				RemoteInputCoverage: RequiredInputCategories(),
			},
		})
	}

	result := CheckSnapshot(snapshot)
	if !result.Complete {
		t.Fatalf("expected complete result, got %#v", result.Lines)
	}
	if !strings.Contains(strings.Join(result.Lines, "\n"), "E2E proof complete") {
		t.Fatalf("expected success summary, got %#v", result.Lines)
	}
}

func TestCheckSnapshotRequiresEveryRouteAndInputCategory(t *testing.T) {
	snapshot := Snapshot{
		Event:                "session.e2e_proof.snapshot",
		Complete:             false,
		TargetRoutesComplete: 1,
		TargetRoutesTotal:    3,
		Routes: []RouteState{
			{
				RouteKey: "android_to_windows",
				Complete: true,
				Status:   "complete",
				LastSuccess: &ProofRecord{
					RouteKey:            "android_to_windows",
					TargetRoute:         true,
					ProofStatus:         "video_and_input_observed",
					VideoObserved:       true,
					InputObserved:       true,
					RemoteInputCoverage: []string{"click", "keyboard"},
				},
			},
			{
				RouteKey:   "windows_to_windows",
				Complete:   false,
				Status:     "video_only",
				Missing:    []string{"input"},
				NextAction: "send input",
			},
		},
	}

	result := CheckSnapshot(snapshot)
	if result.Complete {
		t.Fatalf("expected incomplete result")
	}
	lines := strings.Join(result.Lines, "\n")
	for _, expected := range []string{
		"snapshot complete=false",
		"android_to_windows: missing remote_input_coverage=drag,wheel",
		"windows_to_windows: route complete=false",
		"windows_to_windows: missing last_success proof",
		"windows_to_macos: missing from snapshot",
	} {
		if !strings.Contains(lines, expected) {
			t.Fatalf("expected %q in lines:\n%s", expected, lines)
		}
	}
}

func TestCheckResetSnapshotRequiresEmptyProofState(t *testing.T) {
	clean := Snapshot{
		Event:                "session.e2e_proof.snapshot",
		Complete:             false,
		TargetRoutesComplete: 0,
		TargetRoutesTotal:    3,
	}
	for _, key := range RequiredRoutes() {
		clean.Routes = append(clean.Routes, RouteState{
			RouteKey: key,
			Status:   "not_observed",
			Missing:  []string{"video", "input"},
		})
	}
	if result := CheckResetSnapshot(clean); !result.Complete {
		t.Fatalf("expected clean reset snapshot, got %#v", result.Lines)
	}

	dirty := clean
	dirty.TargetRoutesComplete = 1
	dirty.Routes = append([]RouteState(nil), clean.Routes...)
	dirty.Routes[0].LastSuccess = &ProofRecord{
		RouteKey:            dirty.Routes[0].RouteKey,
		TargetRoute:         true,
		ProofStatus:         "video_and_input_observed",
		VideoObserved:       true,
		InputObserved:       true,
		RemoteInputCoverage: RequiredInputCategories(),
	}
	result := CheckResetSnapshot(dirty)
	if result.Complete {
		t.Fatalf("expected dirty reset snapshot to fail")
	}
	lines := strings.Join(result.Lines, "\n")
	for _, expected := range []string{
		"reset snapshot target_routes_complete=1",
		"reset route still has last_success proof",
	} {
		if !strings.Contains(lines, expected) {
			t.Fatalf("expected %q in lines:\n%s", expected, lines)
		}
	}
}

func TestDecodeSnapshotIgnoresRelayProofExtraFields(t *testing.T) {
	body := []byte(`{
		"event": "session.e2e_proof.snapshot",
		"complete": true,
		"target_routes_complete": 3,
		"target_routes_total": 3,
		"routes": [
			{
				"route_key": "android_to_windows",
				"route": "android -> windows",
				"status": "complete",
				"complete": true,
				"last_success": {
					"route_key": "android_to_windows",
					"route": "android -> windows",
					"target_route": true,
					"proof_status": "video_and_input_observed",
					"video_observed": true,
					"input_observed": true,
					"session_id": "sess-android-win",
					"controller_device_id": "android-1",
					"agent_device_id": "windows-1",
					"controller_platform": "android",
					"agent_platform": "windows",
					"first_frame_ms": 125,
					"rendered_frames": 42,
					"remote_input_applied": 4,
					"remote_input_total": 4,
					"remote_input_executor": "windows.send_input",
					"remote_input_status": "applied",
					"remote_input_trace_id": "trace-1",
					"remote_input_coverage": ["click", "drag", "keyboard", "wheel"],
					"session_quality_hint": "stable",
					"session_e2e_proof_summary": "ok",
					"session_perf_summary": "ok",
					"updated_at": "2026-05-29T00:00:00Z"
				}
			},
			{
				"route_key": "windows_to_windows",
				"status": "complete",
				"complete": true,
				"last_success": {
					"route_key": "windows_to_windows",
					"target_route": true,
					"proof_status": "video_and_input_observed",
					"video_observed": true,
					"input_observed": true,
					"remote_input_coverage": ["click", "drag", "keyboard", "wheel"]
				}
			},
			{
				"route_key": "windows_to_macos",
				"status": "complete",
				"complete": true,
				"last_success": {
					"route_key": "windows_to_macos",
					"target_route": true,
					"proof_status": "video_and_input_observed",
					"video_observed": true,
					"input_observed": true,
					"remote_input_coverage": ["click", "drag", "keyboard", "wheel"]
				}
			}
		],
		"future_field": "allowed"
	}`)
	snapshot, err := DecodeSnapshot(bytes.NewReader(body))
	if err != nil {
		t.Fatalf("DecodeSnapshot failed: %v", err)
	}
	result := CheckSnapshot(snapshot)
	if !result.Complete {
		t.Fatalf("expected complete decoded snapshot, got %#v", result.Lines)
	}
}

func TestNormalizeProofURL(t *testing.T) {
	cases := map[string]string{
		"localhost:18081":                  "http://localhost:18081/e2e-proof",
		"ws://relay.local:18081/ws":        "http://relay.local:18081/e2e-proof",
		"wss://relay.example/rd/ws?x=1":    "https://relay.example/rd/e2e-proof",
		"https://relay.example/e2e-proof":  "https://relay.example/e2e-proof",
		"http://relay.example/custom/path": "http://relay.example/custom/path/e2e-proof",
	}
	for input, want := range cases {
		got, err := NormalizeProofURL(input)
		if err != nil {
			t.Fatalf("NormalizeProofURL(%q) error: %v", input, err)
		}
		if got != want {
			t.Fatalf("NormalizeProofURL(%q)=%q, want %q", input, got, want)
		}
	}
}
