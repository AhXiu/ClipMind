package service

import (
	"clipmind/backend/internal/domain"
	"clipmind/backend/internal/store"
	"clipmind/backend/internal/syncer"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestConfirmAndRollback(t *testing.T) {
	r, e := store.OpenFile(filepath.Join(t.TempDir(), "store.json"))
	if e != nil {
		t.Fatal(e)
	}
	now := time.Now()
	cap := domain.Capture{ID: "cap", ClientCaptureID: "client", Status: domain.StatusAwaitingConfirm, CardID: "card", CreatedAt: now}
	if _, _, e = r.CreateCapture(cap); e != nil {
		t.Fatal(e)
	}
	v1 := domain.CardVersion{ID: "v1", CardID: "card", Number: 1, Markdown: "one", CreatedAt: now}
	v2 := domain.CardVersion{ID: "v2", CardID: "card", Number: 2, Markdown: "two", CreatedAt: now}
	if _, e = r.AddVersion(v1); e != nil {
		t.Fatal(e)
	}
	if _, e = r.AddVersion(v2); e != nil {
		t.Fatal(e)
	}
	card := domain.Card{ID: "card", CaptureID: "cap", Status: domain.StatusAwaitingConfirm, VersionIDs: []string{"v1", "v2"}, ActiveVersionID: "v2", CreatedAt: now, UpdatedAt: now}
	if e = r.CreateCard(card); e != nil {
		t.Fatal(e)
	}
	vault := t.TempDir()
	svc := Cards{Repo: r, Sync: syncer.Obsidian{Vault: vault}}
	got, e := svc.Confirm("card")
	if e != nil || got.Status != domain.StatusSynced {
		t.Fatalf("confirm: %+v %v", got, e)
	}
	got, e = svc.Rollback("card", "v1")
	if e != nil || got.ActiveVersionID != "v1" || got.Status != domain.StatusSynced {
		t.Fatalf("rollback: %+v %v", got, e)
	}
	b, e := os.ReadFile(filepath.Join(vault, "card.md"))
	if e != nil || string(b) != "one" {
		t.Fatalf("rollback output %q %v", b, e)
	}
}
