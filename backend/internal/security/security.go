package security

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

type Filter interface {
	Check(string) (string, bool, string)
}
type SafeFilter struct {
	MaxBytes int
	patterns []*regexp.Regexp
}

func NewSafeFilter(max int) *SafeFilter {
	return &SafeFilter{MaxBytes: max, patterns: []*regexp.Regexp{
		regexp.MustCompile(`(?i)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----`),
		regexp.MustCompile(`(?i)\b(?:password|passwd|api[_-]?key|secret)\s*[:=]\s*\S+`),
		regexp.MustCompile(`\b\d{15,19}\b`),
	}}
}
func (f *SafeFilter) Check(raw string) (string, bool, string) {
	t := strings.TrimSpace(strings.ReplaceAll(raw, "\x00", ""))
	if t == "" {
		return "", false, "empty"
	}
	if len(t) > f.MaxBytes {
		return "", false, "too_large"
	}
	for _, p := range f.patterns {
		if p.MatchString(t) {
			return "", false, "sensitive_content"
		}
	}
	return t, true, ""
}

type RawBackup interface {
	Save(id string, raw []byte) (string, error)
	Delete(path string) error
}
type EncryptedFileBackup struct {
	dir string
	key []byte
}

func NewEncryptedFileBackup(dir string, key []byte) (*EncryptedFileBackup, error) {
	if len(key) != 32 {
		return nil, errors.New("AES-256 requires 32-byte key")
	}
	return &EncryptedFileBackup{dir: dir, key: append([]byte(nil), key...)}, nil
}
func (b *EncryptedFileBackup) Save(id string, raw []byte) (string, error) {
	block, e := aes.NewCipher(b.key)
	if e != nil {
		return "", e
	}
	g, e := cipher.NewGCM(block)
	if e != nil {
		return "", e
	}
	nonce := make([]byte, g.NonceSize())
	if _, e = io.ReadFull(rand.Reader, nonce); e != nil {
		return "", e
	}
	sealed := g.Seal(nonce, nonce, raw, nil)
	if e = os.MkdirAll(b.dir, 0700); e != nil {
		return "", e
	}
	p := filepath.Join(b.dir, id+".enc")
	tmp := p + ".tmp"
	if e = os.WriteFile(tmp, sealed, 0600); e != nil {
		return "", e
	}
	if e = os.Rename(tmp, p); e != nil {
		_ = os.Remove(tmp)
		return "", e
	}
	return p, nil
}
func (b *EncryptedFileBackup) Read(path string) ([]byte, error) {
	data, e := os.ReadFile(path)
	if e != nil {
		return nil, e
	}
	block, e := aes.NewCipher(b.key)
	if e != nil {
		return nil, e
	}
	g, e := cipher.NewGCM(block)
	if e != nil {
		return nil, e
	}
	if len(data) < g.NonceSize() {
		return nil, errors.New("invalid encrypted backup")
	}
	return g.Open(nil, data[:g.NonceSize()], data[g.NonceSize():], nil)
}
func (b *EncryptedFileBackup) Delete(path string) error { return os.Remove(path) }
