#!/bin/sh
# Turns Wi-Fi on while the reader runs (Nickel is stopped): powers the chip up, loads the
# driver, starts wpa_supplicant on Nickel's own configuration (so it joins a network saved in
# the Kobo's Wi-Fi settings) and waits for an address. The reader runs it for "Turn on Wi-Fi
# and sync" and checks the connection itself afterwards; the exit status is only logged.
# wifi-off.sh undoes all of it, and runs after it in every path (the sync is done, Turn
# Wi-Fi off, sleep, exit). Running it when Wi-Fi is already on does nothing harmful.
#
# Nickel's environment (INTERFACE, WIFI_MODULE, PLATFORM) is inherited through NickelMenu,
# launch.sh and the reader; every use has a fallback. MORNINGISSUE_ROOT is only set by
# tests, to point the system paths at a fake root.
#
# The steps follow KOReader's platform/kobo/enable-wifi.sh and obtain-ip.sh (facts only,
# rewritten): the reverse of wifi-off.sh.

R="${MORNINGISSUE_ROOT:-}"
DIR="${MORNINGISSUE_DIR:-$(cd "$(dirname "$0")" && pwd)}"
BIN="$DIR/morningissue"
IFACE="${INTERFACE:-wlan0}"
DRIVERS="$R/drivers/$PLATFORM/wifi"

echo "wifi-on: $IFACE, module ${WIFI_MODULE:-unknown}, platform ${PLATFORM:-unknown}"

pause() {
    usleep 250000 2>/dev/null || sleep 1
}

loaded() {
    grep -q "^$1 " "$R/proc/modules"
}

# insmod_once <module> [parameters...]: loads drivers/$PLATFORM/wifi/<module>.ko unless it
# is loaded already.
insmod_once() {
    mod="$1"
    shift
    loaded "$mod" && return 0
    if [ -e "$DRIVERS/$mod.ko" ]; then
        insmod "$DRIVERS/$mod.ko" "$@" || echo "wifi-on: insmod $mod failed"
    elif [ -e "$R/drivers/$PLATFORM/$mod.ko" ]; then
        insmod "$R/drivers/$PLATFORM/$mod.ko" "$@" || echo "wifi-on: insmod $mod failed"
    else
        echo "wifi-on: no $mod.ko under /drivers/$PLATFORM"
        return 1
    fi
    pause
}

# 1. Power and driver.
case "$WIFI_MODULE" in
    wlan_drv_gen4m)
        # MediaTek (Clara 2E, Clara BW/Colour, Libra Colour): the driver stays loaded and
        # Wi-Fi is switched on through its control node.
        insmod_once wlan_drv_gen4m
        [ -e "$R/dev/wmtWifi" ] && echo 1 >"$R/dev/wmtWifi"
        ;;
    moal)
        # NXP (Sage, Elipsa, some Libra 2s): power through ntx_io, then mlan, then moal with its
        # parameter file. The file's name differs between boards, so it is looked for.
        "$BIN" wifi-on || echo "wifi-on: powering the chip up failed"
        pause
        insmod_once mlan
        para=""
        for f in "$R"/lib/firmware/nxp/wifi_mod_para.conf "$R"/lib/firmware/nxp/wifi_mod_para*.conf; do
            if [ -e "$f" ]; then
                para="mod_para=nxp/$(basename "$f")"
                break
            fi
        done
        echo "wifi-on: moal ${para:-without a parameter file}"
        if [ -n "$para" ]; then
            insmod_once moal "$para"
        else
            insmod_once moal
        fi
        ;;
    *)
        # Older boards power the chip through the sdio_wifi_pwr module, newer ones through
        # ntx_io (ioctl 208). The Libra 2 checked (Realtek 8723ds) takes this path, with
        # sdio_wifi_pwr.
        if [ -e "$DRIVERS/sdio_wifi_pwr.ko" ]; then
            insmod_once sdio_wifi_pwr
        else
            "$BIN" wifi-on || echo "wifi-on: powering the chip up failed"
            pause
        fi
        [ -n "$WIFI_MODULE" ] && insmod_once "$WIFI_MODULE"
        ;;
esac

# The interface appears a moment after the driver loads.
i=0
while [ ! -e "$R/sys/class/net/$IFACE" ] && [ $i -lt 20 ]; do
    pause
    i=$((i + 1))
done
if [ ! -e "$R/sys/class/net/$IFACE" ]; then
    echo "wifi-on: no $IFACE after loading the driver"
    exit 1
fi
ifconfig "$IFACE" up
[ "$WIFI_MODULE" = "dhd" ] && wlarm_le -i "$IFACE" up

# 2. Join a saved network: wpa_supplicant on Nickel's configuration, which holds the networks
#    saved in the Kobo's Wi-Fi settings and connects to one in range by itself. nl80211 is the
#    newer driver interface, wext the older; wpa_supplicant tries them in order.
if ! pidof wpa_supplicant >/dev/null 2>&1; then
    env -u LD_LIBRARY_PATH wpa_supplicant -D nl80211,wext -s -i "$IFACE" \
        -c /etc/wpa_supplicant/wpa_supplicant.conf -C /var/run/wpa_supplicant -B ||
        echo "wifi-on: wpa_supplicant did not start"
else
    wpa_cli -i "$IFACE" reconnect >/dev/null 2>&1
fi

# Wait up to 20 s for the association, so the DHCP client starts on a live link.
i=0
while [ $i -lt 40 ]; do
    if wpa_cli -i "$IFACE" status 2>/dev/null | grep -q "^wpa_state=COMPLETED"; then
        break
    fi
    pause
    i=$((i + 1))
done
if [ $i -ge 40 ]; then
    echo "wifi-on: no saved network joined"
    exit 1
fi
echo "wifi-on: joined $(wpa_cli -i "$IFACE" status 2>/dev/null | sed -n 's/^ssid=//p')"

# 3. An address. dhcpcd waits (-w) for a lease, at most 30 s (-t 30); busybox's udhcpc
#    where there is no dhcpcd.
if command -v dhcpcd >/dev/null 2>&1; then
    if pidof dhcpcd >/dev/null 2>&1; then
        dhcpcd -n "$IFACE"
    else
        dhcpcd -d -t 30 -w "$IFACE"
    fi
else
    udhcpc -S -i "$IFACE" -s /etc/udhcpc.d/default.script -t 15 -T 2 -n -q
fi

# Connected when there is a default route through the interface.
i=0
while [ $i -lt 20 ]; do
    if awk -v i="$IFACE" '$1 == i && $2 == "00000000" { found = 1 } END { exit !found }' "$R/proc/net/route" 2>/dev/null; then
        echo "wifi-on: connected"
        exit 0
    fi
    pause
    i=$((i + 1))
done
echo "wifi-on: no address"
exit 1
