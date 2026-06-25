package auth

import "remote_desk/apps/server/internal/presence"

func ValidateToken(registry *presence.Registry, deviceID string, token string) bool {
	if token == "" || deviceID == "" {
		return false
	}
	return registry.HasValidToken(deviceID, token)
}
