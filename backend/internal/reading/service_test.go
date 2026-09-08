package reading

import (
	"clipmind/backend/internal/domain"
	"clipmind/backend/internal/knowledge"
	"clipmind/backend/internal/llm"
	"context"
	"io"
	"net/http"
	"strings"
	"testing"
)

type analyzerStub struct {
	result llm.Result
	tags   []string
}

func (s *analyzerStub) AnalyzeContext(_ context.Context, _ string, tags []string) (llm.Result, error) {
	s.tags = tags
	return s.result, nil
}

type verifierStub struct{}

func (verifierStub) Verify(_ context.Context, books []domain.BookCandidate) []domain.BookCandidate {
	for i := range books {
		books[i].Verified = books[i].Title != "Missing"
		books[i].OpenLibraryKey = "/works/OL123W"
		books[i].Confidence = "speculative"
	}
	return books
}
func sample() llm.Result {
	return llm.Result{PrimaryTag: "认知", Interpretation: domain.Interpretation{Summary: "summary", Insight: "application", Action: "reflection"}, SecondaryTags: []string{"Existing", "New", "new"}, Keywords: []string{"one", "two", "three"}, Value: "high", Questions: []string{"Where does this apply?", "What contradicts it?"}, Books: []domain.BookCandidate{{Title: "Read", Author: "A"}, {Title: "Unread", Author: "B"}, {Title: "Duplicate author", Author: "B"}, {Title: "Missing"}}}
}
func TestEnrichmentReusesTagsAndFiltersBooks(t *testing.T) {
	a := &analyzerStub{result: sample()}
	s := Service{Analyzer: a, Books: verifierStub{}}
	result, err := s.Generate(context.Background(), Request{Text: "This is a source excerpt", KnownTags: []string{"existing"}, ReadBooks: []string{"read"}, SearchArticles: true})
	if err != nil {
		t.Fatal(err)
	}
	if len(a.tags) != 1 || len(result.SecondaryTags) != 2 || result.SecondaryTags[0].Name != "existing" || result.SecondaryTags[0].Status != "reused" || result.SecondaryTags[1].Status != "pending" {
		t.Fatal("tag governance contract failed")
	}
	if len(result.Books) != 1 || result.Books[0].Title != "Unread" {
		t.Fatal("book exclusion/diversity failed")
	}
	if len(result.Warnings) != 1 || result.Warnings[0] != "ARTICLE_SEARCH_NOT_CONFIGURED" {
		t.Fatal("missing dependency must be visible")
	}
}
func TestRejectsSensitiveMetadataAndIncompleteHighValueQuestions(t *testing.T) {
	for _, input := range []Request{{Text: "normal text", KnownTags: []string{"api_key=private"}}, {Text: "13800138000"}, {Text: "normal text", ReadBooks: []string{"access_token=private"}}, {Text: strings.Repeat("字", 6001)}} {
		if Validate(input) == nil {
			t.Fatal("unsafe/oversized input accepted")
		}
	}
	r := sample()
	r.Questions = nil
	s := Service{Analyzer: &analyzerStub{result: r}, Books: verifierStub{}}
	if _, err := s.Generate(context.Background(), Request{Text: "normal excerpt"}); err == nil {
		t.Fatal("missing high-value questions accepted")
	}
}
func TestWeeklyCitationsAndComparisons(t *testing.T) {
	input := WeeklyRequest{Cards: []knowledge.Card{{ID: "1", Revision: 1, Text: "Original quotation"}}}
	result := knowledge.Result{Title: "Theme", Points: []knowledge.Point{{Kind: "summary", Text: "Point", Evidence: []knowledge.Evidence{{CardID: "1", Quote: "quotation"}}}}, Relations: []knowledge.Relation{}}
	if ValidateWeekResult(input, result) != nil {
		t.Fatal("valid weekly citation rejected")
	}
	result.Points[0].Kind = "agreement"
	if ValidateWeekResult(input, result) == nil {
		t.Fatal("single-source comparison accepted")
	}
	result.Points[0].Kind = "summary"
	result.Points[0].Evidence[0].Quote = "forged"
	if ValidateWeekResult(input, result) == nil {
		t.Fatal("forged citation accepted")
	}
}

type transportFunc func(*http.Request) (*http.Response, error)

func (f transportFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func TestEmbeddingOrderingAndInvalidShapes(t *testing.T) {
	bodies := []struct {
		body  string
		valid bool
	}{
		{`{"model":"m","data":[{"index":1,"embedding":[0,1,0,0,0,0,0,0]},{"index":0,"embedding":[1,0,0,0,0,0,0,0]}]}`, true},
		{`{"model":"m","data":[{"index":0,"embedding":[1,0,0,0,0,0,0,0]},{"index":0,"embedding":[1,0,0,0,0,0,0,0]}]}`, false},
		{`{"model":"different","data":[]}`, false},
		{`{"model":"m","data":[{"index":0,"embedding":[0,0,0,0,0,0,0,0]},{"index":1,"embedding":[0,0,0,0,0,0,0,0]}]}`, false},
	}
	for _, tc := range bodies {
		service := EmbeddingService{Model: "m", Key: "test-only", Client: &http.Client{Transport: transportFunc(func(r *http.Request) (*http.Response, error) {
			if r.URL.String() != "https://api.openai.com/v1/embeddings" || r.Header.Get("Authorization") != "Bearer test-only" {
				t.Fatal("unexpected embedding transport")
			}
			return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(tc.body)), Header: make(http.Header)}, nil
		})}}
		out, err := service.Embed(context.Background(), EmbeddingRequest{Texts: []string{"source one", "source two"}})
		if (err == nil) != tc.valid {
			t.Fatalf("valid=%v err=%v", tc.valid, err)
		}
		if tc.valid && out.Vectors[0][0] != 1 {
			t.Fatal("embedding indices ignored")
		}
	}
}
func TestArticleAllowlistRejectsSSRFAndImpersonation(t *testing.T) {
	for _, u := range []string{"http://sspai.com/post/1", "https://127.0.0.1/", "https://sspai.com.attacker.invalid/", "https://user@sspai.com/", "https://sspai.com:8443/"} {
		if articleURL(u) {
			t.Fatal("unsafe article URL accepted")
		}
	}
	if !articleURL("https://sspai.com/post/1") {
		t.Fatal("allowed publisher rejected")
	}
	if PublicClient().CheckRedirect == nil || PublicClient().Transport.(*http.Transport).Proxy != nil {
		t.Fatal("redirect/proxy guard missing")
	}
}
