// bridge-pc-check hosts the private PC bridge and optional SDK round-trip checks.
package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"time"

	"phonepcbridge/files"
	"phonepcbridge/pc"
	"tailscale.com/tsnet"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run() (result error) {
	state := flag.String("state", "", "Existing private app-state directory")
	peer := flag.String("peer", "", "Local producer peer, or initial Android node for explicit -roundtrip; ignored by ordinary host startup")
	backendPeer := flag.String("backend-peer", "", "Exact enrolled Pi backend identity")
	codex := flag.String("codex", "", "Native Codex executable when no packaged runtime is installed")
	roundtrip := flag.Bool("roundtrip", false, "Queue one random challenge and verify its returned bytes")
	fileOperation := flag.String("files-op", "", "Local file producer command; does not start another bridge node")
	fileID := flag.String("id", "", "Stable managed transfer ID")
	filePath := flag.String("file", "", "Absolute source or new destination file")
	appID := flag.String("app-id", "", "Exact app ID from the mandatory job handoff; queue only")
	jobID := flag.String("job-id", "", "Exact job ID from the mandatory job handoff; queue only")
	threadID := flag.String("thread-id", "", "Exact owned thread from the mandatory job handoff; queue only")
	cursor := flag.String("cursor", "", "Stable file page cursor")
	limit := flag.Int("limit", 25, "File page size, 1–100")
	flag.Parse()
	if *fileOperation != "" {
		if flag.NArg() != 0 || *roundtrip {
			return errors.New("Local commands cannot start a competing host")
		}
		return producerCommand(*state, *peer, *fileOperation, *fileID, *filePath, *cursor, *limit, *appID, *jobID, *threadID)
	}
	if *appID != "" || *jobID != "" || *threadID != "" {
		return errors.New("Job owner flags require the local queue operation")
	}
	if flag.NArg() != 0 || !filepath.IsAbs(*state) {
		return errors.New("Require an absolute private state path")
	}
	info, err := os.Lstat(*state)
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return errors.New("Provision the private state directory before starting")
	}
	*backendPeer, err = resolveBackendIdentity(*state, *backendPeer)
	if err != nil {
		return err
	}
	testPeer := ""
	if *roundtrip {
		testPeer, err = resolvePeerIdentity(*state, *peer)
		if err != nil {
			return err
		}
		if testPeer == *backendPeer {
			return errors.New("Round-trip Android peer must differ from the Pi backend")
		}
	}
	hostExecutable, err := os.Executable()
	if err != nil {
		return err
	}
	codexPath, err := resolveCodexExecutable(hostExecutable, *codex)
	if err != nil {
		return err
	}
	if err := os.Setenv("TS_LOGS_DIR", *state); err != nil {
		return err
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt)
	defer cancel()
	node := &tsnet.Server{Dir: *state, Hostname: "bridge-pc-check", Logf: func(string, ...any) {}, UserLogf: func(string, ...any) {}}
	defer node.Close()
	if err := node.Start(); err != nil {
		return err
	}
	lc, err := node.LocalClient()
	if err != nil {
		return err
	}
	enrollment, stopEnrollment := context.WithTimeout(ctx, 15*time.Minute)
	defer stopEnrollment()
	status, err := waitForPCEnrollment(enrollment, lc, *backendPeer, testPeer)
	if err != nil {
		return err
	}
	cfg := pc.Config{Identity: string(status.Self.ID), Host: strings.TrimSuffix(status.Self.DNSName, "."), CodexExecutable: codexPath, BackendPeer: *backendPeer, AllowedPeers: []string{*backendPeer}, DataDir: filepath.Join(*state, "files"), IntegrationDir: filepath.Dir(filepath.Dir(hostExecutable))}
	if testPeer != "" {
		cfg.AllowedPeers = append(cfg.AllowedPeers, testPeer)
	}
	if err := os.MkdirAll(cfg.DataDir, 0700); err != nil {
		return err
	}
	service, err := pc.New(node, cfg)
	if err != nil {
		return err
	}
	defer func() { result = errors.Join(result, service.Close()) }()
	backendProducer, err := startNamedProducer(*state, service, cfg.Identity, *backendPeer, "producer-"+*backendPeer+".json")
	if err != nil {
		return err
	}
	defer backendProducer.Close()
	if testPeer != "" {
		producer, err := startProducer(*state, service, cfg.Identity, testPeer)
		if err != nil {
			return err
		}
		defer producer.Close()
	}
	startup := map[string]string{"pcIdentity": cfg.Identity, "pcHost": cfg.Host, "backendPeer": *backendPeer}
	if testPeer != "" {
		startup["pairedPeer"] = testPeer
	}
	if err := json.NewEncoder(os.Stdout).Encode(startup); err != nil {
		return err
	}
	finished := make(chan error, 1)
	go func() { finished <- service.Serve(ctx) }()
	if *roundtrip {
		if err := checkRoundtrip(ctx, service, testPeer); err != nil {
			return err
		}
		cancel()
	}
	return <-finished
}

func checkRoundtrip(ctx context.Context, service *pc.Service, peer string) (result error) {
	id, err := files.NewID()
	if err != nil {
		return err
	}
	challenge := make([]byte, 4096)
	if _, err := rand.Read(challenge); err != nil {
		return err
	}
	name := "bridge-roundtrip-" + id + ".bin"
	offer, err := service.QueueFile(ctx, peer, id, name, challenge)
	if err != nil {
		return err
	}
	defer func() {
		if err := service.RemoveQueuedFile(peer, id); err != nil && result == nil {
			result = err
		}
	}()
	if err := json.NewEncoder(os.Stdout).Encode(map[string]any{"roundtripChallenge": offer}); err != nil {
		return err
	}
	deadline, cancel := context.WithTimeout(ctx, 15*time.Minute)
	defer cancel()
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	cursor := ""
	for {
		if deadline.Err() != nil {
			return errors.New("Actual PC-tablet file roundtrip remains unverified")
		}
		page, err := service.ReceivedFiles(deadline, peer, cursor, 100)
		if err != nil {
			return err
		}
		cursor, err = advanceRoundtripCursor(cursor, page.NextCursor)
		if err != nil {
			return err
		}
		for _, f := range page.Files {
			if f.Name != name {
				continue
			}
			_, actual, err := service.ReadReceivedFile(deadline, peer, f.ID)
			if err != nil {
				return err
			}
			if !bytes.Equal(actual, challenge) {
				return errors.New("Returned file differs from the independent PC challenge")
			}
			fmt.Println("EMBEDDED_PC_TABLET_ROUNDTRIP_VERIFIED", len(actual))
			// Keep receipts available until the controller observes Android completion.
			fmt.Println("Press Enter after the tablet confirms receipt and cleanup.")
			input := make(chan error, 1)
			go func() { _, err := bufio.NewReader(os.Stdin).ReadString('\n'); input <- err }()
			select {
			case err := <-input:
				if err != nil {
					return errors.New("Tablet completion was not confirmed before input closed")
				}
			case <-deadline.Done():
				return errors.New("Tablet completion remains unconfirmed")
			}
			if err := service.RemoveReceivedFile(peer, f.ID); err != nil {
				return err
			}
			return nil
		}
		select {
		case <-deadline.Done():
			return errors.New("Actual PC-tablet file roundtrip remains unverified")
		case <-ticker.C:
		}
	}
}

func advanceRoundtripCursor(cursor, next string) (string, error) {
	if next != "" && (!files.ValidID(next) || next <= cursor) {
		return "", errors.New("Received file pagination cursor is malformed or did not advance")
	}
	return next, nil
}
