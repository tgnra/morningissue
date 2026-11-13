# Running the Morning Issue server

The server fetches your feeds, builds each reader's editions on schedule and serves them to the
Android app and to Kobos. One server holds a handful of accounts. This guide covers installing it,
configuring it, adding people and looking after it. The design is in `docs/server-design.md`.

It speaks plain HTTP on port 8417. Put a TLS reverse proxy in front of it: phones and Kobos send
bearer tokens with every request.

## Install

### Docker

Build the image from the repository root, or use a published one
(`ghcr.io/<owner>/morningissue-server:<version>`):

```sh
docker build -f server/Dockerfile -t morningissue-server .
docker run -d --name morningissue -p 127.0.0.1:8417:8417 \
  -v morningissue-data:/data \
  -e MORNINGISSUE_USERS=tania,sam \
  -e MORNINGISSUE_PUBLIC_URL=https://news.example.org \
  morningissue-server
```

`server/docker-compose.yml` is the same as a compose file. The container runs as user 10001,
keeps everything in `/data`, and needs no config file: the environment variables below are
enough. The image is glibc-based (Temurin), because the bundled SQLite library needs glibc.

### NixOS

The flake has a package, an overlay and a module:

```nix
{
  inputs.morningissue.url = "github:tgnra/morningissue";

  outputs = { nixpkgs, morningissue, ... }: {
    nixosConfigurations.myhost = nixpkgs.lib.nixosSystem {
      modules = [
        morningissue.nixosModules.default
        {
          services.morningissue = {
            enable = true;
            users = [ "tania" "sam" ];
            publicUrl = "https://news.example.org";
            # listenAddress = "127.0.0.1"; port = 8417; openFirewall = false;
            # settings = { refresh.intervalMinutes = 60; };   # any config.toml key
            # jvmOptions = [ "-Xmx512m" ];
          };
        }
      ];
    };
  };
}
```

The service runs with `DynamicUser`, keeps its data in `/var/lib/morningissue`, and reads the
generated `/etc/morningissue/config.toml`. `nix build .#morningissue-server` builds the package
alone; `nix build .#docker` builds a container image without Docker.

### From source

```sh
./gradlew -PserverOnly :server:installDist
server/build/install/morningissue-server/bin/morningissue-server --config config.toml serve
```

`-PserverOnly` leaves out the Android modules, so no Android SDK is needed. It needs JDK 21.

## Configuration

The config file is `--config <file>`, else `$MORNINGISSUE_CONFIG`, else
`/etc/morningissue/config.toml`. The last is optional: without it the defaults apply. Every key
is optional.

```toml
[server]
listen = "0.0.0.0"
port = 8417
publicUrl = "https://news.example.org"   # shown to Kobos (/kobo/config.json) and in logs
dataDir = "/var/lib/morningissue"

[users]
names = ["tania", "sam"]                 # removing a name disables the account; data is kept

[refresh]
intervalMinutes = 120                    # the periodic refresh of every subscribed feed
prePrintMaxAgeMinutes = 120              # before a build, feeds fetched this recently are not fetched again
concurrency = 6                          # feeds fetched at once
timeoutSeconds = 240                     # how long a pre-print refresh may take

[editions]
buildLeadMinutes = 30                    # build this long before each release

[kobo]
imageCacheMb = 1024                      # pictures prepared for Kobos

[import]
maxMb = 4096                             # the largest archive the app may upload
```

These environment variables override the file:

| Variable | Overrides |
|---|---|
| `MORNINGISSUE_USERS` | `users.names`, comma-separated |
| `MORNINGISSUE_PORT` | `server.port` |
| `MORNINGISSUE_LISTEN` | `server.listen` |
| `MORNINGISSUE_PUBLIC_URL` | `server.publicUrl` |
| `MORNINGISSUE_DATA_DIR` | `server.dataDir` |
| `JAVA_OPTS` | options for the JVM, such as `-Xmx512m` |

A user name is letters, digits, `.`, `_` and `-`, at most 64 characters. Unknown keys are logged
and ignored; a value of the wrong type stops the server with a message naming the key.

## Accounts

### Adding someone

Add their name to `users.names` (or `MORNINGISSUE_USERS`, or `services.morningissue.users`) and
restart. On start the server creates the account with a random 20-character password and:

- logs it: `Created user "sam" with password "…". Log in from the app and change it.`;
- writes it to `<dataDir>/initial-passwords/sam.txt`, readable only by the server's user.

Give them the password. In the app they enter the server's address, their name and that password,
and must then choose their own (at least 10 characters). Until they do, the server answers nothing
else for them. The file is deleted once they have.

Where to find the password:

- Docker: `docker logs morningissue`, or `docker exec morningissue cat /data/initial-passwords/sam.txt`.
- NixOS: `journalctl -u morningissue`, or `sudo cat /var/lib/morningissue/initial-passwords/sam.txt`.

### Removing someone

Take the name out of the list and restart. The account is disabled: their devices are refused
and they cannot log in. Their data stays; putting the name back re-enables the account as it was.

### A forgotten password

```sh
morningissue-server --config /etc/morningissue/config.toml reset-password sam
```

It prints a new generated password, writes it to `initial-passwords/sam.txt`, and signs out
every phone of that account (Kobos stay paired). Sam logs in with it and chooses a new one. It
can run while the server is running.

- Docker: `docker exec morningissue /opt/morningissue/bin/morningissue-server reset-password sam`.
- NixOS: run it as the service's dynamic user, so the files it touches keep their owner:

  ```sh
  bin=$(systemctl show -P ExecStart morningissue | grep -o '/nix/store/[^ ]*/bin/morningissue-server' | head -1)
  sudo systemd-run --pipe --wait -p DynamicUser=yes -p User=morningissue -p StateDirectory=morningissue \
    -p PrivateTmp=yes -E JAVA_OPTS=-Djava.io.tmpdir=/tmp \
    "$bin" --config /etc/morningissue/config.toml reset-password sam
  ```

Five wrong passwords for a name within 15 minutes lock that name out of logging in for the rest
of the 15 minutes.

### Importing an archive by hand

The app uploads its library when someone first logs in. An archive exported from the app can
also be imported on the server, into an empty account:

```sh
morningissue-server import sam morning-issue-archive.zip           # an empty account only
morningissue-server import --merge sam morning-issue-archive.zip   # merge into one in use
```

## Kobos

A Kobo pairs through the phone: the Kobo shows a QR code, the phone scans it and sends it to the
server. The Kobo also needs the server's address, which it cannot get from the code. Either:

- copy `https://news.example.org/api/v1/kobo/config.json` (it is `{"server":"<publicUrl>"}`)
  to the Kobo's Morning Issue folder as `config.json` over USB; or
- type the address on the Kobo in Settings.

Set `publicUrl` to the address the Kobo reaches the server at. A Kobo removed from the phone's
device list says so the next time it syncs, and can be paired again.

## Reverse proxy

TLS belongs to the proxy. Keep the server listening on `127.0.0.1` and proxy to it. Uploads can be
large (the app's archive), so allow big request bodies on `/api/v1/import`.

Caddy:

```
news.example.org {
    reverse_proxy 127.0.0.1:8417
    request_body /api/v1/import {
        max_size 4GB
    }
}
```

nginx:

```nginx
server {
    listen 443 ssl;
    server_name news.example.org;
    # ssl_certificate ...; ssl_certificate_key ...;

    client_max_body_size 8m;

    location /api/v1/import {
        client_max_body_size 4g;
        proxy_request_buffering off;
        proxy_read_timeout 600s;
        proxy_pass http://127.0.0.1:8417;
    }

    location / {
        proxy_pass http://127.0.0.1:8417;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

On NixOS, `services.caddy.virtualHosts."news.example.org".extraConfig = "reverse_proxy 127.0.0.1:8417";`
does the same.

## The data directory

```
morningissue.db (+ -wal, -shm)   every account's feeds, posts, editions and devices
initial-passwords/               generated passwords not yet changed
cache/catalogue.db               the discovery catalogue, unpacked
kobo-images/                     pictures prepared for Kobos (an LRU cache)
imports/                         archives being imported
tmp/                             temporary files, including the SQLite native library (on NixOS the service uses its private /tmp instead)
```

### Backups

Back up `morningissue.db` with SQLite's own backup, which is safe while the server runs:

```sh
sqlite3 /var/lib/morningissue/morningissue.db ".backup '/backups/morningissue-$(date +%F).db'"
```

Or stop the server and copy the directory. `cache/`, `kobo-images/`, `imports/` and `tmp/` need
no backup. To restore, stop the server, put the file back as `morningissue.db` (deleting any
`-wal` and `-shm` beside it) and start it.

A database restored from an older backup, or a fresh one, tells phones and Kobos so through its
`dbId`; they then fetch everything again.

## Checking on it

- `GET /api/v1/health` answers `{"ok":true,"version":"…","api":1}` without logging in. Use it for
  monitoring.
- Logs go to standard error: requests, refreshes, builds and errors.
- `morningissue-server --version` prints the version.

## Command line

```
morningissue-server [--config <file>] serve                               run the server (the default)
morningissue-server [--config <file>] reset-password <user>               new generated password, phones signed out
morningissue-server [--config <file>] import [--merge] <user> <file.zip>  import an app archive
```
