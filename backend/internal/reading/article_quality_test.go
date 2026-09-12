package reading

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
)

func TestArticleQualityRequiresRelevantGroundedReadingValue(t *testing.T) {
	source := "Practice requires feedback and reflection."
	quote := "Feedback makes deliberate practice more effective."
	for _, tc := range []struct {
		name   string
		change func(map[string]any)
		valid  bool
	}{
		{"grounded", func(map[string]any) {}, true},
		{"unrelated", func(m map[string]any) { m["is_article"] = false }, false},
		{"no reading value", func(m map[string]any) { delete(m, "reason") }, false},
		{"only same topic", func(m map[string]any) { m["relation"] = "same_topic" }, false},
		{"invented source evidence", func(m map[string]any) { m["source_quote"] = "Practice never requires feedback." }, false},
		{"fragment source evidence", func(m map[string]any) { m["source_quote"] = "Practice" }, false},
		{"oversized reason", func(m map[string]any) { m["reason"] = strings.Repeat("字", 201) }, false},
		{"missing body evidence", func(m map[string]any) { delete(m, "quote") }, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			fetches, summaries := 0, 0
			s := BraveSearch{Client: &http.Client{Transport: transportFunc(func(r *http.Request) (*http.Response, error) {
				body := "<p>" + strings.Repeat(quote, 8) + "</p>"
				if r.URL.Host == "api.search.brave.com" {
					if strings.Contains(r.URL.RawQuery, "reflection") {
						t.Fatal("source excerpt leaked into search query")
					}
					body = `{"web":{"results":[{"title":"Feedback","url":"https://sspai.com/post/1#first"},{"title":"Feedback duplicate","url":"https://sspai.com/post/1#second"}]}}`
				} else {
					fetches++
				}
				return &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": {"text/html"}}, Body: io.NopCloser(strings.NewReader(body))}, nil
			})}, Summarizer: completerFunc(func(_ context.Context, system, user string) (string, error) {
				summaries++
				if system != articlePrompt || !strings.Contains(user, source) {
					t.Fatal("missing contextual prompt")
				}
				result := map[string]any{"summary": "How to practice", "quote": quote, "source_quote": source, "relation": "extends", "reason": "Explains the feedback mechanism.", "is_article": true}
				tc.change(result)
				data, _ := json.Marshal(result)
				return string(data), nil
			})}
			out, err := s.Search(context.Background(), ArticleQuery{source, []string{"practice", "feedback", "认知"}})
			if err != nil || (len(out) == 1) != tc.valid || fetches != 1 || summaries != 1 {
				t.Fatalf("quality gate: articles=%d fetches=%d summaries=%d err=%v", len(out), fetches, summaries, err)
			}
		})
	}
}

func TestArticleSearchCapsUpstreamResultsAndRejectsUnsafeInputBeforeNetwork(t *testing.T) {
	calls := 0
	s := BraveSearch{Client: &http.Client{Transport: transportFunc(func(r *http.Request) (*http.Response, error) {
		calls++
		body := "unavailable"
		if r.URL.Host == "api.search.brave.com" {
			items := []map[string]string{}
			for i := 0; i < 12; i++ {
				items = append(items, map[string]string{"title": "Article", "url": "https://sspai.com/post/" + strings.Repeat("a", i+1)})
			}
			data, _ := json.Marshal(map[string]any{"web": map[string]any{"results": items}})
			body = string(data)
		}
		return &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": {"text/html"}}, Body: io.NopCloser(strings.NewReader(body))}, nil
	})}}
	if _, err := s.Search(context.Background(), ArticleQuery{"api_key=private", []string{"a", "b", "c"}}); err == nil || calls != 0 {
		t.Fatal("unsafe source reached transport")
	}
	if _, err := s.Search(context.Background(), ArticleQuery{"Source text", []string{"a", "b", "c"}}); err != nil || calls != 9 {
		t.Fatalf("unbounded fetches: %d, %v", calls, err)
	}
}
