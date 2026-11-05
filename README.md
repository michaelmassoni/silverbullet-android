# SilverBullet for Android (unofficial)

An Android app for any self-hosted [SilverBullet](https://silverbullet.md) v2 server, with
**quick capture** that works offline.

This is a community project. It isn't made by or affiliated with the SilverBullet project.

## Install

Get the APK from [Releases](../../releases). To get updates automatically, add this repo's URL in
[Obtainium](https://github.com/ImranR98/Obtainium).

Signing certificate SHA-256 (check it before trusting an APK):
`6F:B7:35:A5:2C:8F:B4:EC:A7:DE:16:23:BC:0D:77:04:EB:D4:3E:64:C9:72:D3:A5:A9:A2:89:A7:F0:1F:29:6B`

## Features

- **First run:** enter your server URL. The app checks the server is reachable, then shows your
  server's own login page, so `SB_USER`, auth proxies and SSO all work as they do in a browser.
  To switch servers later: long-press the app icon → *SilverBullet settings* → *Change*.
- **Works offline.** SilverBullet's service worker keeps a local copy of your pages and syncs it
  with the server. The app's storage isn't cleared when the phone runs low on space, which a
  browser can do to a PWA. Pressing back on your last page sends the app to the background
  instead of closing it, so it reopens instantly. External links open in your browser, and file
  upload works.
- **Bottom action bar** within thumb reach. Default: sidebar, back, home page, search (Silversearch
  if installed, otherwise the page picker), today's journal and app settings. Also available: open
  page, quick capture, run command, and read-only mode (the lock lights up while it's on). Every
  entry can be switched on or off in settings. It matches your SilverBullet theme (light/dark),
  steps aside while you type, and SilverBullet's own top-right buttons are hidden by default
  since the bar replaces them (toggle in settings; they always return if the bar is off).
- **Formatting bar above the keyboard.** SilverBullet's own keyboard bar (lists, tasks, indent,
  links, bold, undo and more) doesn't appear in a plain WebView, because Android's WebView always
  reports a keyboard height of 0. The app passes SilverBullet the real height, so the bar works and
  the editor stays above the keyboard. Customise its buttons with `keyboardBar` in your space's
  `CONFIG`.
- **Back closes things first:** an open picker, search, panel or sidebar, then page history, then
  the app.
- **Quick capture:** each line you type becomes a `* ` bullet at the end of a page, by default
  `Journal/{date}` (today's daily note; a new one gets `tags: journal` and `date:` frontmatter).
  You can change the page in settings, e.g. to `Inbox`. You can open quick capture from:
  - the app icon's long-press menu
  - a quick-settings tile
  - a home-screen widget
  - **Share** from any app (a shared title and link become a markdown link)
- **Captures are never lost.** They're saved on the phone first and sent when the server is
  reachable, even if the app isn't open or the phone has restarted. If the page is being edited
  at the same moment, the app re-reads it and tries again instead of overwriting the edit.

## Notes

- **Use HTTPS.** Browsers only run SilverBullet's offline cache on secure origins. Plain-HTTP
  servers (e.g. `http://192.168.1.10:3000`) still work, just without offline mode. Certificates
  from a CA you've installed on the phone (self-signed or private CA) are trusted.
- **Capture sign-in:** quick capture uses your app login, so tick **Remember me** when you log in.
  SilverBullet logins last 7 days; after that, captures stay queued and you get a "Sign in"
  notification. To skip that, set `SB_AUTH_TOKEN` on the server and paste it into settings.
  The token is only used for capture sync.

## Build

```sh
nix develop                     # JDK 17, Android SDK, emulator, silverbullet (or use Android Studio)
./gradlew assembleDebug
```

Releases are built by GitHub Actions when you push a `v*` tag (`.github/workflows/release.yml`).
It needs these repository secrets: `SB_KEYSTORE_B64` (the keystore, base64-encoded),
`SB_KEYSTORE_PASSWORD`, `SB_KEY_ALIAS`, `SB_KEY_PASSWORD`. A local `assembleRelease` without those
variables is signed with the debug key.

### Testing against a local server

```sh
SB_USER=test:test123 silverbullet --single -L 127.0.0.1 -p 3999 /tmp/sbspace &
make-avd && run-emulator &
adb reverse tcp:3999 tcp:3999   # "localhost" is a secure origin, so offline mode works
```

Then enter `http://localhost:3999` on the setup screen.

## License

MIT
