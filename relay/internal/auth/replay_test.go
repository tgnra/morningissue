package auth

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func sigOf(b byte) []byte { return bytes.Repeat([]byte{b}, 32) }

func mustSeen(t *testing.T, r *ReplayCache, kid string, sig []byte, ts int64, now time.Time) bool {
	t.Helper()
	s, err := r.Seen(kid, sig, ts, now)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

// A signature whose ts is ahead of the relay's clock passes the skew check until the first whole
// second past ts + 300 s. It must be remembered that long, even when that is a fraction of a
// second more than 600 s after it was first seen.
func TestReplayAtTheEdgeOfSkew(t *testing.T) {
	r := NewReplayCache()
	first := time.Unix(1794466800, 500_000_000)
	ts := first.Unix() + 300
	if !InSkew(ts, first) || mustSeen(t, r, "k", sigOf(1), ts, first) {
		t.Fatal("first use refused")
	}
	again := first.Add(600*time.Second + 200*time.Millisecond)
	if !InSkew(ts, again) {
		t.Fatal("test assumption: still in skew")
	}
	if !mustSeen(t, r, "k", sigOf(1), ts, again) {
		t.Fatal("replay still in skew not caught")
	}
	if InSkew(ts, first.Add(601*time.Second)) {
		t.Fatal("test assumption: out of skew a second later")
	}
}

func TestReplayCacheSurvivesRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "replay")
	now := time.Unix(1794466800, 0)
	r, err := OpenReplayCache(path, now)
	if err != nil {
		t.Fatal(err)
	}
	if mustSeen(t, r, "a", sigOf(1), now.Unix(), now) || mustSeen(t, r, "b", sigOf(2), now.Unix(), now.Add(100*time.Second)) {
		t.Fatal("first use reported as replay")
	}
	// A crash: the file isn't closed, and the last append is torn.
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND, 0)
	if err != nil {
		t.Fatal(err)
	}
	f.WriteString("1794467999 c AAAA")
	f.Close()

	later := now.Add(500 * time.Second)
	r2, err := OpenReplayCache(path, later)
	if err != nil {
		t.Fatal(err)
	}
	if r2.Len() != 2 {
		t.Fatalf("Len after restart = %d, want 2", r2.Len())
	}
	if !mustSeen(t, r2, "a", sigOf(1), now.Unix(), later) || !mustSeen(t, r2, "b", sigOf(2), now.Unix(), later) {
		t.Fatal("replay across a restart not caught")
	}
	if mustSeen(t, r2, "a", sigOf(3), later.Unix(), later) {
		t.Fatal("new signature reported as replay")
	}

	// Expired signatures are dropped on start and by Prune, and the file is rewritten without them.
	r2.Prune(now.Add(650 * time.Second))
	if r2.Len() != 2 { // b, seen 100 s after a, and a's new signature
		t.Fatalf("Len after prune = %d, want 2", r2.Len())
	}
	if !mustSeen(t, r2, "a", sigOf(3), later.Unix(), now.Add(660*time.Second)) {
		t.Fatal("live signature lost by Prune")
	}
	b, _ := os.ReadFile(path)
	if n := bytes.Count(b, []byte("\n")); n != 2 {
		t.Fatalf("file has %d lines after prune, want 2:\n%s", n, b)
	}
	r2.Close()
	r3, err := OpenReplayCache(path, later.Add(600*time.Second))
	if err != nil {
		t.Fatal(err)
	}
	defer r3.Close()
	if r3.Len() != 0 {
		t.Fatalf("Len after the window = %d, want 0", r3.Len())
	}
	if _, err := os.Stat(path + ".tmp"); !os.IsNotExist(err) {
		t.Fatal("temporary file left")
	}
}

func TestReplayCacheRefusesUnstorableKid(t *testing.T) {
	r, err := OpenReplayCache(filepath.Join(t.TempDir(), "replay"), time.Unix(0, 0))
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	for _, kid := range []string{"", "a b", "a|b", "a\nb"} {
		if _, err := r.Seen(kid, sigOf(1), 0, time.Unix(0, 0)); err == nil {
			t.Errorf("kid %q accepted", kid)
		}
	}
}
