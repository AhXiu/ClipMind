package httpapi

import (
	"clipmind/backend/internal/knowledge"
	"clipmind/backend/internal/metrics"
	"clipmind/backend/internal/service"
	"clipmind/backend/internal/store"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strings"
	"time"
)

type Server struct {
	Capture      *service.Service
	Cards        service.Cards
	Metrics      *metrics.Counter
	AuthDisabled bool
	Token        string
	Log          *log.Logger
	Knowledge    *knowledge.Service
}
type apiError struct {
	Error struct {
		Code      string `json:"code"`
		Message   string `json:"message"`
		RequestID string `json:"request_id"`
	} `json:"error"`
}

func (s *Server) Handler() http.Handler { return http.HandlerFunc(s.route) }
func (s *Server) route(w http.ResponseWriter, r *http.Request) {
	rid := r.Header.Get("X-Request-ID")
	if rid == "" {
		rid = service.ID("req_")
	}
	w.Header().Set("X-Request-ID", rid)
	w.Header().Set("Content-Type", "application/json")
	s.Metrics.Inc("http_requests_total")
	s.Log.Printf("request request_id=%s method=%s path=%s", rid, r.Method, r.URL.Path)
	if r.URL.Path == "/health" && r.Method == http.MethodGet {
		s.write(w, 200, map[string]string{"status": "ok"})
		return
	}
	if r.URL.Path == "/metrics" && r.Method == http.MethodGet {
		s.write(w, 200, s.Metrics.Snapshot())
		return
	}
	if !s.AuthDisabled && r.Header.Get("Authorization") != "Bearer "+s.Token {
		s.err(w, 401, "unauthorized", "valid bearer token required", rid)
		return
	}
	if r.URL.Path == "/v1/captures:batch" && r.Method == http.MethodPost {
		s.batch(w, r, rid)
		return
	}
	if r.URL.Path == "/v1/knowledge:synthesize" && r.Method == http.MethodPost {
		s.synthesize(w, r, rid)
		return
	}
	if strings.HasPrefix(r.URL.Path, "/v1/cards/") {
		s.cardRoute(w, r, rid)
		return
	}
	s.err(w, 404, "not_found", "route not found", rid)
}

var synthesisSlots = make(chan struct{}, 2)

func (s *Server) synthesize(w http.ResponseWriter, r *http.Request, rid string) {
	var input knowledge.Request
	d := json.NewDecoder(http.MaxBytesReader(w, r.Body, 128<<10))
	d.DisallowUnknownFields()
	if d.Decode(&input) != nil || d.Decode(new(any)) != io.EOF || knowledge.ValidateRequest(input) != nil {
		s.err(w, 400, "invalid_request", "select 2-8 cards, at most 6000 characters each and 16000 total", rid)
		return
	}
	if s.Knowledge == nil {
		s.err(w, 503, "unavailable", "knowledge provider unavailable", rid)
		return
	}
	if knowledge.ValidateSafety(input) != nil {
		s.err(w, 400, "safety_reject", "selected content failed safety validation", rid)
		return
	}
	select {
	case synthesisSlots <- struct{}{}:
		defer func() { <-synthesisSlots }()
	default:
		s.err(w, 429, "busy", "knowledge concurrency limit reached", rid)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	result, err := s.Knowledge.Generate(ctx, input)
	if err != nil {
		s.err(w, 502, "knowledge_failed", "synthesis failed safety, provider or citation validation", rid)
		return
	}
	s.write(w, 200, result)
}
func (s *Server) batch(w http.ResponseWriter, r *http.Request, rid string) {
	key := r.Header.Get("Idempotency-Key")
	if key == "" {
		s.err(w, 400, "invalid_request", "Idempotency-Key is required", rid)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 2<<20)
	var req struct {
		Captures []service.CaptureInput `json:"captures"`
	}
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if e := dec.Decode(&req); e != nil || len(req.Captures) == 0 || len(req.Captures) > 100 {
		s.err(w, 400, "invalid_request", "captures must contain 1 to 100 valid items", rid)
		return
	}
	res, e := s.Capture.Ingest(key, req.Captures)
	if e != nil {
		s.err(w, 500, "internal_error", "capture processing failed", rid)
		return
	}
	s.Metrics.Inc("capture_batches_total")
	s.write(w, 202, res)
}
func (s *Server) cardRoute(w http.ResponseWriter, r *http.Request, rid string) {
	rest := strings.TrimPrefix(r.URL.Path, "/v1/cards/")
	parts := strings.Split(rest, "/")
	if len(parts) == 0 || parts[0] == "" {
		s.err(w, 404, "not_found", "card not found", rid)
		return
	}
	id := parts[0]
	var out any
	var e error
	switch {
	case len(parts) == 2 && parts[1] == "analyses" && r.Method == http.MethodPost:
		var item service.CaptureInput
		dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 2<<20))
		dec.DisallowUnknownFields()
		if r.Header.Get("Idempotency-Key") == "" || dec.Decode(&item) != nil {
			s.err(w, 400, "invalid_request", "valid analysis input and Idempotency-Key required", rid)
			return
		}
		out, e = s.Capture.AnalyzeAgain(id, r.Header.Get("Idempotency-Key"), item)
	case len(parts) == 1 && r.Method == http.MethodGet:
		out, e = s.Cards.Get(id)
	case len(parts) == 2 && parts[1] == "versions" && r.Method == http.MethodGet:
		out, e = s.Cards.Versions(id)
	case len(parts) == 2 && parts[1] == "confirm" && r.Method == http.MethodPost:
		out, e = s.Cards.Confirm(id)
	case len(parts) == 2 && parts[1] == "sync:retry" && r.Method == http.MethodPost:
		out, e = s.Cards.RetrySync(id)
	case len(parts) == 2 && parts[1] == "rollback" && r.Method == http.MethodPost:
		var req struct {
			VersionID string `json:"version_id"`
		}
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&req) != nil || req.VersionID == "" {
			s.err(w, 400, "invalid_request", "version_id is required", rid)
			return
		}
		out, e = s.Cards.Rollback(id, req.VersionID)
	default:
		s.err(w, 404, "not_found", "route not found", rid)
		return
	}
	if e != nil {
		if errors.Is(e, store.ErrNotFound) {
			s.err(w, 404, "not_found", "resource not found", rid)
		} else {
			s.err(w, 409, "invalid_state", e.Error(), rid)
		}
		return
	}
	s.write(w, 200, out)
}
func (s *Server) write(w http.ResponseWriter, status int, v any) {
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func (s *Server) err(w http.ResponseWriter, status int, code, msg, rid string) {
	s.Metrics.Inc("http_errors_total")
	var x apiError
	x.Error.Code = code
	x.Error.Message = msg
	x.Error.RequestID = rid
	s.write(w, status, x)
}
