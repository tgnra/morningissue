package server_test

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/tgnra/morningissue/relay/internal/auth"
	"github.com/tgnra/morningissue/relay/internal/server"
	"github.com/tgnra/morningissue/relay/internal/store"
)

// clock is the relay's injected clock.
type clock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *clock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *clock) Advance(d time.Duration) {
	c.mu.Lock()
	c.t = c.t.Add(d)
	c.mu.Unlock()
}

type env struct {
	t     *testing.T
	dir   string
	clk   *clock
	cfg   server.Config
	srv   *server.Server
	ts    *httptest.Server
	owner []byte
	// prefix goes in front of every path the helpers send and sign, for a relay under a base path.
	prefix string
}

func newEnv(t *testing.T, configure ...func(*server.Config)) *env {
	t.Helper()
	e := &env{t: t, dir: t.TempDir(), clk: &clock{t: time.Unix(1794466800, 0)}, owner: randKey()}
	for _, f := range configure {
		f(&e.cfg)
	}
	e.start()
	t.Cleanup(func() { e.stop() })
	return e
}

func (e *env) start() {
	e.t.Helper()
	st, err := store.Open(e.dir)
	if err != nil {
		e.t.Fatal(err)
	}
	replay, err := auth.OpenReplayCache(filepath.Join(e.dir, "replay"), e.clk.Now())
	if err != nil {
		e.t.Fatal(err)
	}
	e.cfg.Store = st
	e.cfg.Replay = replay
	e.cfg.Now = e.clk.Now
	e.srv = server.New(e.cfg)
	e.ts = httptest.NewServer(e.srv)
}

func (e *env) stop() {
	e.ts.Close()
	e.cfg.Replay.Close()
}

// restart stops the server and starts a new one on the same data directory.
func (e *env) restart() {
	e.t.Helper()
	e.stop()
	e.start()
}

func randKey() []byte {
	k := make([]byte, 32)
	rand.Read(k)
	return k
}

func envelope(n int) []byte {
	b := make([]byte, n)
	rand.Read(b)
	copy(b, "MIR1")
	return b
}

func b64(b []byte) string { return auth.B64.EncodeToString(b) }

type resp struct {
	status int
	header http.Header
	body   []byte
}

func (r resp) json(t *testing.T, v any) {
	t.Helper()
	if err := json.Unmarshal(r.body, v); err != nil {
		t.Fatalf("decoding %q: %v", r.body, err)
	}
}

func (r resp) errorCode(t *testing.T) string {
	t.Helper()
	var v struct{ Error string }
	r.json(t, &v)
	return v.Error
}

// rawDo sends a request with the given Authorization header (none when empty).
func (e *env) rawDo(method, path string, body []byte, authz string, hdr map[string]string) resp {
	e.t.Helper()
	req, err := http.NewRequest(method, e.ts.URL+e.prefix+path, bytes.NewReader(body))
	if err != nil {
		e.t.Fatal(err)
	}
	if authz != "" {
		req.Header.Set("Authorization", authz)
	}
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer res.Body.Close()
	b, err := io.ReadAll(res.Body)
	if err != nil {
		e.t.Fatal(err)
	}
	if res.Header.Get("Date") != e.clk.Now().UTC().Format(http.TimeFormat) {
		e.t.Errorf("%s %s: Date = %q, want the relay's clock %q", method, path, res.Header.Get("Date"), e.clk.Now().UTC().Format(http.TimeFormat))
	}
	return resp{res.StatusCode, res.Header, b}
}

// do signs and sends a request. The clock moves on a second first, so that identical requests
// don't count as replays.
func (e *env) do(key []byte, method, path string, body []byte, hdr map[string]string) resp {
	e.t.Helper()
	e.clk.Advance(time.Second)
	return e.rawDo(method, path, body, auth.Header(key, method, e.prefix+path, e.clk.Now().Unix(), body), hdr)
}

func (e *env) doJSON(key []byte, method, path string, v any) resp {
	e.t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		e.t.Fatal(err)
	}
	return e.do(key, method, path, b, nil)
}

func expect(t *testing.T, r resp, status int) {
	t.Helper()
	if r.status != status {
		t.Fatalf("status %d, want %d (body %q)", r.status, status, r.body)
	}
}

// register creates the group and lists the devices.
func (e *env) register(devices ...[]byte) {
	e.t.Helper()
	expect(e.t, e.doJSON(e.owner, "PUT", "/v1/group", map[string]string{"ownerKey": b64(e.owner)}), 200)
	e.setDevices(devices...)
}

func (e *env) setDevices(devices ...[]byte) []string {
	e.t.Helper()
	list := []string{}
	for _, d := range devices {
		list = append(list, b64(d))
	}
	r := e.doJSON(e.owner, "PUT", "/v1/group/devices", map[string]any{"devices": list})
	expect(e.t, r, 200)
	var out struct{ Devices []string }
	r.json(e.t, &out)
	return out.Devices
}

// publish uploads a package and publishes it with a key for each device.
func (e *env) publish(pkg []byte, devices ...[]byte) string {
	e.t.Helper()
	pid := store.NewID()
	expect(e.t, e.do(e.owner, "PUT", "/v1/group/packages/"+pid, pkg, nil), 204)
	keys := map[string]string{}
	for _, d := range devices {
		keys[auth.Kid(d)] = b64(envelope(65))
	}
	expect(e.t, e.doJSON(e.owner, "POST", "/v1/group/packages/"+pid+"/publish", map[string]any{"keys": keys}), 204)
	return pid
}

type statusBody struct {
	Package *struct {
		ID          string
		Size        int64
		PublishedAt string
		FetchedBy   []string
	}
	Devices []struct {
		Kid            string
		LastSeenAt     *string
		PendingChanges int
	}
}

func (e *env) status() statusBody {
	e.t.Helper()
	r := e.do(e.owner, "GET", "/v1/group/status", nil, nil)
	expect(e.t, r, 200)
	var s statusBody
	r.json(e.t, &s)
	return s
}

type changeEntry struct {
	ID, Kid, Batch, ReceivedAt string
	Size                       int64
}

func (e *env) changes() []changeEntry {
	e.t.Helper()
	r := e.do(e.owner, "GET", "/v1/group/changes", nil, nil)
	expect(e.t, r, 200)
	var out struct{ Changes []changeEntry }
	r.json(e.t, &out)
	return out.Changes
}

// files counts the files under the group's packages/ or changes/ directory.
func (e *env) files(sub string) int {
	e.t.Helper()
	m, err := filepath.Glob(filepath.Join(e.dir, "groups", auth.Kid(e.owner), sub, "*"))
	if err != nil {
		e.t.Fatal(err)
	}
	return len(m)
}

func TestHealth(t *testing.T) {
	e := newEnv(t)
	r := e.rawDo("GET", "/v1/health", nil, "", nil)
	expect(t, r, 200)
	var h struct {
		OK       bool
		Protocol string
	}
	r.json(t, &h)
	if !h.OK || h.Protocol != "relay/1" {
		t.Fatalf("health = %s", r.body)
	}
	expect(t, e.rawDo("GET", "/v1/nothing", nil, "", nil), 404)
}

func TestFullFlow(t *testing.T) {
	e := newEnv(t)
	d1, d2 := randKey(), randKey()

	r := e.doJSON(e.owner, "PUT", "/v1/group", map[string]string{"ownerKey": b64(e.owner)})
	expect(t, r, 200)
	var g struct {
		Group   string
		Created bool
	}
	r.json(t, &g)
	if g.Group != auth.Kid(e.owner) || !g.Created {
		t.Fatalf("PUT /v1/group = %s", r.body)
	}
	r = e.doJSON(e.owner, "PUT", "/v1/group", map[string]string{"ownerKey": b64(e.owner)})
	expect(t, r, 200)
	r.json(t, &g)
	if g.Created {
		t.Fatal("second PUT /v1/group created the group again")
	}

	kids := e.setDevices(d1, d2)
	if len(kids) != 2 || kids[0] != auth.Kid(d1) || kids[1] != auth.Kid(d2) {
		t.Fatalf("devices = %v", kids)
	}

	// Nothing to fetch yet, and an upload isn't offered until it is published.
	expect(t, e.do(d1, "GET", "/v1/package", nil, nil), 204)
	pkg := envelope(300_000)
	pid := store.NewID()
	expect(t, e.do(e.owner, "PUT", "/v1/group/packages/"+pid, pkg, nil), 204)
	expect(t, e.do(d1, "GET", "/v1/package", nil, nil), 204)
	keys := map[string]string{auth.Kid(d1): b64(envelope(65)), auth.Kid(d2): b64(envelope(65))}
	expect(t, e.doJSON(e.owner, "POST", "/v1/group/packages/"+pid+"/publish", map[string]any{"keys": keys}), 204)

	r = e.do(d1, "GET", "/v1/package", nil, nil)
	expect(t, r, 200)
	var cur struct {
		ID   string
		Size int64
		Key  string
	}
	r.json(t, &cur)
	if cur.ID != pid || cur.Size != int64(len(pkg)) || cur.Key != keys[auth.Kid(d1)] {
		t.Fatalf("GET /v1/package = %s", r.body)
	}

	// Download in two parts, as a resumed download would.
	r = e.do(d1, "GET", "/v1/packages/"+pid, nil, map[string]string{"Range": "bytes=0-99999"})
	expect(t, r, 206)
	if r.header.Get("ETag") != `"`+pid+`"` {
		t.Fatalf("ETag = %q", r.header.Get("ETag"))
	}
	if r.header.Get("Content-Range") != fmt.Sprintf("bytes 0-99999/%d", len(pkg)) {
		t.Fatalf("Content-Range = %q", r.header.Get("Content-Range"))
	}
	got := append([]byte{}, r.body...)
	r = e.do(d1, "GET", "/v1/packages/"+pid, nil, map[string]string{"Range": "bytes=100000-"})
	expect(t, r, 206)
	got = append(got, r.body...)
	if !bytes.Equal(got, pkg) {
		t.Fatal("resumed download differs from the upload")
	}
	r = e.do(d1, "GET", "/v1/packages/"+pid, nil, nil)
	expect(t, r, 200)
	if !bytes.Equal(r.body, pkg) || r.header.Get("Content-Type") != "application/octet-stream" {
		t.Fatal("full download differs from the upload")
	}
	expect(t, e.do(d1, "GET", "/v1/packages/"+pid, nil, map[string]string{"Range": fmt.Sprintf("bytes=%d-", len(pkg)+10)}), 416)
	expect(t, e.do(d1, "POST", "/v1/packages/"+pid+"/fetched", nil, nil), 204)

	// Changes: a retried batch replaces the first upload.
	expect(t, e.do(d1, "PUT", "/v1/changes/1-500", envelope(100), nil), 204)
	second := envelope(120)
	expect(t, e.do(d1, "PUT", "/v1/changes/1-500", second, nil), 204)
	expect(t, e.do(d1, "PUT", "/v1/changes/501-600", envelope(50), nil), 204)
	ch := e.changes()
	if len(ch) != 2 || ch[0].Batch != "1-500" || ch[1].Batch != "501-600" || ch[0].Kid != auth.Kid(d1) || ch[0].Size != 120 {
		t.Fatalf("changes = %+v", ch)
	}
	if e.files("changes") != 2 {
		t.Fatalf("%d change files on disk, want 2", e.files("changes"))
	}
	r = e.do(e.owner, "GET", "/v1/group/changes/"+ch[0].ID, nil, nil)
	expect(t, r, 200)
	if !bytes.Equal(r.body, second) {
		t.Fatal("changes download differs from the replacing upload")
	}

	st := e.status()
	if st.Package == nil || st.Package.ID != pid || st.Package.Size != int64(len(pkg)) || len(st.Package.FetchedBy) != 1 || st.Package.FetchedBy[0] != auth.Kid(d1) {
		t.Fatalf("status package = %+v", st.Package)
	}
	if _, err := time.Parse(time.RFC3339, st.Package.PublishedAt); err != nil {
		t.Fatalf("publishedAt %q: %v", st.Package.PublishedAt, err)
	}
	if len(st.Devices) != 2 || st.Devices[0].PendingChanges != 2 || st.Devices[0].LastSeenAt == nil || st.Devices[1].LastSeenAt != nil {
		t.Fatalf("status devices = %+v", st.Devices)
	}

	// Done: the changes go, the package stays until d2 has it too.
	r = e.doJSON(e.owner, "POST", "/v1/group/done", map[string]any{"changes": []string{ch[0].ID, ch[1].ID, "unknownunknownunknown"}})
	expect(t, r, 200)
	var done struct {
		DeletedChanges int
		PackageDeleted bool
	}
	r.json(t, &done)
	if done.DeletedChanges != 2 || done.PackageDeleted {
		t.Fatalf("done = %s", r.body)
	}
	if len(e.changes()) != 0 || e.files("changes") != 0 {
		t.Fatal("changes left after done")
	}
	expect(t, e.do(e.owner, "GET", "/v1/group/changes/"+ch[0].ID, nil, nil), 404)

	expect(t, e.do(d2, "GET", "/v1/packages/"+pid, nil, nil), 200)
	expect(t, e.do(d2, "POST", "/v1/packages/"+pid+"/fetched", nil, nil), 204)
	r = e.doJSON(e.owner, "POST", "/v1/group/done", map[string]any{"changes": []string{}})
	expect(t, r, 200)
	r.json(t, &done)
	if done.DeletedChanges != 0 || !done.PackageDeleted {
		t.Fatalf("done = %s", r.body)
	}
	if e.files("packages") != 0 {
		t.Fatal("package file left after done")
	}
	expect(t, e.do(d1, "GET", "/v1/packages/"+pid, nil, nil), 404)
	expect(t, e.do(d1, "GET", "/v1/package", nil, nil), 204)
	if e.status().Package != nil {
		t.Fatal("status still shows a package")
	}

	// Deleting the group removes everything.
	expect(t, e.do(e.owner, "DELETE", "/v1/group", nil, nil), 204)
	if _, err := os.Stat(filepath.Join(e.dir, "groups", auth.Kid(e.owner))); !os.IsNotExist(err) {
		t.Fatal("group directory left after DELETE")
	}
	if c := e.do(d1, "GET", "/v1/package", nil, nil); c.status != 401 || c.errorCode(t) != "unknown" {
		t.Fatalf("device after group deletion: %d %s", c.status, c.body)
	}
	if c := e.do(e.owner, "GET", "/v1/group/status", nil, nil); c.status != 401 || c.errorCode(t) != "unknown" {
		t.Fatalf("owner after group deletion: %d %s", c.status, c.body)
	}
}

func TestPublish(t *testing.T) {
	e := newEnv(t)
	d1, d2 := randKey(), randKey()
	e.register(d1, d2)

	expect(t, e.doJSON(e.owner, "POST", "/v1/group/packages/"+store.NewID()+"/publish", map[string]any{"keys": map[string]string{}}), 409)

	// d2 has no key, so it sees no package.
	p1 := e.publish(envelope(1000), d1)
	expect(t, e.do(d2, "GET", "/v1/package", nil, nil), 204)
	expect(t, e.do(d2, "GET", "/v1/packages/"+p1, nil, nil), 404)
	expect(t, e.do(d1, "GET", "/v1/packages/"+p1, nil, nil), 200)

	// The current package can't be overwritten.
	expect(t, e.do(e.owner, "PUT", "/v1/group/packages/"+p1, envelope(10), nil), 409)

	// A new package replaces the old one, which is deleted from disk.
	p2 := e.publish(envelope(2000), d1, d2)
	expect(t, e.do(d1, "GET", "/v1/packages/"+p1, nil, nil), 404)
	expect(t, e.do(d1, "POST", "/v1/packages/"+p1+"/fetched", nil, nil), 404)
	expect(t, e.do(d2, "GET", "/v1/packages/"+p2, nil, nil), 200)
	if n := e.files("packages"); n != 1 {
		t.Fatalf("%d package files, want 1", n)
	}

	// Malformed wrapped keys.
	pid := store.NewID()
	expect(t, e.do(e.owner, "PUT", "/v1/group/packages/"+pid, envelope(10), nil), 204)
	bad := map[string]any{"keys": map[string]string{auth.Kid(d1): b64([]byte("NOPE-not-an-envelope"))}}
	expect(t, e.doJSON(e.owner, "POST", "/v1/group/packages/"+pid+"/publish", bad), 400)
	bad = map[string]any{"keys": map[string]string{auth.Kid(d1): "!!"}}
	expect(t, e.doJSON(e.owner, "POST", "/v1/group/packages/"+pid+"/publish", bad), 400)
}

func TestDonePackageWithNoDevicesFetched(t *testing.T) {
	e := newEnv(t)
	d1 := randKey()
	e.register(d1)
	e.publish(envelope(100), d1)
	r := e.doJSON(e.owner, "POST", "/v1/group/done", map[string]any{"changes": []string{}})
	expect(t, r, 200)
	var done struct{ PackageDeleted bool }
	r.json(t, &done)
	if done.PackageDeleted {
		t.Fatal("package deleted though no device fetched it")
	}
}

func TestUnpairing(t *testing.T) {
	e := newEnv(t)
	d1, d2 := randKey(), randKey()
	e.register(d1, d2)
	pid := e.publish(envelope(100), d1, d2)
	expect(t, e.do(d1, "POST", "/v1/packages/"+pid+"/fetched", nil, nil), 204)
	expect(t, e.do(d1, "PUT", "/v1/changes/1-2", envelope(40), nil), 204)
	expect(t, e.do(d2, "PUT", "/v1/changes/1-3", envelope(40), nil), 204)

	e.setDevices(d2)
	r := e.do(d1, "GET", "/v1/package", nil, nil)
	if r.status != 401 || r.errorCode(t) != "unknown" {
		t.Fatalf("unpaired device: %d %s", r.status, r.body)
	}
	ch := e.changes()
	if len(ch) != 1 || ch[0].Kid != auth.Kid(d2) || e.files("changes") != 1 {
		t.Fatalf("changes after unpairing = %+v", ch)
	}
	st := e.status()
	if len(st.Devices) != 1 || len(st.Package.FetchedBy) != 0 {
		t.Fatalf("status after unpairing = %+v", st)
	}

	// Pairing again: the device starts afresh.
	e.setDevices(d1, d2)
	expect(t, e.do(d1, "GET", "/v1/package", nil, nil), 204) // its key went with the unpairing
}

func TestDeviceMovesBetweenGroups(t *testing.T) {
	e := newEnv(t)
	d := randKey()
	e.register(d)
	expect(t, e.do(d, "PUT", "/v1/changes/1-1", envelope(10), nil), 204)

	other := randKey()
	expect(t, e.doJSON(other, "PUT", "/v1/group", map[string]string{"ownerKey": b64(other)}), 200)
	expect(t, e.doJSON(other, "PUT", "/v1/group/devices", map[string]any{"devices": []string{b64(d)}}), 200)

	if len(e.changes()) != 0 || len(e.status().Devices) != 0 {
		t.Fatal("device still in its first group")
	}
	expect(t, e.do(d, "PUT", "/v1/changes/2-2", envelope(10), nil), 204)
	r := e.do(other, "GET", "/v1/group/changes", nil, nil)
	expect(t, r, 200)
	if !strings.Contains(string(r.body), `"batch":"2-2"`) {
		t.Fatalf("second group's changes = %s", r.body)
	}
}

func TestAuthErrors(t *testing.T) {
	e := newEnv(t)
	d := randKey()
	e.register(d)
	now := func() int64 { return e.clk.Now().Unix() }
	check := func(name string, r resp, code string) {
		t.Helper()
		if r.status != 401 {
			t.Fatalf("%s: status %d, want 401 (%s)", name, r.status, r.body)
		}
		if got := r.errorCode(t); got != code {
			t.Fatalf("%s: error %q, want %q", name, got, code)
		}
	}

	path := "/v1/group/status"
	check("skew behind", e.rawDo("GET", path, nil, auth.Header(e.owner, "GET", path, now()-301, nil), nil), "skew")
	check("skew ahead", e.rawDo("GET", path, nil, auth.Header(e.owner, "GET", path, now()+301, nil), nil), "skew")
	expect(t, e.rawDo("GET", path, nil, auth.Header(e.owner, "GET", path, now()-300, nil), nil), 200)
	expect(t, e.rawDo("GET", path, nil, auth.Header(e.owner, "GET", path, now()+300, nil), nil), 200)

	check("unknown kid", e.do(randKey(), "GET", path, nil, nil), "unknown")
	check("device kid on an owner path", e.do(d, "GET", path, nil, nil), "unknown")
	check("owner kid on a device path", e.do(e.owner, "GET", "/v1/package", nil, nil), "unknown")
	check("no header", e.rawDo("GET", path, nil, "", nil), "signature")
	check("garbled header", e.rawDo("GET", path, nil, "MIR1 kid=x", nil), "signature")

	// Signed for another path, another method, another body, or with another key.
	ts := now()
	h := auth.Header(e.owner, "GET", "/v1/group/changes", ts, nil)
	check("wrong path", e.rawDo("GET", path, nil, h, nil), "signature")
	h = auth.Header(d, "PUT", "/v1/changes/1-1", ts, envelope(8))
	check("wrong body", e.rawDo("PUT", "/v1/changes/1-1", envelope(8), h, nil), "signature")
	h = auth.Header(e.owner, "GET", path+"?x=1", ts, nil)
	check("query not as sent", e.rawDo("GET", path+"?x=2", nil, h, nil), "signature")
	expect(t, e.rawDo("GET", path+"?x=1", nil, h, nil), 200)
	empty := sha256.Sum256(nil)
	forged := fmt.Sprintf("MIR1 kid=%s, ts=%d, sig=%s", auth.Kid(e.owner), ts, b64(auth.Sign(randKey(), "GET", path, ts, empty[:])))
	check("wrong key", e.rawDo("GET", path, nil, forged, nil), "signature")

	// The same signature twice.
	h = auth.Header(e.owner, "GET", path, now(), nil)
	expect(t, e.rawDo("GET", path, nil, h, nil), 200)
	check("replay", e.rawDo("GET", path, nil, h, nil), "replay")
	body := envelope(16)
	h = auth.Header(d, "PUT", "/v1/changes/7-7", now(), body)
	expect(t, e.rawDo("PUT", "/v1/changes/7-7", body, h, nil), 204)
	check("replayed upload", e.rawDo("PUT", "/v1/changes/7-7", body, h, nil), "replay")

	// PUT /v1/group: the kid must be the body's key.
	other := randKey()
	gb, _ := json.Marshal(map[string]string{"ownerKey": b64(other)})
	check("group kid mismatch", e.rawDo("PUT", "/v1/group", gb, auth.Header(e.owner, "PUT", "/v1/group", now(), gb), nil), "signature")
	check("group skew", e.rawDo("PUT", "/v1/group", gb, auth.Header(other, "PUT", "/v1/group", now()-1000, gb), nil), "skew")
}

func TestRegistrationToken(t *testing.T) {
	e := newEnv(t, func(c *server.Config) { c.RegistrationToken = "s3cret" })
	body := map[string]string{"ownerKey": b64(e.owner)}
	b, _ := json.Marshal(body)
	r := e.do(e.owner, "PUT", "/v1/group", b, nil)
	expect(t, r, 403)
	expect(t, e.do(e.owner, "PUT", "/v1/group", b, map[string]string{"X-Relay-Registration": "wrong"}), 403)
	r = e.do(e.owner, "PUT", "/v1/group", b, map[string]string{"X-Relay-Registration": "s3cret"})
	expect(t, r, 200)
	if !strings.Contains(string(r.body), `"created":true`) {
		t.Fatalf("PUT /v1/group = %s", r.body)
	}
	// Confirming an existing group needs no token.
	r = e.do(e.owner, "PUT", "/v1/group", b, nil)
	expect(t, r, 200)
	if !strings.Contains(string(r.body), `"created":false`) {
		t.Fatalf("PUT /v1/group = %s", r.body)
	}
}

func TestLimits(t *testing.T) {
	e := newEnv(t, func(c *server.Config) { c.MaxPackage = 4096 })
	d := randKey()
	e.register(d)

	// Devices.
	many := make([][]byte, 33)
	for i := range many {
		many[i] = randKey()
	}
	list := []string{}
	for _, k := range many {
		list = append(list, b64(k))
	}
	expect(t, e.doJSON(e.owner, "PUT", "/v1/group/devices", map[string]any{"devices": list}), 429)
	expect(t, e.doJSON(e.owner, "PUT", "/v1/group/devices", map[string]any{"devices": list[:32]}), 200)
	expect(t, e.doJSON(e.owner, "PUT", "/v1/group/devices", map[string]any{"devices": []string{"c2hvcnQ"}}), 400)
	expect(t, e.doJSON(e.owner, "PUT", "/v1/group/devices", map[string]any{}), 400)
	e.setDevices(d)

	// Package size.
	expect(t, e.do(e.owner, "PUT", "/v1/group/packages/"+store.NewID(), envelope(4096), nil), 204)
	expect(t, e.do(e.owner, "PUT", "/v1/group/packages/"+store.NewID(), envelope(4097), nil), 413)
	expect(t, e.do(e.owner, "PUT", "/v1/group/packages/short", envelope(10), nil), 400)
	expect(t, e.do(e.owner, "PUT", "/v1/group/packages/"+strings.Repeat("a", 65), envelope(10), nil), 400)
	expect(t, e.do(e.owner, "PUT", "/v1/group/packages/"+strings.Repeat("a", 64), envelope(10), nil), 204)
	expect(t, e.do(e.owner, "PUT", "/v1/group/packages/"+store.NewID(), []byte("ZIP!not an envelope"), nil), 400)
	expect(t, e.do(e.owner, "PUT", "/v1/group/packages/"+store.NewID(), []byte("MI"), nil), 400)

	// Changes size and batch names.
	expect(t, e.do(d, "PUT", "/v1/changes/1-1", envelope(4<<20), nil), 204)
	expect(t, e.do(d, "PUT", "/v1/changes/2-2", envelope(4<<20+1), nil), 413)
	expect(t, e.do(d, "PUT", "/v1/changes/3-3", []byte("nope"), nil), 400)
	for _, b := range []string{"2-1", "01-2", "a-b", "1", "1-2-3", "-1-2", "1-99999999999999999999"} {
		if r := e.do(d, "PUT", "/v1/changes/"+b, envelope(10), nil); r.status != 400 {
			t.Errorf("batch %q: status %d, want 400", b, r.status)
		}
	}

	// At most 64 pending batches; replacing one doesn't count.
	for i := 2; i <= 64; i++ {
		expect(t, e.do(d, "PUT", fmt.Sprintf("/v1/changes/%d-%d", i, i), envelope(10), nil), 204)
	}
	expect(t, e.do(d, "PUT", "/v1/changes/65-65", envelope(10), nil), 429)
	expect(t, e.do(d, "PUT", "/v1/changes/64-64", envelope(10), nil), 204)
	if n := len(e.changes()); n != 64 {
		t.Fatalf("%d changes, want 64", n)
	}
	if n := e.files("changes"); n != 64 {
		t.Fatalf("%d change files, want 64", n)
	}
	if n, _ := filepath.Glob(filepath.Join(e.dir, "tmp", "*")); len(n) != 0 {
		t.Fatalf("temporary files left: %v", n)
	}
}

// A body the relay can't store, here because its tmp directory is gone, is the relay's failure
// (500), not a malformed request (400) that a client would give up on.
func TestStorageFailureIsInternal(t *testing.T) {
	e := newEnv(t)
	d := randKey()
	e.register(d)
	if err := os.RemoveAll(filepath.Join(e.dir, "tmp")); err != nil {
		t.Fatal(err)
	}
	if r := e.do(d, "PUT", "/v1/changes/1-1", envelope(10), nil); r.status != 500 || r.errorCode(t) != "internal" {
		t.Fatalf("changes: %d %s", r.status, r.body)
	}
	if r := e.do(e.owner, "PUT", "/v1/group/packages/"+store.NewID(), envelope(10), nil); r.status != 500 {
		t.Fatalf("package: %d %s", r.status, r.body)
	}
}

func TestSweeper(t *testing.T) {
	e := newEnv(t)
	d := randKey()
	e.register(d)

	// An unpublished upload goes after an hour.
	pid := store.NewID()
	expect(t, e.do(e.owner, "PUT", "/v1/group/packages/"+pid, envelope(100), nil), 204)
	e.clk.Advance(59 * time.Minute)
	if err := e.srv.Sweep(); err != nil {
		t.Fatal(err)
	}
	if e.files("packages") != 1 {
		t.Fatal("upload swept too early")
	}
	e.clk.Advance(2 * time.Minute)
	if err := e.srv.Sweep(); err != nil {
		t.Fatal(err)
	}
	if e.files("packages") != 0 {
		t.Fatal("unpublished upload not swept after an hour")
	}
	expect(t, e.doJSON(e.owner, "POST", "/v1/group/packages/"+pid+"/publish", map[string]any{"keys": map[string]string{}}), 409)

	// Packages and changes go after the TTL.
	e.publish(envelope(100), d)
	expect(t, e.do(d, "PUT", "/v1/changes/1-1", envelope(10), nil), 204)
	e.clk.Advance(47 * time.Hour)
	if err := e.srv.Sweep(); err != nil {
		t.Fatal(err)
	}
	if e.status().Package == nil || len(e.changes()) != 1 {
		t.Fatal("swept before the TTL")
	}
	e.clk.Advance(1*time.Hour + time.Minute)
	if err := e.srv.Sweep(); err != nil {
		t.Fatal(err)
	}
	if e.status().Package != nil || len(e.changes()) != 0 || e.files("packages") != 0 || e.files("changes") != 0 {
		t.Fatal("package or changes not swept after the TTL")
	}
	expect(t, e.do(d, "GET", "/v1/package", nil, nil), 204)

	// Device requests don't keep a group alive; the owner's do.
	e.clk.Advance(89 * 24 * time.Hour)
	expect(t, e.do(d, "GET", "/v1/package", nil, nil), 204)
	if err := e.srv.Sweep(); err != nil {
		t.Fatal(err)
	}
	e.status() // the owner is back: 90 more days
	e.clk.Advance(89 * 24 * time.Hour)
	if err := e.srv.Sweep(); err != nil {
		t.Fatal(err)
	}
	expect(t, e.do(d, "GET", "/v1/package", nil, nil), 204)
	e.clk.Advance(2 * 24 * time.Hour)
	if err := e.srv.Sweep(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(e.dir, "groups", auth.Kid(e.owner))); !os.IsNotExist(err) {
		t.Fatal("idle group not deleted from disk")
	}
	if r := e.do(e.owner, "GET", "/v1/group/status", nil, nil); r.status != 401 || r.errorCode(t) != "unknown" {
		t.Fatalf("owner of an idle group: %d %s", r.status, r.body)
	}
	if r := e.do(d, "GET", "/v1/package", nil, nil); r.status != 401 || r.errorCode(t) != "unknown" {
		t.Fatalf("device of an idle group: %d %s", r.status, r.body)
	}
}

func TestCustomTTL(t *testing.T) {
	e := newEnv(t, func(c *server.Config) { c.TTL = 2 * time.Hour })
	d := randKey()
	e.register(d)
	e.publish(envelope(100), d)
	e.clk.Advance(2*time.Hour + time.Minute)
	if err := e.srv.Sweep(); err != nil {
		t.Fatal(err)
	}
	if e.status().Package != nil {
		t.Fatal("package not swept after a 2-hour TTL")
	}
}

func TestRestartPersistence(t *testing.T) {
	e := newEnv(t)
	d1, d2 := randKey(), randKey()
	e.register(d1, d2)
	pkg := envelope(50_000)
	pid := e.publish(pkg, d1, d2)
	expect(t, e.do(d1, "POST", "/v1/packages/"+pid+"/fetched", nil, nil), 204)
	body := envelope(77)
	expect(t, e.do(d2, "PUT", "/v1/changes/10-20", body, nil), 204)
	unpublished := store.NewID()
	expect(t, e.do(e.owner, "PUT", "/v1/group/packages/"+unpublished, envelope(10), nil), 204)
	before := e.status()

	// Leftovers of a crash: a half-received body and a file no metadata points at.
	os.WriteFile(filepath.Join(e.dir, "tmp", "in-123"), []byte("x"), 0o600)
	stray := filepath.Join(e.dir, "groups", auth.Kid(e.owner), "changes", store.NewID())
	os.WriteFile(stray, []byte("x"), 0o600)

	e.restart()

	after := e.status()
	bj, _ := json.Marshal(before)
	aj, _ := json.Marshal(after)
	if string(bj) != string(aj) {
		t.Fatalf("status changed across restart:\n%s\n%s", bj, aj)
	}
	r := e.do(d2, "GET", "/v1/packages/"+pid, nil, map[string]string{"Range": "bytes=1000-"})
	expect(t, r, 206)
	if !bytes.Equal(r.body, pkg[1000:]) {
		t.Fatal("package differs after restart")
	}
	ch := e.changes()
	if len(ch) != 1 || ch[0].Batch != "10-20" {
		t.Fatalf("changes after restart = %+v", ch)
	}
	r = e.do(e.owner, "GET", "/v1/group/changes/"+ch[0].ID, nil, nil)
	expect(t, r, 200)
	if !bytes.Equal(r.body, body) {
		t.Fatal("changes differ after restart")
	}
	expect(t, e.doJSON(e.owner, "POST", "/v1/group/packages/"+unpublished+"/publish", map[string]any{"keys": map[string]string{}}), 204)
	if _, err := os.Stat(stray); !os.IsNotExist(err) {
		t.Fatal("stray file not removed on start")
	}
	if m, _ := filepath.Glob(filepath.Join(e.dir, "tmp", "*")); len(m) != 0 {
		t.Fatalf("tmp not cleared on start: %v", m)
	}
}

// A request replayed after the relay restarts, within the window, is still refused.
func TestReplayAcrossRestart(t *testing.T) {
	e := newEnv(t)
	d := randKey()
	e.register(d)
	type signed struct {
		method, path string
		body         []byte
		authz        string
	}
	sign := func(key []byte, method, path string, body []byte) signed {
		e.clk.Advance(time.Second)
		return signed{method, path, body, auth.Header(key, method, path, e.clk.Now().Unix(), body)}
	}
	gb, _ := json.Marshal(map[string]string{"ownerKey": b64(e.owner)})
	reqs := []signed{
		sign(d, "PUT", "/v1/changes/1-5", envelope(40)),
		sign(e.owner, "GET", "/v1/group/status", nil),
		sign(e.owner, "PUT", "/v1/group", gb),
	}
	for _, q := range reqs {
		if r := e.rawDo(q.method, q.path, q.body, q.authz, nil); r.status >= 300 {
			t.Fatalf("%s %s: %d %s", q.method, q.path, r.status, r.body)
		}
	}
	replays := func(want string) {
		t.Helper()
		for _, q := range reqs {
			r := e.rawDo(q.method, q.path, q.body, q.authz, nil)
			if r.status != 401 || r.errorCode(t) != want {
				t.Fatalf("%s %s replayed: %d %s, want 401 %q", q.method, q.path, r.status, r.body, want)
			}
		}
	}

	// A crash: the replay file is never closed.
	e.ts.Close()
	e.start()
	e.clk.Advance(100 * time.Second)
	replays("replay")
	// New requests are accepted straight away.
	expect(t, e.do(d, "GET", "/v1/package", nil, nil), 204)

	// A clean restart, after a sweep has rewritten the file.
	if err := e.srv.Sweep(); err != nil {
		t.Fatal(err)
	}
	e.restart()
	e.clk.Advance(190 * time.Second)
	replays("replay")
	if n := len(e.changes()); n != 1 {
		t.Fatalf("%d changes, want 1", n)
	}

	// Once out of skew, they fail on the clock, and the sweeper forgets them.
	e.clk.Advance(20 * time.Second)
	replays("skew")
	e.clk.Advance(10 * time.Minute)
	if err := e.srv.Sweep(); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(filepath.Join(e.dir, "replay"))
	if err != nil {
		t.Fatal(err)
	}
	if len(b) != 0 {
		t.Fatalf("replay file after the window:\n%s", b)
	}
}

// A signature with ts 300 s ahead passes the skew check until 601 s after ts - 300, so it must be
// remembered that long even when it was first seen part way through a second.
func TestReplayAtTheEdgeOfSkew(t *testing.T) {
	e := newEnv(t)
	e.register()
	e.clk.Advance(500 * time.Millisecond)
	path := "/v1/group/status"
	h := auth.Header(e.owner, "GET", path, e.clk.Now().Unix()+300, nil)
	expect(t, e.rawDo("GET", path, nil, h, nil), 200)
	e.clk.Advance(600*time.Second + 200*time.Millisecond)
	if r := e.rawDo("GET", path, nil, h, nil); r.status != 401 || r.errorCode(t) != "replay" {
		t.Fatalf("replay at the edge of skew: %d %s", r.status, r.body)
	}
	e.clk.Advance(time.Second)
	if r := e.rawDo("GET", path, nil, h, nil); r.status != 401 || r.errorCode(t) != "skew" {
		t.Fatalf("a second later: %d %s", r.status, r.body)
	}
}

// Under a base path the relay routes on the path below it and checks signatures over the full
// path as sent, as both clients sign it when the relay address has a path.
func TestBasePath(t *testing.T) {
	e := newEnv(t, func(c *server.Config) { c.BasePath = "/morningissue/" })
	e.prefix = "/morningissue"
	d := randKey()
	e.register(d)
	pkg := envelope(5000)
	pid := e.publish(pkg, d)
	r := e.do(d, "GET", "/v1/package", nil, nil)
	expect(t, r, 200)
	r = e.do(d, "GET", "/v1/packages/"+pid, nil, map[string]string{"Range": "bytes=100-"})
	expect(t, r, 206)
	if !bytes.Equal(r.body, pkg[100:]) {
		t.Fatal("package differs")
	}
	expect(t, e.do(d, "PUT", "/v1/changes/1-3", envelope(30), nil), 204)
	if ch := e.changes(); len(ch) != 1 || ch[0].Batch != "1-3" {
		t.Fatalf("changes = %+v", ch)
	}
	expect(t, e.rawDo("GET", "/v1/health", nil, "", nil), 200)
	expect(t, e.do(e.owner, "GET", "/v1/group/status?x=1", nil, nil), 200)

	// Signed over the path without the base, as a proxy that strips it would pass it on.
	now := e.clk.Now().Unix()
	if r := e.rawDo("GET", "/v1/group/status", nil, auth.Header(e.owner, "GET", "/v1/group/status", now, nil), nil); r.status != 401 || r.errorCode(t) != "signature" {
		t.Fatalf("signed without the base path: %d %s", r.status, r.body)
	}

	// Anything outside the base path is not found, signed or not.
	e.prefix = ""
	for _, p := range []string{"/v1/health", "/morningissue", "/morningissue/", "/morningissuex/v1/health", "/other/morningissue/v1/health"} {
		if r := e.rawDo("GET", p, nil, "", nil); r.status != 404 {
			t.Errorf("GET %s: %d, want 404", p, r.status)
		}
	}
	h := auth.Header(e.owner, "GET", "/v1/group/status", e.clk.Now().Unix(), nil)
	expect(t, e.rawDo("GET", "/v1/group/status", nil, h, nil), 404)
}

func TestCleanBasePath(t *testing.T) {
	for in, want := range map[string]string{"": "", "/": "", "/morningissue": "/morningissue", "/morningissue/": "/morningissue", "/a/b-c_d.e/": "/a/b-c_d.e"} {
		if got, err := server.CleanBasePath(in); err != nil || got != want {
			t.Errorf("CleanBasePath(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
	for _, in := range []string{"morningissue", "/a//b", "/a/../b", "/a/./b", "/a?b", "/a#b", "/a b", "/a%20b"} {
		if got, err := server.CleanBasePath(in); err == nil {
			t.Errorf("CleanBasePath(%q) = %q, want an error", in, got)
		}
	}
}

func TestConcurrentUploads(t *testing.T) {
	e := newEnv(t)
	devices := make([][]byte, 8)
	for i := range devices {
		devices[i] = randKey()
	}
	e.register(devices...)
	pid := e.publish(envelope(10_000), devices...)

	var wg sync.WaitGroup
	errs := make(chan string, 1000)
	for i, d := range devices {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 1; j <= 10; j++ {
				for _, req := range []struct {
					method, path string
					body         []byte
				}{
					{"PUT", "/v1/changes/" + strconv.Itoa(j) + "-" + strconv.Itoa(j), envelope(100 + i)},
					{"GET", "/v1/packages/" + pid, nil},
					{"POST", "/v1/packages/" + pid + "/fetched", nil},
				} {
					h := auth.Header(d, req.method, req.path, e.clk.Now().Unix()+int64(j), req.body)
					hr, _ := http.NewRequest(req.method, e.ts.URL+req.path, bytes.NewReader(req.body))
					hr.Header.Set("Authorization", h)
					res, err := http.DefaultClient.Do(hr)
					if err != nil {
						errs <- err.Error()
						continue
					}
					io.Copy(io.Discard, res.Body)
					res.Body.Close()
					if res.StatusCode >= 300 {
						errs <- fmt.Sprintf("%s %s: %d", req.method, req.path, res.StatusCode)
					}
				}
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Error(err)
	}
	st := e.status()
	for _, d := range st.Devices {
		if d.PendingChanges != 10 {
			t.Errorf("device %s has %d pending changes, want 10", d.Kid, d.PendingChanges)
		}
	}
	if len(st.Package.FetchedBy) != len(devices) {
		t.Errorf("fetchedBy = %v", st.Package.FetchedBy)
	}
}

// putParts uploads pkg in parts of size each, in the order given (all of them when nil), and
// returns the number of parts.
func (e *env) putParts(pid string, pkg []byte, size int, order []int) int {
	e.t.Helper()
	count := (len(pkg) + size - 1) / size
	if order == nil {
		for n := range count {
			order = append(order, n)
		}
	}
	for _, n := range order {
		end := min((n+1)*size, len(pkg))
		expect(e.t, e.do(e.owner, "PUT", fmt.Sprintf("/v1/group/packages/%s/parts/%d", pid, n), pkg[n*size:end], nil), 204)
	}
	return count
}

func sha256Hex(b []byte) string {
	h := sha256.Sum256(b)
	return fmt.Sprintf("%x", h)
}

func (e *env) complete(pid string, parts int, size int, sum string) resp {
	e.t.Helper()
	return e.doJSON(e.owner, "POST", "/v1/group/packages/"+pid+"/complete", map[string]any{"parts": parts, "size": size, "sha256": sum})
}

func TestUploadInParts(t *testing.T) {
	e := newEnv(t)
	d := randKey()
	e.register(d)
	pkg := envelope(1000)
	pid := store.NewID()

	// Out of order, and a part sent twice: the second replaces the first.
	count := e.putParts(pid, pkg, 300, []int{3, 1, 0})
	expect(t, e.do(e.owner, "PUT", "/v1/group/packages/"+pid+"/parts/2", envelope(300), nil), 204)
	if r := e.complete(pid, count, len(pkg), sha256Hex(pkg)); r.status != 409 || r.errorCode(t) != "hash_mismatch" {
		t.Fatalf("a wrong part: %d %s", r.status, r.body)
	}
	e.putParts(pid, pkg, 300, []int{2})
	// Not published until completed.
	expect(t, e.doJSON(e.owner, "POST", "/v1/group/packages/"+pid+"/publish", map[string]any{"keys": map[string]string{}}), 409)
	expect(t, e.complete(pid, count, len(pkg), sha256Hex(pkg)), 204)
	if e.files("parts") != 0 {
		t.Fatal("parts left after completing")
	}
	// A lost response: completing again succeeds; with another size it doesn't.
	expect(t, e.complete(pid, count, len(pkg), sha256Hex(pkg)), 204)
	if r := e.complete(pid, count, len(pkg)+1, sha256Hex(pkg)); r.status != 409 || r.errorCode(t) != "uploaded" {
		t.Fatalf("completing again with another size: %d %s", r.status, r.body)
	}
	// No more parts for an uploaded package.
	if r := e.do(e.owner, "PUT", "/v1/group/packages/"+pid+"/parts/0", pkg[:300], nil); r.status != 409 {
		t.Fatalf("a part for an uploaded package: %d %s", r.status, r.body)
	}

	keys := map[string]string{auth.Kid(d): b64(envelope(65))}
	expect(t, e.doJSON(e.owner, "POST", "/v1/group/packages/"+pid+"/publish", map[string]any{"keys": keys}), 204)
	r := e.do(d, "GET", "/v1/package", nil, nil)
	expect(t, r, 200)
	var meta struct {
		ID   string
		Size int64
	}
	r.json(t, &meta)
	if meta.ID != pid || meta.Size != int64(len(pkg)) {
		t.Fatalf("package %+v", meta)
	}
	got := e.do(d, "GET", "/v1/packages/"+pid, nil, nil)
	expect(t, got, 200)
	if !bytes.Equal(got.body, pkg) {
		t.Fatal("the joined package differs from what was sent")
	}
	if r := e.do(e.owner, "PUT", "/v1/group/packages/"+pid+"/parts/0", pkg[:300], nil); r.status != 409 {
		t.Fatalf("a part for the current package: %d %s", r.status, r.body)
	}
}

func TestUploadInPartsRefusals(t *testing.T) {
	e := newEnv(t, func(c *server.Config) { c.MaxPart = 400; c.MaxPackage = 1000 })
	e.register()
	pkg := envelope(900)

	// Missing parts, a size that doesn't add up, a malformed request.
	pid := store.NewID()
	e.putParts(pid, pkg, 300, []int{0, 2})
	if r := e.complete(pid, 3, len(pkg), sha256Hex(pkg)); r.status != 409 || r.errorCode(t) != "parts_missing" {
		t.Fatalf("a missing part: %d %s", r.status, r.body)
	}
	if r := e.complete(pid, 2, len(pkg), sha256Hex(pkg)); r.status != 409 || r.errorCode(t) != "parts_missing" {
		t.Fatalf("a part beyond the count: %d %s", r.status, r.body)
	}
	e.putParts(pid, pkg, 300, []int{1})
	if r := e.complete(pid, 3, len(pkg)-1, sha256Hex(pkg)); r.status != 409 || r.errorCode(t) != "size_mismatch" {
		t.Fatalf("a wrong size: %d %s", r.status, r.body)
	}
	for _, bad := range []map[string]any{
		{"parts": 0, "size": 900, "sha256": sha256Hex(pkg)},
		{"parts": 3, "size": 900, "sha256": "abc"},
		{"parts": 3, "size": 900, "sha256": strings.ToUpper(sha256Hex(pkg))},
		{"parts": 3, "size": 2},
	} {
		if r := e.doJSON(e.owner, "POST", "/v1/group/packages/"+pid+"/complete", bad); r.status != 400 {
			t.Fatalf("%v: %d %s", bad, r.status, r.body)
		}
	}
	expect(t, e.complete(pid, 3, len(pkg), sha256Hex(pkg)), 204)

	// Part numbers.
	pid = store.NewID()
	for _, n := range []string{"01", "-1", "x", "1024", "10000"} {
		if r := e.do(e.owner, "PUT", "/v1/group/packages/"+pid+"/parts/"+n, envelope(10), nil); r.status != 400 {
			t.Fatalf("part %q: %d %s", n, r.status, r.body)
		}
	}
	// Part 0 starts an envelope; the others needn't.
	if r := e.do(e.owner, "PUT", "/v1/group/packages/"+pid+"/parts/0", []byte("not an envelope"), nil); r.status != 400 || r.errorCode(t) != "not_mir1" {
		t.Fatalf("part 0 not MIR1: %d %s", r.status, r.body)
	}
	expect(t, e.do(e.owner, "PUT", "/v1/group/packages/"+pid+"/parts/1", []byte("anything"), nil), 204)

	// Limits: one part, and the parts together.
	if r := e.do(e.owner, "PUT", "/v1/group/packages/"+pid+"/parts/2", envelope(401), nil); r.status != 413 {
		t.Fatalf("a part over the part limit: %d %s", r.status, r.body)
	}
	expect(t, e.do(e.owner, "PUT", "/v1/group/packages/"+pid+"/parts/2", envelope(400), nil), 204)
	expect(t, e.do(e.owner, "PUT", "/v1/group/packages/"+pid+"/parts/3", envelope(400), nil), 204)
	if r := e.do(e.owner, "PUT", "/v1/group/packages/"+pid+"/parts/4", envelope(400), nil); r.status != 413 {
		t.Fatalf("parts over the package limit: %d %s", r.status, r.body)
	}
	if r := e.complete(pid, 5, 1200, sha256Hex(pkg)); r.status != 413 {
		t.Fatalf("completing over the package limit: %d %s", r.status, r.body)
	}
}

func TestPartsAcrossRestartAndSweep(t *testing.T) {
	e := newEnv(t)
	e.register()
	pkg := envelope(1000)
	pid := store.NewID()
	count := e.putParts(pid, pkg, 400, []int{0, 1})
	e.restart()
	e.putParts(pid, pkg, 400, []int{2})
	expect(t, e.complete(pid, count, len(pkg), sha256Hex(pkg)), 204)

	// Parts left unfinished go an hour after the last one.
	other := store.NewID()
	e.putParts(other, pkg, 400, []int{0})
	e.clk.Advance(50 * time.Minute)
	e.putParts(other, pkg, 400, []int{1})
	e.clk.Advance(50 * time.Minute)
	if err := e.srv.Sweep(); err != nil {
		t.Fatal(err)
	}
	if e.files("parts") != 1 {
		t.Fatal("parts swept within an hour of the last one")
	}
	e.clk.Advance(11 * time.Minute)
	if err := e.srv.Sweep(); err != nil {
		t.Fatal(err)
	}
	if e.files("parts") != 0 {
		t.Fatal("unfinished parts not swept")
	}
	if r := e.complete(other, 3, len(pkg), sha256Hex(pkg)); r.status != 409 || r.errorCode(t) != "parts_missing" {
		t.Fatalf("completing swept parts: %d %s", r.status, r.body)
	}
}
