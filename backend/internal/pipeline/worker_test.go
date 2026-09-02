package pipeline

import (
	"clipmind/backend/internal/domain"
	"clipmind/backend/internal/llm"
	"clipmind/backend/internal/metrics"
	"clipmind/backend/internal/store"
	"context"
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
