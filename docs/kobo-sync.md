# Kobo sync (protocol v1)

The phone is the only device that owns the database. A Kobo pulls the last three released editions from the phone over the local network, shows them in its own reader, and sends back what the reader did as a list of events. The Kobo never changes anything else.

There is no internet service. Sync happens only while the phone's **Sync with Kobo** screen is open and both devices are on the same Wi-Fi.

## Pieces

| Side | What | Where |
|---|---|---|
| Phone | HTTP server, UDP beacon, pairing, bundle export, event merge, Sync screen | `app/` (`net.tgnra.morningissue.kobo`) |
| Kobo | One static binary, `morningissue`, with `sync` and `read` subcommands, launched from NickelMenu | `kobo/` |

## Discovery

While the Sync screen is open the phone sends a UDP broadcast to `255.255.255.255:47823` once a second:

```json
{"app": "morningissue", "v": 1, "port": 47824, "name": "Pixel 9", "dbId": "3f0c…"}
```

`morningissue sync` listens for up to 10 seconds and uses the first beacon whose `app` is `morningissue` and `v` is 1. If none arrives it tries the last address that worked, which is kept in its state file, and then an address set by hand in `config.json` (`"phone": "192.168.1.20:47824"`), for networks that drop broadcasts.

## Transport

Plain HTTP/1.1 on the phone's port (default 47824), JSON bodies in UTF-8. Every request except `POST /v1/pair` carries `Authorization: Bearer <token>`. A missing or unknown token gets `401`, which tells the Kobo to pair again. The token is the only secret. Traffic is not encrypted, which is acceptable for a LAN-only v1.

Times are ISO-8601 instants in UTC (`2026-11-11T06:30:00Z`). IDs are the phone's Room row IDs (`Long`).

### `POST /v1/pair`

```json
{"deviceId": "b7e2…", "name": "Kobo Libra 2"}
```

`deviceId` is a random UUID the Kobo creates once and keeps. The phone shows "Allow Kobo Libra 2 to sync?" on the Sync screen and holds the request open for up to 60 seconds:

- Allow → `200 {"token": "<32 random bytes, base64url>", "dbId": "…"}`
- Deny → `403`
- No answer in time → `408`

The phone stores a hash of the token, never the token itself. Pairing the same `deviceId` again replaces the old token. The Sync screen lists paired devices and can forget one.

### `GET /v1/manifest`

```json
{
  "v": 1,
  "dbId": "3f0c…",
  "ackedSeq": 41,
  "editions": [
    {"id": 120, "date": "2026-11-11", "kind": "MORNING", "number": 120, "releaseAt": "…", "version": "<opaque>"}
  ]
}
```

`editions` lists the three most recent editions whose `releaseAt` has passed, newest first. `version` changes whenever the bundle's content would change; a hash of the bundle bytes is fine. `ackedSeq` is the highest event `seq` from this device that the phone has merged, or 0.

`dbId` is a random ID the phone creates once per install. If it differs from the one the Kobo saved, the Kobo drops all its editions, unsent events and images, then starts over. A reinstalled phone reuses Room IDs, so stale events would otherwise attach to the wrong articles.

### `GET /v1/editions/{id}`

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
- `blocks` is `article_content.blocksJson` as stored, in the `ContentBlocks` JSON format (`model/Content.kt`, discriminator `"t"`: `p`, `h`, `img`, `quote`, `list`, `pre`; inline spans `b`, `i`, `code`, `a`, `s`, `sup`, `sub`). It is `[]` when the article has no stored content; the Kobo then shows `summary`. Kobo readers must ignore block types and fields they don't know.
- `engagement` is the phone's current state, so the Kobo can show what is already read, liked or saved.

### `GET /v1/images?url=<urlencoded>`

This returns the bytes from the phone's image store for that source URL, with the stored `Content-Type` (usually `image/webp`, or JPEG, PNG or GIF). It returns `404` if the image isn't stored. The Kobo fetches every `img` block URL and every `imageUrl` in a bundle during sync, and skips the ones that 404. The phone never fetches anything from the internet for the Kobo.

### `POST /v1/events`

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

Response: `200 {"ackedSeq": 49}`.

`seq` starts at 1 per `deviceId` and only goes up. The phone merges, in one database transaction, every event with `seq > ackedSeq` for that device and stores the new `ackedSeq` in the same transaction. Events at or below `ackedSeq` are skipped, so a retry after a dropped response is harmless. The Kobo deletes events up to the returned `ackedSeq`.

An event naming an article or edition the phone no longer has is skipped but still counts as merged. An unknown `type` is skipped and counts as merged too. These are the only writes the protocol allows.

| Event | Merge on the phone |
|---|---|
| `opened` | `engagement.openedAt` = the earlier of the two |
| `readFraction` | `engagement.readFraction` = the larger of the two |
| `read` | `engagement.readAt` = the earlier of the two |
| `liked` / `saved` | Set `likedAt`/`savedAt` to `at` when `value` is true, clear it when false. Events apply in `seq` order |
| `pageView` | Add `ms` to `edition_page_view.msVisible` for (`editionId`, `page`), creating the row with `firstSeenAt = at` if needed. `ms` is time since the previous event for that page, never a running total |
| `editionOpened` / `editionFinished` | `edition.openedAt` / `finishedAt` = the earlier of the two |

The Kobo sends `liked` and `saved` only when the reader changes them on the Kobo. When it does, it records a `readFraction` of how far the reader got before leaving the article, and `read` when that passes the same threshold the app uses. The phone owns `editionsRunUnopened` and `leadCount`, and the Kobo never sends them. A page counts as viewed by the same rule as `ui/paper/PageViews.kt`, so the Kobo only sends a `pageView` for time the page was actually on screen.

The `dbId` check comes before the events are sent because a reinstalled phone reuses Room IDs: events sent first would be merged into whatever articles now have those IDs.

Known v1 limit: events that arrive after the next edition is built have already counted their stories as unopened for that build.

## Order of a sync (Kobo)

1. Find the phone (beacon, then the last address, then the configured one).
2. Pair if there is no token, or on `401`.
3. `GET /v1/manifest` and compare its `dbId` (or the one a pairing in step 2 returned) with the saved one. On a change, wipe and start over. Then `POST /v1/events` with everything unsent, so the phone's state is current before export. Events at or below the manifest's `ackedSeq` are already merged and are deleted without being sent.
4. If any events were sent, `GET /v1/manifest` again, since merged events change bundle versions.
5. Fetch each edition whose `version` differs from the stored one, then its images.
6. Delete local editions that aren't in the manifest, and images nothing refers to.
7. Write everything to temporary files and rename them into place, so an interrupted sync leaves the last good state.

## Kobo files

Everything lives in `/mnt/onboard/.adds/morningissue/`. Nothing in it has a book file extension, so Nickel never imports it into the library.

```
morningissue          the binary
launch.sh             run by NickelMenu
config.json           optional overrides (phone address, …)
fonts/                bundled fonts and their licences
data/state.json       deviceId, token, dbId, last address, next seq, per-edition versions,
                      last sync result, clock offset from the phone's Date header
data/editions/<id>.json
data/images/<sha256 of url>.img
data/events.jsonl     unsent events, one JSON object per line, appended and fsynced
```

NickelMenu config: `/mnt/onboard/.adds/nm/morningissue`.

## Launch flow (Kobo)

NickelMenu's main menu has an entry **Morning Issue**, which:

1. Turns Wi-Fi on through Nickel (`nickel_wifi:autoconnect`), since Wi-Fi is easiest while Nickel is still running.
2. Runs `launch.sh` with `cmd_spawn`. The script waits up to 20 seconds for an IP address, then runs `morningissue sync`. A failed sync is not fatal: the reader shows the stored editions and "Last synced …".
3. Stops Nickel and runs `morningissue read`, which draws on the framebuffer and reads touch and page-turn buttons itself.
4. When the reader exits, restarts Nickel the same way KOReader's launcher does.

Events recorded while reading are sent on the next launch.
