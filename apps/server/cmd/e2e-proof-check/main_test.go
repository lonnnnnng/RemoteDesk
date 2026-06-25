package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"remote_desk/apps/server/internal/e2eproof"
)

func TestWaitForProofReturnsIncompleteSnapshot(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(e2eproof.Snapshot{
			Event:                "session.e2e_proof.snapshot",
			Complete:             false,
			TargetRoutesComplete: 0,
			TargetRoutesTotal:    3,
		})
	}))
	defer server.Close()

	result, err := waitForProof(context.Background(), server.URL, time.Millisecond)
	if err != nil {
		t.Fatalf("waitForProof returned error: %v", err)
	}
	if result.Complete {
		t.Fatalf("expected incomplete result")
	}
	lines := strings.Join(result.Lines, "\n")
	if !strings.Contains(lines, "snapshot complete=false") {
		t.Fatalf("expected incomplete diagnostics, got:\n%s", lines)
	}
}

func TestWaitForProofPollsUntilComplete(t *testing.T) {
	requests := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests += 1
		snapshot := completeSnapshot()
		if requests == 1 {
			snapshot.Complete = false
			snapshot.TargetRoutesComplete = 2
			snapshot.Routes[2].Complete = false
			snapshot.Routes[2].Status = "video_only"
			snapshot.Routes[2].Missing = []string{"input"}
			snapshot.Routes[2].LastSuccess = nil
		}
		_ = json.NewEncoder(w).Encode(snapshot)
	}))
	defer server.Close()

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	result, err := waitForProof(ctx, server.URL, time.Millisecond)
	if err != nil {
		t.Fatalf("waitForProof returned error: %v", err)
	}
	if !result.Complete {
		t.Fatalf("expected complete result, got %#v", result.Lines)
	}
	if requests < 2 {
		t.Fatalf("expected polling, got %d requests", requests)
	}
}

func TestResetProofUsesDelete(t *testing.T) {
	var method string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		method = r.Method
		_ = json.NewEncoder(w).Encode(incompleteSnapshot())
	}))
	defer server.Close()

	snapshot, err := resetProof(context.Background(), server.URL)
	if err != nil {
		t.Fatalf("resetProof returned error: %v", err)
	}
	if method != http.MethodDelete {
		t.Fatalf("expected DELETE, got %q", method)
	}
	if snapshot.Complete {
		t.Fatalf("expected reset snapshot to be incomplete")
	}
	if result := e2eproof.CheckResetSnapshot(snapshot); !result.Complete {
		t.Fatalf("expected clean reset snapshot, got %#v", result.Lines)
	}
}

func TestResetProofRejectsStaleSuccessState(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(completeSnapshot())
	}))
	defer server.Close()

	snapshot, err := resetProof(context.Background(), server.URL)
	if err != nil {
		t.Fatalf("resetProof returned error: %v", err)
	}
	result := e2eproof.CheckResetSnapshot(snapshot)
	if result.Complete {
		t.Fatalf("expected stale reset snapshot to fail")
	}
	lines := strings.Join(result.Lines, "\n")
	if !strings.Contains(lines, "reset route complete=true") || !strings.Contains(lines, "last_success") {
		t.Fatalf("expected stale proof diagnostics, got:\n%s", lines)
	}
}

func completeSnapshot() e2eproof.Snapshot {
	snapshot := e2eproof.Snapshot{
		Event:                "session.e2e_proof.snapshot",
		Complete:             true,
		TargetRoutesComplete: 3,
		TargetRoutesTotal:    3,
	}
	for _, key := range e2eproof.RequiredRoutes() {
		snapshot.Routes = append(snapshot.Routes, e2eproof.RouteState{
			RouteKey: key,
			Status:   "complete",
			Complete: true,
			LastSuccess: &e2eproof.ProofRecord{
				RouteKey:            key,
				TargetRoute:         true,
				ProofStatus:         "video_and_input_observed",
				VideoObserved:       true,
				InputObserved:       true,
				RemoteInputCoverage: e2eproof.RequiredInputCategories(),
			},
		})
	}
	return snapshot
}

func incompleteSnapshot() e2eproof.Snapshot {
	snapshot := e2eproof.Snapshot{
		Event:                "session.e2e_proof.snapshot",
		Complete:             false,
		TargetRoutesComplete: 0,
		TargetRoutesTotal:    3,
	}
	for _, key := range e2eproof.RequiredRoutes() {
		snapshot.Routes = append(snapshot.Routes, e2eproof.RouteState{
			RouteKey: key,
			Status:   "not_observed",
			Missing:  []string{"video", "input"},
			Complete: false,
		})
	}
	return snapshot
}
