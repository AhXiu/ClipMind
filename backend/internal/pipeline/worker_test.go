package pipeline

import (
	"bytes"
	"clipmind/backend/internal/domain"
	"clipmind/backend/internal/llm"
	"clipmind/backend/internal/metrics"
	"clipmind/backend/internal/security"
	"clipmind/backend/internal/service"
	"clipmind/backend/internal/store"
	"clipmind/backend/internal/syncer"
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestReanalysisCreatesVersionWithoutOverwritingVaultBeforeConfirmation(t *testing.T) {
	dir := t.TempDir()
	repo, err := store.OpenFile(filepath.Join(dir, "store.json"))
	if err != nil {
		t.Fatal(err)
	}
	backup, err := security.NewEncryptedFileBackup(filepath.Join(dir, "backups"), bytes.Repeat([]byte{1}, 32))
	if err != nil {
		t.Fatal(err)
	}
	svc := service.New(repo, security.NewSafeFilter(1000), backup)
	vault := filepath.Join(dir, "vault")
	cards := service.Cards{Repo: repo, Sync: syncer.Obsidian{Vault: vault}}
	w := Worker{Repo: repo, LLM: llm.Deterministic{}, Books: noBooks{}, Metrics: metrics.New(), Cards: cards}
	result, err := svc.Ingest("initial", []service.CaptureInput{{ClientCaptureID: "original", RawText: "Go original excerpt", Mode: "auto"}})
	if err != nil {
		t.Fatal(err)
	}
	id := result.Accepted[0].CardID
	if err = w.RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(vault, id+".md")
	original, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = svc.AnalyzeAgain(id, "new-job", service.CaptureInput{ClientCaptureID: "new-task", RawText: "Go revised excerpt", Mode: "auto"}); err != nil {
		t.Fatal(err)
	}
	if err = w.RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	card, _ := repo.GetCard(id)
	versions, _ := repo.ListVersions(id)
	if card.Status != domain.StatusAwaitingConfirm || len(versions) != 2 || versions[1].Number != 2 {
		t.Fatalf("invalid regeneration: card=%+v versions=%d", card, len(versions))
	}
	unchanged, err := os.ReadFile(path)
	if err != nil || !bytes.Equal(original, unchanged) {
		t.Fatal("unconfirmed result overwrote existing note", err)
	}
	if _, err := cards.Confirm(id); err != nil {
		t.Fatal(err)
	}
	updated, err := os.ReadFile(path)
	if err != nil || bytes.Equal(original, updated) {
		t.Fatal("confirmed version was not written", err)
	}
	files, _ := filepath.Glob(filepath.Join(vault, "*.md"))
	if len(files) != 1 {
		t.Fatal("regeneration created a duplicate Obsidian card")
	}
}

type noBooks struct{}

func (noBooks) Verify(_ context.Context, b []domain.BookCandidate) []domain.BookCandidate { return b }
func TestPipelineStructuredOutput(t *testing.T) {
	r, e := store.OpenFile(filepath.Join(t.TempDir(), "store.json"))
	if e != nil {
		t.Fatal(e)
	}
	now := time.Now()
	c := domain.Capture{ID: "cap_1", ClientCaptureID: "c1", Text: "Go API design", Status: domain.StatusPersisted, CreatedAt: now, CardID: "card_1", StatusHistory: []domain.StatusEvent{{Status: domain.StatusReceived, At: now}, {Status: domain.StatusFilteredPass, At: now}, {Status: domain.StatusPersisted, At: now}}}
	if _, _, e = r.CreateCapture(c); e != nil {
		t.Fatal(e)
	}
	if e = r.CreateCard(domain.Card{ID: "card_1", CaptureID: "cap_1", Status: domain.StatusPersisted, CreatedAt: now, UpdatedAt: now}); e != nil {
		t.Fatal(e)
	}
	w := Worker{Repo: r, LLM: llm.Deterministic{}, Books: noBooks{}, Metrics: metrics.New()}
	if e = w.RunOnce(context.Background()); e != nil {
		t.Fatal(e)
	}
	card, e := r.GetCard("card_1")
	if e != nil {
		t.Fatal(e)
	}
	if card.Status != domain.StatusAwaitingConfirm {
		t.Fatalf("status=%s", card.Status)
	}
	vs, e := r.ListVersions(card.ID)
	if e != nil || len(vs) != 1 {
		t.Fatalf("versions=%d err=%v", len(vs), e)
	}
	v := vs[0]
	if !domain.AllowedTags[v.PrimaryTag] || v.Interpretation.Summary == "" || v.Interpretation.Insight == "" || v.Interpretation.Action == "" || v.Markdown == "" {
		t.Fatalf("invalid structured version: %+v", v)
	}
}

func TestRunOnceRecoversStaleAIRunning(t *testing.T) {
	r, e := store.OpenFile(filepath.Join(t.TempDir(), "store.json"))
	if e != nil {
		t.Fatal(e)
	}
	old := time.Now().Add(-time.Hour)
	c := domain.Capture{ID: "cap-stale", ClientCaptureID: "stale", Text: "Go recovery", Status: domain.StatusAIRunning, CreatedAt: old, CardID: "card-stale", StatusHistory: []domain.StatusEvent{{Status: domain.StatusAIRunning, At: old}}}
	if _, _, e = r.CreateCapture(c); e != nil {
		t.Fatal(e)
	}
	if e = r.CreateCard(domain.Card{ID: "card-stale", CaptureID: c.ID, Status: domain.StatusAIRunning, CreatedAt: old, UpdatedAt: old}); e != nil {
		t.Fatal(e)
	}
	m := metrics.New()
	w := Worker{Repo: r, LLM: llm.Deterministic{}, Books: noBooks{}, Metrics: m, StaleAfter: time.Minute}
	if e = w.RunOnce(context.Background()); e != nil {
		t.Fatal(e)
	}
	got, e := r.GetCapture(c.ID)
	if e != nil {
		t.Fatal(e)
	}
	if got.Status != domain.StatusAwaitingConfirm {
		t.Fatalf("status=%s", got.Status)
	}
	if m.Snapshot()["pipeline_recovered_total"] != 1 {
		t.Fatal("recovery metric not incremented")
	}
}

type flakySync struct {
	failures int
	delegate syncer.Adapter
}

func (s *flakySync) Write(cardID string, markdown []byte) error {
	if s.failures > 0 {
		s.failures--
		return errors.New("sensitive filesystem detail")
	}
	return s.delegate.Write(cardID, markdown)
}

type failingLLM struct{}

func (failingLLM) Analyze(context.Context, string) (llm.Result, error) {
	return llm.Result{}, errors.New("sensitive provider detail")
}

func seedCapture(t *testing.T, r store.Repository, mode, suffix string) domain.Capture {
	t.Helper()
	now := time.Now().UTC()
	capture := domain.Capture{
		ID:              "cap-" + suffix,
		ClientCaptureID: "client-" + suffix,
		Text:            "Go API design",
		Mode:            mode,
		Status:          domain.StatusPersisted,
		CreatedAt:       now,
		CardID:          "card-" + suffix,
		StatusHistory: []domain.StatusEvent{
			{Status: domain.StatusReceived, At: now},
			{Status: domain.StatusFilteredPass, At: now},
			{Status: domain.StatusPersisted, At: now},
		},
	}
	if _, _, err := r.CreateCapture(capture); err != nil {
		t.Fatal(err)
	}
	if err := r.CreateCard(domain.Card{ID: capture.CardID, CaptureID: capture.ID, Status: domain.StatusPersisted, CreatedAt: now, UpdatedAt: now}); err != nil {
		t.Fatal(err)
	}
	return capture
}

func TestAutoPublishesAndSyncsActiveVersion(t *testing.T) {
	dir := t.TempDir()
	r, err := store.OpenFile(filepath.Join(dir, "store.json"))
	if err != nil {
		t.Fatal(err)
	}
	capture := seedCapture(t, r, "AUTO", "auto")
	vault := filepath.Join(dir, "vault")
	cards := service.Cards{Repo: r, Sync: syncer.Obsidian{Vault: vault}}
	w := Worker{Repo: r, LLM: llm.Deterministic{}, Books: noBooks{}, Metrics: metrics.New(), Cards: cards}
	if err = w.RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	gotCapture, _ := r.GetCapture(capture.ID)
	card, _ := r.GetCard(capture.CardID)
	if gotCapture.Status != domain.StatusSynced || card.Status != domain.StatusSynced {
		t.Fatalf("capture=%s card=%s", gotCapture.Status, card.Status)
	}
	if gotCapture.LastError != "" || card.LastError != "" {
		t.Fatalf("last errors not cleared: capture=%q card=%q", gotCapture.LastError, card.LastError)
	}
	version, err := r.GetVersion(card.ActiveVersionID)
	if err != nil {
		t.Fatal(err)
	}
	markdown, err := os.ReadFile(filepath.Join(vault, card.ID+".md"))
	if err != nil || string(markdown) != version.Markdown {
		t.Fatalf("vault markdown mismatch: err=%v", err)
	}
	if err = w.RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	versions, _ := r.ListVersions(card.ID)
	if len(versions) != 1 {
		t.Fatalf("versions after repeated run=%d", len(versions))
	}
}

func TestConfirmWaitsWithoutWritingVault(t *testing.T) {
	dir := t.TempDir()
	r, err := store.OpenFile(filepath.Join(dir, "store.json"))
	if err != nil {
		t.Fatal(err)
	}
	capture := seedCapture(t, r, "confirm", "confirm")
	vault := filepath.Join(dir, "vault")
	cards := service.Cards{Repo: r, Sync: syncer.Obsidian{Vault: vault}}
	w := Worker{Repo: r, LLM: llm.Deterministic{}, Books: noBooks{}, Metrics: metrics.New(), Cards: cards}
	if err = w.RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	gotCapture, _ := r.GetCapture(capture.ID)
	card, _ := r.GetCard(capture.CardID)
	if gotCapture.Status != domain.StatusAwaitingConfirm || card.Status != domain.StatusAwaitingConfirm {
		t.Fatalf("capture=%s card=%s", gotCapture.Status, card.Status)
	}
	if _, err = os.Stat(filepath.Join(vault, card.ID+".md")); !os.IsNotExist(err) {
		t.Fatalf("vault file should not exist: %v", err)
	}
}

func TestAutoSyncFailureRetriesWithoutNewVersion(t *testing.T) {
	dir := t.TempDir()
	r, err := store.OpenFile(filepath.Join(dir, "store.json"))
	if err != nil {
		t.Fatal(err)
	}
	capture := seedCapture(t, r, "auto", "retry")
	flaky := &flakySync{failures: 1, delegate: syncer.Obsidian{Vault: filepath.Join(dir, "vault")}}
	cards := service.Cards{Repo: r, Sync: flaky}
	w := Worker{Repo: r, LLM: llm.Deterministic{}, Books: noBooks{}, Metrics: metrics.New(), Cards: cards}
	if err = w.RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	gotCapture, _ := r.GetCapture(capture.ID)
	card, _ := r.GetCard(capture.CardID)
	if gotCapture.Status != domain.StatusPublished || card.Status != domain.StatusPublished {
		t.Fatalf("capture=%s card=%s", gotCapture.Status, card.Status)
	}
	if gotCapture.LastError != "sync failed" || card.LastError != "sync failed" {
		t.Fatalf("unexpected last errors: capture=%q card=%q", gotCapture.LastError, card.LastError)
	}
	active := card.ActiveVersionID
	versions, _ := r.ListVersions(card.ID)
	if len(versions) != 1 {
		t.Fatalf("versions=%d", len(versions))
	}
	if err = w.RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	gotCapture, _ = r.GetCapture(capture.ID)
	card, _ = r.GetCard(capture.CardID)
	versions, _ = r.ListVersions(card.ID)
	if gotCapture.Status != domain.StatusSynced || card.Status != domain.StatusSynced || card.ActiveVersionID != active || len(versions) != 1 {
		t.Fatalf("capture=%s card=%s active=%q versions=%d", gotCapture.Status, card.Status, card.ActiveVersionID, len(versions))
	}
}

func TestAIFailureDoesNotPublishOrCreateVersions(t *testing.T) {
	r, err := store.OpenFile(filepath.Join(t.TempDir(), "store.json"))
	if err != nil {
		t.Fatal(err)
	}
	capture := seedCapture(t, r, "auto", "ai-fail")
	w := Worker{Repo: r, LLM: failingLLM{}, Books: noBooks{}, Metrics: metrics.New(), MaxAttempts: 1}
	if err = w.RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err = w.RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	gotCapture, _ := r.GetCapture(capture.ID)
	card, _ := r.GetCard(capture.CardID)
	versions, _ := r.ListVersions(card.ID)
	if gotCapture.Status != domain.StatusAIFailed || card.Status != domain.StatusAIFailed || card.ActiveVersionID != "" || len(versions) != 0 {
		t.Fatalf("capture=%s card=%s active=%q versions=%d", gotCapture.Status, card.Status, card.ActiveVersionID, len(versions))
	}
}

func TestRunOnceRecoversStaleSyncingAndRetries(t *testing.T) {
	dir := t.TempDir()
	r, err := store.OpenFile(filepath.Join(dir, "store.json"))
	if err != nil {
		t.Fatal(err)
	}
	old := time.Now().UTC().Add(-time.Hour)
	capture := domain.Capture{
		ID: "cap-stale-sync", ClientCaptureID: "stale-sync", Mode: "auto", Status: domain.StatusSyncing,
		CreatedAt: old, CardID: "card-stale-sync", StatusHistory: []domain.StatusEvent{{Status: domain.StatusSyncing, At: old}},
	}
	if _, _, err = r.CreateCapture(capture); err != nil {
		t.Fatal(err)
	}
	version, err := r.AddVersion(domain.CardVersion{ID: "ver-stale-sync", CardID: capture.CardID, Markdown: "stale markdown", CreatedAt: old})
	if err != nil {
		t.Fatal(err)
	}
	card := domain.Card{ID: capture.CardID, CaptureID: capture.ID, Status: domain.StatusSyncing, VersionIDs: []string{version.ID}, ActiveVersionID: version.ID, CreatedAt: old, UpdatedAt: old}
	if err = r.CreateCard(card); err != nil {
		t.Fatal(err)
	}
	m := metrics.New()
	cards := service.Cards{Repo: r, Sync: syncer.Obsidian{Vault: filepath.Join(dir, "vault")}}
	w := Worker{Repo: r, Metrics: m, Cards: cards, StaleAfter: time.Minute}
	if err = w.RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	gotCapture, _ := r.GetCapture(capture.ID)
	gotCard, _ := r.GetCard(card.ID)
	versions, _ := r.ListVersions(card.ID)
	if gotCapture.Status != domain.StatusSynced || gotCard.Status != domain.StatusSynced || len(versions) != 1 {
		t.Fatalf("capture=%s card=%s versions=%d", gotCapture.Status, gotCard.Status, len(versions))
	}
	if m.Snapshot()["pipeline_recovered_total"] != 1 {
		t.Fatal("stale syncing recovery metric not incremented")
	}
}

type countingLLM struct{ calls int }

func (p *countingLLM) Analyze(context.Context, string) (llm.Result, error) {
	p.calls++
	return llm.Result{}, errors.New("default provider must be skipped")
}

type countingBooks struct{ calls int }

func (v *countingBooks) Verify(_ context.Context, candidates []domain.BookCandidate) []domain.BookCandidate {
	v.calls++
	for i := range candidates {
		candidates[i].Verified = true
		candidates[i].OpenLibraryKey = "/works/OL1W"
	}
	return candidates
}

func TestClientAnalysisSkipsDefaultProviderAndRecordsSource(t *testing.T) {
	for _, source := range []string{"ark", "openrouter", "kimi", "glm", "openai"} {
		t.Run(source, func(t *testing.T) { testClientAnalysisSource(t, source) })
	}
}

func testClientAnalysisSource(t *testing.T, source string) {
	t.Helper()
	repo, err := store.OpenFile(filepath.Join(t.TempDir(), "store.json"))
	if err != nil {
		t.Fatal(err)
	}
	capture := seedCapture(t, repo, "confirm", "byok")
	capture.ClientAnalysis = &domain.ClientAnalysis{
		Provider: source, Model: "test-model", PrimaryTag: "认知",
		Interpretation: domain.Interpretation{Summary: "客户端总结", Insight: "客户端洞察", Action: "客户端行动"},
		Books:          []domain.ClientBook{{Title: "Thinking", Author: "Author"}},
	}
	if err = repo.UpdateCapture(capture); err != nil {
		t.Fatal(err)
	}
	provider := &countingLLM{}
	verifier := &countingBooks{}
	worker := Worker{Repo: repo, LLM: provider, Books: verifier, Metrics: metrics.New()}
	if err = worker.RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if provider.calls != 0 || verifier.calls != 1 {
		t.Fatalf("default calls=%d, book verifier calls=%d", provider.calls, verifier.calls)
	}
	versions, err := repo.ListVersions(capture.CardID)
	if err != nil || len(versions) != 1 {
		t.Fatalf("versions=%d err=%v", len(versions), err)
	}
	version := versions[0]
	if version.LLMProvider != source || version.LLMModel != "test-model" || version.Interpretation.Summary != "客户端总结" || len(version.Books) != 1 || !version.Books[0].Verified {
		t.Fatalf("unexpected client analysis version: %+v", version)
	}
}

func TestDeterministicVersionRecordsSource(t *testing.T) {
	repo, err := store.OpenFile(filepath.Join(t.TempDir(), "store.json"))
	if err != nil {
		t.Fatal(err)
	}
	capture := seedCapture(t, repo, "confirm", "source")
	worker := Worker{Repo: repo, LLM: llm.Deterministic{}, Books: noBooks{}, Metrics: metrics.New()}
	if err = worker.RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	versions, _ := repo.ListVersions(capture.CardID)
	if len(versions) != 1 || versions[0].LLMProvider != "deterministic" || versions[0].LLMModel != "deterministic" {
		t.Fatalf("deterministic source missing: %+v", versions)
	}
}
