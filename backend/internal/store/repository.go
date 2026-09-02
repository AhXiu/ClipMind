package store

import (
	"clipmind/backend/internal/domain"
	"time"
)

// Transaction is a repository-local mutable view. Its mutations become visible
// together only when Repository.Transaction successfully persists them.
type Transaction interface {
	GetReceipt(key string) ([]byte, bool)
	PutReceipt(key string, response []byte)
	CreateCapture(c domain.Capture) (domain.Capture, bool, error)
	UpdateCapture(c domain.Capture) error
	CreateCard(c domain.Card) error
}

type Repository interface {
	Transaction(func(Transaction) error) error
	GetReceipt(key string) ([]byte, bool, error)
	PutReceipt(key string, response []byte) error
	CreateCapture(c domain.Capture) (domain.Capture, bool, error)
	UpdateCapture(c domain.Capture) error
	GetCapture(id string) (domain.Capture, error)
	ListPipelineReady(limit int) ([]domain.Capture, error)
	RecoverStaleAIRunning(before time.Time) (int, error)
	CreateCard(c domain.Card) error
	UpdateCard(c domain.Card) error
	GetCard(id string) (domain.Card, error)
	AddVersion(v domain.CardVersion) (domain.CardVersion, error)
	GetVersion(id string) (domain.CardVersion, error)
	ListVersions(cardID string) ([]domain.CardVersion, error)
}
