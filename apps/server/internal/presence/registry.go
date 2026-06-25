package presence

import (
	"sync"
	"time"
)

type Device struct {
	DeviceID     string             `json:"device_id"`
	DeviceName   string             `json:"device_name"`
	UserID       string             `json:"user_id"`
	Platform     string             `json:"platform"`
	Role         string             `json:"role"`
	Status       string             `json:"status"`
	Capabilities DeviceCapabilities `json:"capabilities"`
	LastSeenAt   time.Time          `json:"last_seen_at"`
	ClientToken  string             `json:"-"`
}

type DeviceCapabilities struct {
	CanControl      bool `json:"can_control"`
	CanBeControlled bool `json:"can_be_controlled"`
}

type PublicDevice struct {
	DeviceID     string             `json:"device_id"`
	DeviceName   string             `json:"device_name"`
	UserID       string             `json:"user_id"`
	Platform     string             `json:"platform"`
	Role         string             `json:"role"`
	Status       string             `json:"status"`
	Capabilities DeviceCapabilities `json:"capabilities"`
	LastSeenAt   time.Time          `json:"last_seen_at"`
}

type Registry struct {
	mu      sync.RWMutex
	devices map[string]Device
}

func NewRegistry() *Registry {
	return &Registry{devices: make(map[string]Device)}
}

func (r *Registry) Upsert(device Device) (Device, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	previous, existed := r.devices[device.DeviceID]
	device.LastSeenAt = time.Now().UTC()
	r.devices[device.DeviceID] = device
	if !existed {
		return device, true
	}
	changed := previous.DeviceName != device.DeviceName ||
		previous.UserID != device.UserID ||
		previous.Platform != device.Platform ||
		previous.Role != device.Role ||
		previous.Status != device.Status ||
		previous.Capabilities != device.Capabilities
	return device, changed
}

func (r *Registry) Heartbeat(deviceID string, status string) (Device, bool, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	device, ok := r.devices[deviceID]
	if !ok {
		return Device{}, false, false
	}
	statusChanged := device.Status != status
	device.Status = status
	device.LastSeenAt = time.Now().UTC()
	r.devices[deviceID] = device
	return device, true, statusChanged
}

func (r *Registry) MarkOffline(deviceID string) (Device, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	device, ok := r.devices[deviceID]
	if !ok {
		return Device{}, false
	}
	alreadyOffline := device.Status == "offline"
	device.Status = "offline"
	device.LastSeenAt = time.Now().UTC()
	r.devices[deviceID] = device
	return device, !alreadyOffline
}

func (r *Registry) HasValidToken(deviceID string, token string) bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	device, ok := r.devices[deviceID]
	if !ok {
		return false
	}
	return device.ClientToken == token
}

func (r *Registry) HasDevice(deviceID string) bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	_, ok := r.devices[deviceID]
	return ok
}

func (r *Registry) Get(deviceID string) (Device, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	device, ok := r.devices[deviceID]
	return device, ok
}

func (r *Registry) PublicList() []PublicDevice {
	r.mu.RLock()
	defer r.mu.RUnlock()
	result := make([]PublicDevice, 0, len(r.devices))
	for _, device := range r.devices {
		result = append(result, PublicDevice{
			DeviceID:     device.DeviceID,
			DeviceName:   device.DeviceName,
			UserID:       device.UserID,
			Platform:     device.Platform,
			Role:         device.Role,
			Status:       device.Status,
			Capabilities: device.Capabilities,
			LastSeenAt:   device.LastSeenAt,
		})
	}
	return result
}
