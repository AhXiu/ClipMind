package main

import (
	"clipmind/backend/internal/books"
	"clipmind/backend/internal/config"
	"clipmind/backend/internal/httpapi"
	"clipmind/backend/internal/knowledge"
	"clipmind/backend/internal/llm"
	"clipmind/backend/internal/metrics"
	"clipmind/backend/internal/pipeline"
	"clipmind/backend/internal/security"
	"clipmind/backend/internal/service"
	"clipmind/backend/internal/store"
	"clipmind/backend/internal/syncer"
	"context"
	"crypto/sha256"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

func main() {
	cfg, e := config.Load()
	if e != nil {
		log.Printf("configuration invalid: %v", e)
		os.Exit(1)
	}
	key := cfg.BackupKey
	if len(key) == 0 {
		x := sha256.Sum256([]byte("clipmind-development-key-not-for-production"))
		key = x[:]
	}
	repo, e := store.OpenFile(filepath.Join(cfg.DataDir, "store.json"))
	if e != nil {
		log.Printf("repository open failed: %v", e)
		os.Exit(1)
	}
	backup, e := security.NewEncryptedFileBackup(filepath.Join(cfg.DataDir, "raw-backups"), key)
	if e != nil {
		log.Printf("backup initialization failed: %v", e)
		os.Exit(1)
	}
	var provider llm.Provider
	if cfg.LLMProvider == "deterministic" {
		provider = llm.Deterministic{}
	} else {
		compatible := llm.NewOpenAICompatible(cfg.LLMProvider, cfg.LLMBaseURL, cfg.LLMAPIKey, cfg.LLMModel, cfg.LLMTimeout)
		if cfg.LLMProvider == "openrouter" {
			compatible.HTTPReferer = cfg.OpenRouterHTTPReferer
			compatible.Title = cfg.OpenRouterTitle
		}
		provider = compatible
	}
	m := metrics.New()
	capture := service.New(repo, security.NewSafeFilter(256*1024), backup)
	cards := service.Cards{Repo: repo, Sync: syncer.Obsidian{Vault: cfg.VaultDir}}
	bookVerifier := books.NewOpenLibrary()
	bookVerifier.Metrics = m
	worker := &pipeline.Worker{Repo: repo, LLM: provider, Books: bookVerifier, Metrics: m, Interval: cfg.WorkerInterval, MaxAttempts: 3, Cards: cards}
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()
	go worker.Run(ctx)
	api := &httpapi.Server{Capture: capture, Cards: cards, Metrics: m, AuthDisabled: cfg.AuthDisabled, Token: cfg.AuthToken, Log: log.Default()}
	if completer, ok := provider.(knowledge.Completer); ok {
		api.Knowledge = &knowledge.Service{Provider: completer}
	}
	srv := &http.Server{Addr: cfg.Addr, Handler: api.Handler(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 60 * time.Second}
	go func() {
		<-ctx.Done()
		shutdownCtx, c := context.WithTimeout(context.Background(), 10*time.Second)
		defer c()
		_ = srv.Shutdown(shutdownCtx)
	}()
	log.Printf("server starting addr=%s environment=%s", cfg.Addr, cfg.Environment)
	if e = srv.ListenAndServe(); e != nil && e != http.ErrServerClosed {
		log.Printf("server failed: %v", e)
		os.Exit(1)
	}
}
