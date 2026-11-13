# Morning Issue for Kobo

A native e-ink reader for Kobo e-readers. You open it from NickelMenu. It shows editions
and saved articles on the bare framebuffer while Nickel (Kobo's own reader) is stopped, and
syncs in the background over the Kobo's Wi-Fi, straight with your self-hosted Morning Issue
server ([`docs/server-design.md`](../docs/server-design.md)). The phone is needed only once,
to pair the Kobo. The sync, the formats it carries, the files on the device and the launch
flow are specified in [`docs/kobo-sync.md`](../docs/kobo-sync.md).

It is one static binary, `morningissue`, built for `armv7-unknown-linux-musleabihf`. It has
five subcommands:

- `read` runs the reader (and its background sync).
- `sync` syncs with the server once, from a shell.
- `check` names the device and fails if this version can't draw on it.
- `wifi-off` and `wifi-on` power the Wi-Fi chip down and up for `wifi-off.sh` and
  `wifi-on.sh` (ntx_io ioctl 208).

## Install

You need [NickelMenu](https://pgaskin.net/NickelMenu/) v0.6.0 or newer, which needs
firmware 4.6 or newer.

1. Connect the Kobo to a computer by USB. It appears as the `KOBOeReader` drive.
2. Unzip `morningissue-kobo-<version>.zip` at the root of that drive. This creates
   `.adds/morningissue/` and `.adds/nm/morningissue`. Folders starting with a dot may be
   hidden on your computer, but they are still created.
3. Optionally, save your server's `https://<server>/api/v1/kobo/config.json` as
   `.adds/morningissue/config.json` on the drive, so the Kobo knows the server's address
   without typing it (see Configuration).
4. Eject the drive. NickelMenu reads its configuration again once the Kobo leaves USB mode.
5. On the Kobo, connect to Wi-Fi in Nickel, then open the main menu and tap
   **Morning Issue**.
6. Pair it with the phone (below).

### Pairing

The Kobo syncs with your server directly, signed in with a token made from a secret in its
pairing code. The phone, signed in to the same server, registers the Kobo with your account
by scanning that code; after that the phone is never needed for a sync.

1. If `config.json` doesn't name the server, open **Settings** on the Kobo and tap
   **Type the server address…** under Pairing: type the address the phone uses (the
   keyboard has `https://` and `http://` keys) and tap **OK**. An address that can't be
   used is refused with the reason. Tap the address later to change it.
2. Tap **Pair with phone**. The Kobo shows a large QR code. (Turn on Wi-Fi and sync, or
   Sync now, on a Kobo that has never been paired opens it too.)
3. Scan it with Morning Issue on the phone (Kobo settings). Scanning is the consent;
   nothing is asked on the Kobo.
4. While the code is shown and the Kobo is on Wi-Fi, it asks the server every 5 seconds
   whether it knows this Kobo yet. Once the phone has registered it, the Kobo goes back to
   Settings and syncs. (Offline, go back and tap **Turn on Wi-Fi and sync** after scanning;
   until the server has accepted the Kobo once, Settings says "Waiting for the phone".)

Reset erases the pairing secret with everything else; pair again afterwards. If the Kobo is
removed from the account (on the phone), its next sync says so: pair it again.

A Kobo that synced through the old relay starts afresh at its first sync with the server:
what it stored (editions, unsent events, Likes, pictures) came from the phone's database
and is deleted, then everything comes again from the server. Pair it again too, since the
phone now registers it with the server.

### Update

Unzip the new zip over the old files in the same way. Everything you added yourself
(`.adds/morningissue/config.json`) and everything the reader stored (`data/`) is kept.
Earlier versions installed the font licences as `.txt` files, which Nickel shows as
books: delete `.adds/morningissue/fonts/*-OFL.txt`.

### Uninstall

Delete `.adds/morningissue/` and `.adds/nm/morningissue` from the drive. Nothing else on
the device is changed. The reader never installs into the system partition, and it doesn't
change the screen mode.

## Use

- **Home:** the latest edition large in the middle, earlier ones on the shelf below (beside
  it in landscape). **Exit** is at the top left, **Saved** and **Settings** at the top right.
  The line at the foot says "Syncing…", when the last sync ran, "Offline", or why the last
  sync failed. Tap a cover to open it.
- **Sync:** runs in the background as soon as the reader starts, if the Kobo is connected
  to Wi-Fi. Editions, saved articles and pictures appear as they are installed. Without a
  connection the home screen shows "Offline", and Settings has **Turn on Wi-Fi and sync**:
  it turns Wi-Fi on (joining a network saved in the Kobo's Wi-Fi settings), syncs, and turns
  Wi-Fi off again. A sync that gets through to the server turns it off even when it brings
  nothing new. If the server can't be reached, or it doesn't know this Kobo yet because the
  phone hasn't registered the code, Wi-Fi stays on and the sync is tried again every few
  seconds until it gets through; **Turn Wi-Fi off** (beside the sync button whenever Wi-Fi
  is on) ends it, and so does sleep. A Kobo that has synced before and isn't let in was
  removed from the account: it says so, and needs pairing again. Anything else turns Wi-Fi
  off. If no network is joined, Wi-Fi goes off again and the reader says so. A Kobo with no
  server address, or that has never been paired, doesn't turn Wi-Fi on: the button opens
  the address keyboard, or the pairing code.
- **Edition and article pages** fill the screen. Swipe down from the top edge (or tap the top
  strip) to show the header (back, title) and the footer (position, and in an article
  **Like**, **Save** and **Aa**). A tap on the page, or 7 seconds, hides them again. The
  end of every article has **Like** and **Save** buttons too.
- **Like** shows only what was Liked on the Kobo (kept in `data/liked.json`); Likes made
  on the phone are not shown, and there is no list of them. Each Like is still sent to the
  phone, which learns from it.
- **Turning pages:** the page buttons, a swipe left or right, or a tap: the left third of an
  article goes back and the rest forward; in an edition, the outer fifths turn and a tap on
  a story opens it.
- **Aa:** five text sizes (the page is laid out again on the same text), the front light's
  brightness, and its warmth on models with a natural light.
- **Saved:** every article saved on the phone, newest first. Articles the phone could not
  get the full text of say so and show the summary. Saving an article on the Kobo puts it at
  the top of the list at once, and unsaving takes it off at once; both are sent to the phone
  at the next sync.
- **Settings:** the Wi-Fi state, the server's address (tap it to type another, unless it
  comes from `config.json`), **Pair with phone** and where pairing stands, **Sync now** or
  **Turn on Wi-Fi and sync**, **Turn Wi-Fi off**, the last sync's result, inverting the
  page-turn buttons, orientation (portrait or landscape), and Reset. With a plain `http://`
  address, Settings warns that the Kobo's token is sent unencrypted. When it
  doesn't all fit on the screen, Settings has pages, turned like Saved's. On a model with a
  mapped orientation sensor, the sensor turns the page upside down within the chosen
  orientation to match how you hold it, once the reading has held for 1.5 s, with a full
  refresh. Until you choose, the reader keeps the orientation the screen was in when it
  opened (this is also what 0.2.3's "follow the sensor" becomes).
  Settings, the text size, the light levels and a typed server address are kept in
  `data/settings.json`.
- **Sleep:** the power button, closing the sleep cover, or 20 minutes without input put the
  Kobo to sleep. The screen goes blank with "Sleeping…" small in the lower right corner, the
  light and Wi-Fi go off, and the Kobo suspends. The power button or opening the cover wakes
  it on the page it was showing; touches and page keys made while it sleeps or wakes are
  ignored. Wi-Fi stays off after waking, so the home screen and Settings say "Offline since
  sleep"; **Turn on Wi-Fi and sync** in Settings syncs again.
- **Leaving:** only **Exit**. The light goes back to Nickel's levels, Wi-Fi is turned off
  the way a boot leaves it, and Nickel restarts. If the reader crashes, the launcher logs
  why and brings Nickel back the same way; tap the menu entry again to reopen it.

Every action can be done by touch alone. Page buttons and the orientation sensor are used
when the model has them.

What you read is recorded in `data/events.jsonl` and sent on the next sync.

### Server sync

Every sync first sends the events the server hasn't acknowledged, 500 at a time, and deletes
them from the Kobo once it has. The server won't hand out editions, the saved shelf or
pictures while it is missing events from this Kobo, so what you did on the Kobo always
counts before anything comes back. Then the sync takes the editions and saved shelf that
changed and the pictures not stored yet, at this screen's width.

`https://` servers are checked against the Mozilla root certificates. Since a Kobo's clock
can be far off, the time used for that is the clock corrected by the last `Date` the server
sent, and never earlier than the date the binary was built. If a certificate still looks out
of date, the reader says the Kobo's clock is wrong: connect to Wi-Fi in Nickel to set it.
A plain `http://` address works too, but then the Kobo's token, and everything you read,
crosses the network unencrypted; use it only on a network you trust.

## Configuration

You can create `.adds/morningissue/config.json` yourself. Every field is optional:

```json
{
  "server": "https://news.example.org",
  "name": "Kobo in the kitchen",
  "flashEvery": 6,
  "idleMinutes": 20,
  "touch": { "device": "/dev/input/event1", "swapXy": true, "mirrorX": false, "mirrorY": false },
  "display": { "quarterTurns": 0, "waveformFull": 2, "waveformPartial": 257, "waveformFast": 1 }
}
```

- `server` is your Morning Issue server's address, `http[s]://host[:port][/prefix]`, the
  one the phone uses. Without `http://` or `https://`, an IPv4 address gets `http://` and a
  name `https://`. There is no default; without it, type the address in Settings. When set
  here it wins over a typed one. The server serves this file, with its address filled in,
  at `/api/v1/kobo/config.json`.
- `name` is the name the phone shows for this Kobo once it has scanned the pairing code.
- `touch` and `display` are escape hatches for a model whose profile turns out to be wrong.
- `flashEvery` sets how many partial page turns pass before a full refresh.
- Problems are logged to `.adds/morningissue/data/launch.log`.
- Fields earlier versions used for LAN sync (`phone`, `beaconSeconds`, `beaconPort`) and
  relay sync (`relay`) are ignored.

## Supported devices

The model is read from `/mnt/onboard/.kobo/version` (the last three characters are the
product ID), then from Nickel's `MODEL_NUMBER`. An unknown ID gets a generic profile:

- the panel driver is probed;
- the density is guessed from the resolution;
- touch uses the most common transform;
- the sensor is ignored.

The screen size and stride are always read from the framebuffer at run time. The layout is
in points, scaled by the screen's density.

**Only the Libra 2 has been checked on a device.** Every other profile comes from published
sources and is unverified.

| Family | Models (product ID) | How it draws | Confidence |
|---|---|---|---|
| i.MX "Mark 7+" mxcfb | Libra 2 (388) | `MXCFB_SEND_UPDATE_V2`, 32 bpp | **verified** |
| i.MX "Mark 7+" mxcfb | Clara HD (376), Forma (377, 380), Libra H2O (384), Nia (382), Clara 2E (386), H2O² r2 (378), Aura² r2 (379) | `MXCFB_SEND_UPDATE_V2` | high (same driver as the Libra 2), unverified |
| i.MX older mxcfb | Touch (310, 320), Mini (340), Glo (330), Aura HD (350), Aura (360), H2O (370), Glo HD (371), Touch 2.0 (372), Aura One (373, 381), H2O² (374), Aura² (375) | Kobo's `SEND_UPDATE` (68-byte struct) | medium, unverified |
| MediaTek hwtcon | Elipsa 2E (389), Clara BW (391, 395), Clara Colour (393), Libra Colour (390) | `HWTCON_SEND_UPDATE` | medium, unverified. The colour models are shown in grey (v1). |
| Allwinner sunxi | Elipsa (387), Sage (383) | needs ION buffers and `/dev/disp` | **not supported**. `check` fails and the menu entry shows a toast without stopping Nickel. |

Known gaps:

- **Orientation:** the sensor is followed only on the Libra 2. 0.2.3 took its mapping from
  KOReader's names for the values and drew landscape upside down on the device; the two
  landscape values are now the other way round (`profile::LIBRA2_SENSOR`), not yet checked
  on the device. Elsewhere the chosen orientation is fixed.
- **Touch:** each model's transform comes from KOReader and Plato and is untested. If taps
  land in the wrong place, set `touch` in `config.json`.
- **Waiting for refreshes:** the Mark 7 "wait for update complete" ioctl is unreliable on
  some models (Libra 2, Libra H2O, Nia, Clara 2E), so those profiles sleep briefly instead.
- **Wi-Fi on from the reader:** `wifi-on.sh` reverses `wifi-off.sh` after KOReader's
  `enable-wifi.sh` and `obtain-ip.sh`: chip power, driver, `wpa_supplicant` on Nickel's
  `/etc/wpa_supplicant/wpa_supplicant.conf`, then `dhcpcd`. Run on the Libra 2, whose
  board (`mx6sll-ntx`) has a Realtek `8723ds` powered through `sdio_wifi_pwr`, as Nickel
  starts it (`wpa_supplicant -D nl80211`, `dhcpcd`). The NXP `moal` path (with the
  `nxp/wifi_mod_para*.conf` it finds in `/lib/firmware`) and the MediaTek path are unverified.
- **Wi-Fi:** when Nickel was connected, the launcher keeps the Wi-Fi module,
  `wpa_supplicant` and `dhcpcd` running while the reader runs, and stops only `dhcpcd-dbus`.
  On exit it tears Wi-Fi down as KOReader does (`moal` and `mlan` on NXP chips, the
  `/dev/wmtWifi` switch on MTK, then the chip's power). Only the Libra 2 path has been
  run on a device, and not this version of it.
- **Front light:** see the table below. Only the brightness and warmth files of the
  Libra 2 family are high confidence; everything else is unverified.

### Device facts relied on

| Fact | Source | Confidence |
|---|---|---|
| Libra 2 is product 388, i.MX6SLL, 1264×1680 at 300 dpi, Nickel leaves the framebuffer at 32 bpp with `rotate=1` (portrait) | FBInk device table and mxcfb notes, KOReader `device.lua` | high |
| mxcfb ioctl numbers and struct layouts (V1 Kobo, V2 Mark 7, `WAIT_FOR_UPDATE_COMPLETE`), waveform modes DU 1, GC16 2, GLR16 6, AUTO 257 | Kobo kernel headers as published in FBInk's `eink/` directory | high |
| MTK hwtcon ioctls and waveforms | the same | medium |
| Libra 2 touch (Elan): swap X/Y, no mirroring; lift reported with a pressure-0 frame, then `ABS_MT_TRACKING_ID -1`, then `BTN_TOUCH 0` | KOReader `device.lua`, Plato `input.rs` | high |
| Page keys 193 (back) and 194 (forward), power 116, sleep cover 59 (`KEY_F1`: 1 closed, 0 opened; 35 on the Elipsa); gyro on `EV_MSC/MSC_RAW` | KOReader (`[59] = "SleepCover"`), Plato (`SLEEP_COVER = [59, 35]`) | high |
| Sleep: `1` to `/sys/power/state-extended`, 2 s, `sync`, `mem` to `/sys/power/state` (returns on wake), `0` to `state-extended`; 15 s between the sleep screen and the first try and between retries; on a refusal, clear the flag and retry; Wi-Fi off first ("suspend will at best fail, and at worst deadlock the system if Wi-Fi is on"), then the light | KOReader `Kobo:suspend`, `_doSuspend`, `Kobo:resume`, `Device:onPowerEvent`; Plato `scripts/suspend.sh`, `resume.sh`, `app.rs` | high (both agree), not yet run on a device |
| Input event numbers differ between hardware revisions, so devices are probed by capability | KOReader | high |
| Stopping and restarting Nickel: which processes to stop, `/tmp/nickel-hardware-status`, Wi-Fi teardown, `-platform kobo -skipFontLoad` | KOReader `koreader.sh` and `nickel.sh` | high (facts only, rewritten) |
| NickelMenu: a ':' inside a label breaks the config; `cmd_spawn` detaches; `chain_failure` | NickelMenu docs; the user's device | high |
| Front light, most i.MX Mark 7 models: `/sys/class/backlight/mxc_msp430.0/brightness` 0–100; warmth `/sys/class/leds/aw99703-bl_FL1/color` or `/sys/class/backlight/lm3630a_led/color` (or `tlc5947_bl` on the Forma), 0–10 where 0 is warmest (the Sage's is not inverted) | KOReader `device.lua` frontlight entries, FBInk/NickelMenu notes | high for the Libra 2, medium elsewhere |
| Front light, older models without sysfs: `/dev/ntx_io` ioctl 241 with 0–100 | KOReader `kobolight.lua` | medium |
| Nickel's own levels: `[PowerOptions] FrontLightLevel`, `FrontLightState` and `ColorSetting` (1500–6400 K) in `Kobo eReader.conf`; restored on exit since nothing shows Nickel reapplies them | the conf on the Libra 2, KOReader | medium |
| Wi-Fi: keeping `wpa_supplicant` and `dhcpcd` without Nickel is safe, `dhcpcd-dbus` is Nickel's bridge; teardown `dhcpcd -k`, `wpa_cli terminate`, `rmmod moal mlan`, ntx_io ioctl 208 or `sdio_wifi_pwr`, `/dev/wmtWifi` on MTK | KOReader `koreader.sh`, `enable-wifi.sh`, `disable-wifi.sh` | high (facts only, rewritten) |
| The system clock is UTC | Kobo firmware behaviour reported by KOReader | medium |

KOReader and Plato (AGPL) and FBInk (GPL) were read for these interface facts only. No code
was copied from them.

## Build

```sh
VERSION=1.0.0 kobo/build.sh
```

This needs Rust with the `armv7-unknown-linux-musleabihf` target, and clang. Without cargo on
the PATH, the script runs rustup through `nix shell nixpkgs#rustup`. Without clang, or with
Nix's wrapped clang (which adds the host's glibc headers and so breaks ring's build for the
target), it takes `nixpkgs#llvmPackages.clang-unwrapped` the same way. The binary is linked by
`rust-lld` with musl's self-contained startup files, so no GCC cross toolchain is needed; the
only C and assembly in the build is ring's (TLS for the server), which clang compiles for
the target (`kobo/.cargo/config.toml` sets `CC_armv7_unknown_linux_musleabihf=clang`). Every
other crate is pure Rust.

The script:

- builds the binary;
- lays out `kobo/dist/.adds/…` as on the device;
- copies the fonts and their OFL licences from `app/` (as `.license` files, since Nickel
  lists any `.txt` as a book);
- fails if any NickelMenu label contains ':';
- writes `kobo/dist/morningissue-kobo-$VERSION.zip`.

## Test

```sh
cd kobo && cargo test
```

- `tests/server.rs` runs `sync` against an in-process fake server (`tests/fakeserver/`) that
  checks the token and the events gate as the real one is specified to. It covers:
  - the pairing wait: `401 unknown` until the code is registered, then a sync on its own;
  - installing editions, the saved shelf and pictures (at this width, or scaled down from a
    wider one), following `version`s, and deleting what the manifest no longer lists;
  - events uploaded 500 at a time and deleted once acknowledged;
  - `409 events_pending`: the events above `acked` sent again and the request repeated, at
    most twice, then giving up; an event recorded during the sync going before the next
    bundle;
  - picture zips asked for 100 URLs at a time;
  - a `dbId` change wiping everything, and what a relay version stored wiped before the
    first upload;
  - a removed Kobo told to pair again;
  - no secret, no address or a bad address asking nothing;
  - a wrong clock put right from the `Date` header;
  - an `https://` server over TLS, and a certificate not yet valid blamed on the clock;
  - addresses, the v=2 pairing code, and the token vector.

- `tests/phone_fixtures.rs` parses the phone's golden fixtures (`docs/kobo-sync-fixtures/`):
  the manifest, an edition bundle with every block type, and the saved shelf.
- `tests/screens.rs` drives the whole reader through the desktop backend with scripted taps,
  swipes and keys, at:
  - 600×800 at 167 dpi;
  - 1072×1448 at 300 dpi;
  - 1264×1680 at 300 dpi in portrait, landscape and inverted;
  - 1404×1872 at 227 dpi.

  It covers the sleep screen at every size; sleeping and waking by the power button, the
  cover and the idle timeout, with touches made while asleep or waking ignored; a kernel
  that refuses to suspend; home with one, two and three editions, offline, syncing and after
  an error; Settings (on two pages where it doesn't fit) and the pairing code (decoded from
  the screenshot at every size); Saved; the reader with its chrome hidden and shown; the Aa
  popover, with and without warmth; a background sync with the fake server (a new edition,
  the saved shelf and pictures arriving while the reader is open); no sync attempt offline;
  orientation (portrait and landscape, each upside down, a brief tilt ignored, no sensor,
  the old setting); inverted buttons; Turn on Wi-Fi and sync (Wi-Fi on, a sync, Wi-Fi off,
  with a new edition and with nothing new; staying on while the server can't be reached or
  doesn't know this Kobo yet, until it gets through or Turn Wi-Fi off is tapped; a removed
  Kobo turning it off; a network that can't be joined; sleep while Wi-Fi is coming on); an
  unpaired Kobo opening the pairing code instead of turning Wi-Fi on; the server address
  typed on the keyboard (a bad one refused, a good one kept and synced with) at every size;
  and the pairing code noticing the phone's scan and syncing, and asking nothing when left
  at once or offline. It writes every
  screen, plus a `contact.png` sheet, to `kobo/target/screens/<size>/<run>/`.
- `tests/launch.rs` runs `launch/launch.sh` against a fake root with busybox-like stubs:
  Exit, a panic and a signal all bring Nickel back with its output on `/dev/null` and free
  the lock; stale locks (a dead PID, a reused PID, the empty lock 0.2.3 left) are taken
  over; a running launcher keeps it; a killed launcher still brings Nickel back. Run it
  under busybox's ash with `nix shell nixpkgs#rustup nixpkgs#busybox -c cargo test --test launch`.
- Unit tests cover ioctl struct sizes, a recorded Libra 2 touch trace, the reading rules,
  pagination of editions, code blocks, settings (old typed addresses ignored, a typed server
  address kept), `config.json`, the token derivation, picture entries and dates.

## Licences

Morning Issue is MIT-licensed. Fraunces, Newsreader and Inter are under the SIL Open Font
License. Their licences are installed next to the fonts in `.adds/morningissue/fonts/`, as
`*-OFL.license`. The binary includes rustls (Apache-2.0, ISC or MIT), ring (Apache-2.0 and
ISC), rustls-webpki (ISC) and webpki-roots, Mozilla's root certificates
(CDLA-Permissive-2.0).
