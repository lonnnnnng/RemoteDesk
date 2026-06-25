package main

import (
	"context"
	"flag"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"remote_desk/apps/server/internal/e2eproof"
)

func main() {
	var rawURL string
	var wait time.Duration
	var interval time.Duration
	var reset bool
	var resetOnly bool
	flag.StringVar(&rawURL, "url", envOrDefault("RD_E2E_PROOF_URL", "http://localhost:18081/e2e-proof"), "relay proof URL, relay ws URL, or relay host")
	flag.DurationVar(&wait, "wait", 0, "poll until proof is complete or this timeout elapses")
	flag.DurationVar(&interval, "interval", 2*time.Second, "poll interval when -wait is set")
	flag.BoolVar(&reset, "reset", false, "DELETE /e2e-proof before checking")
	flag.BoolVar(&resetOnly, "reset-only", false, "DELETE /e2e-proof and print the reset snapshot without checking")
	flag.Parse()

	proofURL, err := e2eproof.NormalizeProofURL(rawURL)
	if err != nil {
		fmt.Fprintf(os.Stderr, "invalid proof URL: %v\n", err)
		os.Exit(2)
	}
	if interval <= 0 {
		fmt.Fprintln(os.Stderr, "interval must be greater than zero")
		os.Exit(2)
	}

	ctx := context.Background()
	if wait > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, wait)
		defer cancel()
	}

	if reset || resetOnly {
		snapshot, err := resetProof(ctx, proofURL)
		if err != nil {
			fmt.Fprintf(os.Stderr, "E2E proof reset failed: %v\n", err)
			os.Exit(1)
		}
		fmt.Printf("reset E2E proof: %s\n", e2eproof.Summary(snapshot))
		resetResult := e2eproof.CheckResetSnapshot(snapshot)
		for _, line := range resetResult.Lines {
			fmt.Println(line)
		}
		if !resetResult.Complete {
			os.Exit(1)
		}
		if resetOnly {
			return
		}
	}

	result, err := waitForProof(ctx, proofURL, interval)
	if err != nil {
		fmt.Fprintf(os.Stderr, "E2E proof check failed: %v\n", err)
		os.Exit(1)
	}
	for _, line := range result.Lines {
		fmt.Println(line)
	}
	if !result.Complete {
		os.Exit(1)
	}
}

func resetProof(ctx context.Context, proofURL string) (e2eproof.Snapshot, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodDelete, proofURL, nil)
	if err != nil {
		return e2eproof.Snapshot{}, err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return e2eproof.Snapshot{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return e2eproof.Snapshot{}, fmt.Errorf("DELETE %s returned HTTP %d", proofURL, resp.StatusCode)
	}
	return e2eproof.DecodeSnapshot(resp.Body)
}

func waitForProof(ctx context.Context, proofURL string, interval time.Duration) (e2eproof.Result, error) {
	var lastResult e2eproof.Result
	for {
		snapshot, err := fetchSnapshot(ctx, proofURL)
		if err != nil {
			return e2eproof.Result{}, err
		}
		lastResult = e2eproof.CheckSnapshot(snapshot)
		if lastResult.Complete {
			return lastResult, nil
		}
		if _, hasDeadline := ctx.Deadline(); !hasDeadline {
			return lastResult, nil
		}
		fmt.Printf("waiting for E2E proof: %s\n", e2eproof.Summary(snapshot))
		timer := time.NewTimer(interval)
		select {
		case <-ctx.Done():
			timer.Stop()
			if len(lastResult.Lines) == 0 {
				lastResult.Lines = append(lastResult.Lines, "E2E proof incomplete")
			}
			lastResult.Lines = append([]string{"timed out waiting for E2E proof"}, lastResult.Lines...)
			return lastResult, nil
		case <-timer.C:
		}
	}
}

func fetchSnapshot(ctx context.Context, proofURL string) (e2eproof.Snapshot, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, proofURL, nil)
	if err != nil {
		return e2eproof.Snapshot{}, err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return e2eproof.Snapshot{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return e2eproof.Snapshot{}, fmt.Errorf("GET %s returned HTTP %d", proofURL, resp.StatusCode)
	}
	return e2eproof.DecodeSnapshot(resp.Body)
}

func envOrDefault(key string, fallback string) string {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	return value
}
