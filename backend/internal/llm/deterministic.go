package llm

import (
	"clipmind/backend/internal/domain"
	"clipmind/backend/internal/knowledge"
	"context"
	"encoding/json"
	"strings"
)

type Deterministic struct{}

func (Deterministic) CompleteJSON(_ context.Context, _, user string) (string, error) {
	var input knowledge.Request
	if err := json.Unmarshal([]byte(user), &input); err != nil {
		return "", err
	}
	result := knowledge.Result{Title: "本地测试归纳（非模型推理）", Points: []knowledge.Point{}, Relations: []knowledge.Relation{}}
	for _, card := range input.Cards {
		quote := []rune(card.Text)
		if len(quote) > 200 {
			quote = quote[:200]
		}
		result.Points = append(result.Points, knowledge.Point{Kind: "summary", Text: string(quote), Evidence: []knowledge.Evidence{{CardID: card.ID, Quote: string(quote)}}})
	}
	data, err := json.Marshal(result)
	return string(data), err
}

func (Deterministic) Name() string  { return "deterministic" }
func (Deterministic) Model() string { return "deterministic" }

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
