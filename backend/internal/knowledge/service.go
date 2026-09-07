package knowledge

import (
	"clipmind/backend/internal/security"
	"context"
	"encoding/json"
	"errors"
	"io"
	"regexp"
	"strings"
)

type Completer interface {
	CompleteJSON(context.Context, string, string) (string, error)
	Name() string
	Model() string
}
type Service struct{ Provider Completer }

var sensitive = regexp.MustCompile(`(?i)(bearer\s+\S+|(?:access[_-]?token|api[_-]?key|secret|password)\s*[:=]\s*\S+|(?:ghp|sk)-[a-z0-9_-]{16,}|eyJ[a-z0-9_-]+\.eyJ[a-z0-9_-]+\.[a-z0-9_-]+|(?:验证码|otp|code)\s*[:：]\s*\d{4,8}|\b1[3-9]\d{9}\b)`)

func ValidateSafety(input Request) error {
	if err := ValidateRequest(input); err != nil {
		return err
	}
	filter := security.NewSafeFilter(24000)
	for _, card := range input.Cards {
		if _, ok, _ := filter.Check(card.Text); !ok || sensitive.MatchString(card.Text) {
			return errors.New("selected card failed safety validation")
		}
	}
	return nil
}

func (s Service) Generate(ctx context.Context, input Request) (Response, error) {
	if err := ValidateSafety(input); err != nil {
		return Response{}, err
	}
	if s.Provider == nil {
		return Response{}, errors.New("knowledge provider unavailable")
	}
	data, err := json.Marshal(input)
	if err != nil {
		return Response{}, errors.New("invalid synthesis input")
	}
	raw, err := s.Provider.CompleteJSON(ctx, SystemPrompt, string(data))
	if err != nil {
		return Response{}, errors.New("knowledge provider failed")
	}
	var result Result
	d := json.NewDecoder(strings.NewReader(raw))
	d.DisallowUnknownFields()
	if err := d.Decode(&result); err != nil {
		return Response{}, errors.New("invalid synthesis JSON")
	}
	if d.Decode(new(any)) != io.EOF {
		return Response{}, errors.New("trailing synthesis data")
	}
	if err := ValidateResult(input, result); err != nil {
		return Response{}, err
	}
	return Response{result, s.Provider.Name(), s.Provider.Model(), PromptVersion}, nil
}
