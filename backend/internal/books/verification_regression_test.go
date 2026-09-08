package books

import (
	"clipmind/backend/internal/domain"
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestModelVerificationFlagAndWrongAuthorCannotPass(t *testing.T) {
	for _, body := range []string{`{"docs":[]}`, `{"docs":[{"key":"/works/OL123W","title":"Same title","author_name":["Another author"]}]}`, `{"docs":[{"key":"/works/OL1W)bad","title":"Same title","author_name":["Expected author"]}]}`} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte(body)) }))
		v := NewOpenLibrary()
		v.BaseURL = srv.URL
		out := v.Verify(context.Background(), []domain.BookCandidate{{Title: "Same title", Author: "Expected author", Verified: true, OpenLibraryKey: "/works/OL999W", Confidence: "direct"}})
		srv.Close()
		if out[0].Verified || out[0].OpenLibraryKey != "" || out[0].Confidence != "speculative" {
			t.Fatal("untrusted verification survived")
		}
	}
}
