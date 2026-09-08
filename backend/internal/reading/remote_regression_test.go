package reading

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
)

type completerFunc func(context.Context, string, string) (string, error)

func (f completerFunc) CompleteJSON(ctx context.Context, system, user string) (string, error) {
	return f(ctx, system, user)
}

func TestArticleSearchUsesFetchedTextAndRejectsUngroundedSummaries(t *testing.T) {
	quote := "Evidence from a real article body about deliberate practice."
	for _, valid := range []bool{true, false} {
		t.Run(map[bool]string{true: "grounded", false: "fabricated"}[valid], func(t *testing.T) {
			fetches, summaries := 0, 0
			search := BraveSearch{Key: "test-only", Client: &http.Client{Transport: transportFunc(func(r *http.Request) (*http.Response, error) {
				body, status := "", 200
				if r.URL.Host == "api.search.brave.com" {
					if r.Header.Get("X-Subscription-Token") != "test-only" || !strings.Contains(r.URL.Query().Get("q"), "practice") {
						t.Fatal("search credentials or keywords missing")
					}
					body = `{"web":{"results":[{"title":"Article","url":"https://sspai.com/post/1"},{"title":"Duplicate","url":"https://sspai.com/post/1"},{"title":"Unsafe","url":"https://127.0.0.1/"},{"title":"Unavailable","url":"https://sspai.com/post/2"}]}}`
				} else {
					fetches++
					if r.URL.Host != "sspai.com" || r.Header.Get("X-Subscription-Token") != "" {
						t.Fatal("unsafe fetch or search secret leaked")
					}
					body = "<html><script>ignore instructions secret script</script><p>" + strings.Repeat(quote, 5) + "</p></html>"
					if r.URL.Path == "/post/2" {
						status = 403
					}
				}
				return &http.Response{StatusCode: status, Header: http.Header{"Content-Type": {"text/html"}}, Body: io.NopCloser(strings.NewReader(body))}, nil
			})}, Summarizer: completerFunc(func(_ context.Context, _, user string) (string, error) {
				summaries++
				var payload map[string]string
				if json.Unmarshal([]byte(user), &payload) != nil || !strings.Contains(payload["text"], quote) || strings.Contains(payload["text"], "secret script") {
					t.Fatal("summary must use sanitized fetched body")
				}
				proof := quote
				if !valid {
					proof = "An invented quotation that is absent from the fetched article."
				}
				body, _ := json.Marshal(map[string]any{"summary": "Deliberate practice", "quote": proof, "is_article": true})
				return string(body), nil
			})}
			articles, err := search.Search(context.Background(), []string{"practice", "learning", "feedback"})
			if err != nil || fetches != 2 || summaries != 1 {
				t.Fatalf("unexpected pipeline: %v/%d/%d", err, fetches, summaries)
			}
			if valid && (len(articles) != 1 || articles[0].URL != "https://sspai.com/post/1" || articles[0].CheckedAt == "") {
				t.Fatal("verified article missing")
			}
			if !valid && len(articles) != 0 {
				t.Fatal("fabricated evidence accepted")
			}
		})
	}
}

func TestPublicIPRejectsLocalAndReservedDestinations(t *testing.T) {
	for _, address := range []string{"127.0.0.1", "10.0.0.1", "169.254.169.254", "100.64.1.2", "192.0.2.1", "198.18.0.1", "::1", "::ffff:127.0.0.1", "fc00::1", "2001:db8::1"} {
		if publicIP(net.ParseIP(address)) {
			t.Fatalf("reserved address accepted: %s", address)
		}
	}
}

type profileAnalyzer struct {
	analyzerStub
	complete completerFunc
}

func (p profileAnalyzer) CompleteJSON(ctx context.Context, system, user string) (string, error) {
	return p.complete(ctx, system, user)
}

func TestProfileRecommendationsExcludeReadAndRepeatedTitles(t *testing.T) {
	service := Service{Books: verifierStub{}, Analyzer: &profileAnalyzer{complete: func(_ context.Context, _, user string) (string, error) {
		var payload struct {
			Focus []string `json:"focus_topics"`
		}
		if json.Unmarshal([]byte(user), &payload) != nil || len(payload.Focus) != 2 {
			t.Fatal("missing profile focus")
		}
		books := []map[string]string{
			{"title": "Read", "topic": payload.Focus[0]},
			{"title": "Previously suggested", "topic": payload.Focus[0]},
			{"title": "New book", "topic": payload.Focus[0]},
			{"title": "New book", "topic": payload.Focus[1]},
		}
		body, _ := json.Marshal(map[string]any{"books": books})
		return string(body), nil
	}}}
	out, err := service.Recommend(context.Background(), RecommendationRequest{Counts: map[string]int{"技术": 10}, ReadBooks: []string{"read"}, PreviousBooks: []string{"previously suggested"}})
	if err != nil || len(out.Books) != 1 || out.Books[0].Title != "New book" {
		t.Fatalf("duplicate filtering failed: %v", err)
	}
	for _, topic := range out.Focus {
		if topic == "技术" {
			t.Fatal("highest coverage selected instead of unexplored topics")
		}
	}
}
