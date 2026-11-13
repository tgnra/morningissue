# Morning Issue

Morning Issue is an RSS reader that publishes one personal newspaper a day from your feeds. Each edition is finite: a front page, a section per tag, a column of briefs and a back page, and when you reach the back page you're done for the day.

It runs on a server you host yourself, for you and a handful of others. The server fetches the feeds, learns what each reader cares about and builds each reader's edition before their morning release, in their own time zone. The Android app logs in to the server, keeps the latest editions for reading offline and sends back what you read. A Kobo e-reader can sync with the same server directly, once the phone has scanned its pairing code.

The project is in early development and not everything in the design is built yet. [How it works](docs/how-it-works.md) describes what is built; [todo.md](todo.md) lists what is left.

## Self-hosting

With Docker, from the repository root:

```sh
docker build -f server/Dockerfile -t morningissue-server .
docker run -d --name morningissue -p 127.0.0.1:8417:8417 -v morningissue-data:/data \
  -e MORNINGISSUE_USERS=tania -e MORNINGISSUE_PUBLIC_URL=https://news.example.org \
  morningissue-server
```

On NixOS, add the flake's module and set `services.morningissue = { enable = true; users = [ "tania" ]; publicUrl = "https://news.example.org"; };`.

Each name in the user list gets an account with a generated password, which the server logs and writes to `initial-passwords/<name>.txt` in its data directory (`docker logs morningissue`, or `journalctl -u morningissue`). In the app, enter the server's address, the name and that password, then choose your own. Put a TLS reverse proxy in front of the server: phones and Kobos send bearer tokens with every request.

Configuration, Kobos, backups and the command line are in [Running the Morning Issue server](docs/server.md).

## Building

You need JDK 21, and for the app the Android SDK.

```sh
./gradlew assembleDebug                          # the app
./gradlew -PserverOnly :server:installDist       # the server, without the Android SDK
(cd kobo && cargo build)                         # the Kobo reader; kobo/build.sh makes the device zip
```

## Releases

Pushing a tag that starts with `v` (for example `v0.1.0`) runs the [release workflow](.github/workflows/release.yml), which builds a signed APK and the Kobo zip and attaches them to a GitHub release for that tag, and the [server image workflow](.github/workflows/server-image.yml), which pushes the server's image to `ghcr.io`.

## Licence

[MIT](LICENSE)
