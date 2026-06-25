package store

import (
	"fmt"
	"sync"
	"time"
)

type Session struct {
	SessionID          string    `json:"session_id"`
	ControllerDeviceID string    `json:"controller_device_id"`
	AgentDeviceID      string    `json:"agent_device_id"`
	ControllerProfile  string    `json:"controller_profile,omitempty"`
	Status             string    `json:"status"`
	CreatedAt          time.Time `json:"created_at"`
}

type Store struct {
	mu       sync.RWMutex
	sessions map[string]Session
	nextID   uint64
}

func New() *Store {
	return &Store{sessions: make(map[string]Session)}
}

func (s *Store) Create(controllerDeviceID string, agentDeviceID string, controllerProfile string) Session {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now().UTC()
	s.nextID++
	session := Session{
		SessionID:          fmt.Sprintf("sess-%d-%d", now.UnixMilli(), s.nextID),
		ControllerDeviceID: controllerDeviceID,
		AgentDeviceID:      agentDeviceID,
		ControllerProfile:  controllerProfile,
		Status:             "active",
		CreatedAt:          now,
	}
	s.sessions[session.SessionID] = session
	return session
}

func (s *Store) Get(sessionID string) (Session, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	current, ok := s.sessions[sessionID]
	return current, ok
}

func (s *Store) Delete(sessionID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.sessions, sessionID)
}

func (s *Store) HasParticipant(sessionID string, deviceID string) bool {
	current, ok := s.Get(sessionID)
	if !ok {
		return false
	}
	return current.ControllerDeviceID == deviceID || current.AgentDeviceID == deviceID
}

func (s *Store) IsController(sessionID string, deviceID string) bool {
	current, ok := s.Get(sessionID)
	if !ok {
		return false
	}
	return current.ControllerDeviceID == deviceID
}

func (s *Store) IsAgent(sessionID string, deviceID string) bool {
	current, ok := s.Get(sessionID)
	if !ok {
		return false
	}
	return current.AgentDeviceID == deviceID
}

func (s *Store) PeerDeviceID(sessionID string, deviceID string) (string, bool) {
	current, ok := s.Get(sessionID)
	if !ok {
		return "", false
	}
	if current.ControllerDeviceID == deviceID {
		return current.AgentDeviceID, true
	}
	if current.AgentDeviceID == deviceID {
		return current.ControllerDeviceID, true
	}
	return "", false
}
