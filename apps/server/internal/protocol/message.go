package protocol

import "encoding/json"

type Envelope struct {
	Version   string         `json:"v"`
	MessageID string         `json:"msg_id"`
	Type      string         `json:"type"`
	Timestamp int64          `json:"ts"`
	SessionID string         `json:"session_id,omitempty"`
	TraceID   string         `json:"trace_id,omitempty"`
	From      From           `json:"from"`
	Payload   map[string]any `json:"payload"`
}

type From struct {
	DeviceID string `json:"device_id"`
	Role     string `json:"role"`
}

func Decode(data []byte) (Envelope, error) {
	var msg Envelope
	err := json.Unmarshal(data, &msg)
	return msg, err
}

func Encode(msg Envelope) ([]byte, error) {
	return json.Marshal(msg)
}
