package syncer

import (
	"os"
	"path/filepath"
	"testing"
)

func TestObsidianAtomicWrite(t *testing.T) {
	dir := t.TempDir()
	o := Obsidian{Vault: dir}
	if e := o.Write("card_abc", []byte("first")); e != nil {
		t.Fatal(e)
	}
	if e := o.Write("card_abc", []byte("second")); e != nil {
		t.Fatal(e)
	}
	b, e := os.ReadFile(filepath.Join(dir, "card_abc.md"))
	if e != nil || string(b) != "second" {
		t.Fatalf("bad output %q %v", b, e)
	}
	if _, e = os.Stat(filepath.Join(dir, "card_abc.md.tmp")); !os.IsNotExist(e) {
		t.Fatal("temporary file remains")
	}
}
