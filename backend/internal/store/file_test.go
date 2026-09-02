package store

import (
	"clipmind/backend/internal/domain"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func TestTransactionFailureDoesNotPolluteMemory(t *testing.T) {
	dir := t.TempDir()
	r, e := OpenFile(filepath.Join(dir, "store.json"))
	if e != nil {
		t.Fatal(e)
	}
	r.path = filepath.Join(dir, "blocked")
	if e = os.Mkdir(r.path+".tmp", 0700); e != nil {
		t.Fatal(e)
	}
	e = r.Transaction(func(tx Transaction) error {
		_, _, x := tx.CreateCapture(domain.Capture{ID: "cap", ClientCaptureID: "client"})
		return x
	})
	if e == nil {
		t.Fatal("expected persistence failure")
	}
	if _, e = r.GetCapture("cap"); !errorsIs(e, ErrNotFound) {
		t.Fatalf("failed transaction polluted memory: %v", e)
	}
}
func errorsIs(got, want error) bool { return got == want }
func TestAddVersionAssignsConcurrentMonotonicNumbers(t *testing.T) {
	r, e := OpenFile(filepath.Join(t.TempDir(), "store.json"))
	if e != nil {
		t.Fatal(e)
	}
	const n = 40
	var wg sync.WaitGroup
	errs := make(chan error, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			_, e := r.AddVersion(domain.CardVersion{ID: fmt.Sprintf("v-%d", i), CardID: "card", Number: 999})
			errs <- e
		}(i)
	}
	wg.Wait()
	close(errs)
	for e := range errs {
		if e != nil {
			t.Fatal(e)
		}
	}
	versions, e := r.ListVersions("card")
	if e != nil {
		t.Fatal(e)
	}
	if len(versions) != n {
		t.Fatalf("versions=%d", len(versions))
	}
	for i, v := range versions {
		if v.Number != i+1 {
			t.Fatalf("version[%d].number=%d", i, v.Number)
		}
	}
}
