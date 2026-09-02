package security

import (
	"bytes"
	"os"
	"testing"
)

func TestEncryptedBackup(t *testing.T) {
	key := bytes.Repeat([]byte{7}, 32)
	b, e := NewEncryptedFileBackup(t.TempDir(), key)
	if e != nil {
		t.Fatal(e)
	}
	raw := []byte("private clipboard value")
	p, e := b.Save("one", raw)
	if e != nil {
		t.Fatal(e)
	}
	cipher, e := os.ReadFile(p)
	if e != nil {
		t.Fatal(e)
	}
	if bytes.Contains(cipher, raw) {
		t.Fatal("raw text leaked into backup")
	}
	plain, e := b.Read(p)
	if e != nil || !bytes.Equal(plain, raw) {
		t.Fatalf("decrypt failed: %v", e)
	}
}
func TestFilter(t *testing.T) {
	f := NewSafeFilter(100)
	if _, ok, _ := f.Check("api_key=secret-value"); ok {
		t.Fatal("secret must be rejected")
	}
	if got, ok, _ := f.Check("  safe note  "); !ok || got != "safe note" {
		t.Fatal("safe note unexpectedly rejected")
	}
}
