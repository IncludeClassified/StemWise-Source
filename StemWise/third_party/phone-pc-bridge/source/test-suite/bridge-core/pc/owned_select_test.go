package pc

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
)

type ownedSelectionFixture struct {
	calls int
	raw   json.RawMessage
	after func()
}

func (n *ownedSelectionFixture) Select(context.Context, string) (json.RawMessage, error) {
	n.calls++
	if n.after != nil {
		n.after()
	}
	return n.raw, nil
}

func TestOwnedSelectionVerifiesExactEnvelopeAndFreshWriter(t *testing.T) {
	s, base, input, _ := creationTestService(t)
	if _, err := s.createThread(context.Background(), "nPi", input, base); err != nil {
		t.Fatal(err)
	}
	input.Operation = "select"
	for id := range base.threads {
		input.ThreadID = id
	}
	valid, _ := json.Marshal(map[string]any{"thread": map[string]any{"id": input.ThreadID, "canAcceptDirectInput": true}})
	native := &ownedSelectionFixture{raw: valid}
	for _, field := range []string{"app", "request", "thread", "timestamp", "model", "name", "turn"} {
		wrong := input
		switch field {
		case "app":
			wrong.AppID = randomToken()
		case "request":
			wrong.RequestID = randomToken()
		case "thread":
			wrong.ThreadID = "foreign"
		case "timestamp":
			wrong.CreatedAt--
		case "model":
			wrong.Model = "changed"
		case "name":
			wrong.Name = "changed"
		case "turn":
			wrong.TurnID = "unexpected"
		}
		if _, err := s.selectCreation(context.Background(), "nPi", wrong, native); err == nil || native.calls != 0 {
			t.Fatal("Invalid owned selection reached native", field, err)
		}
	}
	for _, raw := range []string{`{}`, `{"thread":{"id":"foreign","canAcceptDirectInput":true}}`, `{"thread":{"id":"` + input.ThreadID + `","canAcceptDirectInput":false}}`, `{"thread":{"id":"` + input.ThreadID + `"}}`} {
		native.raw = json.RawMessage(raw)
		_, err := s.selectCreation(context.Background(), "nPi", input, native)
		var failure *creationError
		if !errors.As(err, &failure) || !failure.unknown || failure.code != "chat_writer_unconfirmed" {
			t.Fatal("Invalid native selection became restored writer", err)
		}
	}
	native.raw = valid
	if _, err := s.selectCreation(context.Background(), "nPi", input, native); err != nil {
		t.Fatal(err)
	}
	native.after = func() {
		if err := s.creations.Remove("nPi", creationID("nPi", input.AppID, input.RequestID, "result")); err != nil {
			t.Error(err)
		}
	}
	_, err := s.selectCreation(context.Background(), "nPi", input, native)
	var failure *creationError
	if !errors.As(err, &failure) || !failure.unknown {
		t.Fatal("Changed ownership committed selection", err)
	}
}
