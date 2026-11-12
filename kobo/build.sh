#!/bin/sh
# Builds the Kobo reader and packages it.
#
#   kobo/dist/.adds/morningissue/...          laid out as on the device
#   kobo/dist/.adds/nm/morningissue           the NickelMenu entry
#   kobo/dist/morningissue-kobo-$VERSION.zip  unzip at the root of the KOBOeReader drive
#
# VERSION defaults to "dev". Uses cargo and clang from PATH, or takes them from nix (see below).
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
# ring's C is compiled by clang for the target (.cargo/config.toml). Nix's wrapped clang adds
# the host's glibc headers to every compile, which breaks that, so a wrapped or missing clang
# is replaced by nixpkgs' unwrapped one. Without cargo, rustup comes through nix too.
PKGS=""
command -v cargo >/dev/null 2>&1 || PKGS="nixpkgs#rustup"
if ! command -v clang >/dev/null 2>&1; then
    PKGS="$PKGS nixpkgs#llvmPackages.clang-unwrapped"
else
    case $(readlink -f "$(command -v clang)") in
        */nix/store/*-clang-wrapper-*) PKGS="$PKGS nixpkgs#llvmPackages.clang-unwrapped" ;;
    esac
fi
BUILD="(rustup target add $TARGET >/dev/null 2>&1 || true) && cargo build --release --target $TARGET"
if [ -n "$PKGS" ]; then
    # shellcheck disable=SC2086 # PKGS is a list of installables.
    nix shell $PKGS -c sh -c "$BUILD"
else
    sh -c "$BUILD"
fi

rm -rf "$DIST"
APP="$DIST/.adds/morningissue"
mkdir -p "$APP/fonts" "$DIST/.adds/nm"
cp "target/$TARGET/release/morningissue" "$APP/morningissue"
cp launch/launch.sh "$APP/launch.sh"
cp launch/wifi-off.sh "$APP/wifi-off.sh"
cp launch/wifi-on.sh "$APP/wifi-on.sh"
chmod 755 "$APP/morningissue" "$APP/launch.sh" "$APP/wifi-off.sh" "$APP/wifi-on.sh"
for f in fraunces.ttf fraunces_italic.ttf newsreader.ttf newsreader_italic.ttf inter.ttf; do
    cp "$ROOT/app/src/main/res/font/$f" "$APP/fonts/$f"
done
# Installed as .license, not .txt: Nickel lists every .txt on the drive as a book.
for f in Fraunces-OFL Inter-OFL Newsreader-OFL; do
    cp "$ROOT/app/src/main/assets/licenses/$f.txt" "$APP/fonts/$f.license"
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
