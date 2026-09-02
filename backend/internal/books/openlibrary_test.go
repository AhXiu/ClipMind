package books

import (
	"bytes"
	"clipmind/backend/internal/domain"
	"clipmind/backend/internal/metrics"
	"context"
	"errors"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestOpenLibraryOnlyMarksRealWorks(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"docs":[{"key":"/works/OL123W","title":"Verified Book","author_name":["Author"]}]}`))
	}))
	defer srv.Close()
	v := NewOpenLibrary()
	v.BaseURL = srv.URL
	got := v.Verify(context.Background(), []domain.BookCandidate{{Title: "Verified Book"}, {Title: "Invented Book"}})
	if !got[0].Verified || got[0].OpenLibraryKey != "/works/OL123W" {
		t.Fatalf("real work not verified: %+v", got[0])
	}
	if got[1].Verified {
		t.Fatal("unmatched candidate must remain unverified")
	}
}

func TestOpenLibraryObservabilityRedactsCandidate(t *testing.T) {
	secret := "sensitive candidate title"
	tests := []struct {
		name, body string
		status     int
		metric     string
	}{
		{"not_found", `{"docs":[]}`, http.StatusOK, "openlibrary_not_found_total"},
		{"parse_error", `not-json`, http.StatusOK, "openlibrary_dependency_error_total"},
		{"http_error", `failure`, http.StatusServiceUnavailable, "openlibrary_dependency_error_total"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(tc.status)
				_, _ = w.Write([]byte(tc.body))
			}))
			defer srv.Close()
			var logs bytes.Buffer
			m := metrics.New()
			v := NewOpenLibrary()
			v.BaseURL = srv.URL
			v.Metrics = m
			v.Logger = log.New(&logs, "", 0)
			got := v.Verify(context.Background(), []domain.BookCandidate{{Title: secret}})
			if got[0].Verified {
				t.Fatal("candidate unexpectedly verified")
			}
			if m.Snapshot()[tc.metric] != 1 {
				t.Fatalf("metric %s not incremented", tc.metric)
			}
			if strings.Contains(logs.String(), secret) {
				t.Fatal("candidate text leaked into log")
			}
		})
	}
}

type failingTransport struct{ message string }

func (f failingTransport) RoundTrip(*http.Request) (*http.Response, error) {
	return nil, errors.New(f.message)
}

func TestOpenLibraryNetworkErrorIsRedacted(t *testing.T) {
	secret := "network-secret-candidate"
	var logs bytes.Buffer
	m := metrics.New()
	v := NewOpenLibrary()
	v.Client = &http.Client{Transport: failingTransport{message: secret}}
	v.Metrics = m
	v.Logger = log.New(&logs, "", 0)
	v.Verify(context.Background(), []domain.BookCandidate{{Title: secret}})
	if m.Snapshot()["openlibrary_dependency_error_total"] != 1 {
		t.Fatal("dependency error metric not incremented")
	}
	if strings.Contains(logs.String(), secret) {
		t.Fatal("network error or candidate leaked into log")
	}
}
