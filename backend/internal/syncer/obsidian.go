package syncer

import (
	"errors"
	"os"
	"path/filepath"
	"regexp"
)

type Adapter interface {
	Write(cardID string, markdown []byte) error
}
type Obsidian struct{ Vault string }

var safeID = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)

func (o Obsidian) Write(cardID string, data []byte) error {
	if !safeID.MatchString(cardID) {
		return errors.New("invalid card id")
	}
	if o.Vault == "" {
		return errors.New("vault is not configured")
	}
	if e := os.MkdirAll(o.Vault, 0700); e != nil {
		return e
	}
	p := filepath.Join(o.Vault, cardID+".md")
	tmp := p + ".tmp"
	f, e := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if e != nil {
		return e
	}
	if _, e = f.Write(data); e == nil {
		e = f.Sync()
	}
	ce := f.Close()
	if e == nil {
		e = ce
	}
	if e != nil {
		_ = os.Remove(tmp)
		return e
	}
	if e = os.Rename(tmp, p); e != nil {
		_ = os.Remove(tmp)
		return e
	}
	return nil
}
