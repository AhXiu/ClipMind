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

// OpenAICompatible calls a fixed OpenAI-compatible Chat Completions endpoint.
// ProviderName and ModelName are persisted as provenance, while APIKey is used
// only to construct the Authorization header.
type OpenAICompatible struct {
	ProviderName string
	BaseURL      string
	ModelName    string
	HTTPReferer  string
	Title        string
	Client       *http.Client
	apiKey       string
}

func NewOpenAICompatible(provider, base, key, model string, timeout time.Duration) *OpenAICompatible {
	return &OpenAICompatible{ProviderName: provider, BaseURL: strings.TrimRight(base, "/"), apiKey: key, ModelName: model, Client: &http.Client{Timeout: timeout, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}
}

// NewOpenAI is retained for source compatibility with the legacy provider.
func NewOpenAI(base, key, model string, timeout time.Duration) *OpenAICompatible {
	return NewOpenAICompatible("openai", base, key, model, timeout)
}

func (o *OpenAICompatible) Name() string  { return o.ProviderName }
func (o *OpenAICompatible) Model() string { return o.ModelName }

func (o *OpenAICompatible) Analyze(ctx context.Context, text string) (Result, error) {
	prompt := "仅输出JSON，不要Markdown。结构必须为 {\"primary_tag\":\"...\",\"interpretation\":{\"summary\":\"...\",\"insight\":\"...\",\"action\":\"...\"},\"books\":[{\"title\":\"...\",\"author\":\"...\"}]}。primary_tag只能是人文/商业/技术/认知/职场/社会/随笔。书籍只是候选，不确定则返回空数组。待处理摘录：\n" + text
	content, err := o.CompleteJSON(ctx, "你是严谨的知识卡片编辑器。", prompt)
	if err != nil {
		return Result{}, err
	}
	var out Result
	dec := json.NewDecoder(strings.NewReader(content))
	dec.DisallowUnknownFields()
	if err = dec.Decode(&out); err != nil {
		return Result{}, errors.New("provider returned invalid JSON")
	}
	if err = Validate(out); err != nil {
		return Result{}, err
	}
	return out, nil
}

func (o *OpenAICompatible) CompleteJSON(ctx context.Context, system, user string) (string, error) {
	body := map[string]any{"model": o.ModelName, "temperature": 0, "response_format": map[string]string{"type": "json_object"}, "messages": []map[string]string{{"role": "system", "content": system}, {"role": "user", "content": user}}}
	b, err := json.Marshal(body)
	if err != nil {
		return "", errors.New("could not encode provider request")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, o.BaseURL+"/chat/completions", bytes.NewReader(b))
	if err != nil {
		return "", errors.New("could not create provider request")
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+o.apiKey)
	if o.HTTPReferer != "" {
		req.Header.Set("HTTP-Referer", o.HTTPReferer)
	}
	if o.Title != "" {
		req.Header.Set("X-Title", o.Title)
	}
	resp, err := o.Client.Do(req)
	if err != nil {
		return "", errors.New("provider request failed")
	}
	defer resp.Body.Close()
	limited, err := io.ReadAll(io.LimitReader(resp.Body, (1<<20)+1))
	if err != nil || len(limited) > 1<<20 {
		return "", errors.New("could not read bounded provider response")
	}
	if resp.StatusCode/100 != 2 {
		return "", fmt.Errorf("provider status %d", resp.StatusCode)
	}
	var envelope struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err = json.Unmarshal(limited, &envelope); err != nil || len(envelope.Choices) != 1 {
		return "", errors.New("invalid provider response")
	}
	return envelope.Choices[0].Message.Content, nil
}
