package domain

import (
	"errors"
	"fmt"
	"time"
)

type Status string

const (
	StatusReceived        Status = "received"
	StatusFilteredPass    Status = "filtered_pass"
	StatusFilteredReject  Status = "filtered_reject"
	StatusPersisted       Status = "persisted"
	StatusAIRunning       Status = "ai_running"
	StatusAISucceeded     Status = "ai_succeeded"
	StatusAIFailed        Status = "ai_failed"
	StatusAwaitingConfirm Status = "awaiting_confirm"
	StatusPublished       Status = "published"
	StatusSyncing         Status = "syncing"
	StatusSynced          Status = "synced"
)

var transitions = map[Status]map[Status]bool{
	StatusReceived:        {StatusFilteredPass: true, StatusFilteredReject: true},
	StatusFilteredPass:    {StatusPersisted: true},
	StatusPersisted:       {StatusAIRunning: true},
	StatusAIRunning:       {StatusAISucceeded: true, StatusAIFailed: true},
	StatusAIFailed:        {StatusAIRunning: true},
	StatusAISucceeded:     {StatusAwaitingConfirm: true, StatusPublished: true},
	StatusAwaitingConfirm: {StatusPublished: true},
	StatusPublished:       {StatusSyncing: true},
	StatusSyncing:         {StatusSynced: true, StatusPublished: true},
	StatusSynced:          {StatusPublished: true},
}

func CanTransition(from, to Status) bool { return transitions[from][to] }
func Transition(from, to Status) error {
	if !CanTransition(from, to) {
		return errors.New("invalid state transition")
	}
	return nil
}

var AllowedTags = map[string]bool{"人文": true, "商业": true, "技术": true, "认知": true, "职场": true, "社会": true, "随笔": true}

type Capture struct {
	ID              string        `json:"id"`
	ClientCaptureID string        `json:"client_capture_id"`
	Text            string        `json:"text,omitempty"`
	TextSHA256      string        `json:"text_sha256,omitempty"`
	SourceApp       string        `json:"source_app,omitempty"`
	SourceURL       string        `json:"source_url,omitempty"`
	Mode            string        `json:"mode"`
	CapturedAt      time.Time     `json:"captured_at"`
	CreatedAt       time.Time     `json:"created_at"`
	Status          Status        `json:"status"`
	StatusHistory   []StatusEvent `json:"status_history"`
	BackupPath      string        `json:"backup_path,omitempty"`
	RejectReason    string        `json:"reject_reason,omitempty"`
	CardID          string        `json:"card_id,omitempty"`
	Attempts        int           `json:"attempts"`
	LastError       string        `json:"last_error,omitempty"`
}

func (c Capture) String() string {
	return fmt.Sprintf("Capture{id:%q client_capture_id:%q status:%q card_id:%q}", c.ID, c.ClientCaptureID, c.Status, c.CardID)
}

func (c Capture) GoString() string { return c.String() }

type StatusEvent struct {
	Status Status    `json:"status"`
	At     time.Time `json:"at"`
}

func (c *Capture) Move(to Status, now time.Time) error {
	if err := Transition(c.Status, to); err != nil {
		return err
	}
	c.Status = to
	c.StatusHistory = append(c.StatusHistory, StatusEvent{Status: to, At: now})
	return nil
}

type Interpretation struct {
	Summary string `json:"summary"`
	Insight string `json:"insight"`
	Action  string `json:"action"`
}

type BookCandidate struct {
	Title          string `json:"title"`
	Author         string `json:"author,omitempty"`
	OpenLibraryKey string `json:"openlibrary_key,omitempty"`
	Verified       bool   `json:"verified"`
}

type CardVersion struct {
	ID             string          `json:"id"`
	CardID         string          `json:"card_id"`
	Number         int             `json:"number"`
	CreatedAt      time.Time       `json:"created_at"`
	CleanText      string          `json:"clean_text"`
	PrimaryTag     string          `json:"primary_tag"`
	Interpretation Interpretation  `json:"interpretation"`
	Books          []BookCandidate `json:"books"`
	Markdown       string          `json:"markdown"`
}

type Card struct {
	ID              string    `json:"id"`
	CaptureID       string    `json:"capture_id"`
	Status          Status    `json:"status"`
	VersionIDs      []string  `json:"version_ids"`
	ActiveVersionID string    `json:"active_version_id,omitempty"`
	CreatedAt       time.Time `json:"created_at"`
	UpdatedAt       time.Time `json:"updated_at"`
	LastError       string    `json:"last_error,omitempty"`
}
