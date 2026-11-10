# Morning Issue

Morning Issue is an Android RSS reader that publishes one personal newspaper a day from your feeds. Each edition is finite: a front page, a section per tag, a column of briefs and a back page, and when you reach the back page you're done for the day. All data stays on the phone.

The app is in early development and not everything in the design is built yet. [How it works](docs/how-it-works.md) describes what is built; [todo.md](todo.md) lists what is left.

## Building

You need JDK 21 and the Android SDK.

```sh
./gradlew assembleDebug
```

## Releases

Pushing a tag that starts with `v` (for example `v0.1.0`) runs the [release workflow](.github/workflows/release.yml), which builds a signed APK and attaches it to a GitHub release for that tag.

## Licence

[MIT](LICENSE)
