package service

import (
	"bytes"
	"clipmind/backend/internal/domain"
	"clipmind/backend/internal/security"
	"clipmind/backend/internal/store"
	"errors"
	"path/filepath"
	"sync"
	"testing"
)

func reanalysisFixture(t *testing.T, status domain.Status) (*Service, *store.FileRepository, domain.Card, domain.Capture) {
	t.Helper()
	dir := t.TempDir()
	repo, err := store.OpenFile(filepath.Join(dir, "store.json"))
	if err != nil {
		t.Fatal(err)
	}
	backup, err := security.NewEncryptedFileBackup(filepath.Join(dir, "backups"), bytes.Repeat([]byte{1}, 32))
	if err != nil {
		t.Fatal(err)
	}
	svc := New(repo, security.NewSafeFilter(1000), backup)
	result, err := svc.Ingest("initial", []CaptureInput{{ClientCaptureID: "initial-task", RawText: "Original reading excerpt", Mode: "auto"}})
	if err != nil {
		t.Fatal(err)
	}
	card, _ := repo.GetCard(result.Accepted[0].CardID)
	capture, _ := repo.GetCapture(card.CaptureID)
	version, err := repo.AddVersion(domain.CardVersion{ID: "old-version", CardID: card.ID, CleanText: capture.Text})
	if err != nil {
		t.Fatal(err)
	}
	card.ActiveVersionID, card.VersionIDs, card.Status = version.ID, []string{version.ID}, status
	capture.Status = status
	if err := repo.Transaction(func(tx store.Transaction) error {
		if err := tx.UpdateCapture(capture); err != nil {
			return err
		}
		return tx.UpdateCard(card)
	}); err != nil {
		t.Fatal(err)
	}
	return svc, repo, card, capture
}

func TestReanalysisKeepsCardVersionsAndRequiresConfirmation(t *testing.T) {
	svc, repo, before, oldCapture := reanalysisFixture(t, domain.StatusSynced)
	input := CaptureInput{ClientCaptureID: "new-task", RawText: "Revised reading excerpt", Mode: "auto"}
	result, err := svc.AnalyzeAgain(before.ID, "new-job", input)
	if err != nil || len(result.Accepted) != 1 {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	after, _ := repo.GetCard(before.ID)
	if after.CaptureID == before.CaptureID || after.Status != domain.StatusPersisted || after.ActiveVersionID != "" || len(after.VersionIDs) != 1 {
		t.Fatalf("invalid replacement: %+v", after)
	}
	capture, _ := repo.GetCapture(after.CaptureID)
	if capture.Mode != "confirm" || capture.Text != input.RawText {
		t.Fatal("reanalysis must use reviewed new content")
	}
	if _, err := repo.GetVersion("old-version"); err != nil {
		t.Fatal("old version lost")
	}
	if _, err := svc.Backup.(*security.EncryptedFileBackup).Read(oldCapture.BackupPath); err != nil {
		t.Fatal("original backup lost", err)
	}
	replay, err := svc.AnalyzeAgain(before.ID, "new-job", input)
	if err != nil || replay.Accepted[0].CaptureID != after.CaptureID {
		t.Fatal("retry created a new task", err)
	}
	if err := repo.UpdateCapture(oldCapture); !errors.Is(err, store.ErrStaleWork) {
		t.Fatalf("stale capture write accepted: %v", err)
	}
	if err := repo.UpdateCard(before); !errors.Is(err, store.ErrStaleWork) {
		t.Fatalf("stale card write accepted: %v", err)
	}
}

func TestReanalysisRejectsBusyCardWithoutPartialWrites(t *testing.T) {
	for _, status := range []domain.Status{domain.StatusPersisted, domain.StatusAIRunning, domain.StatusAISucceeded, domain.StatusPublished, domain.StatusSyncing} {
		t.Run(string(status), func(t *testing.T) {
			svc, repo, card, _ := reanalysisFixture(t, status)
			before := repo.PersistCount()
			_, err := svc.AnalyzeAgain(card.ID, "new-job", CaptureInput{ClientCaptureID: "new-task", RawText: "Revised reading excerpt"})
			if err == nil || repo.PersistCount() != before {
				t.Fatal("busy card was changed")
			}
			after, _ := repo.GetCard(card.ID)
			if after.CaptureID != card.CaptureID {
				t.Fatal("task changed")
			}
		})
	}
}

func TestReanalysisFiltersSensitiveEdits(t *testing.T) {
	svc, repo, card, _ := reanalysisFixture(t, domain.StatusAwaitingConfirm)
	result, err := svc.AnalyzeAgain(card.ID, "new-job", CaptureInput{ClientCaptureID: "new-task", RawText: "api_key=secret-value"})
	if err != nil || len(result.Accepted) != 0 || len(result.Rejected) != 1 {
		t.Fatalf("unsafe edit accepted: %+v %v", result, err)
	}
	after, _ := repo.GetCard(card.ID)
	if after.CaptureID != card.CaptureID {
		t.Fatal("filtered edit changed task")
	}
}

func TestConcurrentReanalysisIsOneTaskAndRetiresOldFailedTask(t *testing.T) {
	svc, repo, card, old := reanalysisFixture(t, domain.StatusAIFailed)
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := svc.AnalyzeAgain(card.ID, "new-job", CaptureInput{ClientCaptureID: "new-task", RawText: "Revised reading excerpt"})
			if err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	ready, err := repo.ListPipelineReady(10)
	if err != nil || len(ready) != 1 || ready[0].ID == old.ID {
		t.Fatalf("old task is still runnable: %+v %v", ready, err)
	}
}
