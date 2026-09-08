package books

import (
	"clipmind/backend/internal/domain"
	"clipmind/backend/internal/metrics"
	"context"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type Verifier interface {
	Verify(context.Context, []domain.BookCandidate) []domain.BookCandidate
}
type OpenLibrary struct {
	BaseURL string
	Client  *http.Client
	Metrics *metrics.Counter
	Logger  *log.Logger
}

func NewOpenLibrary() *OpenLibrary {
	return &OpenLibrary{BaseURL: "https://openlibrary.org", Client: &http.Client{Timeout: 8 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}, Metrics: metrics.New(), Logger: log.Default()}
}
func (o *OpenLibrary) observe(kind string, status int) {
	if o.Metrics != nil {
		o.Metrics.Inc("openlibrary_" + kind + "_total")
	}
	if o.Logger != nil {
		o.Logger.Printf("openlibrary lookup outcome=%s status=%d", kind, status)
	}
}
func (o *OpenLibrary) Verify(ctx context.Context, in []domain.BookCandidate) []domain.BookCandidate {
	out := make([]domain.BookCandidate, 0, len(in))
	for _, c := range in {
		// Verification is owned by this adapter, never by model output or callers.
		c.Verified = false
		c.OpenLibraryKey = ""
		if c.Confidence != "semantic" {
			c.Confidence = "speculative"
		}
		q := url.Values{"title": []string{c.Title}, "limit": []string{"3"}, "fields": []string{"key,title,author_name"}}
		req, e := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(o.BaseURL, "/")+"/search.json?"+q.Encode(), nil)
		if e != nil {
			o.observe("dependency_error", 0)
			out = append(out, c)
			continue
		}
		resp, e := o.Client.Do(req)
		if e != nil {
			o.observe("dependency_error", 0)
			out = append(out, c)
			continue
		}
		status := resp.StatusCode
		if status != http.StatusOK {
			_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
			resp.Body.Close()
			o.observe("dependency_error", status)
			out = append(out, c)
			continue
		}
		var data struct {
			Docs []struct {
				Key    string   `json:"key"`
				Title  string   `json:"title"`
				Author []string `json:"author_name"`
			} `json:"docs"`
		}
		e = json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&data)
		resp.Body.Close()
		if e != nil {
			o.observe("dependency_error", status)
			out = append(out, c)
			continue
		}
		for _, d := range data.Docs {
			authorMatches := strings.TrimSpace(c.Author) == ""
			for _, author := range d.Author {
				if strings.EqualFold(strings.TrimSpace(author), strings.TrimSpace(c.Author)) {
					authorMatches = true
				}
			}
			if validWorkKey(d.Key) && authorMatches && strings.EqualFold(strings.TrimSpace(d.Title), strings.TrimSpace(c.Title)) {
				c.Title = d.Title
				c.OpenLibraryKey = d.Key
				c.Verified = true
				if len(d.Author) > 0 {
					c.Author = d.Author[0]
				}
				break
			}
		}
		if c.Verified {
			o.observe("verified", status)
		} else {
			o.observe("not_found", status)
		}
		out = append(out, c)
	}
	return out
}

func validWorkKey(key string) bool {
	if !strings.HasPrefix(key, "/works/OL") || !strings.HasSuffix(key, "W") {
		return false
	}
	digits := strings.TrimSuffix(strings.TrimPrefix(key, "/works/OL"), "W")
	if digits == "" {
		return false
	}
	for _, r := range digits {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}
