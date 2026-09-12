package service

import (
	"bytes"
	"clipmind/backend/internal/domain"
	"clipmind/backend/internal/security"
	"clipmind/backend/internal/store"
	"crypto/sha256"
	"encoding/hex"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestBatchIdempotencyFilteringAndRestart(t *testing.T) {
	dir := t.TempDir()
	storePath := filepath.Join(dir, "store.json")
	r, e := store.OpenFile(storePath)
	if e != nil {
		t.Fatal(e)
	}
	b, e := security.NewEncryptedFileBackup(filepath.Join(dir, "backup"), bytes.Repeat([]byte{1}, 32))
	if e != nil {
		t.Fatal(e)
	}
	s := New(r, security.NewSafeFilter(1000), b)
	items := []CaptureInput{{ClientCaptureID: "client-1", RawText: "Go API design", Mode: "auto"}, {ClientCaptureID: "client-2", RawText: "password=very-secret", Mode: "confirm"}}
	first, e := s.Ingest("batch-key", items)
	if e != nil {
		t.Fatal(e)
	}
	if len(first.Accepted) != 1 || len(first.Rejected) != 1 {
		t.Fatalf("unexpected result: %+v", first)
	}
	again, e := s.Ingest("batch-key", items)
	if e != nil {
		t.Fatal(e)
	}
	if again.Accepted[0].CaptureID != first.Accepted[0].CaptureID {
		t.Fatal("idempotency receipt changed")
	}
	reopened, e := store.OpenFile(storePath)
	if e != nil {
		t.Fatal(e)
	}
	receipt, ok, e := reopened.GetReceipt("batch-key")
	if e != nil || !ok || len(receipt) == 0 {
		t.Fatalf("receipt did not survive restart: %v", e)
	}
}

func TestCaptureInputContractValidation(t *testing.T) {
	dir := t.TempDir()
	r, err := store.OpenFile(filepath.Join(dir, "store.json"))
	if err != nil {
		t.Fatal(err)
	}
	backup, err := security.NewEncryptedFileBackup(filepath.Join(dir, "backup"), bytes.Repeat([]byte{3}, 32))
	if err != nil {
		t.Fatal(err)
	}
	svc := New(r, security.NewSafeFilter(1000), backup)
	raw := "Android contract text"
	sum := sha256.Sum256([]byte(raw))
	hash := hex.EncodeToString(sum[:])
	capturedAt := time.Date(2026, 9, 3, 1, 17, 0, 0, time.FixedZone("CST", 8*60*60))
	items := []CaptureInput{
		{ClientCaptureID: "valid", RawText: raw, TextSHA256: hash, SourceURL: "https://example.com/article", Mode: "confirm", CapturedAt: capturedAt},
		{ClientCaptureID: "bad-mode", RawText: raw, Mode: "manual"},
		{ClientCaptureID: "missing-mode", RawText: raw},
		{ClientCaptureID: "bad-hash", RawText: raw, TextSHA256: strings.Repeat("0", 64), Mode: "auto"},
		{ClientCaptureID: "uppercase-hash", RawText: raw, TextSHA256: strings.ToUpper(hash), Mode: "auto"},
	}
	result, err := svc.Ingest("contract-fields", items)
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Accepted) != 1 || len(result.Rejected) != 4 {
		t.Fatalf("unexpected result: %+v", result)
	}
	wantCodes := []string{"invalid_mode", "invalid_mode", "invalid_text_sha256", "invalid_text_sha256"}
	for i, code := range wantCodes {
		if result.Rejected[i].Code != code {
			t.Fatalf("rejected[%d].code=%q, want %q", i, result.Rejected[i].Code, code)
		}
	}
	stored, err := r.GetCapture(result.Accepted[0].CaptureID)
	if err != nil {
		t.Fatal(err)
	}
	if stored.TextSHA256 != hash || stored.SourceURL != "https://example.com/article" || stored.Mode != "confirm" || !stored.CapturedAt.Equal(capturedAt) {
		t.Fatalf("contract fields were not retained: %+v", stored)
	}
}

func TestBatchPersistsRepositoryOnce(t *testing.T) {
	dir := t.TempDir()
	repo, err := store.OpenFile(filepath.Join(dir, "store.json"))
	if err != nil {
		t.Fatal(err)
	}
	backup, err := security.NewEncryptedFileBackup(filepath.Join(dir, "backup"), bytes.Repeat([]byte{5}, 32))
	if err != nil {
		t.Fatal(err)
	}
	svc := New(repo, security.NewSafeFilter(1000), backup)
	items := make([]CaptureInput, 100)
	for i := range items {
		items[i] = CaptureInput{ClientCaptureID: ID("client_"), RawText: "safe text", Mode: "auto"}
	}
	result, err := svc.Ingest("one-write", items)
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Accepted) != 100 {
		t.Fatalf("accepted=%d", len(result.Accepted))
	}
	if got := repo.PersistCount(); got != 1 {
		t.Fatalf("repository persist count=%d, want 1", got)
	}
}

func TestConcurrentSameIdempotencyKeyReturnsSameReceipt(t *testing.T) {
	dir := t.TempDir()
	repo, err := store.OpenFile(filepath.Join(dir, "store.json"))
	if err != nil {
		t.Fatal(err)
	}
	backup, err := security.NewEncryptedFileBackup(filepath.Join(dir, "backup"), bytes.Repeat([]byte{6}, 32))
	if err != nil {
		t.Fatal(err)
	}
	svc := New(repo, security.NewSafeFilter(1000), backup)
	const callers = 24
	results := make(chan BatchResult, callers)
	errs := make(chan error, callers)
	var wg sync.WaitGroup
	for i := 0; i < callers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			result, err := svc.Ingest("shared-key", []CaptureInput{{ClientCaptureID: "same-client", RawText: "safe concurrent text", Mode: "auto"}})
			results <- result
			errs <- err
		}()
	}
	wg.Wait()
	close(results)
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatal(err)
		}
	}
	var captureID string
	for result := range results {
		if len(result.Accepted) != 1 {
			t.Fatalf("unexpected result: %+v", result)
		}
		if captureID == "" {
			captureID = result.Accepted[0].CaptureID
		}
		if result.Accepted[0].CaptureID != captureID || result.Accepted[0].Duplicate {
			t.Fatalf("receipts differ: %+v", result)
		}
	}
	if got := repo.PersistCount(); got != 1 {
		t.Fatalf("repository persist count=%d, want 1", got)
	}
}

func validClientAnalysis() *domain.ClientAnalysis {
	return &domain.ClientAnalysis{
		Provider: "openrouter", Model: "vendor/model", PrimaryTag: "技术",
		Interpretation: domain.Interpretation{Summary: "总结", Insight: "洞察", Action: "行动"},
		Books:          []domain.ClientBook{{Title: "The Go Programming Language", Author: "Alan Donovan"}},
	}
}

func TestClientAnalysisAcceptedPersistedAndInvalidItemRejected(t *testing.T) {
	dir := t.TempDir()
	repo, err := store.OpenFile(filepath.Join(dir, "store.json"))
	if err != nil {
		t.Fatal(err)
	}
	backup, err := security.NewEncryptedFileBackup(filepath.Join(dir, "backup"), bytes.Repeat([]byte{7}, 32))
	if err != nil {
		t.Fatal(err)
	}
	svc := New(repo, security.NewSafeFilter(1000), backup)
	invalid := validClientAnalysis()
	invalid.Provider = "unknown"
	result, err := svc.Ingest("client-analysis", []CaptureInput{
		{ClientCaptureID: "valid-analysis", RawText: "safe", Mode: "confirm", ClientAnalysis: validClientAnalysis()},
		{ClientCaptureID: "invalid-analysis", RawText: "safe", Mode: "confirm", ClientAnalysis: invalid},
		{ClientCaptureID: "legacy", RawText: "safe legacy request", Mode: "auto"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Accepted) != 2 || len(result.Rejected) != 1 || result.Rejected[0].Code != "invalid_client_analysis" {
		t.Fatalf("unexpected item results: %+v", result)
	}
	stored, err := repo.GetCapture(result.Accepted[0].CaptureID)
	if err != nil || stored.ClientAnalysis == nil || stored.ClientAnalysis.Model != "vendor/model" {
		t.Fatalf("client analysis not persisted: capture=%+v err=%v", stored, err)
	}
}

func TestClientAnalysisLimits(t *testing.T) {
	tests := []struct {
		name string
		edit func(*domain.ClientAnalysis)
	}{
		{"empty model", func(a *domain.ClientAnalysis) { a.Model = " " }},
		{"long model", func(a *domain.ClientAnalysis) { a.Model = strings.Repeat("m", 201) }},
		{"bad tag", func(a *domain.ClientAnalysis) { a.PrimaryTag = "其他" }},
		{"empty summary", func(a *domain.ClientAnalysis) { a.Interpretation.Summary = "" }},
		{"long insight", func(a *domain.ClientAnalysis) { a.Interpretation.Insight = strings.Repeat("洞", 4001) }},
		{"too many books", func(a *domain.ClientAnalysis) { a.Books = make([]domain.ClientBook, 11) }},
		{"empty title", func(a *domain.ClientAnalysis) { a.Books[0].Title = "" }},
		{"long author", func(a *domain.ClientAnalysis) { a.Books[0].Author = strings.Repeat("a", 201) }},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			a := validClientAnalysis()
			test.edit(a)
			if err := domain.ValidateClientAnalysis(*a); err == nil {
				t.Fatal("expected validation failure")
			}
		})
	}
}
