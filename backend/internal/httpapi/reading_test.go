package httpapi

import (
	"bytes"
	"clipmind/backend/internal/books"
	"clipmind/backend/internal/llm"
	"clipmind/backend/internal/metrics"
	"clipmind/backend/internal/reading"
	"io"
	"log"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestReadingRoutesRequireAuthAndRejectUnsafeInput(t *testing.T) {
	var logs bytes.Buffer
	s := Server{Metrics: metrics.New(), Token: "test-token", Log: log.New(&logs, "", 0), Reading: &reading.Service{Analyzer: llm.Deterministic{}, Books: books.NewOpenLibrary(), Provider: "deterministic", Model: "deterministic"}}
	for _, path := range []string{"/v1/reading:analyze", "/v1/reading:embed", "/v1/reading:weekly", "/v1/reading:recommend"} {
		request := httptest.NewRequest("POST", path, strings.NewReader(`{}`))
		w := httptest.NewRecorder()
		s.Handler().ServeHTTP(w, request)
		if w.Code != 401 {
			t.Fatalf("unauthenticated %s accepted", path)
		}
	}
	for _, tc := range []struct {
		body   string
		status int
	}{{`{"text":"An ordinary source excerpt","known_tags":[],"read_books":[],"search_articles":false}`, 200}, {`{"text":"api_key=private-sample"}`, 400}, {`{"text":"ordinary text","extra":true}`, 400}, {`{"text":"ordinary text"} {}`, 400}} {
		request := httptest.NewRequest("POST", "/v1/reading:analyze", strings.NewReader(tc.body))
		request.Header.Set("Authorization", "Bearer test-token")
		w := httptest.NewRecorder()
		s.Handler().ServeHTTP(w, request)
		if w.Code != tc.status {
			b, _ := io.ReadAll(w.Result().Body)
			t.Fatalf("status %d expected %d: %s", w.Code, tc.status, b)
		}
	}
	if strings.Contains(logs.String(), "private-sample") || strings.Contains(logs.String(), "ordinary source") {
		t.Fatal("sensitive request logged")
	}
}
