package domain

import (
	"fmt"
	"strings"
	"testing"
)

func TestCaptureFormattingRedactsText(t *testing.T) {
	secret := "do-not-log-this-text"
	c := Capture{ID: "cap", ClientCaptureID: "client", Text: secret, Status: StatusPersisted, CardID: "card"}
	for _, got := range []string{fmt.Sprint(c), fmt.Sprintf("%v", c), fmt.Sprintf("%+v", c), fmt.Sprintf("%#v", c)} {
		if strings.Contains(got, secret) {
			t.Fatalf("capture formatting leaked Text: %s", got)
		}
	}
}

func boundaryClientAnalysis() ClientAnalysis {
	books := make([]ClientBook, 10)
	for i := range books {
		books[i] = ClientBook{Title: strings.Repeat("题", 300), Author: strings.Repeat("作", 200)}
	}
	return ClientAnalysis{
		Provider:   "ark",
		Model:      strings.Repeat("模", 200),
		PrimaryTag: "技术",
		Interpretation: Interpretation{
			Summary: strings.Repeat("摘", 2000),
			Insight: strings.Repeat("洞", 4000),
			Action:  strings.Repeat("行", 2000),
		},
		Books: books,
	}
}

func TestValidateClientAnalysisAcceptsContractBoundaries(t *testing.T) {
	a := boundaryClientAnalysis()
	if err := ValidateClientAnalysis(a); err != nil {
		t.Fatalf("maximum contract boundaries rejected: %v", err)
	}
	a.Books[0].Author = ""
	if err := ValidateClientAnalysis(a); err != nil {
		t.Fatalf("empty optional author rejected: %v", err)
	}
}

func TestValidateClientAnalysisRejectsValuesPastEachBoundary(t *testing.T) {
	tests := []struct {
		name string
		want string
		edit func(*ClientAnalysis)
	}{
		{"model", "client_analysis.model must be non-empty and at most 200 characters", func(a *ClientAnalysis) { a.Model += "x" }},
		{"summary", "client_analysis.interpretation.summary must be non-empty and at most 2000 characters", func(a *ClientAnalysis) { a.Interpretation.Summary += "x" }},
		{"insight", "client_analysis.interpretation.insight must be non-empty and at most 4000 characters", func(a *ClientAnalysis) { a.Interpretation.Insight += "x" }},
		{"action", "client_analysis.interpretation.action must be non-empty and at most 2000 characters", func(a *ClientAnalysis) { a.Interpretation.Action += "x" }},
		{"book title", "client_analysis book title must be non-empty and at most 300 characters", func(a *ClientAnalysis) { a.Books[0].Title += "x" }},
		{"book author", "client_analysis book author must be at most 200 characters", func(a *ClientAnalysis) { a.Books[0].Author += "x" }},
		{"books", "client_analysis.books must contain at most 10 items", func(a *ClientAnalysis) { a.Books = append(a.Books, ClientBook{Title: "extra"}) }},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			a := boundaryClientAnalysis()
			test.edit(&a)
			if err := ValidateClientAnalysis(a); err == nil {
				t.Fatal("expected validation error")
			} else if err.Error() != test.want {
				t.Fatalf("validation category = %q, want %q", err, test.want)
			}
		})
	}
}
