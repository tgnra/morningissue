# Morning Issue for Kobo

A native e-ink reader for Kobo e-readers. You open it from NickelMenu. It syncs editions
with the Morning Issue app on your phone over the local network, then shows them on the bare
framebuffer while Nickel (Kobo's own reader) is stopped. The protocol, the files on the
device and the launch flow are specified in [`docs/kobo-sync.md`](../docs/kobo-sync.md).

It is one static binary, `morningissue`, built for `armv7-unknown-linux-musleabihf`. It has
three subcommands:

- `sync` fetches from the phone.
- `read` runs the reader.
- `check` names the device and fails if this version can't draw on it.

## Install

You need [NickelMenu](https://pgaskin.net/NickelMenu/) v0.6.0 or newer, which needs
firmware 4.6 or newer.

1. Connect the Kobo to a computer by USB. It appears as the `KOBOeReader` drive.
2. Unzip `morningissue-kobo-<version>.zip` at the root of that drive. This creates
   `.adds/morningissue/` and `.adds/nm/morningissue`. Folders starting with a dot may be
   hidden on your computer, but they are still created.
3. Eject the drive. NickelMenu reads its configuration again once the Kobo leaves USB mode.
4. On the phone, open Morning Issue's Kobo settings and keep the app open on the same Wi-Fi
   network.
5. On the Kobo, open the main menu and tap **Morning Issue**. The phone asks you to allow
   the Kobo the first time.

### Update

Unzip the new zip over the old files in the same way. Everything you added yourself
(`.adds/morningissue/config.json`) and everything the reader stored (`data/`) is kept.

### Uninstall

Delete `.adds/morningissue/` and `.adds/nm/morningissue` from the drive. Nothing else on
the device is changed. The reader never installs into the system partition, and it doesn't
change the screen mode.

## Use

- **Newsstand:** shows the three newest editions, when the last sync ran (or why it failed),
  **Rotate** and **Exit**. Tap a cover to open it.
- **Edition:** pages appear in the order the phone printed them. To turn a page, use the page
  buttons, swipe left or right, tap the outer fifth of the screen, or use **Previous** and
  **Next**. Tap a story to read it.
- **Article:** the header shows the feed and author. To turn a page, tap the left or right
  third of the screen, or swipe. The bottom bar has **Like** and **Save**, and **‹ Edition**
  goes back.
- **Leaving:** the power button, closing the sleep cover, **Exit**, or 20 minutes without
  input all leave the reader and restart Nickel, which then sleeps as usual. The reader never
  suspends the device itself.

Every action can be done by touch alone. Page buttons and the orientation sensor are used
when the model has them.

What you read is recorded in `data/events.jsonl` and sent on the next sync.

## Configuration

You can create `.adds/morningissue/config.json` yourself. Every field is optional:

```json
{
  "phone": "192.168.1.20:47824",
  "name": "Kobo in the kitchen",
  "beaconSeconds": 10,
  "flashEvery": 6,
  "idleMinutes": 20,
  "touch": { "device": "/dev/input/event1", "swapXy": true, "mirrorX": false, "mirrorY": false },
  "display": { "quarterTurns": 0, "waveformFull": 2, "waveformPartial": 257, "waveformFast": 1 }
}
```

- `phone` is for networks that drop UDP broadcasts.
- `touch` and `display` are escape hatches for a model whose profile turns out to be wrong.
- `flashEvery` sets how many partial page turns pass before a full refresh.
- Problems are logged to `.adds/morningissue/data/launch.log`.

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

- **Orientation:** the sensor is followed only on the Libra 2, where KOReader and Plato
  agree on its mapping. Elsewhere, use **Rotate** on the newsstand, which also locks the
  orientation on the Libra 2.
- **Touch:** each model's transform comes from KOReader and Plato and is untested. If taps
  land in the wrong place, set `touch` in `config.json`.
- **Waiting for refreshes:** the Mark 7 "wait for update complete" ioctl is unreliable on
  some models (Libra 2, Libra H2O, Nia, Clara 2E), so those profiles sleep briefly instead.
- **Wi-Fi after exit:** the launcher removes the Wi-Fi module the way KOReader does. On MTK
  models this has not been checked.

### Device facts relied on

| Fact | Source | Confidence |
|---|---|---|
| Libra 2 is product 388, i.MX6SLL, 1264×1680 at 300 dpi, Nickel leaves the framebuffer at 32 bpp with `rotate=1` (portrait) | FBInk device table and mxcfb notes, KOReader `device.lua` | high |
| mxcfb ioctl numbers and struct layouts (V1 Kobo, V2 Mark 7, `WAIT_FOR_UPDATE_COMPLETE`), waveform modes DU 1, GC16 2, GLR16 6, AUTO 257 | Kobo kernel headers as published in FBInk's `eink/` directory | high |
| MTK hwtcon ioctls and waveforms | the same | medium |
| Libra 2 touch (Elan): swap X/Y, no mirroring; lift reported with a pressure-0 frame, then `ABS_MT_TRACKING_ID -1`, then `BTN_TOUCH 0` | KOReader `device.lua`, Plato `input.rs` | high |
| Page keys 193 (back) and 194 (forward), power 116, sleep cover 59; gyro on `EV_MSC/MSC_RAW` | KOReader, Plato | high |
| Input event numbers differ between hardware revisions, so devices are probed by capability | KOReader | high |
| Stopping and restarting Nickel: which processes to stop, `/tmp/nickel-hardware-status`, Wi-Fi teardown, `-platform kobo -skipFontLoad` | KOReader `koreader.sh` and `nickel.sh` | high (facts only, rewritten) |
| NickelMenu: a ':' inside a label breaks the config; `cmd_spawn` detaches; `chain_failure` | NickelMenu docs; the user's device | high |
| The system clock is UTC | Kobo firmware behaviour reported by KOReader | medium |

KOReader and Plato (AGPL) and FBInk (GPL) were read for these interface facts only. No code
was copied from them.

## Build

```sh
VERSION=1.0.0 kobo/build.sh
```

This needs Rust with the `armv7-unknown-linux-musleabihf` target. Without cargo on the PATH,
the script runs rustup through `nix shell nixpkgs#rustup`. The binary is linked by
`rust-lld` with musl's self-contained startup files, so no cross C toolchain is needed.

The script:

- builds the binary;
- lays out `kobo/dist/.adds/…` as on the device;
- copies the fonts and their OFL licences from `app/`;
- fails if any NickelMenu label contains ':';
- writes `kobo/dist/morningissue-kobo-$VERSION.zip`.

## Test

```sh
cd kobo && cargo test
```

- `tests/sync.rs` runs `sync` against an in-process fake phone. It covers:
  - pairing;
  - a 401 leading to re-pairing;
  - idempotent acks;
  - a dbId change wiping state;
  - a changed edition version;
  - image 404s;
  - an interrupted sync keeping the last good state;
  - no phone found.
- `tests/screens.rs` drives the whole reader through the desktop backend with scripted taps,
  swipes and keys. It covers:
  - 600×800 at 167 dpi;
  - 1072×1448 at 300 dpi;
  - 1264×1680 at 300 dpi in portrait, landscape and inverted;
  - 1404×1872 at 227 dpi.

  It writes every screen, plus a `contact.png` sheet, to `kobo/target/screens/<size>/`.
- Unit tests cover ioctl struct sizes, a recorded Libra 2 touch trace, the reading rules,
  pagination of editions and dates.

## Licences

Morning Issue is MIT-licensed. Fraunces, Newsreader and Inter are under the SIL Open Font
License. Their licences are installed next to the fonts in `.adds/morningissue/fonts/`.
