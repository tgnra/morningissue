# Morning Issue

Morning Issue is an Android RSS reader that publishes one personal newspaper a day from your feeds. Each edition is finite: a front page, a section per tag, a column of briefs and a back page, and when you reach the back page you're done for the day. Everything happens on the phone: it fetches the feeds in the background, learns what you care about and prints each edition when you open the app after its release time, which takes a few seconds. All data stays on the phone, and there is nothing to host. On a phone with Gemini Nano, the on-device model first rates the likeliest stories of the edition being printed, which nudges their order; it runs on the phone too, and can be switched off in Settings.

The app is in early development and not everything in the design is built yet. [How it works](docs/how-it-works.md) describes what is built; [todo.md](todo.md) lists what is left.

## Coming back from a server

The 0.5 releases were the client of a self-hosted Morning Issue server, with a Kobo e-reader that synced with it. Both are gone: from 0.6 the app is local again, the server is no longer developed, and the Kobo reader is deprecated (it has no sync to talk to).

Installing 0.6 over the server client clears what the phone held for the server, and onboarding then offers to bring your library back. To do that:

1. Update the server to the last server build, commit `c7db777` (the export is not in 0.5.3 or earlier: check it out and rebuild the image or `./gradlew -PserverOnly :server:installDist`). Then export your library to an archive, either from the command line with `morningissue-server export <user> library.zip`, or by downloading `GET /api/v1/export` while signed in as that user.
2. Copy the file to the phone.
3. In the app, choose **Import a file** on the onboarding screen, or later **Settings › Archive › Import a file**.

The archive carries your feeds with their tags, sliders and mutes, every stored article with its text and your Likes, saves and reading, every edition with the pages you looked at, and your schedule and edition settings. An import into an empty library takes on those settings too; one into a library already in use merges the rest and keeps the phone's own settings. If you installed 0.5 but never finished moving your library to a server, 0.6 simply keeps the library that was on the phone.

## Building

You need JDK 21 and the Android SDK.

```sh
./gradlew assembleDebug
```

## Releases

Pushing a tag that starts with `v` (for example `v0.6.0`) runs the [release workflow](.github/workflows/release.yml), which builds a signed APK and attaches it to a GitHub release for that tag.

## Licence

[MIT](LICENSE)
