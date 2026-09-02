package domain

import (
	"fmt"
	"strings"
	"testing"
)

func TestCaptureFormattingRedactsText(t *testing.T) {
	secret := "do-not-log-this-text"
	c := Capture{ID: "cap", ClientCaptureID: "client", Text: secret, Status: StatusPersisted, CardID: "card"}
	for _, got := range []string{fmt.Sprint(c), fmt.Sprintf("%v", c), fmt.Sprintf("%+v", c), fmt.Sprintf("%#v", c)} {
		if strings.Contains(got, secret) {
			t.Fatalf("capture formatting leaked Text: %s", got)
		}
	}
}
