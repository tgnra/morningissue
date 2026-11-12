# Morning Issue relay

The relay lets a Kobo sync with the phone when they aren't on the same Wi-Fi. The phone uploads an encrypted package of editions and saved articles; each paired Kobo uploads its encrypted changes and downloads the package; the phone collects the changes and tells the relay to delete what it has finished with. The protocol, relay/1, is described in [`docs/relay-sync.md`](../docs/relay-sync.md).

The relay is a mailbox. It never sees plaintext and holds no key that could decrypt anything: it checks request signatures, stores opaque blobs, and deletes them once used, and in any case after 48 hours. You run it on your own server.

It is one Go binary using only the standard library.

## Building

You need Go 1.24 or later.

```sh
cd relay
go build -trimpath -o relay .
```

To build for another machine, set `GOOS` and `GOARCH`, for example `GOOS=linux GOARCH=arm64 go build -trimpath -o relay .` for a Raspberry Pi. Release builds for linux/amd64 and linux/arm64 are attached to each GitHub release.

Tests:

```sh
go vet ./...
go test -race ./...
```

The tests include the shared test vectors in `docs/relay-sync-fixtures/vectors.json`.

## Running

```sh
./relay -addr :8080 -data /var/lib/morningissue-relay
```

Every flag can also be set by the environment variable in brackets.

| Flag | Default | What |
|---|---|---|
| `-addr` [`RELAY_ADDR`] | `:8080` | Plain HTTP listen address. Empty turns it off. |
| `-tls-addr` [`RELAY_TLS_ADDR`] | none | HTTPS listen address, if the relay serves TLS itself. |
| `-tls-cert`, `-tls-key` [`RELAY_TLS_CERT`, `RELAY_TLS_KEY`] | none | PEM certificate and key for `-tls-addr`. |
| `-data` [`RELAY_DATA`] | `data` | Data directory. Created if missing. |
| `-registration-token` [`RELAY_REGISTRATION_TOKEN`] | none | When set, a new group can only be created with this token (the phone sends it as `X-Relay-Registration`). Set one on any relay reachable from the internet, or anyone can use it. |
| `-ttl` [`RELAY_TTL`] | `48h` | How long packages and changes are kept. |
| `-max-package` [`RELAY_MAX_PACKAGE`] | `512MiB` | Largest package accepted, in bytes or with `KiB`, `MiB` or `GiB`. |
| `-max-part` [`RELAY_MAX_PART`] | `64MiB` | Largest part of a package uploaded in parts. The phone sends 32 MiB parts, so behind a proxy with a request size limit (Cloudflare's 100 MB) keep this under that limit. |
| `-base-path` [`RELAY_BASE_PATH`] | none | Serve the API under a path prefix such as `/morningissue`, for a relay that shares a site with other things. Everything outside it gets `404`. See [Under a path prefix](#under-a-path-prefix). |
| `-quiet` [`RELAY_QUIET`] | off | Don't log each request. Errors are still logged. |

Prefer the environment variable for the token, so it doesn't show in the process list.

The relay logs to standard error. `SIGINT` or `SIGTERM` stops it gracefully: it stops accepting connections and waits up to 30 seconds for requests in progress.

`GET /v1/health` answers `{"ok": true, "protocol": "relay/1"}` without a signature, for monitoring.

### HTTP and HTTPS

The phone always uses HTTPS, because registering a group and sending the device list carry keys. Kobos can use plain HTTP: nothing secret crosses the network, and an eavesdropper sees only encrypted blobs, their sizes and timing.

So the relay needs to be reachable both ways. Either put it behind a reverse proxy that serves HTTPS and plain HTTP (below), or let it serve both itself with `-addr` and `-tls-addr`.

The server's clock must be right. Requests are refused when their timestamp is more than 5 minutes from the relay's clock, so run NTP (systemd-timesyncd or chrony). Kobos correct for their own clocks from the relay's `Date` header.

## What it stores

```
<data>/tmp/                       uploads being received
<data>/replay                     signatures seen in the last 10 minutes
<data>/groups/<group>/group.json  devices, the current package, pending changes
<data>/groups/<group>/packages/   package envelopes
<data>/groups/<group>/changes/    changes envelopes
```

A sweeper runs every minute and deletes packages and changes older than the TTL, uploads that were never published after an hour, and groups whose phone hasn't been in touch for 90 days. The phone deletes changes as soon as it has merged them, and the package once every device has it. Deleting means removing the files.

Every signed request is refused if its signature has been seen before. The relay appends each signature to `<data>/replay` before acting on the request, so a request replayed after a restart is still refused; the sweeper rewrites the file without the signatures whose timestamp the clock check would refuse anyway, which keeps it to about ten minutes of requests.

Plan disk space for one package per phone (tens to hundreds of MB) plus a briefly overlapping upload when the phone publishes a new one.

**No backups are needed.** Everything is ephemeral and encrypted with keys the relay never has. If the data directory is lost, the phone registers its group again on its next publish (`PUT /v1/group` after a `401`) and sends the device list; at worst, changes the phone hadn't collected yet are lost. Leave the data directory out of backups.

## Deploying on your own server

These examples assume a Linux server with systemd, the binary at `/usr/local/bin/morningissue-relay`, and the name `relay.example.com` pointing at the server.

### systemd

`/etc/systemd/system/morningissue-relay.service`:

```ini
[Unit]
Description=Morning Issue relay
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/local/bin/morningissue-relay
Environment=RELAY_ADDR=127.0.0.1:8080
Environment=RELAY_DATA=/var/lib/morningissue-relay
EnvironmentFile=-/etc/morningissue-relay.env
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
```

`/etc/morningissue-relay.env`, readable only by root (`chmod 600`):

```sh
RELAY_REGISTRATION_TOKEN=<a long random string, e.g. from: openssl rand -base64 24>
```

Then:

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now morningissue-relay
journalctl -u morningissue-relay -f
```

### Caddy

Caddy gets a certificate for the HTTPS site by itself. The explicit `http://` site serves the same relay over plain HTTP for Kobos, and stops Caddy redirecting that name to HTTPS.

```caddyfile
relay.example.com {
	reverse_proxy 127.0.0.1:8080
}

http://relay.example.com {
	reverse_proxy 127.0.0.1:8080
}
```

On the phone the address is `https://relay.example.com`; on the Kobo, `http://relay.example.com`.

### nginx

If you use nginx instead (with a certificate from certbot or similar):

```nginx
server {
    listen 80;
    listen 443 ssl;
    server_name relay.example.com;

    ssl_certificate     /etc/letsencrypt/live/relay.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/relay.example.com/privkey.pem;

    client_max_body_size 600m;       # above the relay's package limit
    proxy_request_buffering off;     # stream uploads to the relay
    proxy_buffering off;             # stream downloads to the Kobo
    proxy_read_timeout 300s;
    proxy_send_timeout 300s;

    location / {
        proxy_pass http://127.0.0.1:8080;   # no path after the address
    }
}
```

Whatever the proxy, it must pass the path and query through exactly as sent, since they are part of what is signed. `proxy_pass` with no path after the address, and Caddy's `reverse_proxy`, both do. Don't redirect plain HTTP to HTTPS for this name.

### Under a path prefix

To serve the relay from a path on a site that has other things on it, such as `https://example.com/morningissue`, start it with `-base-path /morningissue` (or `RELAY_BASE_PATH=/morningissue`) and give the phone and the Kobo that address, path included. Both sign the full path they send, prefix and all, and the relay checks the signature over that full path and routes on what follows the prefix.

So the proxy must pass the path on with the prefix still in it. In Caddy that is `handle` (not `handle_path`, which strips it):

```caddyfile
example.com {
	handle /morningissue/* {
		reverse_proxy 127.0.0.1:8080
	}
	# … the rest of the site
}
```

In nginx, a `location` with no path after the address in `proxy_pass`:

```nginx
location /morningissue/ {
    proxy_pass http://127.0.0.1:8080;   # not http://127.0.0.1:8080/
}
```

A proxy that strips the prefix makes every signed request fail with `401 "signature"`. The health check is then at `/morningissue/v1/health`.
