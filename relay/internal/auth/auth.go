// Package auth implements relay/1 request signing: key IDs, the canonical string, the
// Authorization header and the replay cache. See docs/relay-sync.md, "Request signing".
package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"strconv"
	"strings"
	"time"
)

// B64 is base64url without padding, the only base64 relay/1 uses.
var B64 = base64.RawURLEncoding

const (
	// MaxSkew is how far a request's ts may be from the relay's clock.
	MaxSkew = 300 * time.Second
	// ReplayWindow is how long a seen signature is remembered at least. A signature passes the
	// skew check for at most 2 × MaxSkew, so this covers it.
	ReplayWindow = 600 * time.Second
)

// Kid returns a key's ID: base64url(sha256(key)), 43 characters.
func Kid(key []byte) string {
	s := sha256.Sum256(key)
	return B64.EncodeToString(s[:])
}

// Canonical builds the string that is signed. bodyHash is sha256 of the body.
func Canonical(method, pathAndQuery string, ts int64, bodyHash []byte) string {
	return "MIR1\n" + method + "\n" + pathAndQuery + "\n" + strconv.FormatInt(ts, 10) + "\n" + hex.EncodeToString(bodyHash)
}

// Sign returns the raw HMAC-SHA256 of the canonical string.
func Sign(key []byte, method, pathAndQuery string, ts int64, bodyHash []byte) []byte {
	m := hmac.New(sha256.New, key)
	m.Write([]byte(Canonical(method, pathAndQuery, ts, bodyHash)))
	return m.Sum(nil)
}

// Header returns the Authorization header value for a request. Used by tests and clients.
func Header(key []byte, method, pathAndQuery string, ts int64, body []byte) string {
	h := sha256.Sum256(body)
	sig := Sign(key, method, pathAndQuery, ts, h[:])
	return "MIR1 kid=" + Kid(key) + ", ts=" + strconv.FormatInt(ts, 10) + ", sig=" + B64.EncodeToString(sig)
}

// Credentials are the parsed parts of an Authorization header.
type Credentials struct {
	Kid string
	TS  int64
	Sig []byte
}

var ErrMalformed = errors.New("malformed Authorization header")

// Parse reads "MIR1 kid=<kid>, ts=<unix seconds>, sig=<base64url>".
func Parse(header string) (Credentials, error) {
	var c Credentials
	rest, ok := strings.CutPrefix(header, "MIR1 ")
	if !ok {
		return c, ErrMalformed
	}
	var haveKid, haveTS, haveSig bool
	for _, part := range strings.Split(rest, ",") {
		name, value, ok := strings.Cut(strings.TrimSpace(part), "=")
		if !ok || value == "" {
			return c, ErrMalformed
		}
		switch name {
		case "kid":
			c.Kid, haveKid = value, true
		case "ts":
			ts, err := strconv.ParseInt(value, 10, 64)
			if err != nil {
				return c, ErrMalformed
			}
			c.TS, haveTS = ts, true
		case "sig":
			sig, err := B64.DecodeString(value)
			if err != nil || len(sig) != sha256.Size {
				return c, ErrMalformed
			}
			c.Sig, haveSig = sig, true
		default:
			return c, ErrMalformed
		}
	}
	if !haveKid || !haveTS || !haveSig {
		return c, ErrMalformed
	}
	return c, nil
}

// InSkew reports whether ts is within MaxSkew of now.
func InSkew(ts int64, now time.Time) bool {
	d := now.Unix() - ts
	return d <= int64(MaxSkew/time.Second) && d >= -int64(MaxSkew/time.Second)
}

// Verify checks c.Sig against the request in constant time.
func Verify(key []byte, c Credentials, method, pathAndQuery string, bodyHash []byte) bool {
	return hmac.Equal(Sign(key, method, pathAndQuery, c.TS, bodyHash), c.Sig)
}
