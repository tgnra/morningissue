# Kobo sync (protocol v1)

The phone is the only device that owns the database. A Kobo pulls the last three released editions from the phone over the local network, shows them in its own reader, and sends back what the reader did as a list of events. The Kobo never changes anything else.

There is no internet service. Sync happens only while the phone's **Sync with Kobo** screen is open and both devices are on the same Wi-Fi.

## Pieces

| Side | What | Where |
|---|---|---|
| Phone | HTTP server, UDP beacon, pairing, bundle export, event merge, Sync screen | `app/` (`net.tgnra.morningissue.kobo`) |
| Kobo | One static binary, `morningissue`, with `sync` and `read` subcommands, launched from NickelMenu | `kobo/` |

## Discovery

While the Sync screen is open the phone sends a UDP broadcast to `255.255.255.255:47823` once a second, and the same datagram to its Wi-Fi subnet's broadcast address (for example `192.168.1.255:47823`), since some routers pass only one of the two:

```json
{"app": "morningissue", "v": 1, "port": 47824, "name": "Pixel 9", "dbId": "3f0c…"}
```

`morningissue sync` listens for up to 10 seconds and uses the first beacon whose `app` is `morningissue` and `v` is 1. If none arrives it tries the last address that worked, which is kept in its state file, and then an address set by hand in `config.json` (`"phone": "192.168.1.20:47824"`), for networks that drop broadcasts.

## Transport

Plain HTTP/1.1 on the phone's port (default 47824), JSON bodies in UTF-8. Every request except `POST /v1/pair` carries `Authorization: Bearer <token>`. A missing or unknown token gets `401`, which tells the Kobo to pair again. The token is the only secret. Traffic is not encrypted, which is acceptable for a LAN-only v1.

The phone serves one request per connection and answers with `Connection: close`. A request body must have a `Content-Length` (chunked bodies get `411`) of at most 1 MiB (larger gets `413`).

Times are ISO-8601 instants in UTC (`2026-11-11T06:30:00Z`). The phone writes whole seconds; it reads fractions of a second and offsets too. IDs are the phone's Room row IDs (`Long`).

### `POST /v1/pair`

```json
{"deviceId": "b7e2…", "name": "Kobo Libra 2"}
```

`deviceId` is a random UUID the Kobo creates once and keeps. The phone shows "Allow Kobo Libra 2 to sync?" on the Sync screen and holds the request open for up to 60 seconds:

- Allow → `200 {"token": "<32 random bytes, base64url>", "dbId": "…"}`
- Deny → `403`
- No answer in time → `408`
- Another pairing request is already waiting → `409`

The phone stores a hash of the token, never the token itself. Pairing the same `deviceId` again replaces the old token and keeps that device's `ackedSeq`. The Sync screen lists paired devices and can forget one.

### `GET /v1/manifest`

```json
{
  "v": 1,
  "dbId": "3f0c…",
  "ackedSeq": 41,
  "editions": [
    {"id": 120, "date": "2026-11-11", "kind": "MORNING", "number": 120, "releaseAt": "…", "version": "<opaque>"}
  ],
  "saved": {"count": 14, "version": "<opaque>"}
}
```

`saved.version` changes whenever `GET /v1/saved` would return something different. A Kobo built before `saved` existed ignores it, and a phone that leaves it out has no saved shelf to offer.

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

- An edition that does not exist, or is not released yet, gets `404`.
- `pages` and `items` mirror `edition_item`, sorted by `page` then `slot`. `kind` and `section` mean the same as in the app (`ItemKind`).
- `blocks` is `article_content.blocksJson` as stored, in the `ContentBlocks` JSON format (`model/Content.kt`, discriminator `"t"`: `p`, `h`, `img`, `quote`, `list`, `pre`; inline spans `b`, `i`, `code`, `a`, `s`, `sup`, `sub`). It is `[]` when the article has no stored content; the Kobo then shows `summary`. Kobo readers must ignore block types and fields they don't know. As stored, fields at their default are left out: an empty `spans`, and a null `href`, `alt`, `caption`, `width`, `height`, `citation` or `language` are absent rather than `[]` or `null`. `docs/kobo-sync-fixtures/edition.json` has every block type.
- `engagement` is the phone's current state, so the Kobo can show what is already read, liked or saved.

### `GET /v1/saved`

```json
{
  "version": "<opaque>",
  "order": [9001, 8754],
  "articles": { "9001": { "…": "the same Article object as in an edition bundle" } }
}
```

Every article saved on the phone, `order` newest saved first, each in full: the phone makes sure a saved article's text is stored (running the same full-text fetch the reader uses when it isn't) before it counts as ready. An article whose text cannot be got is still listed, with `blocks` empty and its `summary`. An article unsaved on the phone, or on the Kobo, drops out of the list at the next sync.

### `GET /v1/images?url=<urlencoded>&w=<pixels>`

The image for that source URL, prepared for e-ink: decoded on the phone, the first frame of an animation, flattened onto white, converted to 8-bit greyscale, scaled down (never up) to at most `w` pixels wide, and encoded as a greyscale JPEG (`image/jpeg`), or as a greyscale PNG (`image/png`) for line art and images with few tones. The Kobo sends `w` as its screen's short side in pixels; without `w` the phone uses 1264.

The phone takes the source from its image store, and fetches it from the internet when the store doesn't have it, with the same client, limits and timeouts as the reader's image loading. Prepared images are cached on the phone (in the app's cache, evictable, keyed by URL and `w`), so a second Kobo or a retried sync costs nothing. `404` means the image can't be got or decoded; SVG is not rasterised in v1 and gets `404`. The phone holds `w` to 160..2048, and an image it has to download can take up to 30 seconds to answer.

The Kobo fetches every `img` block URL and every `imageUrl` in its editions and saved articles, up to 3 requests at a time, and skips the ones that 404. It shows the article text first and fills in images as they arrive.

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

A request carries at most 500 events; more gets `413` and nothing is merged. The Kobo sends its unsent events in batches of up to 500, oldest first, until all are acknowledged.

`seq` starts at 1 per `deviceId` and only goes up. The phone merges, in one database transaction, every event with `seq > ackedSeq` for that device and stores the new `ackedSeq` in the same transaction. Events at or below `ackedSeq` are skipped, so a retry after a dropped response is harmless. The Kobo deletes events up to the returned `ackedSeq`.

An event naming an article or edition the phone no longer has is skipped but still counts as merged. An unknown `type` is skipped and counts as merged too, and so does an event missing a field its type needs (or with one that cannot be read), since sending it again would not change that. These are the only writes the protocol allows.

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
3. `GET /v1/manifest` and compare its `dbId` (or the one a pairing in step 2 returned) with the saved one. On a change, wipe and start over. Then `POST /v1/events` with everything unsent, in batches of up to 500, oldest first, so the phone's state is current before export. Events at or below the manifest's `ackedSeq` are already merged and are deleted without being sent.
4. If any events were sent, `GET /v1/manifest` again, since merged events change bundle versions.
5. Fetch each edition whose `version` differs from the stored one, and `GET /v1/saved` if `saved.version` differs. Save each as it arrives, so the reader can show it straight away, then fetch the images.
6. Delete local editions that aren't in the manifest, saved articles that aren't in the list, and images nothing refers to.
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
data/saved.json       the last GET /v1/saved
data/images/<sha256 of url>.img
data/events.jsonl     unsent events, one JSON object per line, appended and fsynced
data/launch.log       what launch.sh and the binary print, for diagnosing a device
```

NickelMenu config: `/mnt/onboard/.adds/nm/morningissue`.

## Launch flow (Kobo)

NickelMenu's main menu has an entry **Morning Issue**, which:

1. Runs `morningissue check` with `cmd_output`. It fails on a Kobo the reader cannot draw on (in v1, the Allwinner-based Elipsa and Sage), and the entry stops there with a toast, leaving Nickel running.
2. Runs `launch.sh` with `cmd_spawn`, which stops Nickel at once and starts `morningissue read`. The reader's home screen appears immediately with what is stored.
3. The reader uses Nickel's Wi-Fi connection only: if Wi-Fi was connected when the entry was tapped, the launcher leaves the Wi-Fi driver, `wpa_supplicant` and the DHCP client running when it stops Nickel, and the reader syncs in the background (on start, and from **Sync now** in Settings). If there is no connection it shows "Offline" and never tries; it never turns Wi-Fi on itself.
4. When the reader exits, the launcher tears Wi-Fi down to the state Nickel expects after a boot, and restarts Nickel the same way KOReader's launcher does.

Events recorded while reading are sent at the next sync, which can be in the same session.
