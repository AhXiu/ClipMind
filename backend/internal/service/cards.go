package service

import (
	"clipmind/backend/internal/domain"
	"clipmind/backend/internal/store"
	"clipmind/backend/internal/syncer"
	"errors"
	"time"
)

const syncFailed = "sync failed"

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

// Confirm publishes only cards explicitly waiting for user confirmation.
func (c Cards) Confirm(id string) (domain.Card, error) {
	return c.publish(id, domain.StatusAwaitingConfirm)
}

// AutoPublish publishes only cards whose AI result has just succeeded. It is
// intentionally separate from Confirm so HTTP callers cannot bypass confirmation.
func (c Cards) AutoPublish(id string) (domain.Card, error) {
	return c.publish(id, domain.StatusAISucceeded)
}

func (c Cards) publish(id string, from domain.Status) (domain.Card, error) {
	card, e := c.Repo.GetCard(id)
	if e != nil {
		return card, e
	}
	if card.Status != from {
		return card, errors.New("card is not ready to publish")
	}
	capture, e := c.Repo.GetCapture(card.CaptureID)
	if e != nil {
		return card, e
	}
	if capture.Status != from {
		return card, errors.New("capture is not ready to publish")
	}
	now := time.Now().UTC()
	if e = capture.Move(domain.StatusPublished, now); e != nil {
		return card, e
	}
	if e = domain.Transition(card.Status, domain.StatusPublished); e != nil {
		return card, e
	}
	capture.LastError = ""
	card.Status = domain.StatusPublished
	card.LastError = ""
	card.UpdatedAt = now
	if e = c.updatePair(capture, card); e != nil {
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
	capture, e := c.Repo.GetCapture(card.CaptureID)
	if e != nil {
		return card, e
	}
	if card.Status != domain.StatusPublished || capture.Status != domain.StatusPublished {
		return card, errors.New("card is not ready to sync")
	}
	now := time.Now().UTC()
	if e = capture.Move(domain.StatusSyncing, now); e != nil {
		return card, e
	}
	card.Status = domain.StatusSyncing
	card.UpdatedAt = now
	if e = c.updatePair(capture, card); e != nil {
		return card, e
	}
	if e = c.Sync.Write(card.ID, []byte(v.Markdown)); e != nil {
		now = time.Now().UTC()
		_ = capture.Move(domain.StatusPublished, now)
		capture.LastError = syncFailed
		card.Status = domain.StatusPublished
		card.LastError = syncFailed
		card.UpdatedAt = now
		if updateErr := c.updatePair(capture, card); updateErr != nil {
			return card, updateErr
		}
		return card, e
	}
	now = time.Now().UTC()
	if e = capture.Move(domain.StatusSynced, now); e != nil {
		return card, e
	}
	capture.LastError = ""
	card.Status = domain.StatusSynced
	card.LastError = ""
	card.UpdatedAt = now
	e = c.updatePair(capture, card)
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
	capture, e := c.Repo.GetCapture(card.CaptureID)
	if e != nil {
		return card, e
	}
	if card.Status == domain.StatusSynced {
		if e = capture.Move(domain.StatusPublished, time.Now().UTC()); e != nil {
			return card, e
		}
	}
	card.ActiveVersionID = versionID
	card.Status = domain.StatusPublished
	card.UpdatedAt = time.Now().UTC()
	if e = c.updatePair(capture, card); e != nil {
		return card, e
	}
	return c.sync(card)
}

func (c Cards) updatePair(capture domain.Capture, card domain.Card) error {
	return c.Repo.Transaction(func(tx store.Transaction) error {
		if e := tx.UpdateCapture(capture); e != nil {
			return e
		}
		return tx.UpdateCard(card)
	})
}
