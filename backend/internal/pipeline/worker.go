package pipeline

import (
	"clipmind/backend/internal/books"
	"clipmind/backend/internal/domain"
	"clipmind/backend/internal/llm"
	"clipmind/backend/internal/metrics"
	"clipmind/backend/internal/render"
	"clipmind/backend/internal/service"
	"clipmind/backend/internal/store"
	"context"
	"errors"
	"strings"
	"time"
)

type Worker struct {
	Repo        store.Repository
	LLM         llm.Provider
	Books       books.Verifier
	Metrics     *metrics.Counter
	Interval    time.Duration
	MaxAttempts int
	StaleAfter  time.Duration
}

func (w *Worker) Run(ctx context.Context) {
	_ = w.RunOnce(ctx)
	interval := w.Interval
	if interval <= 0 {
		interval = time.Second
	}
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			_ = w.RunOnce(ctx)
		}
	}
}
func (w *Worker) RunOnce(ctx context.Context) error {
	staleAfter := w.StaleAfter
	if staleAfter <= 0 {
		staleAfter = 5 * time.Minute
	}
	recovered, e := w.Repo.RecoverStaleAIRunning(time.Now().UTC().Add(-staleAfter))
	if e != nil {
		return e
	}
	for i := 0; i < recovered; i++ {
		w.Metrics.Inc("pipeline_recovered_total")
	}
	items, e := w.Repo.ListPipelineReady(10)
	if e != nil {
		return e
	}
	for i := range items {
		if e = w.process(ctx, &items[i]); e != nil {
			w.Metrics.Inc("pipeline_failed_total")
		}
	}
	return nil
}
func (w *Worker) process(ctx context.Context, c *domain.Capture) error {
	max := w.MaxAttempts
	if max == 0 {
		max = 3
	}
	if c.Status == domain.StatusAIFailed {
		if c.Attempts >= max {
			return nil
		}
		if e := c.Move(domain.StatusAIRunning, time.Now().UTC()); e != nil {
			return e
		}
	} else if e := c.Move(domain.StatusAIRunning, time.Now().UTC()); e != nil {
		return e
	}
	c.Attempts++
	c.LastError = ""
	if e := w.Repo.UpdateCapture(*c); e != nil {
		return e
	}
	card, e := w.Repo.GetCard(c.CardID)
	if e != nil {
		return e
	}
	card.Status = domain.StatusAIRunning
	card.UpdatedAt = time.Now().UTC()
	if e = w.Repo.UpdateCard(card); e != nil {
		return e
	}
	clean := strings.Join(strings.Fields(c.Text), " ")
	var result llm.Result
	var last error
	for n := 0; n < 3; n++ {
		result, last = w.LLM.Analyze(ctx, clean)
		if last == nil {
			last = llm.Validate(result)
		}
		if last == nil {
			break
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(time.Duration(n+1) * 20 * time.Millisecond):
		}
	}
	if last != nil {
		return w.fail(c, &card, last)
	}
	result.Books = w.Books.Verify(ctx, result.Books)
	now := time.Now().UTC()
	v := domain.CardVersion{ID: service.ID("ver_"), CardID: card.ID, CreatedAt: now, CleanText: clean, PrimaryTag: result.PrimaryTag, Interpretation: result.Interpretation, Books: result.Books}
	v.Markdown = render.Markdown(card.ID, now.Format(time.RFC3339), clean, v.PrimaryTag, v.Interpretation, v.Books)
	v, e = w.Repo.AddVersion(v)
	if e != nil {
		return w.fail(c, &card, e)
	}
	card.VersionIDs = append(card.VersionIDs, v.ID)
	card.ActiveVersionID = v.ID
	card.Status = domain.StatusAISucceeded
	card.UpdatedAt = now
	if e = w.Repo.UpdateCard(card); e != nil {
		return e
	}
	if e = c.Move(domain.StatusAISucceeded, now); e != nil {
		return e
	}
	if e = c.Move(domain.StatusAwaitingConfirm, now); e != nil {
		return e
	}
	card.Status = domain.StatusAwaitingConfirm
	card.UpdatedAt = now
	if e = w.Repo.UpdateCard(card); e != nil {
		return e
	}
	if e = w.Repo.UpdateCapture(*c); e != nil {
		return e
	}
	w.Metrics.Inc("pipeline_succeeded_total")
	return nil
}
func (w *Worker) fail(c *domain.Capture, card *domain.Card, cause error) error {
	now := time.Now().UTC()
	_ = c.Move(domain.StatusAIFailed, now)
	c.LastError = "pipeline step failed"
	card.Status = domain.StatusAIFailed
	card.LastError = "pipeline step failed"
	card.UpdatedAt = now
	_ = w.Repo.UpdateCapture(*c)
	_ = w.Repo.UpdateCard(*card)
	return errors.New("pipeline step failed: " + cause.Error())
}
