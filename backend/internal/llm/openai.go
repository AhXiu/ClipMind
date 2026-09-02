package llm

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type OpenAI struct {
	BaseURL, APIKey, Model string
	Client                 *http.Client
}

func NewOpenAI(base, key, model string, timeout time.Duration) *OpenAI {
	return &OpenAI{BaseURL: strings.TrimRight(base, "/"), APIKey: key, Model: model, Client: &http.Client{Timeout: timeout}}
}
func (o *OpenAI) Analyze(ctx context.Context, text string) (Result, error) {
	prompt := "仅输出JSON，不要Markdown。结构必须为 {\"primary_tag\":\"...\",\"interpretation\":{\"summary\":\"...\",\"insight\":\"...\",\"action\":\"...\"},\"books\":[{\"title\":\"...\",\"author\":\"...\"}]}。primary_tag只能是人文/商业/技术/认知/职场/社会/随笔。书籍只是候选，不确定则返回空数组。待处理摘录：\n" + text
	body := map[string]any{"model": o.Model, "temperature": 0, "response_format": map[string]string{"type": "json_object"}, "messages": []map[string]string{{"role": "system", "content": "你是严谨的知识卡片编辑器。"}, {"role": "user", "content": prompt}}}
	b, e := json.Marshal(body)
	if e != nil {
		return Result{}, e
	}
	req, e := http.NewRequestWithContext(ctx, http.MethodPost, o.BaseURL+"/chat/completions", bytes.NewReader(b))
	if e != nil {
		return Result{}, e
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+o.APIKey)
	resp, e := o.Client.Do(req)
	if e != nil {
		return Result{}, e
	}
	defer resp.Body.Close()
	limited, e := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if e != nil {
		return Result{}, e
	}
	if resp.StatusCode/100 != 2 {
		return Result{}, fmt.Errorf("provider status %d", resp.StatusCode)
	}
	var envelope struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if e = json.Unmarshal(limited, &envelope); e != nil || len(envelope.Choices) != 1 {
		return Result{}, errors.New("invalid provider response")
	}
	var out Result
	if e = json.Unmarshal([]byte(envelope.Choices[0].Message.Content), &out); e != nil {
		return Result{}, errors.New("provider returned invalid JSON")
	}
	if e = Validate(out); e != nil {
		return Result{}, e
	}
	return out, nil
}
