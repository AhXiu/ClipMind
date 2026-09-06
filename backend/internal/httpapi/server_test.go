package httpapi

import (
	"bytes"
	"clipmind/backend/internal/metrics"
	"clipmind/backend/internal/security"
	"clipmind/backend/internal/service"
	"clipmind/backend/internal/store"
	"clipmind/backend/internal/syncer"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func TestBatchAPIAuthIdempotencyAndNoRawLog(t *testing.T) {
	dir := t.TempDir()
	repo, e := store.OpenFile(filepath.Join(dir, "store.json"))
	if e != nil {
		t.Fatal(e)
	}
	backup, e := security.NewEncryptedFileBackup(filepath.Join(dir, "backups"), bytes.Repeat([]byte{2}, 32))
	if e != nil {
		t.Fatal(e)
	}
	capture := service.New(repo, security.NewSafeFilter(1000), backup)
	var logs bytes.Buffer
	s := Server{Capture: capture, Cards: service.Cards{Repo: repo, Sync: syncer.Obsidian{Vault: filepath.Join(dir, "vault")}}, Metrics: metrics.New(), Token: "token", Log: log.New(&logs, "", 0)}
	raw := "private but safe note"
	sum := sha256.Sum256([]byte(raw))
	body := fmt.Sprintf(`{"captures":[{"client_capture_id":"android-1","raw_text":%q,"text_sha256":"%s","source_url":"https://example.com/post","mode":"auto","captured_at":"2026-09-03T01:17:00+08:00"}]}`, raw, hex.EncodeToString(sum[:]))
	req := httptest.NewRequest(http.MethodPost, "/v1/captures:batch", strings.NewReader(body))
	req.Header.Set("Authorization", "Bearer token")
	req.Header.Set("Idempotency-Key", "same")
	rr := httptest.NewRecorder()
	s.Handler().ServeHTTP(rr, req)
	if rr.Code != 202 {
		b, _ := io.ReadAll(rr.Body)
		t.Fatalf("status=%d body=%s", rr.Code, b)
	}
	first := rr.Body.String()
	req = httptest.NewRequest(http.MethodPost, "/v1/captures:batch", strings.NewReader(body))
	req.Header.Set("Authorization", "Bearer token")
	req.Header.Set("Idempotency-Key", "same")
	rr = httptest.NewRecorder()
	s.Handler().ServeHTTP(rr, req)
	if rr.Code != 202 || rr.Body.String() != first {
		t.Fatal("idempotent HTTP response changed")
	}
	if strings.Contains(logs.String(), "private but safe note") {
		t.Fatal("raw text leaked into logs")
	}
}

func TestBatchAPIKeepsAuthIdempotencyAndRejectsUnknownFields(t *testing.T) {
	dir := t.TempDir()
	repo, err := store.OpenFile(filepath.Join(dir, "store.json"))
	if err != nil {
		t.Fatal(err)
	}
	backup, err := security.NewEncryptedFileBackup(filepath.Join(dir, "backups"), bytes.Repeat([]byte{4}, 32))
	if err != nil {
		t.Fatal(err)
	}
	s := Server{
		Capture: service.New(repo, security.NewSafeFilter(1000), backup),
		Cards:   service.Cards{Repo: repo, Sync: syncer.Obsidian{Vault: filepath.Join(dir, "vault")}},
		Metrics: metrics.New(), Token: "token", Log: log.New(io.Discard, "", 0),
	}
	validBody := `{"captures":[{"client_capture_id":"android-2","raw_text":"safe","mode":"confirm"}]}`

	req := httptest.NewRequest(http.MethodPost, "/v1/captures:batch", strings.NewReader(validBody))
	req.Header.Set("Idempotency-Key", "auth-check")
	rr := httptest.NewRecorder()
	s.Handler().ServeHTTP(rr, req)
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("missing Authorization status=%d, want %d", rr.Code, http.StatusUnauthorized)
	}

	req = httptest.NewRequest(http.MethodPost, "/v1/captures:batch", strings.NewReader(validBody))
	req.Header.Set("Authorization", "Bearer token")
	rr = httptest.NewRecorder()
	s.Handler().ServeHTTP(rr, req)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("missing Idempotency-Key status=%d, want %d", rr.Code, http.StatusBadRequest)
	}

	unknownBody := `{"captures":[{"client_capture_id":"android-3","raw_text":"safe","mode":"auto","unknown_field":true}]}`
	req = httptest.NewRequest(http.MethodPost, "/v1/captures:batch", strings.NewReader(unknownBody))
	req.Header.Set("Authorization", "Bearer token")
	req.Header.Set("Idempotency-Key", "unknown-check")
	rr = httptest.NewRecorder()
	s.Handler().ServeHTTP(rr, req)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("unknown field status=%d, want %d", rr.Code, http.StatusBadRequest)
	}
}

func TestBatchAPIClientAnalysisValidationAndNoClientKey(t *testing.T) {
	dir := t.TempDir()
	repo, err := store.OpenFile(filepath.Join(dir, "store.json"))
	if err != nil {
		t.Fatal(err)
	}
	backup, err := security.NewEncryptedFileBackup(filepath.Join(dir, "backups"), bytes.Repeat([]byte{8}, 32))
	if err != nil {
		t.Fatal(err)
	}
	s := Server{Capture: service.New(repo, security.NewSafeFilter(1000), backup), Cards: service.Cards{Repo: repo}, Metrics: metrics.New(), AuthDisabled: true, Log: log.New(io.Discard, "", 0)}

	invalid := `{"captures":[{"client_capture_id":"bad-analysis","raw_text":"safe","mode":"confirm","client_analysis":{"provider":"openai","model":"m","primary_tag":"技术","interpretation":{"summary":"s","insight":"i","action":"a"},"books":[]}}]}`
	req := httptest.NewRequest(http.MethodPost, "/v1/captures:batch", strings.NewReader(invalid))
	req.Header.Set("Idempotency-Key", "invalid-analysis")
	rr := httptest.NewRecorder()
	s.Handler().ServeHTTP(rr, req)
	if rr.Code != http.StatusAccepted || !strings.Contains(rr.Body.String(), `"code":"invalid_client_analysis"`) {
		t.Fatalf("invalid client analysis should be item rejection: status=%d body=%s", rr.Code, rr.Body.String())
	}

	withKey := `{"captures":[{"client_capture_id":"key-attempt","raw_text":"safe","mode":"confirm","client_analysis":{"provider":"ark","model":"m","primary_tag":"技术","interpretation":{"summary":"s","insight":"i","action":"a"},"books":[],"api_key":"must-not-enter-backend"}}]}`
	req = httptest.NewRequest(http.MethodPost, "/v1/captures:batch", strings.NewReader(withKey))
	req.Header.Set("Idempotency-Key", "key-attempt")
	rr = httptest.NewRecorder()
	s.Handler().ServeHTTP(rr, req)
	if rr.Code != http.StatusBadRequest || strings.Contains(rr.Body.String(), "must-not-enter-backend") {
		t.Fatalf("client key field was not safely rejected: status=%d body=%s", rr.Code, rr.Body.String())
	}
}
