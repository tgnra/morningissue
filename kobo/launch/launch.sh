#!/bin/sh
# Morning Issue launcher. NickelMenu runs this with cmd_spawn. It stops Nickel, keeping the
# Wi-Fi connection Nickel had (if any), runs the reader on the bare framebuffer (the reader
# syncs in the background when there is a connection), then tears Wi-Fi down the way a
# boot leaves it and brings Nickel back. See docs/kobo-sync.md, "Launch flow".
#
# Nickel's environment (PLATFORM, INTERFACE, WIFI_MODULE, ...) is inherited through
# NickelMenu; every use below has a fallback in case a firmware leaves one out. The Wi-Fi
# handling follows KOReader's (platform/kobo/koreader.sh, enable-wifi.sh, disable-wifi.sh
# and nickel.sh).

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

IFACE="${INTERFACE:-wlan0}"

# Is Nickel's Wi-Fi connected? A default route through an interface with a carrier.
# The reader never turns Wi-Fi on: no connection now means it shows Offline.
wifi_up() {
    while read -r iface dest rest; do
        if [ "$dest" = "00000000" ] && [ "$iface" != "lo" ]; then
            [ "$(cat "/sys/class/net/$iface/carrier" 2>/dev/null)" = "1" ] && return 0
        fi
    done </proc/net/route
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
killall -q -TERM nickel hindenburg sickel fickel strickel fontickel adobehost foxitpdf iink \
    dhcpcd-dbus $NET_KILL bluealsa bluetoothd fmon nanoclock.lua memorylogger QtWebEngineProcess
i=0
while [ $i -lt 16 ] && pidof nickel >/dev/null 2>&1; do
    usleep 250000 2>/dev/null || sleep 1
    i=$((i + 1))
done
# Without Nickel nothing reads this pipe, and udev's and udhcpc's scripts that write to it
# would hang.
rm -f /tmp/nickel-hardware-status

# dhcpcd -k (below) empties resolv.conf; keep Nickel's to put back.
[ -f /etc/resolv.conf ] && cp -f /etc/resolv.conf /tmp/morningissue-resolv.conf

# 2. Read. The reader shows the home screen at once and syncs in the background when online.
#    It returns on Exit, the power button, the sleep cover or after the idle timeout in
#    config.json.
"$BIN" read
echo "reader exited with $?"

# 3. Wi-Fi off, as a boot leaves it, so Nickel can bring it up itself. Nickel finding the
#    modules half unloaded (moal gone, mlan still there) or the chip still powered trips
#    its own Wi-Fi script; the "insmod: short read" in older logs came after such a teardown.
cd / || true
if grep -q "^ *$IFACE:" /proc/net/dev 2>/dev/null; then
    if pidof dhcpcd >/dev/null 2>&1; then
        dhcpcd -d -k "$IFACE"
    else
        killall -q udhcpc
    fi
    wpa_cli -i "$IFACE" terminate >/dev/null 2>&1
    usleep 250000 2>/dev/null || sleep 1
    killall -q wpa_supplicant
    [ "$WIFI_MODULE" = "dhd" ] && wlarm_le -i "$IFACE" down
    ifconfig "$IFACE" down
fi
[ -f /tmp/morningissue-resolv.conf ] && cp -f /tmp/morningissue-resolv.conf /etc/resolv.conf
usleep 250000 2>/dev/null || sleep 1
case "$WIFI_MODULE" in
    wlan_drv_gen4m)
        # MediaTek (Clara 2E, Clara BW/Colour, Libra Colour): the driver stays loaded and
        # Wi-Fi is switched off through its control node.
        [ -e /dev/wmtWifi ] && echo 0 >/dev/wmtWifi
        ;;
    moal)
        # NXP (Libra 2, Sage, Elipsa): moal sits on mlan, and both must go.
        grep -q "^moal " /proc/modules && rmmod moal
        grep -q "^mlan " /proc/modules && rmmod mlan
        ;;
    *)
        if [ -n "$WIFI_MODULE" ] && grep -q "^$WIFI_MODULE " /proc/modules; then
            rmmod "$WIFI_MODULE"
        fi
        ;;
esac
if [ "$WIFI_MODULE" != "wlan_drv_gen4m" ]; then
    usleep 250000 2>/dev/null || sleep 1
    # Power the chip down: older boards through the sdio_wifi_pwr module, newer ones through
    # ntx_io (ioctl 208), which the reader's binary does for this script.
    if [ -e "/drivers/$PLATFORM/wifi/sdio_wifi_pwr.ko" ]; then
        grep -q "^sdio_wifi_pwr " /proc/modules && rmmod sdio_wifi_pwr
    else
        "$BIN" wifi-off
    fi
fi

# 4. Bring Nickel back, as it expects to find the device after a boot: Wi-Fi off, the SD
#    card unmounted, the hardware-status pipe present.
export LD_LIBRARY_PATH=/usr/local/Kobo
# The boot animation script prints "sh: ON: unknown operand" for every frame on models
# whose hwconfig has no colour flag; at boot rcS throws that away, and so do we.
[ -x /etc/init.d/on-animator.sh ] && /etc/init.d/on-animator.sh >/dev/null 2>&1 &

if grep -q " /mnt/sd " /proc/mounts; then
    umount /mnt/sd
fi
rm -f /tmp/nickel-hardware-status
mkfifo /tmp/nickel-hardware-status
sync

# What a shell leaves behind that a freshly booted Nickel would not have.
unset OLDPWD LOCK NET_KILL
/usr/local/Kobo/hindenburg &
LIBC_FATAL_STDERR_=1 /usr/local/Kobo/nickel -platform kobo -skipFontLoad &
if [ -n "$PLATFORM" ] && [ "$PLATFORM" != "freescale" ]; then
    udevadm trigger &
fi
echo "nickel restarted"
