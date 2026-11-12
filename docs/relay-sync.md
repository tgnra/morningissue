# Relay sync (protocol relay/1)

Relay sync is how a Kobo syncs with the phone, wherever each of them is. The phone uploads an encrypted package of editions, saved articles and pictures to a relay server. Each paired device uploads its encrypted changes and downloads the package. The phone then collects the changes, merges them and tells the relay to delete what it has finished with.

It is the only way a Kobo syncs. It carries the formats of `docs/kobo-sync.md` unchanged: the manifest, edition bundles, the saved shelf, pictures and events, first defined by protocol v1, a LAN protocol the Kobo no longer speaks. Only the way they travel is new.

The relay is a dumb mailbox:

- It never sees plaintext. Everything it stores is encrypted with keys only the phone and the paired devices have.
- It never holds a secret that could decrypt anything. It holds request-signing keys, which only let a caller talk to the relay.
- The phone alone decides which devices belong to its group, by sending the relay the list.
- Everything is deleted once used, and in any case after 48 hours.

## Pieces

| Side | What | Where |
|---|---|---|
| Relay | One Go binary, standard library only | `relay/` |
| Phone | Owner key, QR scanning, device list, package export, change merge | `app/` (`net.tgnra.morningissue.relay`) |
| Kobo | Device secret, QR display, signed client, package install | `kobo/` |

## Keys

All keys are 32 bytes. HKDF is HKDF-SHA256 (RFC 5869) with an empty salt, 32 bytes of output, and the `info` string given here in UTF-8.

| Key | Made by | From |
|---|---|---|
| Device secret `S` | The device, once, from a secure random source | Kept in the device's state. Erased by Reset. |
| Device auth key | Both the device and the phone | `HKDF(S, "morningissue relay auth v1")` |
| Device enc key | Both the device and the phone | `HKDF(S, "morningissue relay enc v1")`. Never leaves the device or the phone. |
| Owner secret `O` | The phone, once per install | Kept in the phone's settings. |
| Owner key | The phone | `HKDF(O, "morningissue relay owner v1")` |
| Package key | The phone, fresh for every package | Random |

A key's **kid** is `base64url(sha256(key))`, with no padding (43 characters). The group's ID is the owner key's kid.

The relay learns the owner key and every device auth key, because it needs them to check signatures. It never learns `S`, `O`, an enc key or a package key.

`base64url` means RFC 4648 §5 with no padding, everywhere in this document.

## Pairing

The device has no camera, so it shows a QR code and the phone scans it. Pairing never touches the relay.

The QR code is this URI, its query values percent-encoded:

```
morningissue:pair?v=1&s=<base64url S>&d=<deviceId>&n=<name>&w=<short side in px>
```

- `d` is the device's `deviceId`, a random UUID it makes once and keeps.
- `n` is its name ("Kobo Libra 2").
- `w` is its screen's short side in pixels, the width the phone prepares its pictures at.
- The phone refuses `v` values it doesn't know.

Scanning is the consent: there is no extra Allow prompt.

The phone stores the device: `deviceId`, name, `w`, auth key and enc key. It should keep the two keys wrapped with an Android Keystore key. It then sends the relay the new device list straight away (`PUT /v1/group/devices`). Until then the device's requests get `401`, and the device shows "Waiting for the phone".

While it shows the code, a device may ask whether the phone has done so: a signed `GET /v1/package` answers `401 "unknown"` until the device is listed, and `200` or `204` after. The Kobo asks every 5 seconds while the code is on screen and it is online, and syncs as soon as it is listed. It takes nothing else from that answer.

The device also needs the relay's address. The QR code can't carry it, since the device shows the code and doesn't read one. A Kobo uses `https://mirelay.tgnra.net` unless `"relay"` in its `config.json` names another (`http[s]://host[:port][/prefix]`; without a scheme, an IPv4 address gets `http://` and a name `https://`). Nothing on the Kobo changes it; Settings shows it. The phone must publish to the same relay.

**Unpairing:** the phone removes the device and sends the list again. The relay deletes that device's pending changes and its fetch record. Its requests get `401` from then on, and the device shows itself as unpaired.

Pairing the same `deviceId` again replaces its keys and keeps its `ackedSeq`.

## Encryption envelope (MIR1)

Every blob the relay stores is one envelope. Large packages are split into chunks, so they can be decrypted as a stream with little memory, and a dropped or reordered chunk fails.

```
header (17 bytes) = "MIR1" | kind (1 byte) | nonce prefix (8 bytes, random) | chunk size (uint32 BE)
body              = chunk_0 | chunk_1 | … | chunk_last
chunk_i           = AES-256-GCM(key, nonce = prefix | uint32_be(i),
                                plaintext_i,
                                aad = header | uint32_be(i) | final_i | context)   (plaintext + 16-byte tag)
```

- **`kind`:** 1 for a package, 2 for changes, 3 for a wrapped key.
- **Chunks:** the plaintext is split into chunks of `chunk size` bytes. The last chunk is shorter, or empty when the plaintext length is a multiple of the chunk size, including empty plaintext. So there is always a final chunk.
- **`final_i`:** 1 for the last chunk, 0 otherwise. It is what stops truncation.
- **Chunk size:** writers use 1 MiB (1048576). Readers accept 1 KiB to 16 MiB and refuse anything else.
- **Reading:** a reader knows a chunk is the last one when it ends the body. It must refuse an envelope whose last chunk fails, or that has data after a final chunk.
- **`context`** binds the envelope to its place, in UTF-8:

| Envelope | Key | `context` |
|---|---|---|
| Package | Package key | `package|<packageId>` |
| Changes | Device enc key | `changes|<device kid>|<batch>` |
| Wrapped package key | Device enc key | `wrap|<device kid>|<packageId>` (plaintext is the 32-byte package key) |

Nonces are never reused. Each package key encrypts one package, and the random 8-byte prefix keeps enc-key envelopes apart.

## Request signing

Every request except `GET /v1/health` carries:

```
Authorization: MIR1 kid=<kid>, ts=<unix seconds>, sig=<base64url HMAC-SHA256(key, canonical)>
canonical = "MIR1\n" + METHOD + "\n" + path-and-query exactly as sent + "\n" + ts + "\n" + hex(sha256(body))
```

- `hex` is lowercase. An empty body hashes as the SHA-256 of zero bytes.
- **The key** is the owner key for `/v1/group…` paths and the device's auth key for device paths.
- **Rejected with `401`:**
  - an unknown kid;
  - a kid used on the wrong kind of path;
  - a bad signature;
  - `ts` more than 300 seconds from the relay's clock;
  - a signature already seen in the last 600 seconds (replay).
- **Clock:** every response carries `Date`. A device with an unreliable clock (a Kobo) sets `ts` from the offset to the last `Date` it saw. After a `401` it retries once with the offset from that response's `Date`.
- **`401` body:** `{"error": "skew" | "unknown" | "signature" | "replay"}`, so a client can tell a clock problem from being unpaired.

No secret crosses the network. Signing and end-to-end encryption therefore protect everything even over plain `http://`, where an eavesdropper sees only encrypted blobs, their sizes and timing. TLS is extra protection on top, not a replacement.

- **The phone always uses `https://`**, because `PUT /v1/group` and `PUT /v1/group/devices` carry keys.
- **The Kobo supports `https://`** (rustls with the ring provider and the webpki root store; clang compiles ring for the ARM build). It checks certificate validity against its clock corrected by the last `Date` offset, and never against a time earlier than its own build date. Plain `http://` remains allowed for relays without a certificate.
- **The relay can serve both**: TLS directly or behind a reverse proxy, and plain HTTP on another port if wanted.
- **The relay can sit under a path prefix** (`https://example.com/morningissue`). Clients put the address's path in front of every path in this document and sign the full path as sent; the relay, started with `-base-path`, routes on the path below the prefix (`relay/README.md`).

## Relay API

JSON bodies are UTF-8. Errors are `{"error": "<code>"}` with these statuses:

- `400`: malformed request.
- `401`: see above.
- `404`: not found.
- `409`: a conflicting state, for example publishing a package that wasn't uploaded.
- `413`: over a size limit.
- `429`: over a count limit.

Blob IDs are base64url and 16 to 64 characters long.

### Owner (signed with the owner key)

**`PUT /v1/group`** creates the group or confirms it exists. It is idempotent.

- Body: `{"ownerKey": "<base64url owner key>"}`.
- The request's `kid` must be that key's kid, and it is signed with that key.
- If the relay is configured with a registration token, a group can only be created with the header `X-Relay-Registration: <token>`, and gets `403` without it.
- Response: `200 {"group": "<kid>", "created": true|false}`.

**`PUT /v1/group/devices`** replaces the group's device list.

- Body: `{"devices": ["<base64url auth key>", …]}`, at most 32 entries.
- Removed devices lose their pending changes and fetch records.
- Response: `200 {"devices": ["<kid>", …]}`.

**`PUT /v1/group/packages/{packageId}`** uploads a package envelope.

- `packageId` is 16 random bytes in base64url, made by the phone.
- The body is streamed, up to the relay's package limit (default 512 MiB).
- The package isn't offered to anyone until it is published. Unpublished uploads are deleted after 1 hour.
- Response: `204`.

**`POST /v1/group/packages/{packageId}/publish`** makes the package current.

- Body: `{"keys": {"<device kid>": "<base64url wrapped-key envelope>", …}}`.
- Every listed device should have a key. A device without one gets `204` from `GET /v1/package`, as though there were no package.
- The previous current package and its keys are deleted.
- Response: `204`.

**`GET /v1/group/status`** returns:

```json
{"package": {"id": "…", "size": 123, "publishedAt": "…", "fetchedBy": ["<kid>"]} | null,
 "devices": [{"kid": "…", "lastSeenAt": "…" | null, "pendingChanges": 2}]}
```

**`GET /v1/group/changes`** returns `{"changes": [{"id": "…", "kid": "…", "batch": "1-500", "size": 123, "receivedAt": "…"}]}`, oldest first.

**`GET /v1/group/changes/{id}`** returns the envelope bytes (`application/octet-stream`).

**`POST /v1/group/done`** is the phone's "Done".

- Body: `{"changes": ["<id>", …]}`.
- Deletes those changes.
- Deletes the current package if every device in the list has fetched it. Otherwise the package stays until it is replaced or expires.
- Response: `200 {"deletedChanges": n, "packageDeleted": true|false}`.

**`DELETE /v1/group`** deletes the group and everything in it. Response: `204`.

### Device (signed with the device auth key)

**`GET /v1/package`** returns `200 {"id": "…", "size": 123, "key": "<base64url wrapped-key envelope>"}` for the current package, or `204` when there is none for this device.

**`GET /v1/packages/{packageId}`** returns the package envelope.

- It supports `Range: bytes=<start>-` and `bytes=<start>-<end>` (`206`), so a dropped download resumes. `ETag` is the package ID.
- `404` once the package has been replaced or deleted. The device then asks `GET /v1/package` again.

**`POST /v1/packages/{packageId}/fetched`** records that this device has the package. Response: `204`.

**`PUT /v1/changes/{batch}`** stores one changes envelope.

- `batch` is `<firstSeq>-<lastSeq>` in decimal, the range of event `seq` values inside.
- Body: at most 4 MiB.
- Uploading the same batch again replaces it, so retries are harmless.
- A device may have at most 64 pending batches. More get `429`.
- Response: `204`.

### Public

**`GET /v1/health`** returns `200 {"ok": true, "protocol": "relay/1"}`.

### Retention

A sweeper runs every minute and deletes:

- packages and changes older than the TTL (default 48 hours);
- unpublished uploads older than 1 hour;
- groups whose owner hasn't made a signed request in 90 days.

Deleted means removed from disk, not just hidden.

## Package contents

The package plaintext is a zip:

| Entry | Holds |
|---|---|
| `manifest.json` | The manifest of `docs/kobo-sync.md` (`v`, `dbId`, `editions`, `saved`), with `"protocol": "relay/1"`, `"builtAt"` and `"acked": {"<deviceId>": <ackedSeq>, …}` for every paired relay device |
| `editions/<id>.json` | One edition bundle (`docs/kobo-sync.md`) per manifest edition |
| `saved.json` | The saved shelf (`docs/kobo-sync.md`) |
| `images.json` | `[{"url": "…", "w": 1264, "file": "images/<sha256 hex of url>-<w>", "type": "image/jpeg"}]` |
| `images/…` | Pictures prepared as `docs/kobo-sync.md` ("Pictures") describes, at each paired device's `w` (one copy per distinct width) |

A device reads `manifest.json` first. If `dbId` differs from the one it saved, it wipes and starts over (`docs/kobo-sync.md`, "Manifest"). It takes the images at its own `w`; if its width is missing, it uses the nearest larger width and scales down.

## Changes contents

The changes plaintext is `{"deviceId": "…", "events": [ … ]}`, holding at most 500 events (`docs/kobo-sync.md`, "Events"). Their `seq` values must lie within the batch's range.

The phone merges them as `docs/kobo-sync.md` describes:

- only events with `seq > ackedSeq` for that device;
- in one transaction that also stores the new `ackedSeq`.

The phone drops, unmerged, a batch that fails to decrypt, or whose `deviceId` doesn't belong to the key that sent it. It still deletes that batch from the relay.

## Order of a sync

### Phone: publish

Runs after the early build, when a relay is configured, and from **Publish** on the Sync screen.

1. `PUT /v1/group` (only the first time, or after a `401`), then `PUT /v1/group/devices`.
2. Build the package from the three latest released editions, the saved shelf and the images, exported and prepared as `docs/kobo-sync.md` describes.
3. Encrypt it with a fresh package key and `PUT` it. Wrap the key for each device, then publish.

### Device: sync

Runs on every Kobo sync, once the device has a secret (it has shown its pairing code). Without one the Kobo says it is not paired and asks the relay nothing.

1. Upload unsent events, oldest first, in batches of up to 500 (`PUT /v1/changes/{first}-{last}`).
   - "Unsent" means above the highest `seq` already uploaded.
   - Uploaded events are kept until a package's `acked` shows the phone has merged them, and they are still applied on top of what the device shows, as unsent events are.
2. `GET /v1/package`. If its `id` differs from the last installed one:
   - download to a temporary file, resuming with `Range`;
   - unwrap the key and decrypt as a stream;
   - check `dbId`, then install the editions, saved shelf and images (`docs/kobo-sync.md`, "Installing a package", writing to temporary files and renaming them into place);
   - `POST …/fetched`.
3. Delete events at or below `acked[deviceId]`.
   - The relay may delete uploaded changes before the phone merges them: after the TTL, or when the phone drops the device. So a device uploads its unacknowledged events again after a `401 "unknown"`, and once an upload has gone 48 hours without being acknowledged. The phone skips events at or below `ackedSeq`, so this is harmless.
   - A device must never send two requests with the same signature. If a new request would carry the same `ts` as one it sent before with the same method, path and body, it moves `ts` on by a second.
4. `401` with `"unknown"` means the phone no longer accepts this device. Show "Not paired" and offer pairing again. Other `401`s follow the clock rule above.

### Phone: done

Runs from **Done** on the Sync screen, and from the periodic sync when a relay is configured.

1. `GET /v1/group/changes`, then download and decrypt each batch.
2. Merge each batch as above.
3. `POST /v1/group/done` with every batch handled, merged or dropped.
4. Relearn affinity if anything merged, as for the phone's own reader.

## Test vectors

`docs/relay-sync-fixtures/vectors.json` has HKDF outputs, kids, envelopes (several chunk sizes, an exact multiple, empty plaintext), a wrapped key, a changes envelope and two signatures, all from fixed inputs. `docs/relay-sync-fixtures/generate/` is the Go program that writes them (`go run .`). They have been checked against an independent Python implementation.

Every implementation must have a test that reproduces every vector.

Some vectors use 16-byte chunks so that a short plaintext still spans several chunks. That is below the 1 KiB that readers accept. Implementations keep the 1 KiB minimum in production and relax it only in the test that opens those vectors.

## Known limits of relay/1

- The QR code can't carry the relay's address, because the device shows the code and doesn't read one. A Kobo uses the default relay, or the one in its `config.json`.
- A device's changes reach the edition engine only when the phone runs Done. Events that arrive after the next build have already counted their stories as unopened (`docs/kobo-sync.md`, "Events").
- The relay sees group sizes, blob sizes and timing. Over plain HTTP, so does anyone on the network path.
