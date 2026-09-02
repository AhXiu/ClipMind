package config

import (
	"encoding/base64"
	"errors"
	"os"
	"strconv"
	"time"
)

type Config struct {
	Addr, DataDir, VaultDir, AuthToken, Environment       string
	AuthDisabled                                          bool
	BackupKey                                             []byte
	LLMProvider, OpenAIBaseURL, OpenAIAPIKey, OpenAIModel string
	LLMTimeout                                            time.Duration
	WorkerInterval                                        time.Duration
}

func Load() (Config, error) {
	c := Config{Addr: get("CLIPMIND_ADDR", ":8080"), DataDir: get("CLIPMIND_DATA_DIR", "./data"), VaultDir: get("CLIPMIND_VAULT_DIR", "./vault"), Environment: get("CLIPMIND_ENV", "development"), AuthToken: os.Getenv("CLIPMIND_AUTH_TOKEN"), LLMProvider: get("CLIPMIND_LLM_PROVIDER", "deterministic"), OpenAIBaseURL: get("OPENAI_BASE_URL", "https://api.openai.com/v1"), OpenAIAPIKey: os.Getenv("OPENAI_API_KEY"), OpenAIModel: get("OPENAI_MODEL", "gpt-4o-mini"), LLMTimeout: duration("CLIPMIND_LLM_TIMEOUT", 20*time.Second), WorkerInterval: duration("CLIPMIND_WORKER_INTERVAL", time.Second)}
	c.AuthDisabled = boolean("CLIPMIND_AUTH_DISABLED", c.Environment != "production")
	if s := os.Getenv("CLIPMIND_BACKUP_KEY"); s != "" {
		b, err := base64.StdEncoding.DecodeString(s)
		if err != nil {
			return c, errors.New("CLIPMIND_BACKUP_KEY must be base64")
		}
		c.BackupKey = b
	}
	if c.LLMProvider != "deterministic" && c.LLMProvider != "openai" {
		return c, errors.New("CLIPMIND_LLM_PROVIDER must be deterministic or openai")
	}
	if c.LLMProvider == "openai" && c.OpenAIAPIKey == "" {
		return c, errors.New("OPENAI_API_KEY is required for openai provider")
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
