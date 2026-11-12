// Package server is the relay/1 HTTP API. See docs/relay-sync.md, "Relay API".
package server

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"hash"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strconv"
	"time"

	"github.com/tgnra/morningissue/relay/internal/auth"
	"github.com/tgnra/morningissue/relay/internal/store"
)

const (
	// Protocol is what GET /v1/health reports.
	Protocol = "relay/1"
	// DefaultTTL is how long packages and changes are kept.
	DefaultTTL = 48 * time.Hour
	// DefaultMaxPackage is the default package size limit.
	DefaultMaxPackage = 512 << 20
	// MaxChanges is the size limit of one changes envelope.
	MaxChanges = 4 << 20
	// SweepInterval is how often the sweeper runs.
	SweepInterval = time.Minute

	maxJSON       = 1 << 20 // JSON request bodies
	maxWrappedKey = 4 << 10 // one wrapped-key envelope, decoded
	magic         = "MIR1"
)

var (
	blobID  = regexp.MustCompile(`^[A-Za-z0-9_-]{16,64}$`)
	batchRe = regexp.MustCompile(`^(0|[1-9][0-9]*)-(0|[1-9][0-9]*)$`)
)

// Config configures a Server.
type Config struct {
	Store *store.Store
	// Now is the relay's clock. Defaults to time.Now.
	Now func() time.Time
	// TTL is how long packages and changes are kept. Defaults to DefaultTTL.
	TTL time.Duration
	// MaxPackage is the package size limit in bytes. Defaults to DefaultMaxPackage.
	MaxPackage int64
	// RegistrationToken, when set, is needed to create a group.
	RegistrationToken string
	// Log receives errors, and one line per request when LogRequests is set. Nil means no logging.
	Log         *log.Logger
	LogRequests bool
}

// Server serves the relay API.
type Server struct {
	cfg    Config
	replay *auth.ReplayCache
	mux    *http.ServeMux
}

// New returns a Server for cfg.
func New(cfg Config) *Server {
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	if cfg.TTL <= 0 {
		cfg.TTL = DefaultTTL
	}
	if cfg.MaxPackage <= 0 {
		cfg.MaxPackage = DefaultMaxPackage
	}
	s := &Server{cfg: cfg, replay: auth.NewReplayCache(), mux: http.NewServeMux()}
	m := s.mux
	m.HandleFunc("GET /v1/health", s.health)
	m.HandleFunc("PUT /v1/group", s.putGroup)
	m.HandleFunc("DELETE /v1/group", s.deleteGroup)
	m.HandleFunc("PUT /v1/group/devices", s.putDevices)
	m.HandleFunc("PUT /v1/group/packages/{packageId}", s.putPackage)
	m.HandleFunc("POST /v1/group/packages/{packageId}/publish", s.publish)
	m.HandleFunc("GET /v1/group/status", s.status)
	m.HandleFunc("GET /v1/group/changes", s.listChanges)
	m.HandleFunc("GET /v1/group/changes/{id}", s.getChange)
	m.HandleFunc("POST /v1/group/done", s.done)
	m.HandleFunc("GET /v1/package", s.devicePackage)
	m.HandleFunc("GET /v1/packages/{packageId}", s.getPackage)
	m.HandleFunc("POST /v1/packages/{packageId}/fetched", s.fetched)
	m.HandleFunc("PUT /v1/changes/{batch}", s.putChanges)
	m.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) { writeError(w, http.StatusNotFound, "not_found") })
	return s
}

// ServeHTTP sets Date from the relay's clock on every response and logs the request.
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	start := time.Now()
	w.Header().Set("Date", s.cfg.Now().UTC().Format(http.TimeFormat))
	rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
	s.mux.ServeHTTP(rec, r)
	if s.cfg.Log != nil && s.cfg.LogRequests {
		s.cfg.Log.Printf("%s %s %d %dB %s", r.Method, r.URL.Path, rec.status, rec.written, time.Since(start).Round(time.Millisecond))
	}
}

type statusRecorder struct {
	http.ResponseWriter
	status  int
	written int64
}

func (r *statusRecorder) WriteHeader(code int) { r.status = code; r.ResponseWriter.WriteHeader(code) }
func (r *statusRecorder) Write(b []byte) (int, error) {
	n, err := r.ResponseWriter.Write(b)
	r.written += int64(n)
	return n, err
}
func (r *statusRecorder) Unwrap() http.ResponseWriter { return r.ResponseWriter }

// Sweep deletes expired data and forgets expired signatures.
func (s *Server) Sweep() error {
	now := s.cfg.Now()
	s.replay.Prune(now)
	return s.cfg.Store.Sweep(now, s.cfg.TTL)
}

// RunSweeper calls Sweep every SweepInterval until ctx is done.
func (s *Server) RunSweeper(ctx context.Context) {
	t := time.NewTicker(SweepInterval)
	defer t.Stop()
	for {
		if err := s.Sweep(); err != nil && s.cfg.Log != nil {
			s.cfg.Log.Printf("sweep: %v", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// ---- responses

func writeJSON(w http.ResponseWriter, status int, v any) {
	b, err := json.Marshal(v)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Content-Length", strconv.Itoa(len(b)))
	w.WriteHeader(status)
	_, _ = w.Write(b)
}

func writeError(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]string{"error": code})
}

func (s *Server) internal(w http.ResponseWriter, err error) {
	if s.cfg.Log != nil {
		s.cfg.Log.Printf("error: %v", err)
	}
	writeError(w, http.StatusInternalServerError, "internal")
}

// storeError maps a store error to a response.
func (s *Server) storeError(w http.ResponseWriter, err error, conflictCode string) {
	switch {
	case errors.Is(err, store.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found")
	case errors.Is(err, store.ErrConflict):
		writeError(w, http.StatusConflict, conflictCode)
	case errors.Is(err, store.ErrTooMany):
		writeError(w, http.StatusTooManyRequests, "too_many")
	default:
		s.internal(w, err)
	}
}

func timeString(t time.Time) string { return t.UTC().Format(time.RFC3339) }

// ---- signing

type kind int

const (
	ownerKind kind = iota
	deviceKind
)

// caller is an authenticated request's origin.
type caller struct {
	creds auth.Credentials
	key   []byte
	gid   string // the group, for both kinds
	kid   string // the device's kid, for deviceKind
}

// pathAndQuery is the request target exactly as sent.
func pathAndQuery(r *http.Request) string {
	if len(r.RequestURI) > 0 && r.RequestURI[0] == '/' {
		return r.RequestURI
	}
	if u, err := url.Parse(r.RequestURI); err == nil && u.Path != "" {
		return u.RequestURI()
	}
	return r.URL.RequestURI()
}

func unauthorized(w http.ResponseWriter, code string) {
	writeError(w, http.StatusUnauthorized, code)
}

// preauth checks everything that doesn't need the body: the header, the clock and the kid.
// It writes the 401 itself when it fails.
func (s *Server) preauth(w http.ResponseWriter, r *http.Request, k kind) (*caller, bool) {
	creds, err := auth.Parse(r.Header.Get("Authorization"))
	if err != nil {
		unauthorized(w, "signature")
		return nil, false
	}
	if !auth.InSkew(creds.TS, s.cfg.Now()) {
		unauthorized(w, "skew")
		return nil, false
	}
	c := &caller{creds: creds}
	switch k {
	case ownerKind:
		key, ok := s.cfg.Store.OwnerKey(creds.Kid)
		if !ok {
			unauthorized(w, "unknown")
			return nil, false
		}
		c.key, c.gid = key, creds.Kid
	case deviceKind:
		gid, key, ok := s.cfg.Store.DeviceKey(creds.Kid)
		if !ok {
			unauthorized(w, "unknown")
			return nil, false
		}
		c.key, c.gid, c.kid = key, gid, creds.Kid
	}
	return c, true
}

// finish checks the signature over the body hash and the replay cache, then records the
// caller's activity.
func (s *Server) finish(w http.ResponseWriter, r *http.Request, c *caller, k kind, bodyHash []byte) bool {
	if !auth.Verify(c.key, c.creds, r.Method, pathAndQuery(r), bodyHash) {
		unauthorized(w, "signature")
		return false
	}
	now := s.cfg.Now()
	if s.replay.Seen(c.creds.Kid, c.creds.Sig, now) {
		unauthorized(w, "replay")
		return false
	}
	var err error
	if k == ownerKind {
		err = s.cfg.Store.TouchOwner(c.gid, now)
	} else {
		err = s.cfg.Store.TouchDevice(c.kid, now)
	}
	if errors.Is(err, store.ErrNotFound) {
		// Removed between preauth and now.
		unauthorized(w, "unknown")
		return false
	}
	if err != nil {
		s.internal(w, err)
		return false
	}
	return true
}

var errTooLarge = errors.New("too large")

// readSmall reads a small body whole, up to limit bytes.
func readSmall(r *http.Request, limit int64) ([]byte, error) {
	if r.ContentLength > limit {
		return nil, errTooLarge
	}
	b, err := io.ReadAll(io.LimitReader(r.Body, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(b)) > limit {
		return nil, errTooLarge
	}
	return b, nil
}

// authSmall authenticates a request whose body is small enough to hold in memory, and returns
// the body.
func (s *Server) authSmall(w http.ResponseWriter, r *http.Request, k kind) (*caller, []byte, bool) {
	c, ok := s.preauth(w, r, k)
	if !ok {
		return nil, nil, false
	}
	body, err := readSmall(r, maxJSON)
	if errors.Is(err, errTooLarge) {
		writeError(w, http.StatusRequestEntityTooLarge, "too_large")
		return nil, nil, false
	}
	if err != nil {
		writeError(w, http.StatusBadRequest, "malformed")
		return nil, nil, false
	}
	h := sha256.Sum256(body)
	if !s.finish(w, r, c, k, h[:]) {
		return nil, nil, false
	}
	return c, body, true
}

// received is a body streamed to a temporary file.
type received struct {
	path string
	size int64
	hash []byte
	head []byte // the first bytes, up to 4
}

type headWriter struct{ b []byte }

func (h *headWriter) Write(p []byte) (int, error) {
	if n := len(magic) - len(h.b); n > 0 {
		h.b = append(h.b, p[:min(n, len(p))]...)
	}
	return len(p), nil
}

// receive streams the body to a temporary file, hashing it, up to limit bytes. On success the
// caller owns the file at path.
func (s *Server) receive(r *http.Request, limit int64) (*received, error) {
	if r.ContentLength > limit {
		return nil, errTooLarge
	}
	f, err := s.cfg.Store.TempFile()
	if err != nil {
		return nil, err
	}
	ok := false
	defer func() {
		if !ok {
			f.Close()
			os.Remove(f.Name())
		}
	}()
	var h hash.Hash = sha256.New()
	head := &headWriter{}
	n, err := io.Copy(io.MultiWriter(f, h, head), io.LimitReader(r.Body, limit+1))
	if err != nil {
		return nil, err
	}
	if n > limit {
		return nil, errTooLarge
	}
	if err := f.Sync(); err != nil {
		return nil, err
	}
	if err := f.Close(); err != nil {
		return nil, err
	}
	ok = true
	return &received{path: f.Name(), size: n, hash: h.Sum(nil), head: head.b}, nil
}

// authStream authenticates a request with a large body, streaming the body to disk first.
// On success the caller must commit or remove the received file.
func (s *Server) authStream(w http.ResponseWriter, r *http.Request, k kind, limit int64, valid func() bool) (*caller, *received, bool) {
	c, ok := s.preauth(w, r, k)
	if !ok {
		return nil, nil, false
	}
	if !valid() {
		writeError(w, http.StatusBadRequest, "malformed")
		return nil, nil, false
	}
	rc, err := s.receive(r, limit)
	if errors.Is(err, errTooLarge) {
		writeError(w, http.StatusRequestEntityTooLarge, "too_large")
		return nil, nil, false
	}
	if err != nil {
		writeError(w, http.StatusBadRequest, "malformed")
		return nil, nil, false
	}
	if !s.finish(w, r, c, k, rc.hash) {
		os.Remove(rc.path)
		return nil, nil, false
	}
	if string(rc.head) != magic {
		os.Remove(rc.path)
		writeError(w, http.StatusBadRequest, "not_mir1")
		return nil, nil, false
	}
	return c, rc, true
}

// ---- public

func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "protocol": Protocol})
}

// ---- owner

func (s *Server) putGroup(w http.ResponseWriter, r *http.Request) {
	creds, err := auth.Parse(r.Header.Get("Authorization"))
	if err != nil {
		unauthorized(w, "signature")
		return
	}
	if !auth.InSkew(creds.TS, s.cfg.Now()) {
		unauthorized(w, "skew")
		return
	}
	body, err := readSmall(r, maxJSON)
	if errors.Is(err, errTooLarge) {
		writeError(w, http.StatusRequestEntityTooLarge, "too_large")
		return
	}
	var req struct {
		OwnerKey string `json:"ownerKey"`
	}
	if err != nil || json.Unmarshal(body, &req) != nil {
		writeError(w, http.StatusBadRequest, "malformed")
		return
	}
	key, err := auth.B64.DecodeString(req.OwnerKey)
	if err != nil || len(key) != 32 {
		writeError(w, http.StatusBadRequest, "malformed")
		return
	}
	// The kid must be the body's key, and the request signed with it.
	if subtle.ConstantTimeCompare([]byte(creds.Kid), []byte(auth.Kid(key))) != 1 {
		unauthorized(w, "signature")
		return
	}
	h := sha256.Sum256(body)
	if !auth.Verify(key, creds, r.Method, pathAndQuery(r), h[:]) {
		unauthorized(w, "signature")
		return
	}
	now := s.cfg.Now()
	if s.replay.Seen(creds.Kid, creds.Sig, now) {
		unauthorized(w, "replay")
		return
	}
	create := true
	if tok := s.cfg.RegistrationToken; tok != "" {
		create = subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Relay-Registration")), []byte(tok)) == 1
	}
	gid, created, err := s.cfg.Store.CreateGroup(key, create, now)
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusForbidden, "registration")
		return
	}
	if err != nil {
		s.internal(w, err)
		return
	}
	if err := s.cfg.Store.TouchOwner(gid, now); err != nil {
		s.internal(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"group": gid, "created": created})
}

func (s *Server) deleteGroup(w http.ResponseWriter, r *http.Request) {
	c, _, ok := s.authSmall(w, r, ownerKind)
	if !ok {
		return
	}
	if err := s.cfg.Store.DeleteGroup(c.gid); err != nil {
		s.storeError(w, err, "conflict")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) putDevices(w http.ResponseWriter, r *http.Request) {
	c, body, ok := s.authSmall(w, r, ownerKind)
	if !ok {
		return
	}
	var req struct {
		Devices *[]string `json:"devices"`
	}
	if json.Unmarshal(body, &req) != nil || req.Devices == nil {
		writeError(w, http.StatusBadRequest, "malformed")
		return
	}
	if len(*req.Devices) > store.MaxDevices {
		writeError(w, http.StatusTooManyRequests, "too_many")
		return
	}
	keys := make([][]byte, 0, len(*req.Devices))
	for _, d := range *req.Devices {
		k, err := auth.B64.DecodeString(d)
		if err != nil || len(k) != 32 {
			writeError(w, http.StatusBadRequest, "malformed")
			return
		}
		keys = append(keys, k)
	}
	kids, err := s.cfg.Store.SetDevices(c.gid, keys)
	if err != nil {
		s.storeError(w, err, "conflict")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"devices": kids})
}

func (s *Server) putPackage(w http.ResponseWriter, r *http.Request) {
	pid := r.PathValue("packageId")
	c, rc, ok := s.authStream(w, r, ownerKind, s.cfg.MaxPackage, func() bool { return blobID.MatchString(pid) })
	if !ok {
		return
	}
	if err := s.cfg.Store.PutPackage(c.gid, pid, rc.path, rc.size, s.cfg.Now()); err != nil {
		os.Remove(rc.path)
		s.storeError(w, err, "published")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) publish(w http.ResponseWriter, r *http.Request) {
	c, body, ok := s.authSmall(w, r, ownerKind)
	if !ok {
		return
	}
	pid := r.PathValue("packageId")
	var req struct {
		Keys map[string]string `json:"keys"`
	}
	if !blobID.MatchString(pid) || json.Unmarshal(body, &req) != nil {
		writeError(w, http.StatusBadRequest, "malformed")
		return
	}
	for _, k := range req.Keys {
		env, err := auth.B64.DecodeString(k)
		if err != nil || len(env) > maxWrappedKey || len(env) < len(magic) || string(env[:len(magic)]) != magic {
			writeError(w, http.StatusBadRequest, "malformed")
			return
		}
	}
	if err := s.cfg.Store.Publish(c.gid, pid, req.Keys, s.cfg.Now()); err != nil {
		s.storeError(w, err, "not_uploaded")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

type statusPackage struct {
	ID          string   `json:"id"`
	Size        int64    `json:"size"`
	PublishedAt string   `json:"publishedAt"`
	FetchedBy   []string `json:"fetchedBy"`
}

type statusDevice struct {
	Kid            string  `json:"kid"`
	LastSeenAt     *string `json:"lastSeenAt"`
	PendingChanges int     `json:"pendingChanges"`
}

func (s *Server) status(w http.ResponseWriter, r *http.Request) {
	c, _, ok := s.authSmall(w, r, ownerKind)
	if !ok {
		return
	}
	g, ok := s.cfg.Store.Snapshot(c.gid)
	if !ok {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	var pkg *statusPackage
	if p := g.Package; p != nil {
		pkg = &statusPackage{ID: p.ID, Size: p.Size, PublishedAt: timeString(p.PublishedAt), FetchedBy: append([]string{}, p.FetchedBy...)}
	}
	devices := []statusDevice{}
	for _, d := range g.Devices {
		sd := statusDevice{Kid: d.Kid}
		if d.LastSeenAt != nil {
			t := timeString(*d.LastSeenAt)
			sd.LastSeenAt = &t
		}
		for _, ch := range g.Changes {
			if ch.Kid == d.Kid {
				sd.PendingChanges++
			}
		}
		devices = append(devices, sd)
	}
	writeJSON(w, http.StatusOK, map[string]any{"package": pkg, "devices": devices})
}

type changeEntry struct {
	ID         string `json:"id"`
	Kid        string `json:"kid"`
	Batch      string `json:"batch"`
	Size       int64  `json:"size"`
	ReceivedAt string `json:"receivedAt"`
}

func (s *Server) listChanges(w http.ResponseWriter, r *http.Request) {
	c, _, ok := s.authSmall(w, r, ownerKind)
	if !ok {
		return
	}
	g, ok := s.cfg.Store.Snapshot(c.gid)
	if !ok {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	out := []changeEntry{}
	for _, ch := range g.Changes {
		out = append(out, changeEntry{ID: ch.ID, Kid: ch.Kid, Batch: ch.Batch, Size: ch.Size, ReceivedAt: timeString(ch.ReceivedAt)})
	}
	writeJSON(w, http.StatusOK, map[string]any{"changes": out})
}

func (s *Server) getChange(w http.ResponseWriter, r *http.Request) {
	c, _, ok := s.authSmall(w, r, ownerKind)
	if !ok {
		return
	}
	id := r.PathValue("id")
	if !blobID.MatchString(id) {
		writeError(w, http.StatusBadRequest, "malformed")
		return
	}
	f, _, err := s.cfg.Store.OpenChange(c.gid, id)
	if err != nil {
		s.storeError(w, err, "conflict")
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("ETag", `"`+id+`"`)
	http.ServeContent(w, r, "", time.Time{}, f)
}

func (s *Server) done(w http.ResponseWriter, r *http.Request) {
	c, body, ok := s.authSmall(w, r, ownerKind)
	if !ok {
		return
	}
	var req struct {
		Changes []string `json:"changes"`
	}
	if json.Unmarshal(body, &req) != nil {
		writeError(w, http.StatusBadRequest, "malformed")
		return
	}
	n, pkgDeleted, err := s.cfg.Store.Done(c.gid, req.Changes)
	if err != nil {
		s.storeError(w, err, "conflict")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"deletedChanges": n, "packageDeleted": pkgDeleted})
}

// ---- device

func (s *Server) devicePackage(w http.ResponseWriter, r *http.Request) {
	c, _, ok := s.authSmall(w, r, deviceKind)
	if !ok {
		return
	}
	p, key, ok := s.cfg.Store.DevicePackage(c.kid)
	if !ok {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"id": p.ID, "size": p.Size, "key": key})
}

func (s *Server) getPackage(w http.ResponseWriter, r *http.Request) {
	c, _, ok := s.authSmall(w, r, deviceKind)
	if !ok {
		return
	}
	pid := r.PathValue("packageId")
	if !blobID.MatchString(pid) {
		writeError(w, http.StatusBadRequest, "malformed")
		return
	}
	f, _, err := s.cfg.Store.OpenPackage(c.kid, pid)
	if err != nil {
		s.storeError(w, err, "conflict")
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("ETag", `"`+pid+`"`)
	// ServeContent streams from the file and handles Range (206, 416) and If-Range.
	http.ServeContent(w, r, "", time.Time{}, f)
}

func (s *Server) fetched(w http.ResponseWriter, r *http.Request) {
	c, _, ok := s.authSmall(w, r, deviceKind)
	if !ok {
		return
	}
	pid := r.PathValue("packageId")
	if !blobID.MatchString(pid) {
		writeError(w, http.StatusBadRequest, "malformed")
		return
	}
	if err := s.cfg.Store.MarkFetched(c.kid, pid); err != nil {
		s.storeError(w, err, "conflict")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func validBatch(b string) bool {
	m := batchRe.FindStringSubmatch(b)
	if m == nil {
		return false
	}
	first, err1 := strconv.ParseUint(m[1], 10, 64)
	last, err2 := strconv.ParseUint(m[2], 10, 64)
	return err1 == nil && err2 == nil && first <= last
}

func (s *Server) putChanges(w http.ResponseWriter, r *http.Request) {
	batch := r.PathValue("batch")
	c, rc, ok := s.authStream(w, r, deviceKind, MaxChanges, func() bool { return validBatch(batch) })
	if !ok {
		return
	}
	if err := s.cfg.Store.PutChanges(c.kid, batch, rc.path, rc.size, s.cfg.Now()); err != nil {
		os.Remove(rc.path)
		s.storeError(w, err, "conflict")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
