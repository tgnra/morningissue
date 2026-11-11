#!/bin/sh
# Morning Issue launcher. NickelMenu runs this with cmd_spawn. It stops Nickel, keeping the
# Wi-Fi connection Nickel had (if any), runs the reader on the bare framebuffer (the reader
# syncs in the background when there is a connection), then tears Wi-Fi down the way a
# boot leaves it and brings Nickel back. See docs/kobo-sync.md, "Launch flow".
#
# Whatever happens to the reader (Exit, a crash, a signal), Nickel comes back and the lock
# is released, so the menu entry works again straight away. The reader is never restarted
# automatically.
#
# Nickel's environment (PLATFORM, INTERFACE, WIFI_MODULE, ...) is inherited through
# NickelMenu; every use below has a fallback in case a firmware leaves one out. The Wi-Fi
# and Nickel handling follows KOReader's (platform/kobo/koreader.sh, disable-wifi.sh and
# nickel.sh).
#
# MORNINGISSUE_ROOT and MORNINGISSUE_DIR are only set by the launcher's tests
# (kobo/tests/launch.rs), to point the system paths at a fake root.

R="${MORNINGISSUE_ROOT:-}"
DIR="${MORNINGISSUE_DIR:-/mnt/onboard/.adds/morningissue}"
BIN="$DIR/morningissue"
LOG="$DIR/data/launch.log"
PANIC="$DIR/data/panic.txt"

mkdir -p "$DIR/data"
# Keep the log small: start a new one when it passes 256 KB.
if [ -f "$LOG" ] && [ "$(wc -c <"$LOG")" -gt 262144 ]; then
    mv -f "$LOG" "$LOG.old"
fi
exec >>"$LOG" 2>&1 </dev/null
echo "--- $(date -u '+%Y-%m-%dT%H:%M:%SZ') launch (pid $$)"

# One reader at a time. The lock is a directory (mkdir is atomic) holding the launcher's
# PID. A lock whose launcher is gone (killed, crashed, or the device lost power mid-session
# on a firmware that keeps /tmp) is stale and is taken over, so it can never block a launch.
MI_LOCK="$R/tmp/morningissue.lock"

# Whether the launcher that wrote the lock is still running: its PID is alive and is a
# shell running this script (not an unrelated process that reused the PID).
lock_owner_alive() {
    owner=$(cat "$MI_LOCK/pid" 2>/dev/null)
    [ -n "$owner" ] || return 1
    kill -0 "$owner" 2>/dev/null || return 1
    tr '\0' ' ' <"/proc/$owner/cmdline" 2>/dev/null | grep -q "launch.sh"
}

take_lock() {
    if mkdir "$MI_LOCK" 2>/dev/null; then
        echo $$ >"$MI_LOCK/pid"
        return 0
    fi
    # A launcher that has only just made the directory writes its PID next; give it a moment.
    [ -f "$MI_LOCK/pid" ] || sleep 1
    if lock_owner_alive; then
        return 1
    fi
    echo "removing a stale lock (pid ${owner:-unknown} is gone)"
    rm -rf "$MI_LOCK"
    if mkdir "$MI_LOCK" 2>/dev/null; then
        echo $$ >"$MI_LOCK/pid"
        return 0
    fi
    return 1
}

release_lock() {
    if [ "$(cat "$MI_LOCK/pid" 2>/dev/null)" = "$$" ]; then
        rm -rf "$MI_LOCK"
    fi
}

if ! take_lock; then
    echo "already running (pid $owner)"
    exit 0
fi

NICKEL_STOPPED=0
NICKEL_BACK=0

# 4. Bring Nickel back, as it expects to find the device after a boot: Wi-Fi off, the SD
#    card unmounted, the hardware-status pipe present. Nothing started here may keep the
#    log open: Nickel and what it starts (dhcpcd, insmod, ...) run for the whole session
#    and would fill launch.log, so their output goes to /dev/null.
bring_nickel_back() {
    NICKEL_BACK=1
    cd / || true
    # 3. Wi-Fi off, as a boot leaves it, so Nickel can bring it up itself.
    sh "$DIR/wifi-off.sh"

    export LD_LIBRARY_PATH=/usr/local/Kobo
    # The boot animation script prints "sh: ON: unknown operand" for every frame on models
    # whose hwconfig has no colour flag; at boot rcS throws that away, and so do we.
    if [ -x "$R/etc/init.d/on-animator.sh" ]; then
        "$R/etc/init.d/on-animator.sh" </dev/null >/dev/null 2>&1 &
    fi

    if grep -q " /mnt/sd " "$R/proc/mounts" 2>/dev/null; then
        umount /mnt/sd
    fi
    rm -f "$R/tmp/nickel-hardware-status"
    mkfifo "$R/tmp/nickel-hardware-status"
    sync

    # What a shell leaves behind that a freshly booted Nickel would not have.
    unset OLDPWD
    "$R/usr/local/Kobo/hindenburg" </dev/null >/dev/null 2>&1 &
    LIBC_FATAL_STDERR_=1 "$R/usr/local/Kobo/nickel" -platform kobo -skipFontLoad </dev/null >/dev/null 2>&1 &
    if [ -n "$PLATFORM" ] && [ "$PLATFORM" != "freescale" ]; then
        udevadm trigger </dev/null >/dev/null 2>&1 &
    fi
    echo "nickel restarted"
}

on_exit() {
    if [ "$NICKEL_STOPPED" = 1 ] && [ "$NICKEL_BACK" = 0 ]; then
        echo "launcher interrupted; bringing Nickel back"
        bring_nickel_back
    fi
    release_lock
}
trap on_exit EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

# A model the reader cannot draw on: leave Nickel alone.
if ! "$BIN" check; then
    echo "unsupported device; not stopping Nickel"
    exit 0
fi

IFACE="${INTERFACE:-wlan0}"

# Is Nickel's Wi-Fi connected? A default route through an interface with a carrier.
# No connection now means the reader shows Offline; it turns Wi-Fi on (wifi-on.sh) only when
# asked to in its Settings, and off again after the sync.
wifi_up() {
    while read -r iface dest rest; do
        if [ "$dest" = "00000000" ] && [ "$iface" != "lo" ]; then
            [ "$(cat "$R/sys/class/net/$iface/carrier" 2>/dev/null)" = "1" ] && return 0
        fi
    done <"$R/proc/net/route"
    return 1
}

# 1. Stop Nickel and the services that draw on the screen.
#    With Wi-Fi connected, the link stays as it is: the module, wpa_supplicant (which keeps
#    the association) and dhcpcd (which renews the lease) keep running. dhcpcd-dbus always
#    goes: it is Nickel's D-Bus bridge to dhcpcd and has nothing to talk to without Nickel.
#    Without a connection, dhcpcd goes too, so nothing tries to bring a lease up behind the
#    reader's back.
sync
if wifi_up; then
    echo "wi-fi connected on $IFACE; keeping it for the reader"
    NET_KILL=""
else
    echo "wi-fi not connected; the reader will show Offline"
    NET_KILL="dhcpcd"
fi
NICKEL_STOPPED=1
killall -q -TERM nickel hindenburg sickel fickel strickel fontickel adobehost foxitpdf iink \
    dhcpcd-dbus $NET_KILL bluealsa bluetoothd fmon nanoclock.lua memorylogger QtWebEngineProcess
i=0
while [ $i -lt 16 ] && pidof nickel >/dev/null 2>&1; do
    usleep 250000 2>/dev/null || sleep 1
    i=$((i + 1))
done
# Without Nickel nothing reads this pipe, and udev's and udhcpc's scripts that write to it
# would hang.
rm -f "$R/tmp/nickel-hardware-status"

# dhcpcd -k (in wifi-off.sh) empties resolv.conf; keep Nickel's to put back.
[ -f "$R/etc/resolv.conf" ] && cp -f "$R/etc/resolv.conf" "$R/tmp/morningissue-resolv.conf"

# 2. Read. The reader shows the home screen at once and syncs in the background when online.
#    The power button and the sleep cover put the Kobo to sleep from inside the reader; it
#    returns only on Exit (or when it crashes). A panic leaves its message in panic.txt.
rm -f "$PANIC"
"$BIN" read
rc=$?
if [ $rc -eq 0 ]; then
    echo "reader exited"
else
    if [ $rc -gt 128 ]; then
        why="killed by signal $((rc - 128))"
    else
        why="exit code $rc"
    fi
    if [ -s "$PANIC" ]; then
        why="$why; $(cat "$PANIC")"
    fi
    echo "reader crashed ($why); bringing Nickel back"
fi

bring_nickel_back
