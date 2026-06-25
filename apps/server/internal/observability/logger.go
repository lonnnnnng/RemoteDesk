package observability

import (
	"encoding/json"
	"log"
	"os"
	"time"
)

type Logger struct {
	base *log.Logger
}

func New() *Logger {
	return &Logger{base: log.New(os.Stdout, "", 0)}
}

func (l *Logger) Info(fields map[string]any) {
	l.write("info", fields)
}

func (l *Logger) Error(fields map[string]any) {
	l.write("error", fields)
}

func (l *Logger) write(level string, fields map[string]any) {
	if fields == nil {
		fields = map[string]any{}
	}
	fields["level"] = level
	fields["ts"] = time.Now().UTC().Format(time.RFC3339Nano)
	payload, err := json.Marshal(fields)
	if err != nil {
		l.base.Printf(`{"level":"error","event":"logger.marshal_failed","message":%q}`+"\n", err.Error())
		return
	}
	l.base.Println(string(payload))
}
