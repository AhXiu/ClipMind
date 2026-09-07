package httpapi

import (
	"bytes"
	"clipmind/backend/internal/knowledge"
	"clipmind/backend/internal/llm"
	"clipmind/backend/internal/metrics"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestKnowledgeEndpointAuthenticationSafetyAndNoRawLogs(t *testing.T) {
	var logs bytes.Buffer
	s := Server{Metrics: metrics.New(), Token: "test-token", Log: log.New(&logs, "", 0), Knowledge: &knowledge.Service{Provider: llm.Deterministic{}}}
	valid := `{"cards":[{"id":"1","revision":1,"text":"First private reading excerpt"},{"id":"2","revision":1,"text":"Second private reading excerpt"}]}`
	for _, tc := range []struct {
		name, body, auth string
		status           int
	}{
		{"anonymous", valid, "", http.StatusUnauthorized},
		{"valid", valid, "Bearer test-token", http.StatusOK},
		{"scope too small", `{"cards":[{"id":"1","revision":1,"text":"First private reading excerpt"}]}`, "Bearer test-token", http.StatusBadRequest},
		{"unexpected credential", strings.Replace(valid, `{"cards":`, `{"api_key":"never-log-this","cards":`, 1), "Bearer test-token", http.StatusBadRequest},
		{"sensitive content", strings.Replace(valid, "First private reading excerpt", "api_key=never-log-this", 1), "Bearer test-token", http.StatusBadRequest},
		{"trailing data", valid + " {}", "Bearer test-token", http.StatusBadRequest},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := httptest.NewRequest(http.MethodPost, "/v1/knowledge:synthesize", strings.NewReader(tc.body))
			r.Header.Set("Authorization", tc.auth)
			w := httptest.NewRecorder()
			s.Handler().ServeHTTP(w, r)
			if w.Code != tc.status {
				t.Fatalf("status=%d want=%d", w.Code, tc.status)
			}
			if tc.status != 200 && strings.Contains(w.Body.String(), "never-log-this") {
				t.Fatal("credential leaked in error")
			}
		})
	}
	if strings.Contains(logs.String(), "private reading") || strings.Contains(logs.String(), "never-log-this") {
		t.Fatal("raw text leaked to logs")
	}
}
