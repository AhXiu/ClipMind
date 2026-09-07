package service

import (
	"clipmind/backend/internal/domain"
	"clipmind/backend/internal/security"
	"clipmind/backend/internal/store"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"sync"
	"time"
)

type CaptureInput struct {
	ClientCaptureID string                 `json:"client_capture_id"`
	RawText         string                 `json:"raw_text"`
	TextSHA256      string                 `json:"text_sha256,omitempty"`
	SourceApp       string                 `json:"source_app,omitempty"`
	SourceURL       string                 `json:"source_url,omitempty"`
	Mode            string                 `json:"mode"`
	CapturedAt      time.Time              `json:"captured_at"`
	ClientAnalysis  *domain.ClientAnalysis `json:"client_analysis,omitempty"`
}
type Accepted struct {
	ClientCaptureID string `json:"client_capture_id"`
	CaptureID       string `json:"capture_id"`
	CardID          string `json:"card_id"`
	Duplicate       bool   `json:"duplicate,omitempty"`
}
type Rejected struct {
	ClientCaptureID string `json:"client_capture_id,omitempty"`
	Code            string `json:"code"`
	Message         string `json:"message"`
}
type BatchResult struct {
	Accepted []Accepted `json:"accepted"`
	Rejected []Rejected `json:"rejected"`
}
type keyedLock struct {
	mu   sync.Mutex
	refs int
}
type Service struct {
	Repo   store.Repository
	Filter security.Filter
	Backup security.RawBackup
	Now    func() time.Time
	keysMu sync.Mutex
	keys   map[string]*keyedLock
}

func New(repo store.Repository, f security.Filter, b security.RawBackup) *Service {
	return &Service{Repo: repo, Filter: f, Backup: b, Now: time.Now, keys: map[string]*keyedLock{}}
}
func ID(prefix string) string {
	b := make([]byte, 12)
	_, _ = rand.Read(b)
	return prefix + hex.EncodeToString(b)
}
func (s *Service) lockKey(key string) func() {
	s.keysMu.Lock()
	k := s.keys[key]
	if k == nil {
		k = &keyedLock{}
		s.keys[key] = k
	}
	k.refs++
	s.keysMu.Unlock()
	k.mu.Lock()
	return func() {
		k.mu.Unlock()
		s.keysMu.Lock()
		k.refs--
		if k.refs == 0 {
			delete(s.keys, key)
		}
		s.keysMu.Unlock()
	}
}

type preparedCapture struct {
	capture domain.Capture
	card    domain.Card
}

var errReceiptExists = errors.New("receipt already exists")

func (s *Service) Ingest(key string, items []CaptureInput) (BatchResult, error) {
	return s.ingest(key, items, "")
}

func (s *Service) AnalyzeAgain(cardID, key string, item CaptureInput) (BatchResult, error) {
	if key == "" {
		return BatchResult{}, errors.New("idempotency key is required")
	}
	// A regenerated result always requires review before replacing an exported note.
	item.Mode = "confirm"
	return s.ingest("analysis:"+cardID+":"+key, []CaptureInput{item}, cardID)
}

func (s *Service) ingest(key string, items []CaptureInput, targetCardID string) (BatchResult, error) {
	if key == "" {
		return BatchResult{}, errors.New("idempotency key is required")
	}
	unlock := s.lockKey(key)
	defer unlock()
	if b, ok, e := s.Repo.GetReceipt(key); e != nil {
		return BatchResult{}, e
	} else if ok {
		return decodeReceipt(b)
	}
	out := BatchResult{Accepted: []Accepted{}, Rejected: []Rejected{}}
	prepared := make([]preparedCapture, 0, len(items))
	seen := map[string]bool{}
	cleanup := func() {
		for _, p := range prepared {
			if p.capture.BackupPath != "" {
				_ = s.Backup.Delete(p.capture.BackupPath)
			}
		}
	}
	for _, in := range items {
		if in.ClientCaptureID == "" {
			out.Rejected = append(out.Rejected, Rejected{Code: "invalid_client_capture_id", Message: "client_capture_id is required"})
			continue
		}
		if in.Mode != "auto" && in.Mode != "confirm" {
			out.Rejected = append(out.Rejected, Rejected{ClientCaptureID: in.ClientCaptureID, Code: "invalid_mode", Message: "mode must be auto or confirm"})
			continue
		}
		if in.ClientAnalysis != nil {
			if err := domain.ValidateClientAnalysis(*in.ClientAnalysis); err != nil {
				out.Rejected = append(out.Rejected, Rejected{ClientCaptureID: in.ClientCaptureID, Code: "invalid_client_analysis", Message: err.Error()})
				continue
			}
		}
		if in.TextSHA256 != "" {
			sum := sha256.Sum256([]byte(in.RawText))
			if in.TextSHA256 != hex.EncodeToString(sum[:]) {
				out.Rejected = append(out.Rejected, Rejected{ClientCaptureID: in.ClientCaptureID, Code: "invalid_text_sha256", Message: "text_sha256 must be the lowercase SHA-256 of raw_text"})
				continue
			}
		}
		if seen[in.ClientCaptureID] {
			out.Rejected = append(out.Rejected, Rejected{ClientCaptureID: in.ClientCaptureID, Code: "duplicate_in_batch", Message: "client_capture_id duplicated in batch"})
			continue
		}
		seen[in.ClientCaptureID] = true
		clean, ok, reason := s.Filter.Check(in.RawText)
		if !ok {
			out.Rejected = append(out.Rejected, Rejected{ClientCaptureID: in.ClientCaptureID, Code: "filtered_reject", Message: reason})
			continue
		}
		now := s.Now().UTC()
		captured := in.CapturedAt
		if captured.IsZero() {
			captured = now
		}
		cid, cardID := ID("cap_"), ID("card_")
		if targetCardID != "" {
			cardID = targetCardID
		}
		path, e := s.Backup.Save(cid, []byte(in.RawText))
		if e != nil {
			cleanup()
			return out, e
		}
		c := domain.Capture{ID: cid, ClientCaptureID: in.ClientCaptureID, Text: clean, TextSHA256: in.TextSHA256, SourceApp: in.SourceApp, SourceURL: in.SourceURL, Mode: in.Mode, CapturedAt: captured, CreatedAt: now, Status: domain.StatusReceived, StatusHistory: []domain.StatusEvent{{Status: domain.StatusReceived, At: now}}, CardID: cardID, BackupPath: path, ClientAnalysis: in.ClientAnalysis}
		if e = c.Move(domain.StatusFilteredPass, now); e != nil {
			cleanup()
			_ = s.Backup.Delete(path)
			return out, e
		}
		if e = c.Move(domain.StatusPersisted, now); e != nil {
			cleanup()
			_ = s.Backup.Delete(path)
			return out, e
		}
		prepared = append(prepared, preparedCapture{capture: c, card: domain.Card{ID: cardID, CaptureID: cid, Status: domain.StatusPersisted, CreatedAt: now, UpdatedAt: now}})
	}
	var replay []byte
	duplicates := map[string]bool{}
	e := s.Repo.Transaction(func(tx store.Transaction) error {
		if b, ok := tx.GetReceipt(key); ok {
			replay = b
			return errReceiptExists
		}
		for _, p := range prepared {
			existing, created, e := tx.CreateCapture(p.capture)
			if e != nil {
				return e
			}
			if !created {
				if targetCardID != "" && existing.CardID != targetCardID {
					return errors.New("analysis task belongs to another card")
				}
				duplicates[p.capture.ID] = true
				out.Accepted = append(out.Accepted, Accepted{ClientCaptureID: p.capture.ClientCaptureID, CaptureID: existing.ID, CardID: existing.CardID, Duplicate: true})
				continue
			}
			if targetCardID != "" {
				if e = tx.ReplaceCardCapture(targetCardID, p.capture); e != nil {
					return e
				}
			}
			if e = tx.UpdateCapture(p.capture); e != nil {
				return e
			}
			if targetCardID == "" {
				e = tx.CreateCard(p.card)
			}
			if e != nil {
				return e
			}
			out.Accepted = append(out.Accepted, Accepted{ClientCaptureID: p.capture.ClientCaptureID, CaptureID: p.capture.ID, CardID: p.card.ID})
		}
		b, e := json.Marshal(out)
		if e != nil {
			return e
		}
		tx.PutReceipt(key, b)
		return nil
	})
	if errors.Is(e, errReceiptExists) {
		cleanup()
		return decodeReceipt(replay)
	}
	if e != nil {
		cleanup()
		return out, e
	}
	for _, p := range prepared {
		if duplicates[p.capture.ID] {
			_ = s.Backup.Delete(p.capture.BackupPath)
		}
	}
	return out, nil
}
func decodeReceipt(b []byte) (BatchResult, error) {
	var out BatchResult
	e := json.Unmarshal(b, &out)
	return out, e
}
