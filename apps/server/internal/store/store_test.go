package store

import "testing"

func TestCreateGeneratesUniqueSessionIDs(t *testing.T) {
	s := New()
	seen := make(map[string]struct{})
	for i := 0; i < 100; i++ {
		current := s.Create("controller-01", "agent-01", "standard")
		if _, exists := seen[current.SessionID]; exists {
			t.Fatalf("duplicate session id generated: %s", current.SessionID)
		}
		seen[current.SessionID] = struct{}{}
	}
}
