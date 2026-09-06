package pipeline

import (
	"clipmind/backend/internal/domain"
	"clipmind/backend/internal/llm"
	"clipmind/backend/internal/metrics"
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
