// Command relay is the Morning Issue relay server (protocol relay/1): a mailbox that stores the
// phone's encrypted packages and the Kobos' encrypted changes, and deletes them once used.
// See docs/relay-sync.md and relay/README.md.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/tgnra/morningissue/relay/internal/auth"
	"github.com/tgnra/morningissue/relay/internal/server"
	"github.com/tgnra/morningissue/relay/internal/store"
)

func main() {
	err := run(os.Args[1:])
	if errors.Is(err, flag.ErrHelp) {
		return
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "relay:", err)
		os.Exit(1)
	}
}

func env(name, def string) string {
	if v, ok := os.LookupEnv(name); ok {
		return v
	}
	return def
}

// parseSize reads a byte count: a plain number, or one ending in KiB, MiB or GiB.
func parseSize(s string) (int64, error) {
	mult := int64(1)
	for suffix, m := range map[string]int64{"KiB": 1 << 10, "MiB": 1 << 20, "GiB": 1 << 30} {
		if n, ok := strings.CutSuffix(s, suffix); ok {
			s, mult = n, m
			break
		}
	}
	n, err := strconv.ParseInt(strings.TrimSpace(s), 10, 64)
	if err != nil || n <= 0 {
		return 0, fmt.Errorf("bad size %q", s)
	}
	return n * mult, nil
}

func run(args []string) error {
	fs := flag.NewFlagSet("relay", flag.ContinueOnError)
	addr := fs.String("addr", env("RELAY_ADDR", ":8080"), "plain HTTP listen address (empty to disable) [RELAY_ADDR]")
	tlsAddr := fs.String("tls-addr", env("RELAY_TLS_ADDR", ""), "HTTPS listen address, optional [RELAY_TLS_ADDR]")
	tlsCert := fs.String("tls-cert", env("RELAY_TLS_CERT", ""), "TLS certificate file (PEM) [RELAY_TLS_CERT]")
	tlsKey := fs.String("tls-key", env("RELAY_TLS_KEY", ""), "TLS private key file (PEM) [RELAY_TLS_KEY]")
	dataDir := fs.String("data", env("RELAY_DATA", "data"), "data directory [RELAY_DATA]")
	token := fs.String("registration-token", env("RELAY_REGISTRATION_TOKEN", ""), "token needed to create a group; empty allows anyone [RELAY_REGISTRATION_TOKEN]")
	ttlStr := fs.String("ttl", env("RELAY_TTL", server.DefaultTTL.String()), "how long packages and changes are kept [RELAY_TTL]")
	maxStr := fs.String("max-package", env("RELAY_MAX_PACKAGE", "512MiB"), "package size limit, in bytes or with KiB/MiB/GiB [RELAY_MAX_PACKAGE]")
	partStr := fs.String("max-part", env("RELAY_MAX_PART", "64MiB"), "size limit of one part of a package uploaded in parts [RELAY_MAX_PART]")
	baseStr := fs.String("base-path", env("RELAY_BASE_PATH", ""), "serve the API under this path prefix, e.g. /morningissue [RELAY_BASE_PATH]")
	quiet := fs.Bool("quiet", env("RELAY_QUIET", "") != "", "don't log each request [RELAY_QUIET]")
	if err := fs.Parse(args); err != nil {
		return err
	}
	ttl, err := time.ParseDuration(*ttlStr)
	if err != nil || ttl <= 0 {
		return fmt.Errorf("bad -ttl %q", *ttlStr)
	}
	maxPackage, err := parseSize(*maxStr)
	if err != nil {
		return fmt.Errorf("-max-package: %w", err)
	}
	maxPart, err := parseSize(*partStr)
	if err != nil {
		return fmt.Errorf("-max-part: %w", err)
	}
	basePath, err := server.CleanBasePath(*baseStr)
	if err != nil {
		return fmt.Errorf("-base-path: %w", err)
	}
	if *addr == "" && *tlsAddr == "" {
		return errors.New("nothing to listen on: set -addr or -tls-addr")
	}
	if *tlsAddr != "" && (*tlsCert == "" || *tlsKey == "") {
		return errors.New("-tls-addr needs -tls-cert and -tls-key")
	}

	logger := log.New(os.Stderr, "", log.LstdFlags)
	st, err := store.Open(*dataDir)
	if err != nil {
		return fmt.Errorf("opening %s: %w", *dataDir, err)
	}
	// Signatures seen in the last 10 minutes, kept on disk so a replay is caught across a restart.
	replay, err := auth.OpenReplayCache(filepath.Join(*dataDir, "replay"), time.Now())
	if err != nil {
		return fmt.Errorf("opening the replay cache: %w", err)
	}
	defer replay.Close()
	srv := server.New(server.Config{
		Store:             st,
		Replay:            replay,
		TTL:               ttl,
		MaxPackage:        maxPackage,
		MaxPart:           maxPart,
		RegistrationToken: *token,
		BasePath:          basePath,
		Log:               logger,
		LogRequests:       !*quiet,
	})

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	sweepCtx, stopSweep := context.WithCancel(context.Background())
	var wg sync.WaitGroup
	wg.Add(1)
	go func() { defer wg.Done(); srv.RunSweeper(sweepCtx) }()

	newHTTP := func(a string) *http.Server {
		return &http.Server{
			Addr:              a,
			Handler:           srv,
			ReadHeaderTimeout: 10 * time.Second,
			IdleTimeout:       2 * time.Minute,
			ErrorLog:          logger,
			// No read or write timeout: packages are large and Kobos can be slow.
		}
	}
	var servers []*http.Server
	errc := make(chan error, 2)
	if *addr != "" {
		hs := newHTTP(*addr)
		servers = append(servers, hs)
		go func() { errc <- hs.ListenAndServe() }()
		logger.Printf("listening on %s (HTTP)", *addr)
	}
	if *tlsAddr != "" {
		hs := newHTTP(*tlsAddr)
		servers = append(servers, hs)
		go func() { errc <- hs.ListenAndServeTLS(*tlsCert, *tlsKey) }()
		logger.Printf("listening on %s (HTTPS)", *tlsAddr)
	}
	logger.Printf("data in %s, TTL %s, package limit %d bytes, part limit %d bytes, registration token %s",
		*dataDir, ttl, maxPackage, maxPart, map[bool]string{true: "required", false: "not required"}[*token != ""])
	if basePath != "" {
		logger.Printf("serving under %s/", basePath)
	}

	var runErr error
	select {
	case <-ctx.Done():
		logger.Printf("shutting down")
	case err := <-errc:
		if !errors.Is(err, http.ErrServerClosed) {
			runErr = err
		}
	}
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	for _, hs := range servers {
		if err := hs.Shutdown(shutdownCtx); err != nil {
			logger.Printf("shutdown: %v", err)
		}
	}
	stopSweep()
	wg.Wait()
	return runErr
}
