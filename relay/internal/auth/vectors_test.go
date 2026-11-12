package auth

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hkdf"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// The shared test vectors in docs/relay-sync-fixtures/vectors.json. The relay itself only needs
// kids and signatures; HKDF and the envelopes are checked too, so the relay agrees with the phone
// and the Kobo on every byte it handles.

type envelopeVector struct {
	ChunkSize   uint32 `json:"chunkSize"`
	Context     string `json:"context"`
	Envelope    string `json:"envelope"`
	EnvelopeB64 string `json:"envelope_b64"`
	Plaintext   string `json:"plaintext"` // absent means the top-level plaintext
	Batch       string `json:"batch"`
}

type signatureVector struct {
	Key    string `json:"key"`
	Method string `json:"method"`
	Path   string `json:"path"`
	TS     int64  `json:"ts"`
	Body   string `json:"body"`
	SigB64 string `json:"sig_b64"`
	Header string `json:"header"`
}

type vectors struct {
	DeviceSecret       string          `json:"deviceSecret"`
	DeviceAuthKey      string          `json:"deviceAuthKey"`
	DeviceAuthKeyB64   string          `json:"deviceAuthKey_b64"`
	DeviceEncKey       string          `json:"deviceEncKey"`
	DeviceKidB64       string          `json:"deviceKid_b64"`
	OwnerSecret        string          `json:"ownerSecret"`
	OwnerKey           string          `json:"ownerKey"`
	OwnerKeyB64        string          `json:"ownerKey_b64"`
	OwnerKidB64        string          `json:"ownerKid_b64"`
	PackageID          string          `json:"packageId"`
	PackageKey         string          `json:"packageKey"`
	NoncePrefix        string          `json:"noncePrefix"`
	Plaintext          string          `json:"plaintext"`
	PackageChunk16     *envelopeVector `json:"package_chunk16"`
	PackageChunk1MiB   *envelopeVector `json:"package_chunk1MiB"`
	PackageExact       *envelopeVector `json:"package_exact_multiple"`
	PackageEmpty       *envelopeVector `json:"package_empty"`
	Wrap               *envelopeVector `json:"wrap"`
	Changes            *envelopeVector `json:"changes"`
	Signature          signatureVector `json:"signature"`
	SignatureEmptyBody signatureVector `json:"signature_empty_body"`
}

func loadVectors(t *testing.T) vectors {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "docs", "relay-sync-fixtures", "vectors.json"))
	if err != nil {
		t.Fatal(err)
	}
	var v vectors
	dec := json.NewDecoder(bytes.NewReader(raw))
	if err := dec.Decode(&v); err != nil {
		t.Fatal(err)
	}
	return v
}

func unhex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func derive(t *testing.T, secret []byte, info string) []byte {
	t.Helper()
	k, err := hkdf.Key(sha256.New, secret, nil, info, 32)
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func TestVectorKeysAndKids(t *testing.T) {
	v := loadVectors(t)
	S := unhex(t, v.DeviceSecret)
	O := unhex(t, v.OwnerSecret)
	authKey := derive(t, S, "morningissue relay auth v1")
	encKey := derive(t, S, "morningissue relay enc v1")
	ownerKey := derive(t, O, "morningissue relay owner v1")

	check := func(name, got, want string) {
		t.Helper()
		if got != want {
			t.Errorf("%s = %s, want %s", name, got, want)
		}
	}
	check("deviceAuthKey", hex.EncodeToString(authKey), v.DeviceAuthKey)
	check("deviceAuthKey_b64", B64.EncodeToString(authKey), v.DeviceAuthKeyB64)
	check("deviceEncKey", hex.EncodeToString(encKey), v.DeviceEncKey)
	check("ownerKey", hex.EncodeToString(ownerKey), v.OwnerKey)
	check("ownerKey_b64", B64.EncodeToString(ownerKey), v.OwnerKeyB64)
	check("deviceKid_b64", Kid(authKey), v.DeviceKidB64)
	check("ownerKid_b64", Kid(ownerKey), v.OwnerKidB64)
	if len(Kid(authKey)) != 43 {
		t.Errorf("kid length %d, want 43", len(Kid(authKey)))
	}
}

func TestVectorSignatures(t *testing.T) {
	v := loadVectors(t)
	keys := map[string][]byte{
		"deviceAuthKey": unhex(t, v.DeviceAuthKey),
		"ownerKey":      unhex(t, v.OwnerKey),
	}
	for name, sv := range map[string]signatureVector{"signature": v.Signature, "signature_empty_body": v.SignatureEmptyBody} {
		t.Run(name, func(t *testing.T) {
			key := keys[sv.Key]
			body := unhex(t, sv.Body)
			bh := sha256.Sum256(body)
			got := B64.EncodeToString(Sign(key, sv.Method, sv.Path, sv.TS, bh[:]))
			if got != sv.SigB64 {
				t.Fatalf("sig = %s, want %s", got, sv.SigB64)
			}
			hdr := Header(key, sv.Method, sv.Path, sv.TS, body)
			if sv.Header != "" && hdr != sv.Header {
				t.Fatalf("header = %q, want %q", hdr, sv.Header)
			}
			c, err := Parse(hdr)
			if err != nil {
				t.Fatal(err)
			}
			if c.Kid != Kid(key) || c.TS != sv.TS {
				t.Fatalf("parsed %+v", c)
			}
			if !Verify(key, c, sv.Method, sv.Path, bh[:]) {
				t.Fatal("Verify rejected the vector")
			}
			if Verify(key, c, sv.Method, sv.Path+"x", bh[:]) {
				t.Fatal("Verify accepted a changed path")
			}
		})
	}
}

// seal is the reference MIR1 writer from docs/relay-sync-fixtures/generate/main.go.
func seal(t *testing.T, key []byte, kind byte, prefix []byte, chunk uint32, context string, pt []byte) []byte {
	t.Helper()
	hdr := append([]byte("MIR1"), kind)
	hdr = append(hdr, prefix...)
	hdr = binary.BigEndian.AppendUint32(hdr, chunk)
	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatal(err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		t.Fatal(err)
	}
	out := append([]byte{}, hdr...)
	n := len(pt)/int(chunk) + 1 // always a final chunk, empty on an exact multiple
	for i := 0; i < n; i++ {
		start := i * int(chunk)
		end := min(start+int(chunk), len(pt))
		nonce := binary.BigEndian.AppendUint32(append([]byte{}, prefix...), uint32(i))
		final := byte(0)
		if i == n-1 {
			final = 1
		}
		aad := append([]byte{}, hdr...)
		aad = binary.BigEndian.AppendUint32(aad, uint32(i))
		aad = append(aad, final)
		aad = append(aad, context...)
		out = append(out, gcm.Seal(nil, nonce, pt[start:end], aad)...)
	}
	return out
}

func TestVectorEnvelopes(t *testing.T) {
	v := loadVectors(t)
	prefix := unhex(t, v.NoncePrefix)
	pkgKey := unhex(t, v.PackageKey)
	encKey := unhex(t, v.DeviceEncKey)
	kid := v.DeviceKidB64
	cases := []struct {
		name    string
		ev      *envelopeVector
		key     []byte
		kind    byte
		context string
	}{
		{"package_chunk16", v.PackageChunk16, pkgKey, 1, "package|" + v.PackageID},
		{"package_chunk1MiB", v.PackageChunk1MiB, pkgKey, 1, "package|" + v.PackageID},
		{"package_exact_multiple", v.PackageExact, pkgKey, 1, "package|" + v.PackageID},
		{"package_empty", v.PackageEmpty, pkgKey, 1, "package|" + v.PackageID},
		{"wrap", v.Wrap, encKey, 3, "wrap|" + kid + "|" + v.PackageID},
		{"changes", v.Changes, encKey, 2, "changes|" + kid + "|" + "1-2"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if c.ev == nil {
				t.Fatal("vector missing")
			}
			if c.ev.Context != c.context {
				t.Fatalf("context = %q, want %q", c.ev.Context, c.context)
			}
			pt := unhex(t, v.Plaintext)
			if c.name != "package_chunk16" && c.name != "package_chunk1MiB" {
				pt = unhex(t, c.ev.Plaintext)
			}
			var want []byte
			if c.ev.EnvelopeB64 != "" {
				var err error
				if want, err = B64.DecodeString(c.ev.EnvelopeB64); err != nil {
					t.Fatal(err)
				}
			} else {
				want = unhex(t, c.ev.Envelope)
			}
			got := seal(t, c.key, c.kind, prefix, c.ev.ChunkSize, c.ev.Context, pt)
			if !bytes.Equal(got, want) {
				t.Fatalf("envelope mismatch\n got %x\nwant %x", got, want)
			}
			if !bytes.HasPrefix(want, []byte("MIR1")) {
				t.Fatal("no MIR1 magic")
			}
		})
	}
}

func TestParseRejects(t *testing.T) {
	good := Header([]byte("k"), "GET", "/v1/package", 1, nil)
	if _, err := Parse(good); err != nil {
		t.Fatal(err)
	}
	for _, h := range []string{
		"",
		"Bearer x",
		"MIR1 kid=a, ts=1",
		"MIR1 kid=a, ts=x, sig=" + B64.EncodeToString(make([]byte, 32)),
		"MIR1 kid=a, ts=1, sig=short",
		"MIR1 kid=a, ts=1, sig=" + B64.EncodeToString(make([]byte, 32)) + ", extra=1",
	} {
		if _, err := Parse(h); err == nil {
			t.Errorf("Parse(%q) accepted", h)
		}
	}
}

func TestSkewAndReplay(t *testing.T) {
	now := time.Unix(1794466800, 0)
	if !InSkew(now.Unix()-300, now) || !InSkew(now.Unix()+300, now) {
		t.Error("300 s should be accepted")
	}
	if InSkew(now.Unix()-301, now) || InSkew(now.Unix()+301, now) {
		t.Error("301 s should be refused")
	}
	r := NewReplayCache()
	sig := []byte("sig")
	seen := func(at time.Time) bool {
		t.Helper()
		s, err := r.Seen("k", sig, now.Unix(), at)
		if err != nil {
			t.Fatal(err)
		}
		return s
	}
	if seen(now) {
		t.Fatal("first use reported as replay")
	}
	if !seen(now.Add(599 * time.Second)) {
		t.Fatal("replay within 600 s not caught")
	}
	if seen(now.Add(600 * time.Second)) {
		t.Fatal("signature still remembered after 600 s")
	}
	r.Prune(now.Add(2000 * time.Second))
	if r.Len() != 0 {
		t.Fatalf("Len after prune = %d", r.Len())
	}
}
