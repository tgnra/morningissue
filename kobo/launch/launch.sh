#!/bin/sh
# Morning Issue launcher. NickelMenu runs this with cmd_spawn after asking Nickel to bring
# Wi-Fi up. It syncs with the phone, stops Nickel, runs the reader on the bare framebuffer,
# then brings Nickel back. See docs/kobo-sync.md, "Launch flow".
#
# Nickel's environment (PLATFORM, INTERFACE, WIFI_MODULE, ...) is inherited through
# NickelMenu; every use below has a fallback in case a firmware leaves one out.

DIR=/mnt/onboard/.adds/morningissue
BIN="$DIR/morningissue"
LOG="$DIR/data/launch.log"

mkdir -p "$DIR/data"
# Keep the log small: start a new one when it passes 256 KB.
if [ -f "$LOG" ] && [ "$(wc -c <"$LOG")" -gt 262144 ]; then
    mv -f "$LOG" "$LOG.old"
fi
exec >>"$LOG" 2>&1 </dev/null
echo "--- $(date -u '+%Y-%m-%dT%H:%M:%SZ') launch"

# One reader at a time: a second tap on the menu entry while this runs does nothing.
LOCK=/tmp/morningissue.lock
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "already running"
    exit 0
fi
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

# A model the reader cannot draw on: leave Nickel alone.
if ! "$BIN" check; then
    echo "unsupported device; not stopping Nickel"
    exit 0
fi

# 1. Wait up to 20 seconds for a default route, then sync. A failed sync is not fatal.
has_route() {
    # /proc/net/route: Iface Destination Gateway ...; destination 00000000 is the default.
    while read -r iface dest rest; do
        [ "$dest" = "00000000" ] && [ "$iface" != "lo" ] && return 0
    done </proc/net/route
    return 1
}
i=0
while [ $i -lt 20 ] && ! has_route; do
    sleep 1
    i=$((i + 1))
done
if has_route; then
    "$BIN" sync
else
    echo "no network after 20 s; reading what is stored"
fi

# 2. Stop Nickel and the services that draw on the screen or hold the Wi-Fi.
sync
killall -q -TERM nickel hindenburg sickel fickel strickel fontickel adobehost foxitpdf iink \
    dhcpcd-dbus dhcpcd bluealsa bluetoothd fmon nanoclock.lua memorylogger QtWebEngineProcess
i=0
while [ $i -lt 16 ] && pidof nickel >/dev/null 2>&1; do
    usleep 250000 2>/dev/null || sleep 1
    i=$((i + 1))
done
rm -f /tmp/nickel-hardware-status

# 3. Read. The reader returns on Exit, the power button, the sleep cover or after the idle
#    timeout in config.json.
"$BIN" read
echo "reader exited with $?"

# 4. Bring Nickel back, as it expects to find the device after a boot: Wi-Fi off and its
#    module unloaded, the SD card unmounted, the hardware-status pipe present.
cd / || true
export LD_LIBRARY_PATH=/usr/local/Kobo
[ -x /etc/init.d/on-animator.sh ] && /etc/init.d/on-animator.sh &

IFACE="${INTERFACE:-wlan0}"
if grep -q "^ *$IFACE:" /proc/net/dev 2>/dev/null; then
    if pidof dhcpcd >/dev/null 2>&1; then
        dhcpcd -d -k "$IFACE"
    else
        killall -q udhcpc
    fi
    # A wpa_supplicant left running stops Nickel bringing Wi-Fi up again.
    wpa_cli -i "$IFACE" terminate >/dev/null 2>&1
    killall -q wpa_supplicant
    ifconfig "$IFACE" down
fi
if [ -n "$WIFI_MODULE" ] && grep -q "^$WIFI_MODULE " /proc/modules; then
    rmmod "$WIFI_MODULE"
fi
if grep -q "^sdio_wifi_pwr " /proc/modules; then
    rmmod sdio_wifi_pwr
fi

if grep -q " /mnt/sd " /proc/mounts; then
    umount /mnt/sd
fi
rm -f /tmp/nickel-hardware-status
mkfifo /tmp/nickel-hardware-status
sync

/usr/local/Kobo/hindenburg &
LIBC_FATAL_STDERR_=1 /usr/local/Kobo/nickel -platform kobo -skipFontLoad &
if [ -n "$PLATFORM" ] && [ "$PLATFORM" != "freescale" ]; then
    udevadm trigger &
fi
echo "nickel restarted"
