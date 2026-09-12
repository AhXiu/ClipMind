package render

import (
	"clipmind/backend/internal/domain"
	"strings"
	"testing"
)

func TestFullTemplatePreservesRawAndDoesNotInventReferences(t *testing.T) {
	raw := "  first line\n\n```\nsecond line  "
	markdown := FullMarkdown("card1", "2026-09-08T00:00:00Z", raw, "Reader", "https://example.invalid/source", "认知", domain.Interpretation{Summary: "meaning", Insight: "application", Action: "inspiration"}, []domain.TagSuggestion{{Name: "compound", Status: "pending"}}, []domain.BookCandidate{{Title: "Invented", Verified: false}}, nil)
	for _, expected := range []string{raw, "# 摘抄｜2026-09-08", "核心释义", "场景应用", "认知启发", "新增待确认", "## 知识关联", "## 延伸阅读", "````text"} {
		if !strings.Contains(markdown, expected) {
			t.Fatalf("missing %q", expected)
		}
	}
	if strings.Contains(markdown, "Invented") {
		t.Fatal("unverified book rendered")
	}
}

func TestArticleEvidenceIsExportedAndEscaped(t *testing.T) {
	article := domain.Article{Title: "Article", URL: "https://sspai.com/post/1", Summary: "Summary", Relation: "extends", Reason: "[unsafe](link)", SourceQuote: "Original source evidence.", Quote: "Article evidence with context."}
	markdown := FullMarkdown("1", "2026-09-13", "Original", "", "", "", domain.Interpretation{}, nil, nil, []domain.Article{article})
	for _, expected := range []string{"\\[unsafe\\]", article.SourceQuote, article.Quote} {
		if !strings.Contains(markdown, expected) {
			t.Fatalf("missing escaped evidence: %s", expected)
		}
	}
}
