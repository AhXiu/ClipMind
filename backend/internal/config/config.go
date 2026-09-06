package config

import (
	"encoding/base64"
	"errors"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	DefaultArkBaseURL    = "https://ark.cn-beijing.volces.com/api/v3"
	DefaultArkModel      = "ep-20260306164116-j9fgc"
	OpenRouterBaseURL    = "https://openrouter.ai/api/v1"
	DefaultOpenAIBaseURL = "https://api.openai.com/v1"
	DefaultOpenAIModel   = "gpt-4o-mini"
)

type Config struct {
	Addr, DataDir, VaultDir, AuthToken, Environment string
	AuthDisabled                                    bool
	BackupKey                                       []byte
	LLMProvider                                     string
	LLMBaseURL                                      string
	LLMAPIKey                                       string
	LLMModel                                        string
	OpenRouterHTTPReferer                           string
	OpenRouterTitle                                 string
	LLMTimeout                                      time.Duration
	WorkerInterval                                  time.Duration
}

func Load() (Config, error) {
	c := Config{
		Addr:                  get("CLIPMIND_ADDR", ":8080"),
		DataDir:               get("CLIPMIND_DATA_DIR", "./data"),
		VaultDir:              get("CLIPMIND_VAULT_DIR", "./vault"),
		Environment:           get("CLIPMIND_ENV", "development"),
		AuthToken:             os.Getenv("CLIPMIND_AUTH_TOKEN"),
		LLMProvider:           get("CLIPMIND_LLM_PROVIDER", "ark"),
		OpenRouterHTTPReferer: os.Getenv("OPENROUTER_HTTP_REFERER"),
		OpenRouterTitle:       os.Getenv("OPENROUTER_X_TITLE"),
		LLMTimeout:            duration("CLIPMIND_LLM_TIMEOUT", 20*time.Second),
		WorkerInterval:        duration("CLIPMIND_WORKER_INTERVAL", time.Second),
	}
	c.AuthDisabled = boolean("CLIPMIND_AUTH_DISABLED", c.Environment != "production")
	if s := os.Getenv("CLIPMIND_BACKUP_KEY"); s != "" {
		b, err := base64.StdEncoding.DecodeString(s)
		if err != nil {
			return c, errors.New("CLIPMIND_BACKUP_KEY must be base64")
		}
		c.BackupKey = b
	}

	switch c.LLMProvider {
	case "deterministic":
		c.LLMModel = "deterministic"
	case "ark":
		c.LLMBaseURL = get("ARK_BASE_URL", DefaultArkBaseURL)
		c.LLMAPIKey = os.Getenv("ARK_API_KEY")
		c.LLMModel = get("ARK_MODEL", DefaultArkModel)
	case "openrouter":
		// This URL is deliberately not configurable: uploaded or operator-supplied
		// arbitrary URLs would turn the provider client into an SSRF primitive.
		c.LLMBaseURL = OpenRouterBaseURL
		c.LLMAPIKey = os.Getenv("OPENROUTER_API_KEY")
		c.LLMModel = os.Getenv("OPENROUTER_MODEL")
	case "openai":
		c.LLMBaseURL = get("OPENAI_BASE_URL", DefaultOpenAIBaseURL)
		c.LLMAPIKey = os.Getenv("OPENAI_API_KEY")
		c.LLMModel = get("OPENAI_MODEL", DefaultOpenAIModel)
	default:
		return c, errors.New("CLIPMIND_LLM_PROVIDER must be ark, openrouter, openai, or deterministic")
	}
	if c.LLMProvider != "deterministic" {
		if c.LLMAPIKey == "" {
			return c, errors.New("API key is required for selected LLM provider")
		}
		if strings.TrimSpace(c.LLMModel) == "" || len([]rune(c.LLMModel)) > 200 {
			return c, errors.New("model for selected LLM provider must be non-empty and at most 200 characters")
		}
	}
	if c.Environment == "production" {
		if len(c.BackupKey) != 32 {
			return c, errors.New("production requires a 32-byte CLIPMIND_BACKUP_KEY")
		}
		if c.AuthDisabled || c.AuthToken == "" {
			return c, errors.New("production requires authentication")
		}
	}
	if len(c.BackupKey) != 0 && len(c.BackupKey) != 32 {
		return c, errors.New("backup key must be exactly 32 bytes")
	}
	return c, nil
}

func get(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}
func boolean(k string, d bool) bool {
	v := os.Getenv(k)
	if v == "" {
		return d
	}
	b, e := strconv.ParseBool(v)
	if e != nil {
		return d
	}
	return b
}
func duration(k string, d time.Duration) time.Duration {
	v := os.Getenv(k)
	if v == "" {
		return d
	}
	x, e := time.ParseDuration(v)
	if e != nil {
		return d
	}
	return x
}
