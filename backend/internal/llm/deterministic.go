package llm

import (
	"clipmind/backend/internal/domain"
	"context"
	"strings"
)

type Deterministic struct{}

func (Deterministic) Analyze(_ context.Context, text string) (Result, error) {
	clean := strings.Join(strings.Fields(text), " ")
	r := []rune(clean)
	short := clean
	if len(r) > 48 {
		short = string(r[:48]) + "…"
	}
	tag := "随笔"
	low := strings.ToLower(clean)
	if strings.Contains(low, "go ") || strings.Contains(low, "api") || strings.Contains(low, "代码") {
		tag = "技术"
	}
	return Result{PrimaryTag: tag, Interpretation: domain.Interpretation{Summary: short, Insight: "这段摘录的核心在于识别信息背后的结构、约束与可迁移价值。", Action: "记录一个可验证的小行动，并在实践后补充结果。"}, Books: []domain.BookCandidate{}}, nil
}
