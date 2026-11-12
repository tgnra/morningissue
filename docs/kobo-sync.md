# Kobo sync: formats, files and launch

The phone is the only device that owns the database. A Kobo takes the last three released editions, the saved shelf and their pictures from the phone, shows them in its own reader, and sends back what the reader did as a list of events. The Kobo never changes anything else.

Everything travels through a relay, end-to-end encrypted ([`docs/relay-sync.md`](relay-sync.md), protocol relay/1). This document holds what relay/1 carries and what the Kobo does with it: the formats of the manifest, edition bundles, the saved shelf, pictures and events (first defined by protocol v1, a LAN protocol the Kobo no longer speaks, and kept unchanged), how the Kobo installs them, the files on the Kobo and its launch flow.

## Pieces

| Side | What | Where |
|---|---|---|
| Phone | Bundle export, picture preparation, event merge, relay publish and Done | `app/` (`net.tgnra.morningissue.kobo`, `net.tgnra.morningissue.relay`) |
| Relay | A mailbox for encrypted packages and changes | `relay/` |
| Kobo | One static binary, `morningissue`, with `sync` and `read` subcommands, launched from NickelMenu | `kobo/` |

## Conventions

JSON is UTF-8. Times are ISO-8601 instants in UTC (`2026-11-11T06:30:00Z`). The phone writes whole seconds; it reads fractions of a second and offsets too. IDs are the phone's Room row IDs (`Long`). Readers ignore fields they don't know, so a newer phone does not break an older Kobo.

## Manifest

A package's `manifest.json` (v1's `GET /v1/manifest`), with the fields relay/1 adds (`docs/relay-sync.md`, "Package contents"):

```json
{
  "v": 1,
  "protocol": "relay/1",
  "builtAt": "2026-11-12T06:00:00Z",
  "dbId": "3f0c…",
  "editions": [
    {"id": 120, "date": "2026-11-11", "kind": "MORNING", "number": 120, "releaseAt": "…", "version": "<opaque>"}
  ],
  "saved": {"count": 14, "version": "<opaque>"},
  "acked": {"b7e2…": 41}
}
```

`editions` lists the three most recent editions whose `releaseAt` has passed, newest first. `version` changes whenever the bundle's content would change; a hash of the bundle bytes is fine.

`saved.version` changes whenever the saved shelf would be different. A phone that leaves `saved` out has no saved shelf to offer, and the Kobo then drops its own.

`acked` holds, for each paired device by `deviceId`, the highest event `seq` from it that the phone has merged, or 0. (v1's manifest had this device's alone, as `ackedSeq`.)

`dbId` is a random ID the phone creates once per install. If it differs from the one the Kobo saved, the Kobo drops all its editions, unsent events, Likes and images, then starts over. A reinstalled phone reuses Room IDs, so stale events would otherwise attach to the wrong articles.

## Edition bundle

A package's `editions/<id>.json` (v1's `GET /v1/editions/{id}`):

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
- `engagement` is the phone's current state, so the Kobo can show what is already read or saved. The Kobo ignores `likedAt`: it shows only what was Liked on the Kobo itself (`data/liked.json`), never the phone's Likes.

## Saved shelf

A package's `saved.json` (v1's `GET /v1/saved`):

```json
{
  "version": "<opaque>",
  "order": [9001, 8754],
  "articles": { "9001": { "…": "the same Article object as in an edition bundle" } }
}
```

Every article saved on the phone, `order` newest saved first, each in full: the phone makes sure a saved article's text is stored (running the same full-text fetch the reader uses when it isn't) before it counts as ready. An article whose text cannot be got is still listed, with `blocks` empty and its `summary`. An article unsaved on the phone, or on the Kobo, drops out of the list at the next sync.

## Pictures

A package carries a picture for every `img` block URL and every `imageUrl` in its editions and saved articles, listed in `images.json` (`docs/relay-sync.md`). Each is prepared for e-ink as v1's `GET /v1/images` prepared it: decoded on the phone, the first frame of an animation, flattened onto white, converted to 8-bit greyscale, scaled down (never up) to at most `w` pixels wide, and encoded as a greyscale JPEG (`image/jpeg`), or as a greyscale PNG (`image/png`) for line art and images with few tones. `w` is each paired device's screen short side in pixels, sent in its pairing code; the phone holds it to 160..2048.

The phone takes the source from its image store, and fetches it from the internet when the store doesn't have it, with the same client, limits and timeouts as the reader's image loading. A picture that can't be got or decoded is left out (SVG is not rasterised), and the Kobo shows the article without it.

## Events

A changes batch (`docs/relay-sync.md`, "Changes contents") carries the Kobo's events, as v1's `POST /v1/events` did:

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

A batch carries at most 500 events. `seq` starts at 1 per `deviceId` and only goes up. The phone merges, in one database transaction, every event with `seq > ackedSeq` for that device and stores the new `ackedSeq` in the same transaction. Events at or below `ackedSeq` are skipped, so sending them again is harmless. The Kobo deletes its events up to the `acked` value a package gives it.

An event naming an article or edition the phone no longer has is skipped but still counts as merged. An unknown `type` is skipped and counts as merged too, and so does an event missing a field its type needs (or with one that cannot be read), since sending it again would not change that. These are the only writes the Kobo can make.

| Event | Merge on the phone |
|---|---|
| `opened` | `engagement.openedAt` = the earlier of the two |
| `readFraction` | `engagement.readFraction` = the larger of the two |
| `read` | `engagement.readAt` = the earlier of the two |
| `liked` / `saved` | Set `likedAt`/`savedAt` to `at` when `value` is true, clear it when false. Events apply in `seq` order |
| `pageView` | Add `ms` to `edition_page_view.msVisible` for (`editionId`, `page`), creating the row with `firstSeenAt = at` if needed. `ms` is time since the previous event for that page, never a running total |
| `editionOpened` / `editionFinished` | `edition.openedAt` / `finishedAt` = the earlier of the two |

The Kobo sends `liked` and `saved` only when the reader changes them on the Kobo. When it does, it records a `readFraction` of how far the reader got before leaving the article, and `read` when that passes the same threshold the app uses. The phone owns `editionsRunUnopened` and `leadCount`, and the Kobo never sends them. A page counts as viewed by the same rule as `ui/paper/PageViews.kt`, so the Kobo only sends a `pageView` for time the page was actually on screen.

Known limit: events that arrive after the next edition is built have already counted their stories as unopened for that build.

## Installing a package (Kobo)

After the relay steps of `docs/relay-sync.md` ("Device: sync") have downloaded and decrypted a package:

1. Read `manifest.json` and compare its `dbId` with the saved one. On a change, wipe and start over.
2. Take each edition whose `version` differs from the stored one, and `saved.json` if `saved.version` differs. Save each as it is taken out, so the reader can show it straight away.
3. Take the pictures not stored yet at this Kobo's width (else the nearest wider one, scaled down): the newest edition's first, in the order a reader meets them, then the saved articles'.
4. Delete local editions that aren't in the manifest, saved articles that aren't in the list, and images nothing refers to.
5. Write everything to temporary files and rename them into place, so an interrupted sync leaves the last good state.

Unsent events, and events uploaded but not yet acknowledged, are applied on top of what the package brought, so the reader shows what was done on the Kobo at once.

## Kobo files

Everything lives in `/mnt/onboard/.adds/morningissue/`. Nothing in it has a book file extension, so Nickel never imports it into the library.

```
morningissue          the binary
launch.sh             run by NickelMenu
wifi-off.sh           turns Wi-Fi off (run by launch.sh on exit, and by the reader before sleep and after a sync on Wi-Fi it turned on)
wifi-on.sh            turns Wi-Fi on and joins a network saved in Nickel's settings (run by the reader for Turn on Wi-Fi and sync)
config.json           optional overrides (the relay address as "relay", …)
fonts/                bundled fonts and their licences
data/state.json       deviceId, dbId, next seq, per-edition versions, last sync result,
                      clock offset from the relay's last Date header, and for relay sync
                      (docs/relay-sync.md): the device secret S (relaySecret, made when the
                      pairing code is first shown, erased only by Reset), the highest seq
                      uploaded (relayUploadedSeq) and since when uploads have waited for an
                      ack (relayUnackedSince), the last package installed (relayPackage,
                      relayFetched) and its acked[deviceId] (relayAcked), whether the relay
                      has accepted this Kobo (relaySeen) or last answered 401 unknown
                      (relayUnpaired)
data/settings.json    what Settings and Aa change (page-turn buttons, orientation, text size,
                      light)
data/editions/<id>.json
data/saved.json       the last saved shelf (unsent `saved` events are applied on top when it
                      is loaded: a Kobo Save lists the article at once, an unsave drops it)
data/liked.json       article IDs Liked on this Kobo, oldest first, at most 2000; erased
                      with the rest when the phone's dbId changes
data/images/<sha256 of url>.img
data/events.jsonl     events not yet acknowledged, one JSON object per line, appended and
                      fsynced; uploaded ones stay here (and are applied on top) until a
                      package's acked covers them
data/relay/           a relay package while it downloads (<id>.mir, resumed with Range) and
                      is decrypted (<id>.zip); emptied once it is installed
data/launch.log       what launch.sh and the binary print, for diagnosing a device
data/panic.txt        the last panic's thread, place and message, quoted by launch.sh after a crash
```

Versions that synced over the LAN also kept a pairing token, the phone's address and name in `state.json`, and a typed phone and relay address in `settings.json`. They are ignored, and dropped at the next write.

NickelMenu config: `/mnt/onboard/.adds/nm/morningissue`.

## Launch flow (Kobo)

NickelMenu's main menu has an entry **Morning Issue**, which:

1. Runs `morningissue check` with `cmd_output`. It fails on a Kobo the reader cannot draw on (in v1, the Allwinner-based Elipsa and Sage), and the entry stops there with a toast, leaving Nickel running.
2. Runs `launch.sh` with `cmd_spawn`, which stops Nickel at once and starts `morningissue read`. The reader's home screen appears immediately with what is stored.
3. The reader uses Nickel's Wi-Fi connection only: if Wi-Fi was connected when the entry was tapped, the launcher leaves the Wi-Fi driver, `wpa_supplicant` and the DHCP client running when it stops Nickel, and the reader syncs through the relay in the background (on start, and from **Sync now** in Settings). If there is no connection it shows "Offline" and does not try on its own. **Turn on Wi-Fi and sync** in Settings runs `wifi-on.sh` (chip power, driver, `wpa_supplicant` on Nickel's configuration, DHCP) in the background, syncs once there is a default route, and runs `wifi-off.sh` when the sync ends. A sync that got through the relay ends it, even with nothing new. If the relay can't be reached (or the connection broke), or it is still waiting for the phone to list this Kobo, Wi-Fi stays on and the sync is tried again every 5 s until one gets through; **Turn Wi-Fi off** ends it. Any other failure (the phone dropped this Kobo, an unexpected answer, a certificate the clock can't check, a package that fails its check, storage) turns Wi-Fi off. If no network is joined, Wi-Fi is turned off again and the reader says so. A Kobo that has never shown its pairing code has nothing to sync with: Turn on Wi-Fi and sync (and Sync now) open the pairing code instead, and Wi-Fi is left as it is.
4. The power button, closing the sleep cover, or the idle timeout put the Kobo to sleep from inside the reader (a blank "Sleeping…" screen, Wi-Fi and the light off, then suspend to RAM); the power button or opening the cover wakes it on the same page. Sleep ends a Wi-Fi session the reader started (a running `wifi-on.sh` is stopped first). Wi-Fi stays off after waking, and the reader says so ("Offline since sleep") until Turn on Wi-Fi and sync or the next launch. Only **Exit** leaves the reader.
5. When the reader exits, or crashes, the launcher tears Wi-Fi down to the state Nickel expects after a boot (`wifi-off.sh`, shared with the reader's sleep), and restarts Nickel the same way KOReader's launcher does, with Nickel's output on `/dev/null`. A crash is logged with its exit code or signal and the reader's panic message; the reader is not restarted. The launcher's lock (`/tmp/morningissue.lock`, holding its PID) is always released, and a lock whose launcher is gone is taken over, so the menu entry always works again.

Events recorded while reading are sent at the next sync, which can be in the same session.
