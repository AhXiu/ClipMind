package service

import (
	"bytes"
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
