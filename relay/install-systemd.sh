#!/bin/sh
# Installs the Morning Issue relay as a systemd service, listening on 127.0.0.1 only, for a
# reverse proxy or a Cloudflare tunnel in front of it. Run as root from the directory holding
# the `morningissue-relay` binary:
#
#   sudo sh install-systemd.sh
#
# Running it again updates the binary and the unit and restarts the service. The
# registration token in /etc/morningissue-relay.env is set once and then kept: from
# RELAY_REGISTRATION_TOKEN in this script's environment, or a `morningissue-relay.env` file
# next to it holding that line (the token built into the app), or else a new random one.
#
# RELAY_PORT (default 8737) and RELAY_MAX_PACKAGE (default 95MiB, under Cloudflare's 100 MB
# request limit) can be set in the environment of this script.
set -eu

PORT="${RELAY_PORT:-8737}"
MAX_PACKAGE="${RELAY_MAX_PACKAGE:-95MiB}"
HERE=$(cd "$(dirname "$0")" && pwd)
BIN=/usr/local/bin/morningissue-relay
UNIT=/etc/systemd/system/morningissue-relay.service
ENV=/etc/morningissue-relay.env

if [ "$(id -u)" != 0 ]; then
    echo "Run as root: sudo sh $0" >&2
    exit 1
fi
if [ ! -f "$HERE/morningissue-relay" ]; then
    echo "No morningissue-relay binary next to this script." >&2
    exit 1
fi

install -m 0755 "$HERE/morningissue-relay" "$BIN.new"
mv -f "$BIN.new" "$BIN"

cat >"$UNIT" <<EOF
[Unit]
Description=Morning Issue relay
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=$BIN
Environment=RELAY_ADDR=127.0.0.1:$PORT
Environment=RELAY_DATA=/var/lib/morningissue-relay
Environment=RELAY_MAX_PACKAGE=$MAX_PACKAGE
EnvironmentFile=-$ENV
DynamicUser=yes
StateDirectory=morningissue-relay
StateDirectoryMode=0700
Restart=on-failure
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
PrivateDevices=yes

[Install]
WantedBy=multi-user.target
EOF

new_token=""
if [ ! -f "$ENV" ] || ! grep -q '^RELAY_REGISTRATION_TOKEN=.' "$ENV"; then
    new_token="${RELAY_REGISTRATION_TOKEN:-}"
    if [ -z "$new_token" ] && [ -f "$HERE/morningissue-relay.env" ]; then
        new_token=$(sed -n 's/^RELAY_REGISTRATION_TOKEN=//p' "$HERE/morningissue-relay.env" | head -n 1)
    fi
    if [ -z "$new_token" ]; then
        new_token=$(head -c 24 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')
    fi
    umask 077
    printf 'RELAY_REGISTRATION_TOKEN=%s\n' "$new_token" >>"$ENV"
fi
chmod 600 "$ENV"

# The relay refuses requests more than 5 minutes off its clock.
if command -v timedatectl >/dev/null 2>&1 && [ "$(timedatectl show -p NTPSynchronized --value 2>/dev/null)" != yes ]; then
    echo "Warning: the clock is not NTP-synchronised; the relay needs the right time." >&2
fi

systemctl daemon-reload
systemctl enable morningissue-relay >/dev/null 2>&1
systemctl restart morningissue-relay

ok=""
i=0
while [ $i -lt 20 ]; do
    if out=$(curl -fsS "http://127.0.0.1:$PORT/v1/health" 2>/dev/null); then
        ok=1
        break
    fi
    sleep 0.5
    i=$((i + 1))
done
if [ -z "$ok" ]; then
    echo "The relay did not answer on 127.0.0.1:$PORT. Look at: journalctl -u morningissue-relay" >&2
    exit 1
fi
echo "Relay running on 127.0.0.1:$PORT: $out"
if [ -n "$new_token" ]; then
    echo "Registration token (the phone needs it; it is kept in $ENV):"
    echo "  $new_token"
else
    echo "Registration token kept in $ENV (sudo cat $ENV to see it)."
fi
