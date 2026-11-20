package backend

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func TestAppJSONMediaTypesPreserveUTF8RequestsAndRejectAmbiguity(t *testing.T) {
	for _, tc := range []struct {
		name    string
		headers []string
		valid   bool
	}{
		{"plain", []string{"application/json"}, true},
		{"utf8", []string{"application/json; charset=utf-8"}, true},
		{"case_and_quotes", []string{`Application/JSON; Charset="UTF-8"`}, true},
		{"spacing", []string{`application/json ; charset="utf-8"`}, true},
		{"missing", nil, false},
		{"text", []string{"text/plain"}, false},
		{"latin1", []string{"application/json; charset=iso-8859-1"}, false},
		{"utf16", []string{"application/json; charset=utf-16"}, false},
		{"unknown_parameter", []string{"application/json; profile=bridge"}, false},
		{"conflicting_parameter", []string{"application/json; charset=utf-8; charset=utf-16"}, false},
		{"malformed_parameter", []string{`application/json; charset="utf-8`}, false},
		{"multiple_types", []string{"application/json, text/plain"}, false},
		{"multiple_headers", []string{"application/json", "text/plain"}, false},
		{"duplicate_headers", []string{"application/json", "application/json"}, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s, native, _ := fixtureAPI(t)
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				// Adapt only authentication; real HTTP parsing, app routes and disk state remain active.
				r.RemoteAddr = "nAlice"
				s.ServeHTTP(w, r)
			}))
			defer server.Close()
			client := server.Client()
			client.Timeout = 3 * time.Second
			request := func(method, path string, body []byte) *http.Response {
				t.Helper()
				r, err := http.NewRequest(method, server.URL+path, bytes.NewReader(body))
				if err != nil {
					t.Fatal(err)
				}
				for _, value := range tc.headers {
					r.Header.Add("Content-Type", value)
				}
				response, err := client.Do(r)
				if err != nil {
					t.Fatal(err)
				}
				defer response.Body.Close()
				if _, err := io.Copy(io.Discard, response.Body); err != nil {
					t.Fatal(err)
				}
				return response
			}
			response := request("POST", "/v2/app", mustJSON(map[string]string{"name": "owned UTF-8 Ω"}))
			if !tc.valid {
				if response.StatusCode != 400 {
					t.Fatal("Invalid or ambiguous JSON media type was accepted", response.StatusCode)
				}
				if _, err := s.loadApp(s.getPeer("nAlice")); !os.IsNotExist(err) || native.calls != 0 {
					t.Fatal("Rejected media type mutated app or native state", err)
				}
				return
			}
			if response.StatusCode != 200 {
				t.Fatal("UTF-8 JSON media type was rejected", response.StatusCode)
			}
			id := randomToken()
			prompt := appPrompt{Title: "Owned UTF-8 Ω", Text: "سياق 😀 & original\nunchanged", Instructions: "separate Ω"}
			if response := request("PUT", "/v2/prompts/"+id, mustJSON(prompt)); response.StatusCode != 200 {
				t.Fatal("UTF-8 saved prompt was rejected", response.StatusCode)
			}
			state, err := s.loadApp(s.getPeer("nAlice"))
			if err != nil || state.Name != "owned UTF-8 Ω" || state.Prompts[id] != prompt || native.calls != 0 {
				t.Fatal("Media type parsing changed decoded input or triggered native work", err)
			}
		})
	}
}

func TestAppUTF8MediaTypeStillRejectsInvalidJSONBeforeMutation(t *testing.T) {
	for _, body := range []string{`{"name":"one","name":"two"}`, `{"Name":"one"}`, `{"name":"one","unexpected":true}`, "{\"name\":\"\xff\"}"} {
		s, native, _ := fixtureAPI(t)
		r := httptest.NewRequest("POST", "/v2/app", strings.NewReader(body))
		r.RemoteAddr = "nAlice"
		r.Header.Set("Content-Type", "application/json; charset=utf-8")
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		if w.Code != 400 {
			t.Fatal("UTF-8 header bypassed strict JSON body validation", w.Code)
		}
		if _, err := s.loadApp(s.getPeer("nAlice")); !os.IsNotExist(err) || native.calls != 0 {
			t.Fatal("Invalid JSON mutated app or native state", err)
		}
	}
}
