# Kobo sync: formats, files and launch

The self-hosted server owns the database ([`docs/server-design.md`](server-design.md)). A Kobo takes the last three released editions, the saved shelf and their pictures from the server, shows them in its own reader, and sends back what the reader did as a list of events. The Kobo never changes anything else.

The Kobo talks HTTPS (or plain HTTP) straight to the server, protocol server/1, with a token derived from the secret in its pairing code. Once the phone has registered that code, no phone is involved again. This document holds what server/1 carries and what the Kobo does with it:

- the transport: auth, the events gate, errors and the order of a sync;
- the formats of the manifest, edition bundles, the saved shelf, pictures and events (first defined by protocol v1, a LAN protocol, then carried by the relay's relay/1, and kept unchanged);
- how the Kobo installs them;
- the files on the Kobo and its launch flow.

## Pieces

| Side | What | Where |
|---|---|---|
| Server | The database, edition builds, the Kobo API, picture preparation, event merge | `server/` |
| Phone | Registers the Kobo by scanning its pairing code (`POST /api/v1/devices/kobo`) | `app/` |
| Kobo | One static binary, `morningissue`, with `sync` and `read` subcommands, launched from NickelMenu | `kobo/` |

## Conventions

JSON is UTF-8. Times are ISO-8601 instants in UTC (`2026-11-11T06:30:00Z`). The server writes whole seconds; the Kobo reads fractions of a second and offsets too. IDs are the server's row IDs (`Long`). Readers ignore fields they don't know, so a newer server does not break an older Kobo.

## Transport (server/1)

### Pairing code and token

The Kobo makes a 32-byte secret `S` the first time it shows its pairing code, keeps it in `state.json` (`secret`) and erases it only on Reset. The code is:

```
morningissue:pair?v=2&s=<b64url S>&d=<deviceId>&n=<name>&w=<short side px>
```

`b64url` is base64url without padding. The phone sends the raw text to the server, which derives the Kobo's token, stores its hash and drops `S`. Scanning is the consent; nothing is asked on the Kobo.

The token is `T = b64url(HKDF-SHA256(ikm = S, salt = empty, info = "morningissue server token v1", 32 bytes))`, sent as `Authorization: Bearer <T>`. The server keeps `hex(sha256(T))`, hashing the ASCII string `T`. A vector (`kobo/src/crypto.rs`, `tests/server.rs`):

| | |
|---|---|
| `S` | bytes `00 01 … 1f`; b64url `AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8` |
| `T` | `gLYEzapDfvjcIu5h42jFUFPx46QBYg0sKsGaedJRfzc` |
| `hex(sha256(T))` | `fc27ecea41821dbf6c80ee0f797b450c9eb03525dcd0c4bdc67c8d9dc441a89b` |

### Server address

The address is `http[s]://host[:port][/prefix]`; every path below is under `<address>/api/v1`. It comes from `config.json` (`"server"`), or else from the Settings keyboard, saved in `data/settings.json` (`server`). `config.json` wins. Without `http://` or `https://`, an IPv4 address gets `http://` and a name `https://`. There is no built-in default. The server serves `GET /api/v1/kobo/config.json` (`{"server": "<public URL>"}`) to copy onto the Kobo over USB.

`https://` servers are checked against the Mozilla root certificates, with the clock corrected by the last `Date` header the server sent and never earlier than the build date. Plain `http://` works, but Settings warns that the token then travels unencrypted.

### Errors

Errors are `{"error": "<code>", "message": "<text>"}`. The Kobo acts on:

| Answer | Meaning to the Kobo |
|---|---|
| `401 unknown` | The token is not registered. Before the server has ever accepted this Kobo, the phone hasn't scanned the code yet ("Waiting for the phone"). After, the Kobo was removed from the account ("This Kobo was removed from your account; pair again.") |
| Another `401` (`unauthenticated`) | The token was refused; reported as an unexpected answer |
| `409 events_pending` | The body has `"acked": n`: upload the events above `n`, then ask again |
| `400 events_through_required` | A bug in the Kobo; reported as an unexpected answer |

Anything else unexpected (403, 404 for a listed edition, 413, 503 `busy`, a body that doesn't parse) ends the sync with the status, code and message. Not reaching the server, or a connection that broke off, is "Can't reach the server."

### The events gate

`GET /sync/manifest`, `GET /editions/{id}/bundle`, `GET /saved` and `POST /kobo/images` are gated. Each carries `X-Events-Through: <seq>`, the highest event `seq` still in `data/events.jsonl`, or 0 when it holds none. If that is above what the server has merged from this Kobo, the server answers `409 events_pending` with its `acked`. The Kobo then drops its events at or below `acked`, uploads the rest and repeats the request. It gives up after two such rounds (three tries in all): "The server didn't take this Kobo's reading history. Sync again later." An event recorded while a sync runs raises the header for the requests after it, so it is uploaded in the same sync.

### Pairing wait

While the pairing code is shown and the Kobo is online, it asks `GET /kobo/hello` every 5 seconds. `401 unknown` means not yet; `200 {"username", "server", "dbId"}` means the phone has registered it, and the reader goes back to Settings and syncs. Nothing is uploaded or installed by the question. The phone needn't stay open, then or later.

### A sync

1. No secret: "Not paired yet". No server address: the reader opens the address keyboard; `morningissue sync` says there is none.
2. `GET /kobo/hello`, before anything is uploaded. If its `dbId` differs from the stored one, wipe first ("Manifest" below), so events recorded against a database the server no longer has (reset, or restored from another backup) are never uploaded; then store the new `dbId` and go on. A server that leaves `dbId` out of hello changes nothing here, and the manifest's `dbId` decides. Its errors are handled as any request's ("Errors" above): `401 unknown` is "Waiting for the phone" or "removed", and nothing is uploaded.
3. Upload events: `POST /sync/events` `{"events": [...]}` with every event above the stored `acked`, at most 500 per request. Each answer is `200 {"acked": n}`; store `n` and delete events at or below it from `events.jsonl`.
4. `GET /sync/manifest?editions=3`, gated. Check `protocol` is `server/1`, and `dbId` again (on a change, wipe and start over: "Manifest" below). Store `acked` for this device and drop events at or below it.
5. Each edition whose `version` differs from the stored one: `GET /editions/{id}/bundle`, gated. Save each as it comes, so the reader can show it straight away.
6. If `saved.version` differs from the stored one: `GET /saved`, gated.
7. Pictures not stored yet: `POST /kobo/images` `{"width": <screen short side>, "urls": [...]}`, gated, at most 100 URLs per request ("Pictures" below).
8. Delete local editions that aren't in the manifest, saved articles that aren't in the list, and images nothing refers to. Record the sync's time and result.

Requests time out after 30 s each (120 s for a picture zip), and a whole sync after 10 minutes.

## Manifest

`GET /api/v1/sync/manifest?editions=3`:

```json
{
  "v": 1,
  "protocol": "server/1",
  "builtAt": "2026-11-12T06:00:00Z",
  "serverTime": "2026-11-12T06:00:00Z",
  "dbId": "3f0c…",
  "editions": [
    {"id": 120, "date": "2026-11-11", "kind": "MORNING", "number": 120, "releaseAt": "…", "version": "<opaque>"}
  ],
  "saved": {"count": 14, "version": "<opaque>"},
  "acked": {"b7e2…": 41}
}
```

`editions` lists the three most recent editions whose `releaseAt` has passed, newest first. `version` changes whenever the bundle's content would change; the server uses the SHA-256 of the bundle bytes.

`saved.version` changes whenever the saved shelf would be different. A manifest that leaves `saved` out has no saved shelf to offer, and the Kobo then drops its own.

`acked` holds, for this Kobo's `deviceId`, the highest event `seq` from it that the server has merged, or 0. (v1's manifest had it as `ackedSeq`.)

`dbId` is a random ID the server keeps per account; `GET /kobo/hello` answers it too, and a sync checks it there first, before uploading. If it differs from the one the Kobo saved, the Kobo drops all its editions, unsent events, Likes and images, then starts over: events naming the old database's IDs would otherwise attach to the wrong articles.

## Edition bundle

`GET /api/v1/editions/{id}/bundle` (v1's `GET /v1/editions/{id}`):

```json
{
  "id": 120, "date": "2026-11-11", "kind": "MORNING", "number": 120,
  "length": "STANDARD", "releaseAt": "…", "finishedAt": null,
  "pages": [
    {"page": 1, "items": [
      {"articleId": 9001, "slot": 0, "kind": "LEAD", "section": null, "reason": "…"}
    ]}
  ],
  "articles": {
    "9001": {
      "feedTitle": "…", "title": "…", "author": null, "url": "…",
      "publishedAt": "…", "summary": "…", "imageUrl": null, "wordCount": 1240,
      "blocks": [ {"t": "p", "text": {"text": "…", "spans": []}} ],
      "engagement": {"openedAt": null, "readFraction": 0.0, "readAt": null, "savedAt": null, "likedAt": null}
    }
  }
}
```

- `pages` and `items` mirror `edition_item`, sorted by `page` then `slot`. `kind` and `section` mean the same as in the app (`ItemKind`).
- `blocks` is `article_content.blocksJson` as stored, in the `ContentBlocks` JSON format (`model/Content.kt`, discriminator `"t"`: `p`, `h`, `img`, `quote`, `list`, `pre`; inline spans `b`, `i`, `code`, `a`, `s`, `sup`, `sub`). It is `[]` when the article has no stored content; the Kobo then shows `summary`. Kobo readers must ignore block types and fields they don't know. As stored, fields at their default are left out: an empty `spans`, and a null `href`, `alt`, `caption`, `width`, `height`, `citation` or `language` are absent rather than `[]` or `null`. `docs/kobo-sync-fixtures/edition.json` has every block type.
- `engagement` is the account's current state, so the Kobo can show what is already read or saved. The Kobo ignores `likedAt`: it shows only what was Liked on the Kobo itself (`data/liked.json`), never the phone's Likes.
- The server adds fields for the phone (`cutoffAt`, `pageViews`, `feedId`, …), which the Kobo ignores.

## Saved shelf

`GET /api/v1/saved` (v1's `GET /v1/saved`):

```json
{
  "version": "<opaque>",
  "order": [9001, 8754],
  "articles": { "9001": { "…": "the same Article object as in an edition bundle" } }
}
```

Every saved article, `order` newest saved first, each in full: the server fetches a saved article's text when it is saved. An article whose text cannot be got is still listed, with `blocks` empty and its `summary`. An article unsaved on the phone, or on the Kobo, drops out of the list at the next sync.

## Pictures

The Kobo wants a picture for every `img` block URL and every `imageUrl` in its editions and saved articles: the newest edition's first, in the order a reader meets them, then the older editions', then the saved articles'. It asks for those not stored yet with `POST /api/v1/kobo/images` `{"width": 1264, "urls": [...]}`, at most 100 URLs per request, `width` being the screen's short side in pixels.

The answer is an `application/zip` of stored (or deflated) entries:

- `images.json`: `[{"url", "file", "type", "width", "height"}]`, one per picture it holds;
- `images/<sha256 of url>.jpg` or `.png`: each picture.

Each is prepared for e-ink as v1's `GET /v1/images` prepared it: the first frame of an animation, flattened onto white, converted to 8-bit greyscale, scaled down (never up) to at most `width` pixels wide, and encoded as a greyscale JPEG (`image/jpeg`), or as a greyscale PNG (`image/png`) for line art and images with few tones. A picture that can't be got or decoded is left out (SVG is not rasterised), and the Kobo shows the article without it. A picture wider than asked is scaled down on the Kobo. Pictures are installed as each zip arrives, so they appear while the sync goes on.

## Events

`POST /api/v1/sync/events` carries the Kobo's events, as v1's `POST /v1/events` did:

```json
{"events": [
  {"seq": 42, "type": "opened",        "articleId": 9001, "at": "…"},
  {"seq": 43, "type": "readFraction",  "articleId": 9001, "value": 0.6, "at": "…"},
  {"seq": 44, "type": "read",          "articleId": 9001, "at": "…"},
  {"seq": 45, "type": "liked",         "articleId": 9001, "value": true, "at": "…"},
  {"seq": 46, "type": "saved",         "articleId": 9001, "value": false, "at": "…"},
  {"seq": 47, "type": "pageView",      "editionId": 120, "page": 2, "ms": 8200, "at": "…"},
  {"seq": 48, "type": "editionOpened", "editionId": 120, "at": "…"},
  {"seq": 49, "type": "editionFinished","editionId": 120, "at": "…"}
]}
```

A request carries at most 500 events. `seq` starts at 1 per `deviceId` and only goes up. The server merges, in one database transaction, every event with `seq > ackedSeq` for that device and stores the new `ackedSeq` in the same transaction, answering `{"acked": n}`. Events at or below `ackedSeq` are skipped, so sending them again is harmless. The Kobo deletes its events up to the `acked` it is given.

An event naming an article or edition the account no longer has is skipped but still counts as merged. An unknown `type` is skipped and counts as merged too, and so does an event missing a field its type needs (or with one that cannot be read), since sending it again would not change that. These are the only writes the Kobo can make.

| Event | Merge |
|---|---|
| `opened` | `engagement.openedAt` = the earlier of the two |
| `readFraction` | `engagement.readFraction` = the larger of the two |
| `read` | `engagement.readAt` = the earlier of the two |
| `liked` / `saved` | Set `likedAt`/`savedAt` to `at` when `value` is true, clear it when false. Events apply in `seq` order |
| `pageView` | Add `ms` to `edition_page_view.msVisible` for (`editionId`, `page`), creating the row with `firstSeenAt = at` if needed. `ms` is time since the previous event for that page, never a running total |
| `editionOpened` / `editionFinished` | `edition.openedAt` / `finishedAt` = the earlier of the two |

The Kobo sends `liked` and `saved` only when the reader changes them on the Kobo. When it does, it records a `readFraction` of how far the reader got before leaving the article, and `read` when that passes the same threshold the app uses. The server owns `editionsRunUnopened` and `leadCount`, and the Kobo never sends them. A page counts as viewed by the same rule as `ui/paper/PageViews.kt`, so the Kobo only sends a `pageView` for time the page was actually on screen.

Known limit: events that arrive after the next edition is built have already counted their stories as unopened for that build.

## Installing (Kobo)

- Each edition, the saved shelf and each picture is written to a temporary file and renamed into place as it arrives, and `state.json` is written last, so an interrupted sync leaves the last good state plus whatever had already arrived.
- Unsent events, and events uploaded but not yet acknowledged, are applied on top of what the server sent, so the reader shows what was done on the Kobo at once.
- A Kobo that synced through the relay (its `state.json` has a `dbId` but no `dbProtocol` of `server/1`) wipes everything stored, unsent events included, before its first upload: the relay's IDs were the phone's, and its events would attach to the wrong articles on the server. It also deletes `data/relay/`.

## Kobo files

Everything lives in `/mnt/onboard/.adds/morningissue/`. Nothing in it has a book file extension, so Nickel never imports it into the library.

```
morningissue          the binary
launch.sh             run by NickelMenu
wifi-off.sh           turns Wi-Fi off (run by launch.sh on exit, and by the reader before sleep and after a sync on Wi-Fi it turned on)
wifi-on.sh            turns Wi-Fi on and joins a network saved in Nickel's settings (run by the reader for Turn on Wi-Fi and sync)
config.json           optional overrides (the server address as "server", …)
fonts/                bundled fonts and their licences
data/state.json       deviceId, dbId and the protocol it came with (dbProtocol), next seq,
                      per-edition versions, last sync result, clock offset from the server's
                      last Date header, the device secret S (secret, made when the pairing
                      code is first shown, erased only by Reset; relaySecret is still read),
                      the server's acked for this Kobo (acked; relayAcked is still read),
                      whether the server has accepted this Kobo (serverSeen) and whether it
                      last answered 401 unknown after that (serverNotListed: removed)
data/settings.json    what Settings and Aa change (page-turn buttons, orientation, text size,
                      light) and the typed server address (server)
data/editions/<id>.json
data/saved.json       the last saved shelf (unsent `saved` events are applied on top when it
                      is loaded: a Kobo Save lists the article at once, an unsave drops it)
data/liked.json       article IDs Liked on this Kobo, oldest first, at most 2000; erased
                      with the rest when the dbId changes
data/images/<sha256 of url>.img
data/events.jsonl     events not yet acknowledged, one JSON object per line, appended and
                      fsynced; uploaded ones are deleted once an acked covers them
data/incoming/        a picture zip while it downloads; emptied once it is installed
data/launch.log       what launch.sh and the binary print, for diagnosing a device
data/panic.txt        the last panic's thread, place and message, quoted by launch.sh after a crash
```

Versions that synced over the LAN kept a pairing token, the phone's address and name in `state.json`, and a typed phone and relay address in `settings.json`; versions that synced through the relay kept the last package (`relayPackage`, `relayFetched`) and the relay's view of the Kobo (`relaySeen`, `relayNotListed`) in `state.json`, a `relay` address in `config.json`, and packages in `data/relay/`. They are ignored, and dropped at the next write (`data/relay/` at the first sync).

NickelMenu config: `/mnt/onboard/.adds/nm/morningissue`.

## Launch flow (Kobo)

NickelMenu's main menu has an entry **Morning Issue**, which:

1. Runs `morningissue check` with `cmd_output`. It fails on a Kobo the reader cannot draw on (in v1, the Allwinner-based Elipsa and Sage), and the entry stops there with a toast, leaving Nickel running.
2. Runs `launch.sh` with `cmd_spawn`, which stops Nickel at once and starts `morningissue read`. The reader's home screen appears immediately with what is stored.
3. The reader uses Nickel's Wi-Fi connection only: if Wi-Fi was connected when the entry was tapped, the launcher leaves the Wi-Fi driver, `wpa_supplicant` and the DHCP client running when it stops Nickel, and the reader syncs with the server in the background (on start, and from **Sync now** in Settings). If there is no connection it shows "Offline" and does not try on its own.
   - **Turn on Wi-Fi and sync** in Settings runs `wifi-on.sh` (chip power, driver, `wpa_supplicant` on Nickel's configuration, DHCP) in the background, syncs once there is a default route, and runs `wifi-off.sh` when the sync ends. A sync that got through to the server ends it, even with nothing new.
   - If the server can't be reached (or the connection broke), or it doesn't know this Kobo yet because the phone hasn't registered the code, Wi-Fi stays on and the sync is tried again every 5 s until one gets through; **Turn Wi-Fi off** ends it.
   - Any other failure (this Kobo was removed from the account, an unexpected answer, a certificate the clock can't check, storage) turns Wi-Fi off. If no network is joined, Wi-Fi is turned off again and the reader says so.
   - A Kobo with no server address opens the address keyboard, and one that has never shown its pairing code opens the pairing code, instead of syncing; Wi-Fi is left as it is.
4. The power button, closing the sleep cover, or the idle timeout put the Kobo to sleep from inside the reader (a blank "Sleeping…" screen, Wi-Fi and the light off, then suspend to RAM); the power button or opening the cover wakes it on the same page. Sleep ends a Wi-Fi session the reader started (a running `wifi-on.sh` is stopped first). Wi-Fi stays off after waking, and the reader says so ("Offline since sleep") until Turn on Wi-Fi and sync or the next launch. Only **Exit** leaves the reader.
5. When the reader exits, or crashes, the launcher tears Wi-Fi down to the state Nickel expects after a boot (`wifi-off.sh`, shared with the reader's sleep), and restarts Nickel the same way KOReader's launcher does, with Nickel's output on `/dev/null`. A crash is logged with its exit code or signal and the reader's panic message; the reader is not restarted. The launcher's lock (`/tmp/morningissue.lock`, holding its PID) is always released, and a lock whose launcher is gone is taken over, so the menu entry always works again.

Events recorded while reading are sent at the next sync, which can be in the same session.
