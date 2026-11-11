#!/bin/sh
# Builds the Kobo reader and packages it.
#
#   kobo/dist/.adds/morningissue/...          laid out as on the device
#   kobo/dist/.adds/nm/morningissue           the NickelMenu entry
#   kobo/dist/morningissue-kobo-$VERSION.zip  unzip at the root of the KOBOeReader drive
#
# VERSION defaults to "dev". Uses cargo from PATH, or rustup through nix when there is none.
set -eu

KOBO=$(cd "$(dirname "$0")" && pwd)
ROOT=$(dirname "$KOBO")
TARGET=armv7-unknown-linux-musleabihf
VERSION=${VERSION:-dev}
DIST="$KOBO/dist"
NM_CONFIG="$KOBO/launch/nm-morningissue"

# NickelMenu splits every line on ':'; a colon inside a label breaks the whole config.
# A menu_item line is menu_item:<location>:<label>:<action>:<arg>, so the label is field 3
# and it must be followed by a known action.
check_labels() {
    bad=0
    while IFS= read -r line; do
        case "$line" in
            menu_item:*) ;;
            *) continue ;;
        esac
        action=$(printf '%s\n' "$line" | cut -d: -f4)
        case "$action" in
            cmd_spawn|cmd_output|dbg_syslog|dbg_error|dbg_msg|dbg_toast|kfmon|kfmon_id|nickel_setting|nickel_extras|nickel_browser|nickel_misc|nickel_open|nickel_wifi|nickel_orientation|power|skip) ;;
            *)
                echo "build.sh: menu label contains ':' or the action is unknown: $line" >&2
                bad=1
                ;;
        esac
    done <"$1"
    return $bad
}
check_labels "$NM_CONFIG"

cd "$KOBO"
if command -v cargo >/dev/null 2>&1; then
    rustup target add "$TARGET" >/dev/null 2>&1 || true
    cargo build --release --target "$TARGET"
else
    nix shell nixpkgs#rustup -c sh -c "rustup target add $TARGET >/dev/null && cargo build --release --target $TARGET"
fi

rm -rf "$DIST"
APP="$DIST/.adds/morningissue"
mkdir -p "$APP/fonts" "$DIST/.adds/nm"
cp "target/$TARGET/release/morningissue" "$APP/morningissue"
cp launch/launch.sh "$APP/launch.sh"
cp launch/wifi-off.sh "$APP/wifi-off.sh"
chmod 755 "$APP/morningissue" "$APP/launch.sh" "$APP/wifi-off.sh"
for f in fraunces.ttf fraunces_italic.ttf newsreader.ttf newsreader_italic.ttf inter.ttf; do
    cp "$ROOT/app/src/main/res/font/$f" "$APP/fonts/$f"
done
for f in Fraunces-OFL.txt Inter-OFL.txt Newsreader-OFL.txt; do
    cp "$ROOT/app/src/main/assets/licenses/$f" "$APP/fonts/$f"
done
cp "$ROOT/LICENSE" "$APP/LICENSE" 2>/dev/null || true
printf '%s\n' "$VERSION" >"$APP/VERSION"
cp "$NM_CONFIG" "$DIST/.adds/nm/morningissue"
check_labels "$DIST/.adds/nm/morningissue"

ZIP="$DIST/morningissue-kobo-$VERSION.zip"
python3 -I - "$DIST" "$ZIP" <<'EOF'
import os, sys, zipfile
dist, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for top, dirs, files in os.walk(os.path.join(dist, ".adds")):
        dirs.sort()
        for name in sorted(files):
            path = os.path.join(top, name)
            arc = os.path.relpath(path, dist)
            info = zipfile.ZipInfo.from_file(path, arc)
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(path, "rb") as f:
                z.writestr(info, f.read())
EOF
echo "built $ZIP"
