package config

import (
	"strings"
	"testing"
)

func baseEnvironment(t *testing.T) {
	t.Helper()
	for _, key := range []string{"CLIPMIND_LLM_PROVIDER", "ARK_BASE_URL", "ARK_API_KEY", "ARK_MODEL", "OPENROUTER_API_KEY", "OPENROUTER_MODEL", "OPENROUTER_BASE_URL", "OPENAI_BASE_URL", "OPENAI_API_KEY", "OPENAI_MODEL"} {
		t.Setenv(key, "")
	}
}

func TestDefaultProviderIsArk(t *testing.T) {
	baseEnvironment(t)
	t.Setenv("ARK_API_KEY", "ark-secret")
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.LLMProvider != "ark" || cfg.LLMBaseURL != DefaultArkBaseURL || cfg.LLMModel != DefaultArkModel || cfg.LLMAPIKey != "ark-secret" {
		t.Fatalf("unexpected Ark defaults: provider=%q base=%q model=%q", cfg.LLMProvider, cfg.LLMBaseURL, cfg.LLMModel)
	}
}

func TestOpenRouterConfigurationHasFixedBaseURL(t *testing.T) {
	baseEnvironment(t)
	t.Setenv("CLIPMIND_LLM_PROVIDER", "openrouter")
	t.Setenv("OPENROUTER_API_KEY", "router-secret")
	t.Setenv("OPENROUTER_MODEL", "vendor/model")
	t.Setenv("OPENROUTER_BASE_URL", "http://127.0.0.1/private")
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.LLMBaseURL != OpenRouterBaseURL || cfg.LLMModel != "vendor/model" {
		t.Fatalf("unexpected OpenRouter config: base=%q model=%q", cfg.LLMBaseURL, cfg.LLMModel)
	}
}

func TestSelectedProviderFailsFastWithoutCredentialOrModel(t *testing.T) {
	baseEnvironment(t)
	if _, err := Load(); err == nil || strings.Contains(err.Error(), "secret") {
		t.Fatalf("expected redacted missing Ark key error, got %v", err)
	}
	baseEnvironment(t)
	t.Setenv("CLIPMIND_LLM_PROVIDER", "openrouter")
	t.Setenv("OPENROUTER_API_KEY", "router-secret")
	if _, err := Load(); err == nil || strings.Contains(err.Error(), "router-secret") {
		t.Fatalf("expected redacted missing model error, got %v", err)
	}
}

func TestLegacyOpenAIConfiguration(t *testing.T) {
	baseEnvironment(t)
	t.Setenv("CLIPMIND_LLM_PROVIDER", "openai")
	t.Setenv("OPENAI_API_KEY", "legacy-secret")
	t.Setenv("OPENAI_BASE_URL", "https://legacy.example/v1")
	t.Setenv("OPENAI_MODEL", "legacy-model")
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.LLMProvider != "openai" || cfg.LLMBaseURL != "https://legacy.example/v1" || cfg.LLMModel != "legacy-model" {
		t.Fatalf("legacy OpenAI config not retained: %+v", cfg)
	}
}

func TestDeterministicNeedsNoCredential(t *testing.T) {
	baseEnvironment(t)
	t.Setenv("CLIPMIND_LLM_PROVIDER", "deterministic")
	cfg, err := Load()
	if err != nil || cfg.LLMModel != "deterministic" {
		t.Fatalf("deterministic config failed: model=%q err=%v", cfg.LLMModel, err)
	}
}
