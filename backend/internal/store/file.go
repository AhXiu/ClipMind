package store

import (
	"clipmind/backend/internal/domain"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

var ErrNotFound = errors.New("not found")

type state struct {
	Captures  map[string]domain.Capture     `json:"captures"`
	ClientIDs map[string]string             `json:"client_ids"`
	Cards     map[string]domain.Card        `json:"cards"`
	Versions  map[string]domain.CardVersion `json:"versions"`
	Receipts  map[string]json.RawMessage    `json:"receipts"`
}
type FileRepository struct {
	mu           sync.RWMutex
	path         string
	s            state
	persistCount uint64
}
type fileTx struct{ s *state }

func emptyState() state {
	return state{Captures: map[string]domain.Capture{}, ClientIDs: map[string]string{}, Cards: map[string]domain.Card{}, Versions: map[string]domain.CardVersion{}, Receipts: map[string]json.RawMessage{}}
}
func OpenFile(path string) (*FileRepository, error) {
	r := &FileRepository{path: path, s: emptyState()}
	b, e := os.ReadFile(path)
	if e == nil {
		if e = json.Unmarshal(b, &r.s); e != nil {
			return nil, e
		}
		r.ensureMaps()
	} else if !os.IsNotExist(e) {
		return nil, e
	}
	return r, nil
}
func (r *FileRepository) ensureMaps() {
	if r.s.Captures == nil {
		r.s.Captures = map[string]domain.Capture{}
	}
	if r.s.ClientIDs == nil {
		r.s.ClientIDs = map[string]string{}
	}
	if r.s.Cards == nil {
		r.s.Cards = map[string]domain.Card{}
	}
	if r.s.Versions == nil {
		r.s.Versions = map[string]domain.CardVersion{}
	}
	if r.s.Receipts == nil {
		r.s.Receipts = map[string]json.RawMessage{}
	}
}
func cloneState(src state) state {
	dst := emptyState()
	for k, v := range src.Captures {
		v.StatusHistory = append([]domain.StatusEvent(nil), v.StatusHistory...)
		dst.Captures[k] = v
	}
	for k, v := range src.ClientIDs {
		dst.ClientIDs[k] = v
	}
	for k, v := range src.Cards {
		v.VersionIDs = append([]string(nil), v.VersionIDs...)
		dst.Cards[k] = v
	}
	for k, v := range src.Versions {
		v.Books = append([]domain.BookCandidate(nil), v.Books...)
		dst.Versions[k] = v
	}
	for k, v := range src.Receipts {
		dst.Receipts[k] = append(json.RawMessage(nil), v...)
	}
	return dst
}
func (r *FileRepository) persistState(s state) error {
	if e := os.MkdirAll(filepath.Dir(r.path), 0700); e != nil {
		return e
	}
	b, e := json.MarshalIndent(s, "", "  ")
	if e != nil {
		return e
	}
	tmp := r.path + ".tmp"
	f, e := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if e != nil {
		return e
	}
	if _, e = f.Write(b); e == nil {
		e = f.Sync()
	}
	ce := f.Close()
	if e == nil {
		e = ce
	}
	if e != nil {
		_ = os.Remove(tmp)
		return e
	}
	if e = os.Rename(tmp, r.path); e != nil {
		_ = os.Remove(tmp)
		return e
	}
	return nil
}
func (r *FileRepository) Transaction(fn func(Transaction) error) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	next := cloneState(r.s)
	if e := fn(&fileTx{s: &next}); e != nil {
		return e
	}
	if e := r.persistState(next); e != nil {
		return e
	}
	r.s = next
	r.persistCount++
	return nil
}
func (r *FileRepository) PersistCount() uint64 {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.persistCount
}
func (t *fileTx) GetReceipt(k string) ([]byte, bool) {
	v, ok := t.s.Receipts[k]
	return append([]byte(nil), v...), ok
}
func (t *fileTx) PutReceipt(k string, b []byte) { t.s.Receipts[k] = append([]byte(nil), b...) }
func (t *fileTx) CreateCapture(c domain.Capture) (domain.Capture, bool, error) {
	if id, ok := t.s.ClientIDs[c.ClientCaptureID]; ok {
		return t.s.Captures[id], false, nil
	}
	t.s.Captures[c.ID] = c
	t.s.ClientIDs[c.ClientCaptureID] = c.ID
	return c, true, nil
}
func (t *fileTx) UpdateCapture(c domain.Capture) error {
	if _, ok := t.s.Captures[c.ID]; !ok {
		return ErrNotFound
	}
	t.s.Captures[c.ID] = c
	return nil
}
func (t *fileTx) CreateCard(c domain.Card) error {
	if _, ok := t.s.Cards[c.ID]; ok {
		return errors.New("card exists")
	}
	t.s.Cards[c.ID] = c
	return nil
}
func (t *fileTx) UpdateCard(c domain.Card) error {
	if _, ok := t.s.Cards[c.ID]; !ok {
		return ErrNotFound
	}
	t.s.Cards[c.ID] = c
	return nil
}
func (t *fileTx) AddVersion(v domain.CardVersion) (domain.CardVersion, error) {
	if _, ok := t.s.Versions[v.ID]; ok {
		return v, errors.New("version exists")
	}
	max := 0
	for _, existing := range t.s.Versions {
		if existing.CardID == v.CardID && existing.Number > max {
			max = existing.Number
		}
	}
	v.Number = max + 1
	t.s.Versions[v.ID] = v
	return v, nil
}
func (r *FileRepository) GetReceipt(k string) ([]byte, bool, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	v, ok := r.s.Receipts[k]
	return append([]byte(nil), v...), ok, nil
}
func (r *FileRepository) PutReceipt(k string, b []byte) error {
	return r.Transaction(func(tx Transaction) error { tx.PutReceipt(k, b); return nil })
}
func (r *FileRepository) CreateCapture(c domain.Capture) (out domain.Capture, created bool, err error) {
	err = r.Transaction(func(tx Transaction) error { out, created, err = tx.CreateCapture(c); return err })
	return
}
func (r *FileRepository) UpdateCapture(c domain.Capture) error {
	return r.Transaction(func(tx Transaction) error { return tx.UpdateCapture(c) })
}
func (r *FileRepository) GetCapture(id string) (domain.Capture, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	v, ok := r.s.Captures[id]
	if !ok {
		return v, ErrNotFound
	}
	v.StatusHistory = append([]domain.StatusEvent(nil), v.StatusHistory...)
	return v, nil
}
func (r *FileRepository) ListPipelineReady(limit int) ([]domain.Capture, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	a := []domain.Capture{}
	for _, v := range r.s.Captures {
		if v.Status == domain.StatusPersisted || v.Status == domain.StatusAIFailed || v.Status == domain.StatusAISucceeded || (v.Status == domain.StatusPublished && strings.EqualFold(v.Mode, "auto")) {
			v.StatusHistory = append([]domain.StatusEvent(nil), v.StatusHistory...)
			a = append(a, v)
		}
	}
	sort.Slice(a, func(i, j int) bool { return a[i].CreatedAt.Before(a[j].CreatedAt) })
	if len(a) > limit {
		a = a[:limit]
	}
	return a, nil
}
func (r *FileRepository) RecoverStaleAIRunning(before time.Time) (count int, err error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, c := range r.s.Captures {
		if c.Status == domain.StatusAIRunning {
			started := c.CreatedAt
			if n := len(c.StatusHistory); n > 0 {
				started = c.StatusHistory[n-1].At
			}
			if !started.After(before) {
				count++
			}
		}
	}
	if count == 0 {
		return 0, nil
	}
	next := cloneState(r.s)
	now := time.Now().UTC()
	for id, c := range next.Captures {
		if c.Status != domain.StatusAIRunning {
			continue
		}
		started := c.CreatedAt
		if n := len(c.StatusHistory); n > 0 {
			started = c.StatusHistory[n-1].At
		}
		if started.After(before) {
			continue
		}
		c.Status = domain.StatusPersisted
		c.StatusHistory = append(c.StatusHistory, domain.StatusEvent{Status: domain.StatusPersisted, At: now})
		c.LastError = "recovered stale ai_running"
		next.Captures[id] = c
		if card, ok := next.Cards[c.CardID]; ok {
			card.Status = domain.StatusPersisted
			card.LastError = c.LastError
			card.UpdatedAt = now
			next.Cards[card.ID] = card
		}
	}
	if err = r.persistState(next); err != nil {
		return 0, err
	}
	r.s = next
	r.persistCount++
	return count, nil
}
func (r *FileRepository) RecoverStaleSyncing(before time.Time) (count int, err error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	next := cloneState(r.s)
	now := time.Now().UTC()
	for id, c := range next.Captures {
		if c.Status != domain.StatusSyncing {
			continue
		}
		started := c.CreatedAt
		if n := len(c.StatusHistory); n > 0 {
			started = c.StatusHistory[n-1].At
		}
		if started.After(before) {
			continue
		}
		c.Status = domain.StatusPublished
		c.StatusHistory = append(c.StatusHistory, domain.StatusEvent{Status: domain.StatusPublished, At: now})
		c.LastError = "recovered stale syncing"
		next.Captures[id] = c
		if card, ok := next.Cards[c.CardID]; ok {
			card.Status = domain.StatusPublished
			card.LastError = c.LastError
			card.UpdatedAt = now
			next.Cards[card.ID] = card
		}
		count++
	}
	if count == 0 {
		return 0, nil
	}
	if err = r.persistState(next); err != nil {
		return 0, err
	}
	r.s = next
	r.persistCount++
	return count, nil
}
func (r *FileRepository) CreateCard(c domain.Card) error {
	return r.Transaction(func(tx Transaction) error { return tx.CreateCard(c) })
}
func (r *FileRepository) UpdateCard(c domain.Card) error {
	return r.Transaction(func(tx Transaction) error {
		t := tx.(*fileTx)
		if _, ok := t.s.Cards[c.ID]; !ok {
			return ErrNotFound
		}
		t.s.Cards[c.ID] = c
		return nil
	})
}
func (r *FileRepository) GetCard(id string) (domain.Card, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	v, ok := r.s.Cards[id]
	if !ok {
		return v, ErrNotFound
	}
	v.VersionIDs = append([]string(nil), v.VersionIDs...)
	return v, nil
}
func (r *FileRepository) AddVersion(v domain.CardVersion) (domain.CardVersion, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	next := cloneState(r.s)
	if _, ok := next.Versions[v.ID]; ok {
		return v, errors.New("version exists")
	}
	max := 0
	for _, existing := range next.Versions {
		if existing.CardID == v.CardID && existing.Number > max {
			max = existing.Number
		}
	}
	v.Number = max + 1
	next.Versions[v.ID] = v
	if e := r.persistState(next); e != nil {
		return v, e
	}
	r.s = next
	r.persistCount++
	return v, nil
}
func (r *FileRepository) GetVersion(id string) (domain.CardVersion, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	v, ok := r.s.Versions[id]
	if !ok {
		return v, ErrNotFound
	}
	v.Books = append([]domain.BookCandidate(nil), v.Books...)
	return v, nil
}
func (r *FileRepository) ListVersions(cardID string) ([]domain.CardVersion, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	a := []domain.CardVersion{}
	for _, v := range r.s.Versions {
		if v.CardID == cardID {
			v.Books = append([]domain.BookCandidate(nil), v.Books...)
			a = append(a, v)
		}
	}
	sort.Slice(a, func(i, j int) bool { return a[i].Number < a[j].Number })
	return a, nil
}
