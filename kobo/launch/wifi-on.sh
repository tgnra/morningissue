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
#    saved in the Kobo's Wi-Fi settings. nl80211 is the newer driver interface, wext the
#    older; wpa_supplicant tries them in order.
if ! pidof wpa_supplicant >/dev/null 2>&1; then
    env -u LD_LIBRARY_PATH wpa_supplicant -D nl80211,wext -s -i "$IFACE" \
        -c /etc/wpa_supplicant/wpa_supplicant.conf -C /var/run/wpa_supplicant -B ||
        echo "wifi-on: wpa_supplicant did not start"
else
    wpa_cli -i "$IFACE" reconnect >/dev/null 2>&1
fi

# Nickel's saved networks don't connect by themselves: Nickel marks every one but the last
# used as disabled=1 and chooses one itself over the control socket, so wpa_supplicant only
# ever tries the last one used (KoboUI's spike 3 on the Libra 2 saw it scan for 35 s with five
# saved and never join). As KOReader does, wait until a saved network shows up in the scan
# (scanning again every 5 s, for up to 15 s) and select the strongest one. select_network
# changes wpa_supplicant's state in memory only: nothing here calls save_config, so Nickel's
# file is left as it was.
wpa_cli -i "$IFACE" list_networks 2>/dev/null | tail -n +2 | while IFS="$(printf '\t')" read -r id ssid bssid flags; do
    echo "wifi-on: saved $id: $ssid ${flags:-(no flags)}"
done
best=""
i=0
while [ $i -lt 60 ]; do
    [ $((i % 20)) -eq 0 ] && wpa_cli -i "$IFACE" scan >/dev/null 2>&1
    pause
    # scan_results: bssid freq signal flags ssid; list_networks: id ssid bssid flags (tabs).
    wpa_cli -i "$IFACE" scan_results 2>/dev/null | tail -n +2 >/tmp/morningissue-scan
    wpa_cli -i "$IFACE" list_networks 2>/dev/null | tail -n +2 >/tmp/morningissue-nets
    best=$(awk -F'\t' '
        NR == FNR { if (!($5 in sig) || $3 + 0 > sig[$5] + 0) { sig[$5] = $3; freq[$5] = $2 } next }
        ($2 in sig) && (id == "" || sig[$2] + 0 > bestsig + 0) { id = $1; bestsig = sig[$2]; ssid = $2; f = freq[$2] }
        END { if (id != "") printf "%s\t%s\t%s\t%s\n", id, bestsig, f, ssid }' /tmp/morningissue-scan /tmp/morningissue-nets)
    [ -n "$best" ] && break
    i=$((i + 1))
done
rm -f /tmp/morningissue-scan /tmp/morningissue-nets
if [ -z "$best" ]; then
    echo "wifi-on: no saved network in range after 15 s of scanning"
    exit 1
fi
id=$(printf '%s' "$best" | cut -f1)
echo "wifi-on: selecting $(printf '%s' "$best" | cut -f4) (id $id, signal $(printf '%s' "$best" | cut -f2) dBm, $(printf '%s' "$best" | cut -f3) MHz)"
wpa_cli -i "$IFACE" select_network "$id" >/dev/null 2>&1 || echo "wifi-on: select_network failed"

# Wait for the association, so the DHCP client starts on a live link. Straight after the
# driver loads the 8723ds can be slow to associate, so allow 30 s, logging each state.
i=0
last=""
state=""
while [ $i -lt 120 ]; do
    state=$(wpa_cli -i "$IFACE" status 2>/dev/null | sed -n 's/^wpa_state=//p')
    if [ "$state" != "$last" ]; then
        echo "wifi-on: wpa_state ${state:-unknown}"
        last="$state"
    fi
    [ "$state" = "COMPLETED" ] && break
    pause
    i=$((i + 1))
done
if [ "$state" != "COMPLETED" ]; then
    echo "wifi-on: no saved network joined in 30 s"
    exit 1
fi
echo "wifi-on: joined $(wpa_cli -i "$IFACE" status 2>/dev/null | sed -n 's/^ssid=//p')"

# 3. An address, from a fresh DHCP client. A dhcpcd left over from an earlier session would
#    only be asked to rebind (dhcpcd -n), which often ended in "no address" on the Libra 2,
#    while a fresh one had a lease in about 3 s. dhcpcd waits (-w) for a lease, at most 30 s
#    (-t 30); busybox's udhcpc where there is no dhcpcd.
if pidof dhcpcd >/dev/null 2>&1; then
    echo "wifi-on: stopping a leftover dhcpcd"
    killall -q dhcpcd
    pause
fi
if command -v dhcpcd >/dev/null 2>&1; then
    dhcpcd -d -t 30 -w "$IFACE" || echo "wifi-on: dhcpcd got no lease"
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
