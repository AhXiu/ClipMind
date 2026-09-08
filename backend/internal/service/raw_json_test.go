package service

import (
	"bytes"
	"clipmind/backend/internal/security"
	"clipmind/backend/internal/store"
	"encoding/json"
	"path/filepath"
	"testing"
)

func TestRawJSONBackupRetainsOriginalWhitespaceAndMetadata(t *testing.T) {
	dir := t.TempDir()
	repo, err := store.OpenFile(filepath.Join(dir, "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	backup, err := security.NewEncryptedFileBackup(filepath.Join(dir, "raw"), bytes.Repeat([]byte{1}, 32))
	if err != nil {
		t.Fatal(err)
	}
	svc := New(repo, security.NewSafeFilter(10000), backup)
	input := CaptureInput{ClientCaptureID: "test", RawText: "  first line\n\nsecond line  ", SourceApp: "reader", SourceURL: "https://example.invalid/source", Mode: "confirm"}
	result, err := svc.Ingest("test", []CaptureInput{input})
	if err != nil {
		t.Fatal(err)
	}
	capture, err := repo.GetCapture(result.Accepted[0].CaptureID)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := backup.Read(capture.BackupPath)
	if err != nil {
		t.Fatal(err)
	}
	var restored CaptureInput
	if json.Unmarshal(raw, &restored) != nil || restored.RawText != input.RawText || restored.SourceURL != input.SourceURL || capture.Text != input.RawText {
		t.Fatal("original JSON or raw text changed")
	}
}
