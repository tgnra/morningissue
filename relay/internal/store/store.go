// Package store keeps the relay's state on local disk: one directory per group, holding a
// group.json with its metadata and the opaque blobs (packages and changes) as files.
//
//	<dir>/tmp/                      bodies being received
//	<dir>/groups/<gid>/group.json   metadata, written atomically (temp file + rename)
//	<dir>/groups/<gid>/packages/<packageId>
//	<dir>/groups/<gid>/changes/<changeId>
//
// Every change to a group is made on a copy, written to disk, and only then swapped in, so the
// in-memory state never runs ahead of what would survive a restart. Files that a change drops
// are removed after the metadata is written; anything left behind by a crash is removed by Open.
package store

import (
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"time"

	"github.com/tgnra/morningissue/relay/internal/auth"
)

const (
	// MaxDevices is the most devices a group may list.
	MaxDevices = 32
	// MaxPendingBatches is the most changes a device may have waiting.
	MaxPendingBatches = 64
	// UploadTTL is how long an unpublished package upload is kept.
	UploadTTL = time.Hour
	// IdleGroupTTL is how long a group is kept without a signed request from its owner.
	IdleGroupTTL = 90 * 24 * time.Hour
)

var (
	ErrNotFound = errors.New("not found")
	ErrConflict = errors.New("conflict")
	ErrTooMany  = errors.New("too many")
)

// Device is one paired device in a group.
type Device struct {
	Kid        string     `json:"kid"`
	AuthKey    []byte     `json:"authKey"`
	LastSeenAt *time.Time `json:"lastSeenAt,omitempty"`
}

// Package is the group's current, published package.
type Package struct {
	ID          string            `json:"id"`
	Size        int64             `json:"size"`
	UploadedAt  time.Time         `json:"uploadedAt"`
	PublishedAt time.Time         `json:"publishedAt"`
	Keys        map[string]string `json:"keys"` // device kid -> base64url wrapped-key envelope
	FetchedBy   []string          `json:"fetchedBy"`
}

// Upload is a package that has been uploaded but not published.
type Upload struct {
	Size       int64     `json:"size"`
	UploadedAt time.Time `json:"uploadedAt"`
}

// Change is one pending changes envelope from a device.
type Change struct {
	ID         string    `json:"id"`
	Kid        string    `json:"kid"`
	Batch      string    `json:"batch"`
	Size       int64     `json:"size"`
	ReceivedAt time.Time `json:"receivedAt"`
}

// Group is everything the relay holds for one phone.
type Group struct {
	ID          string             `json:"id"`
	OwnerKey    []byte             `json:"ownerKey"`
	CreatedAt   time.Time          `json:"createdAt"`
	LastOwnerAt time.Time          `json:"lastOwnerAt"`
	Devices     []Device           `json:"devices"`
	Package     *Package           `json:"package,omitempty"`
	Uploads     map[string]*Upload `json:"uploads,omitempty"`
	Changes     []Change           `json:"changes"` // oldest first
}

func (g *Group) device(kid string) *Device {
	for i := range g.Devices {
		if g.Devices[i].Kid == kid {
			return &g.Devices[i]
		}
	}
	return nil
}

func (g *Group) clone() *Group {
	b, err := json.Marshal(g)
	if err != nil {
		panic(err)
	}
	var c Group
	if err := json.Unmarshal(b, &c); err != nil {
		panic(err)
	}
	return &c
}

// Store is the relay's on-disk state. It is safe for concurrent use.
type Store struct {
	dir string

	mu      sync.Mutex
	groups  map[string]*Group
	devices map[string]string // device kid -> group ID
}

// Open loads the state under dir, creating it if needed, and removes anything left over from
// an unclean shutdown.
func Open(dir string) (*Store, error) {
	s := &Store{dir: dir, groups: map[string]*Group{}, devices: map[string]string{}}
	if err := os.RemoveAll(s.tmpDir()); err != nil {
		return nil, err
	}
	for _, d := range []string{s.tmpDir(), filepath.Join(dir, "groups")} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			return nil, err
		}
	}
	entries, err := os.ReadDir(filepath.Join(dir, "groups"))
	if err != nil {
		return nil, err
	}
	for _, e := range entries {
		gdir := filepath.Join(dir, "groups", e.Name())
		g, err := s.load(e.Name())
		if err != nil {
			log.Printf("store: removing unreadable group %s: %v", e.Name(), err)
			if err := os.RemoveAll(gdir); err != nil {
				return nil, err
			}
			continue
		}
		s.groups[g.ID] = g
		for _, d := range g.Devices {
			s.devices[d.Kid] = g.ID
		}
	}
	return s, nil
}

func (s *Store) tmpDir() string             { return filepath.Join(s.dir, "tmp") }
func (s *Store) groupDir(gid string) string { return filepath.Join(s.dir, "groups", gid) }
func (s *Store) packagePath(gid, pid string) string {
	return filepath.Join(s.groupDir(gid), "packages", pid)
}
func (s *Store) changePath(gid, id string) string {
	return filepath.Join(s.groupDir(gid), "changes", id)
}

// load reads one group and reconciles it with the files present: metadata pointing at a missing
// file is dropped, and files no metadata points at are removed.
func (s *Store) load(name string) (*Group, error) {
	gdir := s.groupDir(name)
	_ = os.Remove(filepath.Join(gdir, "group.json.tmp"))
	b, err := os.ReadFile(filepath.Join(gdir, "group.json"))
	if err != nil {
		return nil, err
	}
	var g Group
	if err := json.Unmarshal(b, &g); err != nil {
		return nil, err
	}
	if g.ID != name || g.ID != auth.Kid(g.OwnerKey) {
		return nil, fmt.Errorf("group ID %q does not match its directory or owner key", g.ID)
	}
	exists := func(p string) bool { _, err := os.Stat(p); return err == nil }
	dirty := false
	if g.Package != nil && !exists(s.packagePath(g.ID, g.Package.ID)) {
		g.Package, dirty = nil, true
	}
	for pid := range g.Uploads {
		if !exists(s.packagePath(g.ID, pid)) {
			delete(g.Uploads, pid)
			dirty = true
		}
	}
	kept := g.Changes[:0]
	for _, c := range g.Changes {
		if exists(s.changePath(g.ID, c.ID)) {
			kept = append(kept, c)
		} else {
			dirty = true
		}
	}
	g.Changes = kept
	removeUnlisted(filepath.Join(gdir, "packages"), func(n string) bool {
		_, up := g.Uploads[n]
		return up || (g.Package != nil && g.Package.ID == n)
	})
	removeUnlisted(filepath.Join(gdir, "changes"), func(n string) bool {
		return slices.ContainsFunc(g.Changes, func(c Change) bool { return c.ID == n })
	})
	if dirty {
		if err := s.save(&g); err != nil {
			return nil, err
		}
	}
	return &g, nil
}

func removeUnlisted(dir string, listed func(string) bool) {
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		if !listed(e.Name()) {
			_ = os.RemoveAll(filepath.Join(dir, e.Name()))
		}
	}
}

// save writes group.json atomically.
func (s *Store) save(g *Group) error {
	gdir := s.groupDir(g.ID)
	for _, d := range []string{gdir, filepath.Join(gdir, "packages"), filepath.Join(gdir, "changes")} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			return err
		}
	}
	b, err := json.MarshalIndent(g, "", "  ")
	if err != nil {
		return err
	}
	tmp := filepath.Join(gdir, "group.json.tmp")
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if _, err := f.Write(b); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	return os.Rename(tmp, filepath.Join(gdir, "group.json"))
}

// mutate applies fn to a copy of the group, saves the copy and swaps it in, then removes the
// files fn returned. The caller holds s.mu.
func (s *Store) mutate(gid string, fn func(g *Group) (remove []string, err error)) error {
	old, ok := s.groups[gid]
	if !ok {
		return ErrNotFound
	}
	g := old.clone()
	remove, err := fn(g)
	if err != nil {
		return err
	}
	if err := s.save(g); err != nil {
		return err
	}
	s.groups[gid] = g
	for _, d := range old.Devices {
		if s.devices[d.Kid] == gid && g.device(d.Kid) == nil {
			delete(s.devices, d.Kid)
		}
	}
	for _, d := range g.Devices {
		s.devices[d.Kid] = gid
	}
	for _, p := range remove {
		if err := os.Remove(p); err != nil && !errors.Is(err, os.ErrNotExist) {
			log.Printf("store: %v", err)
		}
	}
	return nil
}

// TempFile creates a file for receiving a body. The caller either commits it with PutPackage
// or PutChanges, which move it into place, or removes it.
func (s *Store) TempFile() (*os.File, error) {
	return os.CreateTemp(s.tmpDir(), "in-*")
}

// NewID returns a random blob ID: 16 bytes in base64url.
func NewID() string {
	b := make([]byte, 16)
	if _, err := io.ReadFull(rand.Reader, b); err != nil {
		panic(err)
	}
	return auth.B64.EncodeToString(b)
}

// OwnerKey returns a group's owner key.
func (s *Store) OwnerKey(gid string) ([]byte, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	g, ok := s.groups[gid]
	if !ok {
		return nil, false
	}
	return g.OwnerKey, true
}

// DeviceKey returns the group and auth key of a device.
func (s *Store) DeviceKey(kid string) (gid string, key []byte, ok bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	gid, ok = s.devices[kid]
	if !ok {
		return "", nil, false
	}
	return gid, s.groups[gid].device(kid).AuthKey, true
}

// Snapshot returns a copy of a group, for reading.
func (s *Store) Snapshot(gid string) (*Group, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	g, ok := s.groups[gid]
	if !ok {
		return nil, false
	}
	return g.clone(), true
}

// TouchOwner records a signed request from the group's owner.
func (s *Store) TouchOwner(gid string, now time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.mutate(gid, func(g *Group) ([]string, error) {
		g.LastOwnerAt = now.UTC()
		return nil, nil
	})
}

// TouchDevice records a signed request from a device.
func (s *Store) TouchDevice(kid string, now time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	gid, ok := s.devices[kid]
	if !ok {
		return ErrNotFound
	}
	return s.mutate(gid, func(g *Group) ([]string, error) {
		t := now.UTC()
		g.device(kid).LastSeenAt = &t
		return nil, nil
	})
}

// CreateGroup creates the group for ownerKey, or confirms it exists. When create is false a
// missing group is not created and ErrNotFound is returned.
func (s *Store) CreateGroup(ownerKey []byte, create bool, now time.Time) (gid string, created bool, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	gid = auth.Kid(ownerKey)
	if _, ok := s.groups[gid]; ok {
		return gid, false, nil
	}
	if !create {
		return gid, false, ErrNotFound
	}
	g := &Group{ID: gid, OwnerKey: ownerKey, CreatedAt: now.UTC(), LastOwnerAt: now.UTC(), Devices: []Device{}, Changes: []Change{}}
	if err := s.save(g); err != nil {
		_ = os.RemoveAll(s.groupDir(gid))
		return gid, false, err
	}
	s.groups[gid] = g
	return gid, true, nil
}

// dropDevice removes a device's pending changes, fetch record and wrapped key from g, and
// returns the files to remove.
func (s *Store) dropDevice(g *Group, kid string) []string {
	var remove []string
	kept := g.Changes[:0]
	for _, c := range g.Changes {
		if c.Kid == kid {
			remove = append(remove, s.changePath(g.ID, c.ID))
		} else {
			kept = append(kept, c)
		}
	}
	g.Changes = kept
	if g.Package != nil {
		g.Package.FetchedBy = slices.DeleteFunc(g.Package.FetchedBy, func(k string) bool { return k == kid })
		delete(g.Package.Keys, kid)
	}
	return remove
}

// SetDevices replaces the group's device list with these auth keys and returns their kids.
// A device that was in another group moves to this one, losing its state there.
func (s *Store) SetDevices(gid string, keys [][]byte) ([]string, error) {
	if len(keys) > MaxDevices {
		return nil, ErrTooMany
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.groups[gid]; !ok {
		return nil, ErrNotFound
	}
	var kids []string
	var devs []Device
	for _, k := range keys {
		kid := auth.Kid(k)
		if slices.Contains(kids, kid) {
			continue
		}
		kids = append(kids, kid)
		devs = append(devs, Device{Kid: kid, AuthKey: slices.Clone(k)})
		if other, ok := s.devices[kid]; ok && other != gid {
			err := s.mutate(other, func(g *Group) ([]string, error) {
				remove := s.dropDevice(g, kid)
				g.Devices = slices.DeleteFunc(g.Devices, func(d Device) bool { return d.Kid == kid })
				return remove, nil
			})
			if err != nil {
				return nil, err
			}
		}
	}
	if kids == nil {
		kids = []string{}
	}
	err := s.mutate(gid, func(g *Group) ([]string, error) {
		var remove []string
		for _, d := range g.Devices {
			if !slices.Contains(kids, d.Kid) {
				remove = append(remove, s.dropDevice(g, d.Kid)...)
			}
		}
		for i := range devs {
			if old := g.device(devs[i].Kid); old != nil {
				devs[i].LastSeenAt = old.LastSeenAt
			}
		}
		g.Devices = append([]Device{}, devs...)
		return remove, nil
	})
	if err != nil {
		return nil, err
	}
	return kids, nil
}

// PutPackage moves an uploaded package from tmpPath into place as an unpublished upload.
// Uploading again under the same ID replaces it; the current package can't be replaced.
func (s *Store) PutPackage(gid, pid, tmpPath string, size int64, now time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.mutate(gid, func(g *Group) ([]string, error) {
		if g.Package != nil && g.Package.ID == pid {
			return nil, ErrConflict
		}
		if err := os.MkdirAll(filepath.Dir(s.packagePath(gid, pid)), 0o700); err != nil {
			return nil, err
		}
		if err := os.Rename(tmpPath, s.packagePath(gid, pid)); err != nil {
			return nil, err
		}
		if g.Uploads == nil {
			g.Uploads = map[string]*Upload{}
		}
		g.Uploads[pid] = &Upload{Size: size, UploadedAt: now.UTC()}
		return nil, nil
	})
}

// Publish makes an uploaded package current, with keys for the listed devices (keys for
// devices not in the group are ignored). The previous package is deleted. Publishing the
// current package again replaces its keys.
func (s *Store) Publish(gid, pid string, keys map[string]string, now time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.mutate(gid, func(g *Group) ([]string, error) {
		filtered := map[string]string{}
		for kid, k := range keys {
			if g.device(kid) != nil {
				filtered[kid] = k
			}
		}
		if g.Package != nil && g.Package.ID == pid {
			g.Package.Keys = filtered
			return nil, nil
		}
		up, ok := g.Uploads[pid]
		if !ok {
			return nil, ErrConflict
		}
		var remove []string
		if g.Package != nil {
			remove = append(remove, s.packagePath(gid, g.Package.ID))
		}
		g.Package = &Package{ID: pid, Size: up.Size, UploadedAt: up.UploadedAt, PublishedAt: now.UTC(), Keys: filtered, FetchedBy: []string{}}
		delete(g.Uploads, pid)
		return remove, nil
	})
}

// OpenChange opens a pending changes envelope.
func (s *Store) OpenChange(gid, id string) (*os.File, Change, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	g, ok := s.groups[gid]
	if !ok {
		return nil, Change{}, ErrNotFound
	}
	i := slices.IndexFunc(g.Changes, func(c Change) bool { return c.ID == id })
	if i < 0 {
		return nil, Change{}, ErrNotFound
	}
	f, err := os.Open(s.changePath(gid, id))
	if err != nil {
		return nil, Change{}, err
	}
	return f, g.Changes[i], nil
}

// Done deletes the given changes, and the current package if every listed device has fetched it.
func (s *Store) Done(gid string, ids []string) (deleted int, packageDeleted bool, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	err = s.mutate(gid, func(g *Group) ([]string, error) {
		var remove []string
		kept := g.Changes[:0]
		for _, c := range g.Changes {
			if slices.Contains(ids, c.ID) {
				remove = append(remove, s.changePath(gid, c.ID))
				deleted++
			} else {
				kept = append(kept, c)
			}
		}
		g.Changes = kept
		if p := g.Package; p != nil {
			all := true
			for _, d := range g.Devices {
				if !slices.Contains(p.FetchedBy, d.Kid) {
					all = false
					break
				}
			}
			if all {
				remove = append(remove, s.packagePath(gid, p.ID))
				g.Package = nil
				packageDeleted = true
			}
		}
		return remove, nil
	})
	return deleted, packageDeleted, err
}

// DeleteGroup removes a group and everything in it.
func (s *Store) DeleteGroup(gid string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.deleteGroup(gid)
}

func (s *Store) deleteGroup(gid string) error {
	g, ok := s.groups[gid]
	if !ok {
		return ErrNotFound
	}
	delete(s.groups, gid)
	for _, d := range g.Devices {
		if s.devices[d.Kid] == gid {
			delete(s.devices, d.Kid)
		}
	}
	return os.RemoveAll(s.groupDir(gid))
}

// DevicePackage returns the current package of a device's group and the device's wrapped key
// for it. ok is false when there is no package, or none for this device.
func (s *Store) DevicePackage(kid string) (p Package, key string, ok bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	g := s.groups[s.devices[kid]]
	if g == nil || g.Package == nil {
		return Package{}, "", false
	}
	key, ok = g.Package.Keys[kid]
	if !ok {
		return Package{}, "", false
	}
	return *g.Package, key, true
}

// OpenPackage opens the current package for a device that has a key for it.
func (s *Store) OpenPackage(kid, pid string) (*os.File, Package, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	g := s.groups[s.devices[kid]]
	if g == nil || g.Package == nil || g.Package.ID != pid {
		return nil, Package{}, ErrNotFound
	}
	if _, ok := g.Package.Keys[kid]; !ok {
		return nil, Package{}, ErrNotFound
	}
	f, err := os.Open(s.packagePath(g.ID, pid))
	if err != nil {
		return nil, Package{}, err
	}
	return f, *g.Package, nil
}

// MarkFetched records that a device has the current package.
func (s *Store) MarkFetched(kid, pid string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	gid, ok := s.devices[kid]
	if !ok {
		return ErrNotFound
	}
	return s.mutate(gid, func(g *Group) ([]string, error) {
		if g.Package == nil || g.Package.ID != pid {
			return nil, ErrNotFound
		}
		if !slices.Contains(g.Package.FetchedBy, kid) {
			g.Package.FetchedBy = append(g.Package.FetchedBy, kid)
		}
		return nil, nil
	})
}

// PutChanges moves a changes envelope from tmpPath into place. The same batch from the same
// device replaces the earlier one, under a new ID.
func (s *Store) PutChanges(kid, batch, tmpPath string, size int64, now time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	gid, ok := s.devices[kid]
	if !ok {
		return ErrNotFound
	}
	return s.mutate(gid, func(g *Group) ([]string, error) {
		var remove []string
		pending := 0
		kept := g.Changes[:0]
		for _, c := range g.Changes {
			if c.Kid == kid && c.Batch == batch {
				remove = append(remove, s.changePath(gid, c.ID))
				continue
			}
			if c.Kid == kid {
				pending++
			}
			kept = append(kept, c)
		}
		if pending >= MaxPendingBatches {
			return nil, ErrTooMany
		}
		g.Changes = kept
		id := NewID()
		if err := os.MkdirAll(filepath.Dir(s.changePath(gid, id)), 0o700); err != nil {
			return nil, err
		}
		if err := os.Rename(tmpPath, s.changePath(gid, id)); err != nil {
			return nil, err
		}
		g.Changes = append(g.Changes, Change{ID: id, Kid: kid, Batch: batch, Size: size, ReceivedAt: now.UTC()})
		return remove, nil
	})
}

// Sweep deletes what has expired: packages and changes older than ttl, unpublished uploads
// older than UploadTTL, and groups whose owner has been idle for IdleGroupTTL.
func (s *Store) Sweep(now time.Time, ttl time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	var errs []error
	for gid, g := range s.groups {
		if now.Sub(g.LastOwnerAt) > IdleGroupTTL {
			errs = append(errs, s.deleteGroup(gid))
			continue
		}
		expired := g.Package != nil && now.Sub(g.Package.PublishedAt) > ttl
		for _, u := range g.Uploads {
			expired = expired || now.Sub(u.UploadedAt) > UploadTTL
		}
		for _, c := range g.Changes {
			expired = expired || now.Sub(c.ReceivedAt) > ttl
		}
		if !expired {
			continue
		}
		errs = append(errs, s.mutate(gid, func(g *Group) ([]string, error) {
			var remove []string
			if g.Package != nil && now.Sub(g.Package.PublishedAt) > ttl {
				remove = append(remove, s.packagePath(gid, g.Package.ID))
				g.Package = nil
			}
			for pid, u := range g.Uploads {
				if now.Sub(u.UploadedAt) > UploadTTL {
					remove = append(remove, s.packagePath(gid, pid))
					delete(g.Uploads, pid)
				}
			}
			kept := g.Changes[:0]
			for _, c := range g.Changes {
				if now.Sub(c.ReceivedAt) > ttl {
					remove = append(remove, s.changePath(gid, c.ID))
				} else {
					kept = append(kept, c)
				}
			}
			g.Changes = kept
			return remove, nil
		}))
	}
	return errors.Join(errs...)
}
