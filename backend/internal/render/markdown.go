package render

import (
	"clipmind/backend/internal/domain"
	"fmt"
	"strings"
)

// Markdown follows the Phase 1 card template: metadata, excerpt, three-part interpretation and verified references.
func Markdown(cardID string, created string, text, tag string, in domain.Interpretation, books []domain.BookCandidate) string {
	var b strings.Builder
	fmt.Fprintf(&b, "---\ncard_id: %s\ntag: %s\ncreated_at: %s\n---\n\n", cardID, tag, created)
	fmt.Fprintf(&b, "# %s\n\n## 原文摘录\n\n> %s\n\n", title(in.Summary), strings.ReplaceAll(text, "\n", "\n> "))
	fmt.Fprintf(&b, "## 一句话总结\n\n%s\n\n## 深度解读\n\n%s\n\n## 行动启发\n\n%s\n\n", in.Summary, in.Insight, in.Action)
	verified := false
	for _, x := range books {
		if x.Verified {
			if !verified {
				b.WriteString("## 相关书籍（已验证）\n\n")
				verified = true
			}
			fmt.Fprintf(&b, "- [%s](https://openlibrary.org%s)", x.Title, x.OpenLibraryKey)
			if x.Author != "" {
				fmt.Fprintf(&b, " — %s", x.Author)
			}
			b.WriteString("\n")
		}
	}
	return b.String()
}
func title(s string) string {
	r := []rune(strings.TrimSpace(s))
	if len(r) == 0 {
		return "ClipMind 卡片"
	}
	if len(r) > 32 {
		r = r[:32]
	}
	return string(r)
}
