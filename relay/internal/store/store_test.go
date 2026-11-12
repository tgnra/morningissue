package store

import (
	"crypto/rand"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func key() []byte {
	k := make([]byte, 32)
	rand.Read(k)
	return k
}

// When the metadata can't be saved, a body already moved into place is removed again rather than
// left with nothing pointing at it until the next start.
func TestFailedSaveLeavesNoFile(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root ignores directory permissions")
	}
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Unix(1794466800, 0)
	gid, _, err := s.CreateGroup(key(), true, now)
	if err != nil {
		t.Fatal(err)
	}
	dev := key()
	kids, err := s.SetDevices(gid, [][]byte{dev})
	if err != nil {
		t.Fatal(err)
	}
	body := func() string {
		f, err := s.TempFile()
		if err != nil {
			t.Fatal(err)
		}
		f.WriteString("MIR1 body")
		f.Close()
		return f.Name()
	}

	// group.json can't be written; packages/ and changes/ still can.
	gdir := s.groupDir(gid)
	if err := os.Chmod(gdir, 0o500); err != nil {
		t.Fatal(err)
	}
	defer os.Chmod(gdir, 0o700)
	if err := s.PutChanges(kids[0], "1-1", body(), 9, now); err == nil {
		t.Fatal("PutChanges succeeded without saving")
	}
	if err := s.PutPackage(gid, NewID(), body(), 9, now); err == nil {
		t.Fatal("PutPackage succeeded without saving")
	}
	for _, sub := range []string{"changes", "packages"} {
		if m, _ := filepath.Glob(filepath.Join(gdir, sub, "*")); len(m) != 0 {
			t.Errorf("%s left behind: %v", sub, m)
		}
	}
	if g, _ := s.Snapshot(gid); len(g.Changes) != 0 || len(g.Uploads) != 0 {
		t.Errorf("metadata changed: %+v", g)
	}
}
