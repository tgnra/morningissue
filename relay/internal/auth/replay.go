package auth

import (
	"bufio"
	"crypto/sha256"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ReplayCache remembers signatures until they could no longer pass the skew check, and at least
// for ReplayWindow. It is safe for concurrent use.
//
// Opened with OpenReplayCache it also keeps them in a file, so that a restart doesn't forget
// them: each signature is appended and synced before Seen returns, so a request is never acted
// on before its signature would survive a crash. Prune rewrites the file with only the live
// signatures (temp file + rename), which keeps it to the requests of the last ten minutes or so.
//
// The file has one line per signature: "<expiry, unix seconds> <kid> <base64url sig>". A line
// that doesn't parse, such as one torn by a crash, is skipped: its request was never acted on.
type ReplayCache struct {
	mu   sync.Mutex
	seen map[string]time.Time // kid|sig -> when it expires
	path string               // the file, or "" when in memory only
	f    *os.File             // open for appending, when path is set
	// broken is set when an append failed, so the file has to be rewritten before the next one.
	broken bool
}

// NewReplayCache returns a cache kept in memory only, which a restart forgets.
func NewReplayCache() *ReplayCache {
	return &ReplayCache{seen: make(map[string]time.Time)}
}

// OpenReplayCache loads the signatures kept at path that are still live at now, creating the
// file if needed, and keeps every signature seen from then on there too.
func OpenReplayCache(path string, now time.Time) (*ReplayCache, error) {
	r := &ReplayCache{seen: make(map[string]time.Time), path: path}
	_ = os.Remove(path + ".tmp")
	f, err := os.Open(path)
	switch {
	case errors.Is(err, os.ErrNotExist):
	case err != nil:
		return nil, err
	default:
		sc := bufio.NewScanner(f)
		for sc.Scan() {
			if k, exp, ok := parseLine(sc.Text()); ok && now.Before(exp) {
				r.seen[k] = exp
			}
		}
		err := sc.Err()
		f.Close()
		if err != nil {
			return nil, fmt.Errorf("reading %s: %w", path, err)
		}
	}
	if err := r.rewrite(); err != nil {
		return nil, err
	}
	return r, nil
}

func parseLine(line string) (key string, exp time.Time, ok bool) {
	parts := strings.Split(line, " ")
	if len(parts) != 3 || parts[1] == "" {
		return "", time.Time{}, false
	}
	sec, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		return "", time.Time{}, false
	}
	sig, err := B64.DecodeString(parts[2])
	if err != nil || len(sig) != sha256.Size {
		return "", time.Time{}, false
	}
	return parts[1] + "|" + string(sig), time.Unix(sec, 0), true
}

func formatLine(key string, exp time.Time) string {
	kid, sig, _ := strings.Cut(key, "|")
	// Rounded up, so a signature is never forgotten early.
	sec := exp.Unix()
	if exp.After(time.Unix(sec, 0)) {
		sec++
	}
	return strconv.FormatInt(sec, 10) + " " + kid + " " + B64.EncodeToString([]byte(sig)) + "\n"
}

// rewrite replaces the file with the signatures in memory and keeps the new file open for
// appending. On failure the old file and handle stay in use. The caller holds r.mu, or owns r.
func (r *ReplayCache) rewrite() error {
	var b strings.Builder
	for k, exp := range r.seen {
		b.WriteString(formatLine(k, exp))
	}
	tmp := r.path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_TRUNC|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}
	fail := func(err error) error {
		f.Close()
		os.Remove(tmp)
		return err
	}
	if _, err := f.WriteString(b.String()); err != nil {
		return fail(err)
	}
	if err := f.Sync(); err != nil {
		return fail(err)
	}
	if err := os.Rename(tmp, r.path); err != nil {
		return fail(err)
	}
	if d, err := os.Open(filepath.Dir(r.path)); err == nil {
		_ = d.Sync()
		d.Close()
	}
	if r.f != nil {
		r.f.Close()
	}
	r.f = f // the handle follows the file to its new name
	r.broken = false
	return nil
}

// Seen records the signature of a request with timestamp ts, and reports whether it had already
// been seen. It returns an error when the signature couldn't be written to the file; the request
// should then be refused, although the signature is still remembered in memory.
func (r *ReplayCache) Seen(kid string, sig []byte, ts int64, now time.Time) (bool, error) {
	if kid == "" || strings.ContainsAny(kid, " |\n") {
		return false, fmt.Errorf("replay cache: kid %q can't be stored", kid)
	}
	k := kid + "|" + string(sig)
	r.mu.Lock()
	defer r.mu.Unlock()
	if exp, ok := r.seen[k]; ok && now.Before(exp) {
		return true, nil
	}
	// Until the skew check refuses ts (the first whole second past ts + MaxSkew), and at least
	// ReplayWindow from now.
	exp := now.Add(ReplayWindow)
	if last := time.Unix(ts, 0).Add(MaxSkew + time.Second); last.After(exp) {
		exp = last
	}
	r.seen[k] = exp
	switch {
	case r.path == "":
		return false, nil
	case r.f == nil:
		return false, errClosed
	case r.broken:
		// The file may end in a partial line: write it out whole, this signature included.
		return false, r.rewrite()
	}
	if _, err := r.f.WriteString(formatLine(k, exp)); err != nil {
		r.broken = true
		return false, err
	}
	if err := r.f.Sync(); err != nil {
		r.broken = true
		return false, err
	}
	return false, nil
}

// Prune forgets expired signatures, and rewrites the file without them.
func (r *ReplayCache) Prune(now time.Time) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	for k, exp := range r.seen {
		if !now.Before(exp) {
			delete(r.seen, k)
		}
	}
	switch {
	case r.path == "":
		return nil
	case r.f == nil:
		return errClosed
	}
	return r.rewrite()
}

// Len returns the number of remembered signatures.
func (r *ReplayCache) Len() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.seen)
}

var errClosed = errors.New("replay cache closed")

// Close closes the file. Seen fails from then on, so no request is accepted unrecorded.
func (r *ReplayCache) Close() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.f == nil {
		return nil
	}
	err := r.f.Close()
	r.f = nil
	return err
}
