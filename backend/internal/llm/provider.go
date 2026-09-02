package llm

import (
	"clipmind/backend/internal/domain"
	"context"
	"errors"
)

type Result struct {
	PrimaryTag     string                 `json:"primary_tag"`
	Interpretation domain.Interpretation  `json:"interpretation"`
	Books          []domain.BookCandidate `json:"books"`
}
type Provider interface {
	Analyze(context.Context, string) (Result, error)
}

func Validate(r Result) error {
	if !domain.AllowedTags[r.PrimaryTag] {
		return errors.New("invalid primary_tag")
	}
	if r.Interpretation.Summary == "" || r.Interpretation.Insight == "" || r.Interpretation.Action == "" {
		return errors.New("incomplete three-part interpretation")
	}
	for _, b := range r.Books {
		if b.Title == "" {
			return errors.New("book title is required")
		}
	}
	return nil
}
