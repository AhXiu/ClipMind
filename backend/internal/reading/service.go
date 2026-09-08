package reading

import (
	"clipmind/backend/internal/books"
	"clipmind/backend/internal/domain"
	"clipmind/backend/internal/knowledge"
	"clipmind/backend/internal/llm"
	"clipmind/backend/internal/security"
	"context"
	"errors"
	"strings"
	"unicode/utf8"
)

type Analyzer interface {
	AnalyzeContext(context.Context, string, []string) (llm.Result, error)
}
type Searcher interface {
	Search(context.Context, []string) ([]domain.Article, error)
}
type Request struct {
	Text           string   `json:"text"`
	KnownTags      []string `json:"known_tags"`
	ReadBooks      []string `json:"read_books"`
	SearchArticles bool     `json:"search_articles"`
}
type Response struct {
	SchemaVersion  int                    `json:"schema_version"`
	Provider       string                 `json:"provider"`
	Model          string                 `json:"model"`
	PrimaryTag     string                 `json:"primary_tag"`
	Interpretation domain.Interpretation  `json:"interpretation"`
	SecondaryTags  []domain.TagSuggestion `json:"secondary_tags"`
	Keywords       []string               `json:"keywords"`
	Books          []domain.BookCandidate `json:"books"`
	Articles       []domain.Article       `json:"articles"`
	Value          string                 `json:"value"`
	ValueReason    string                 `json:"value_reason"`
	Questions      []string               `json:"questions"`
	Warnings       []string               `json:"warnings"`
}
type Service struct {
	Analyzer        Analyzer
	Books           books.Verifier
	Search          Searcher
	Provider, Model string
}

func Normalize(s string) string { return strings.ToLower(strings.Join(strings.Fields(s), " ")) }
func Validate(r Request) error {
	if strings.TrimSpace(r.Text) == "" || utf8.RuneCountInString(r.Text) > 6000 || len(r.KnownTags) > 200 || len(r.ReadBooks) > 500 {
		return errors.New("input limits exceeded")
	}
	filter := security.NewSafeFilter(30000)
	for _, text := range append(append([]string{r.Text}, r.KnownTags...), r.ReadBooks...) {
		if _, ok, _ := filter.Check(text); !ok {
			return errors.New("unsafe input")
		}
		if knowledge.ValidateSafety(knowledge.Request{Cards: []knowledge.Card{{ID: "1", Revision: 1, Text: text}, {ID: "2", Revision: 1, Text: "safety validation placeholder"}}}) != nil {
			return errors.New("unsafe input")
		}
	}
	for _, s := range r.KnownTags {
		if len([]rune(s)) > 30 || domain.AllowedTags[s] {
			return errors.New("invalid secondary tag")
		}
	}
	for _, s := range r.ReadBooks {
		if len([]rune(s)) > 300 {
			return errors.New("invalid book")
		}
	}
	return nil
}
func (s *Service) Generate(ctx context.Context, input Request) (Response, error) {
	var out Response
	if err := Validate(input); err != nil {
		return out, err
	}
	r, err := s.Analyzer.AnalyzeContext(ctx, input.Text, input.KnownTags)
	if err != nil {
		return out, errors.New("analysis failed")
	}
	if err = llm.Validate(r); err != nil {
		return out, err
	}
	if len(r.Keywords) != 3 || (r.Value != "high" && r.Value != "medium" && r.Value != "low") || (r.Value == "high" && len(r.Questions) < 2) {
		return out, errors.New("incomplete enriched analysis")
	}
	keywords := map[string]bool{}
	for _, keyword := range r.Keywords {
		key := Normalize(keyword)
		if keywords[key] {
			return out, errors.New("duplicate keyword")
		}
		keywords[key] = true
	}
	for _, q := range r.Questions {
		if strings.TrimSpace(q) == "" || len([]rune(q)) > 200 {
			return out, errors.New("invalid question")
		}
	}
	if r.Questions == nil {
		r.Questions = []string{}
	}
	known := map[string]string{}
	for _, t := range input.KnownTags {
		known[Normalize(t)] = t
	}
	tags := []domain.TagSuggestion{}
	seen := map[string]bool{}
	for _, t := range r.SecondaryTags {
		key := Normalize(t)
		if key == "" || domain.AllowedTags[t] || seen[key] {
			continue
		}
		seen[key] = true
		status := "pending"
		name := strings.TrimSpace(t)
		if old, ok := known[key]; ok {
			name = old
			status = "reused"
		}
		tags = append(tags, domain.TagSuggestion{Name: name, Status: status})
	}
	read := map[string]bool{}
	for _, b := range input.ReadBooks {
		read[Normalize(b)] = true
	}
	candidates := []domain.BookCandidate{}
	for _, b := range r.Books {
		if !read[Normalize(b.Title)] {
			candidates = append(candidates, b)
		}
	}
	verified := s.Books.Verify(ctx, candidates)
	selected := []domain.BookCandidate{}
	authors := map[string]bool{}
	titles := map[string]bool{}
	for _, b := range verified {
		title, author := Normalize(b.Title), Normalize(b.Author)
		if !b.Verified || read[title] || titles[title] || (author != "" && authors[author]) {
			continue
		}
		titles[title] = true
		authors[author] = true
		selected = append(selected, b)
		if len(selected) == 3 {
			break
		}
	}
	out = Response{SchemaVersion: 2, Provider: s.Provider, Model: s.Model, PrimaryTag: r.PrimaryTag, Interpretation: r.Interpretation, SecondaryTags: tags, Keywords: r.Keywords, Books: selected, Articles: []domain.Article{}, Value: r.Value, ValueReason: r.ValueReason, Questions: r.Questions, Warnings: []string{}}
	if len(candidates) > 0 && len(selected) == 0 {
		out.Warnings = append(out.Warnings, "NO_VERIFIED_UNREAD_BOOKS")
	}
	if input.SearchArticles {
		if s.Search == nil {
			out.Warnings = append(out.Warnings, "ARTICLE_SEARCH_NOT_CONFIGURED")
		} else {
			articles, e := s.Search.Search(ctx, r.Keywords)
			if e != nil {
				out.Warnings = append(out.Warnings, "ARTICLE_SEARCH_FAILED")
			} else {
				out.Articles = articles
			}
		}
	}
	return out, nil
}
