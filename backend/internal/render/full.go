package render

import (
	"clipmind/backend/internal/domain"
	"fmt"
	"net/url"
	"strings"
)

// Text fields are escaped; raw excerpts remain literal in a fenced block.
func Escape(s string) string {
	s = strings.ReplaceAll(strings.ReplaceAll(s, "\r", " "), "\n", " ")
	return strings.NewReplacer("\\", "\\\\", "[", "\\[", "]", "\\]", "<", "&lt;", ">", "&gt;", "*", "\\*", "_", "\\_", "`", "\\`", "#", "\\#", "|", "\\|").Replace(s)
}
func SafeURL(s string) string {
	u, err := url.Parse(s)
	if err != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil {
		return ""
	}
	return u.String()
}
func FullMarkdown(id, captured, raw, source, sourceURL, primary string, in domain.Interpretation, tags []domain.TagSuggestion, books []domain.BookCandidate, articles []domain.Article) string {
	var b strings.Builder
	date := captured
	if len(date) > 10 {
		date = date[:10]
	}
	if source == "" {
		source = "来源未知"
	}
	if SafeURL(sourceURL) == "" {
		sourceURL = "来源未知"
	}
	fmt.Fprintf(&b, "---\ncard_id: %q\nschema_version: 2\n---\n\n# 摘抄｜%s\n> 来源：%s\n> 原文链接：%s\n\n## 原文摘抄\n\n", Escape(id), Escape(date), Escape(source), Escape(sourceURL))
	fence := "```"
	for strings.Contains(raw, fence) {
		fence += "`"
	}
	fmt.Fprintf(&b, "%stext\n%s\n%s\n\n## AI解读\n1. 核心释义：%s\n2. 场景应用：%s\n3. 认知启发：%s\n\n## 智能标签\n一级：#%s\n", fence, raw, fence, Escape(in.Summary), Escape(in.Insight), Escape(in.Action), Escape(primary))
	b.WriteString("二级：")
	for _, tag := range tags {
		status := "新增待确认"
		if tag.Status == "reused" {
			status = "复用历史标签"
		}
		fmt.Fprintf(&b, " #%s（%s）", Escape(tag.Name), status)
	}
	b.WriteString("\n\n## 知识关联\n尚无已确认关联；以本机确认后的导出为准。\n\n## 推荐书籍\n")
	count := 0
	for _, book := range books {
		if !book.Verified || !strings.HasPrefix(book.OpenLibraryKey, "/works/OL") {
			continue
		}
		count++
		level := "仅推测"
		if book.Confidence == "semantic" {
			level = "语义匹配"
		}
		fmt.Fprintf(&b, "%d. 《%s》｜%s｜匹配说明：%s｜<https://openlibrary.org%s>\n", count, Escape(book.Title), level, Escape(book.Reason), book.OpenLibraryKey)
	}
	if count == 0 {
		b.WriteString("暂无通过真实性校验的推荐书籍。\n")
	}
	b.WriteString("\n## 延伸阅读\n")
	for _, a := range articles {
		if u := SafeURL(a.URL); u != "" {
			fmt.Fprintf(&b, "- 《%s》｜摘要：%s｜<%s>\n", Escape(a.Title), Escape(a.Summary), u)
		}
	}
	if len(articles) == 0 {
		b.WriteString("暂无经检索和访问校验的文章。\n")
	}
	return b.String()
}
