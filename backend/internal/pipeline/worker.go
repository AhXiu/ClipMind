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
	Cards       service.Cards
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
	recovered, e = w.Repo.RecoverStaleSyncing(time.Now().UTC().Add(-staleAfter))
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
	current, err := w.Repo.GetCard(c.CardID)
	if err != nil {
		return err
	}
	if current.CaptureID != c.ID {
		return store.ErrStaleWork
	}
	if c.Status == domain.StatusPublished {
		cards := w.Cards
		if cards.Repo == nil {
			cards.Repo = w.Repo
		}
		_, e := cards.RetrySync(c.CardID)
		return e
	}
	if c.Status == domain.StatusAISucceeded {
		card, e := w.Repo.GetCard(c.CardID)
		if e != nil {
			return e
		}
		return w.finishAI(c, &card)
	}
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
	card, e := w.Repo.GetCard(c.CardID)
	if e != nil {
		return e
	}
	card.Status = domain.StatusAIRunning
	card.LastError = ""
	card.UpdatedAt = time.Now().UTC()
	if e = w.Repo.Transaction(func(tx store.Transaction) error {
		if updateErr := tx.UpdateCapture(*c); updateErr != nil {
			return updateErr
		}
		return tx.UpdateCard(card)
	}); e != nil {
		return e
	}
	clean := strings.Join(strings.Fields(c.Text), " ")
	var result llm.Result
	var last error
	providerName, modelName := llm.Identity(w.LLM)
	if c.ClientAnalysis != nil {
		if last = domain.ValidateClientAnalysis(*c.ClientAnalysis); last == nil {
			candidates := make([]domain.BookCandidate, len(c.ClientAnalysis.Books))
			for i, book := range c.ClientAnalysis.Books {
				candidates[i] = domain.BookCandidate{Title: book.Title, Author: book.Author}
			}
			result = llm.Result{PrimaryTag: c.ClientAnalysis.PrimaryTag, Interpretation: c.ClientAnalysis.Interpretation, Books: candidates}
			providerName, modelName = c.ClientAnalysis.Provider, c.ClientAnalysis.Model
		}
	} else {
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
	}
	if last != nil {
		return w.fail(c, &card, last)
	}
	result.Books = w.Books.Verify(ctx, result.Books)
	now := time.Now().UTC()
	v := domain.CardVersion{ID: service.ID("ver_"), CardID: card.ID, CreatedAt: now, CleanText: clean, PrimaryTag: result.PrimaryTag, Interpretation: result.Interpretation, Books: result.Books, LLMProvider: providerName, LLMModel: modelName}
	v.Markdown = render.Markdown(card.ID, now.Format(time.RFC3339), clean, v.PrimaryTag, v.Interpretation, v.Books)
	if e = c.Move(domain.StatusAISucceeded, now); e != nil {
		return e
	}
	card.Status = domain.StatusAISucceeded
	card.UpdatedAt = now
	e = w.Repo.Transaction(func(tx store.Transaction) error {
		var addErr error
		v, addErr = tx.AddVersion(v)
		if addErr != nil {
			return addErr
		}
		card.VersionIDs = append(card.VersionIDs, v.ID)
		card.ActiveVersionID = v.ID
		if addErr = tx.UpdateCard(card); addErr != nil {
			return addErr
		}
		return tx.UpdateCapture(*c)
	})
	if e != nil {
		return e
	}
	if e = w.finishAI(c, &card); e != nil {
		return e
	}
	w.Metrics.Inc("pipeline_succeeded_total")
	return nil
}

func (w *Worker) finishAI(c *domain.Capture, card *domain.Card) error {
	cards := w.Cards
	if cards.Repo == nil {
		cards.Repo = w.Repo
	}
	if strings.EqualFold(c.Mode, "auto") {
		_, e := cards.AutoPublish(card.ID)
		return e
	}
	now := time.Now().UTC()
	if e := c.Move(domain.StatusAwaitingConfirm, now); e != nil {
		return e
	}
	if e := domain.Transition(card.Status, domain.StatusAwaitingConfirm); e != nil {
		return e
	}
	card.Status = domain.StatusAwaitingConfirm
	card.UpdatedAt = now
	return w.Repo.Transaction(func(tx store.Transaction) error {
		if e := tx.UpdateCard(*card); e != nil {
			return e
		}
		return tx.UpdateCapture(*c)
	})
}
func (w *Worker) fail(c *domain.Capture, card *domain.Card, cause error) error {
	now := time.Now().UTC()
	_ = c.Move(domain.StatusAIFailed, now)
	c.LastError = "pipeline step failed"
	card.Status = domain.StatusAIFailed
	card.LastError = "pipeline step failed"
	card.UpdatedAt = now
	_ = w.Repo.Transaction(func(tx store.Transaction) error {
		if e := tx.UpdateCapture(*c); e != nil {
			return e
		}
		return tx.UpdateCard(*card)
	})
	return errors.New("pipeline step failed: " + cause.Error())
}
