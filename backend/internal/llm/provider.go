package llm

import (
	"clipmind/backend/internal/domain"
	"context"
	"errors"
	"strings"
)

type Result struct {
	PrimaryTag     string                 `json:"primary_tag"`
	Interpretation domain.Interpretation  `json:"interpretation"`
	Books          []domain.BookCandidate `json:"books"`
	SecondaryTags  []string               `json:"secondary_tags,omitempty"`
	Keywords       []string               `json:"keywords,omitempty"`
	Value          string                 `json:"value,omitempty"`
	ValueReason    string                 `json:"value_reason,omitempty"`
	Questions      []string               `json:"questions,omitempty"`
}
type Provider interface {
	Analyze(context.Context, string) (Result, error)
}

type IdentifiedProvider interface {
	Provider
	Name() string
	Model() string
}

func Identity(p Provider) (string, string) {
	if identified, ok := p.(IdentifiedProvider); ok {
		return identified.Name(), identified.Model()
	}
	return "unknown", "unknown"
}

func Validate(r Result) error {
	if !domain.AllowedTags[r.PrimaryTag] {
		return errors.New("invalid primary_tag")
	}
	for _, text := range []string{r.Interpretation.Summary, r.Interpretation.Insight, r.Interpretation.Action} {
		if strings.TrimSpace(text) == "" || len([]rune(text)) > 4000 {
			return errors.New("invalid three-part interpretation")
		}
	}
	for _, b := range r.Books {
		if strings.TrimSpace(b.Title) == "" || len([]rune(b.Title)) > 300 || len([]rune(b.Author)) > 200 || len([]rune(b.Reason)) > 300 {
			return errors.New("invalid book candidate")
		}
	}
	if len(r.Books) > 10 || len(r.SecondaryTags) > 5 || len(r.Keywords) > 3 {
		return errors.New("too many analysis items")
	}
	if len(r.Questions) > 3 {
		return errors.New("too many questions")
	}
	for _, tag := range append(append([]string{}, r.SecondaryTags...), r.Keywords...) {
		if strings.TrimSpace(tag) == "" || len([]rune(tag)) > 30 {
			return errors.New("invalid tag or keyword")
		}
	}
	return nil
}
