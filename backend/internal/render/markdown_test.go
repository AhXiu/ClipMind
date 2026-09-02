package render

import (
	"clipmind/backend/internal/domain"
	"strings"
	"testing"
)

func TestMarkdownTemplate(t *testing.T) {
	md := Markdown("card_1", "2026-01-01T00:00:00Z", "line one\nline two", "技术", domain.Interpretation{Summary: "总结", Insight: "解读", Action: "行动"}, []domain.BookCandidate{{Title: "The Go Programming Language", OpenLibraryKey: "/works/OL1W", Verified: true}, {Title: "Guess", Verified: false}})
	for _, want := range []string{"card_id: card_1", "tag: 技术", "## 原文摘录", "> line two", "## 一句话总结", "## 深度解读", "## 行动启发", "/works/OL1W"} {
		if !strings.Contains(md, want) {
			t.Errorf("missing %q", want)
		}
	}
	if strings.Contains(md, "Guess") {
		t.Fatal("unverified book must not be rendered as a certain reference")
	}
}
