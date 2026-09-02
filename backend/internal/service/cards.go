package service

import (
	"clipmind/backend/internal/domain"
	"clipmind/backend/internal/store"
	"clipmind/backend/internal/syncer"
	"errors"
	"time"
)

type Cards struct {
	Repo store.Repository
	Sync syncer.Adapter
}

func (c Cards) Get(id string) (domain.Card, error) { return c.Repo.GetCard(id) }
func (c Cards) Versions(id string) ([]domain.CardVersion, error) {
	if _, e := c.Repo.GetCard(id); e != nil {
		return nil, e
	}
	return c.Repo.ListVersions(id)
}
func (c Cards) Confirm(id string) (domain.Card, error) {
	card, e := c.Repo.GetCard(id)
	if e != nil {
		return card, e
	}
	if card.Status != domain.StatusAwaitingConfirm {
		return card, errors.New("card is not awaiting confirmation")
	}
	now := time.Now().UTC()
	if e = c.moveCapture(card.CaptureID, domain.StatusPublished, now); e != nil {
		return card, e
	}
	card.Status = domain.StatusPublished
	card.UpdatedAt = now
	if e = c.Repo.UpdateCard(card); e != nil {
		return card, e
	}
	return c.sync(card)
}
func (c Cards) RetrySync(id string) (domain.Card, error) {
	card, e := c.Repo.GetCard(id)
	if e != nil {
		return card, e
	}
	if card.Status != domain.StatusPublished {
		return card, errors.New("card is not ready to sync")
	}
	return c.sync(card)
}
func (c Cards) sync(card domain.Card) (domain.Card, error) {
	v, e := c.Repo.GetVersion(card.ActiveVersionID)
	if e != nil {
		return card, e
	}
	now := time.Now().UTC()
	if e = c.moveCapture(card.CaptureID, domain.StatusSyncing, now); e != nil {
		return card, e
	}
	card.Status = domain.StatusSyncing
	card.UpdatedAt = now
	if e = c.Repo.UpdateCard(card); e != nil {
		return card, e
	}
	if e = c.Sync.Write(card.ID, []byte(v.Markdown)); e != nil {
		card.Status = domain.StatusPublished
		card.LastError = "sync failed"
		_ = c.moveCapture(card.CaptureID, domain.StatusPublished, time.Now().UTC())
		_ = c.Repo.UpdateCard(card)
		return card, e
	}
	if e = c.moveCapture(card.CaptureID, domain.StatusSynced, time.Now().UTC()); e != nil {
		return card, e
	}
	card.Status = domain.StatusSynced
	card.LastError = ""
	card.UpdatedAt = time.Now().UTC()
	e = c.Repo.UpdateCard(card)
	return card, e
}
func (c Cards) Rollback(id, versionID string) (domain.Card, error) {
	card, e := c.Repo.GetCard(id)
	if e != nil {
		return card, e
	}
	v, e := c.Repo.GetVersion(versionID)
	if e != nil {
		return card, e
	}
	if v.CardID != id {
		return card, errors.New("version does not belong to card")
	}
	if card.Status != domain.StatusSynced && card.Status != domain.StatusPublished {
		return card, errors.New("card cannot be rolled back in current state")
	}
	if card.Status == domain.StatusSynced {
		if e = c.moveCapture(card.CaptureID, domain.StatusPublished, time.Now().UTC()); e != nil {
			return card, e
		}
	}
	card.ActiveVersionID = versionID
	card.Status = domain.StatusPublished
	card.UpdatedAt = time.Now().UTC()
	if e = c.Repo.UpdateCard(card); e != nil {
		return card, e
	}
	return c.sync(card)
}
func (c Cards) moveCapture(id string, to domain.Status, now time.Time) error {
	capture, e := c.Repo.GetCapture(id)
	if e != nil {
		return e
	}
	if e = capture.Move(to, now); e != nil {
		return e
	}
	return c.Repo.UpdateCapture(capture)
}
