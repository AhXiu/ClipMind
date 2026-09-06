package llm

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestOpenAICompatibleChatCompletionsRequest(t *testing.T) {
	const key = "provider-secret-key"
	var gotPath, gotAuth, gotReferer, gotTitle string
	var gotBody map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotAuth = r.Header.Get("Authorization")
		gotReferer = r.Header.Get("HTTP-Referer")
		gotTitle = r.Header.Get("X-Title")
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"choices":[{"message":{"content":"{\"primary_tag\":\"技术\",\"interpretation\":{\"summary\":\"总结\",\"insight\":\"洞察\",\"action\":\"行动\"},\"books\":[]}"}}]}`)
	}))
	defer server.Close()

	client := NewOpenAICompatible("openrouter", server.URL+"/api/v1/", key, "vendor/model", time.Second)
	client.HTTPReferer = "https://clipmind.example"
	client.Title = "ClipMind"
	result, err := client.Analyze(context.Background(), "Go API")
	if err != nil {
		t.Fatal(err)
	}
	if gotPath != "/api/v1/chat/completions" || gotAuth != "Bearer "+key || gotReferer != "https://clipmind.example" || gotTitle != "ClipMind" {
		t.Fatalf("unexpected request path=%q auth=%q referer=%q title=%q", gotPath, gotAuth, gotReferer, gotTitle)
	}
	if gotBody["model"] != "vendor/model" || result.PrimaryTag != "技术" {
		t.Fatalf("unexpected body/result: body=%v result=%+v", gotBody, result)
	}
	encoded, _ := json.Marshal(gotBody)
	if strings.Contains(string(encoded), key) {
		t.Fatal("API key appeared outside Authorization header")
	}
	if client.Name() != "openrouter" || client.Model() != "vendor/model" {
		t.Fatal("provider identity was not retained")
	}
}

func TestProviderErrorsDoNotLeakKeyOrResponse(t *testing.T) {
	const key = "never-leak-this-key"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = io.WriteString(w, "upstream says "+key)
	}))
	defer server.Close()
	client := NewOpenAICompatible("ark", server.URL, key, "endpoint-id", time.Second)
	_, err := client.Analyze(context.Background(), "text")
	if err == nil || strings.Contains(err.Error(), key) || strings.Contains(err.Error(), "upstream says") {
		t.Fatalf("provider error leaked sensitive content: %v", err)
	}
}
