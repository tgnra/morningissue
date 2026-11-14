#!/bin/sh
# Turns Wi-Fi off the way a boot leaves it: no DHCP client, no wpa_supplicant, the interface
# down, the driver unloaded and the chip unpowered. launch.sh runs it before bringing Nickel
# back; the reader runs it after a sync on Wi-Fi it turned on (wifi-on.sh), for Turn Wi-Fi
# off, and before the Kobo goes to sleep (suspending with Wi-Fi on can
# hang several models; KOReader and Plato both turn it off first). Running it again when
# Wi-Fi is already off does nothing harmful.
#
# Nickel's environment (INTERFACE, WIFI_MODULE, PLATFORM) is inherited through NickelMenu,
# launch.sh and the reader; every use has a fallback. MORNINGISSUE_ROOT is only set by the
# launcher's tests, to point the system paths at a fake root.
#
# The steps follow KOReader's platform/kobo/disable-wifi.sh and nickel.sh (facts only,
# rewritten). Nickel finding the modules half unloaded (moal gone, mlan still there) or the
# chip still powered trips its own Wi-Fi script.

R="${MORNINGISSUE_ROOT:-}"
DIR="${MORNINGISSUE_DIR:-$(cd "$(dirname "$0")" && pwd)}"
BIN="$DIR/morningissue"
IFACE="${INTERFACE:-wlan0}"

pause() {
    usleep 250000 2>/dev/null || sleep 1
}

if grep -q "^ *$IFACE:" "$R/proc/net/dev" 2>/dev/null; then
    # Release the lease, then make sure no DHCP client survives: dhcpcd -k can leave the
    # master process running, and the next wifi-on.sh must start a fresh one.
    if pidof dhcpcd >/dev/null 2>&1; then
        dhcpcd -d -k "$IFACE"
    fi
    killall -q dhcpcd udhcpc
    wpa_cli -i "$IFACE" terminate >/dev/null 2>&1
    pause
    killall -q wpa_supplicant
    [ "$WIFI_MODULE" = "dhd" ] && wlarm_le -i "$IFACE" down
    ifconfig "$IFACE" down
fi
# dhcpcd -k empties resolv.conf; launch.sh kept Nickel's.
[ -f "$R/tmp/morningissue-resolv.conf" ] && cp -f "$R/tmp/morningissue-resolv.conf" "$R/etc/resolv.conf"
case "$WIFI_MODULE" in
    wlan_drv_gen4m)
        # MediaTek (Clara 2E, Clara BW/Colour, Libra Colour): the driver stays loaded and
        # Wi-Fi is switched off through its control node.
        [ -e "$R/dev/wmtWifi" ] && echo 0 >"$R/dev/wmtWifi"
        ;;
    moal)
        # NXP (Sage, Elipsa, some Libra 2s): moal sits on mlan, and both must go.
        if grep -q "^moal " "$R/proc/modules"; then
            pause
            rmmod moal
        fi
        if grep -q "^mlan " "$R/proc/modules"; then
            pause
            rmmod mlan
        fi
        ;;
    *)
        if [ -n "$WIFI_MODULE" ] && grep -q "^$WIFI_MODULE " "$R/proc/modules"; then
            pause
            rmmod "$WIFI_MODULE"
        fi
        ;;
esac
if [ "$WIFI_MODULE" != "wlan_drv_gen4m" ]; then
    # Power the chip down: older boards through the sdio_wifi_pwr module, newer ones through
    # ntx_io (ioctl 208), which the reader's binary does for this script.
    if [ -e "$R/drivers/$PLATFORM/wifi/sdio_wifi_pwr.ko" ]; then
        if grep -q "^sdio_wifi_pwr " "$R/proc/modules"; then
            pause
            rmmod sdio_wifi_pwr
        fi
    else
        pause
        "$BIN" wifi-off
    fi
fi
exit 0
