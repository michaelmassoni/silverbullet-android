# Building SilverBullet for Android

## Requirements

- JDK 17 and the Android SDK (platform 35, build-tools 35.0.0). Android Studio works.
- Or use Nix: `nix develop` gives you JDK 17, the SDK, an emulator, and a `silverbullet` server
  for testing.

## Build and install

```sh
nix develop                 # optional, see above
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds enable WebView remote debugging: open `chrome://inspect` in desktop Chrome with the
phone connected over USB to inspect the SilverBullet page inside the app.

## Testing against a local server

```sh
SB_USER=test:test123 silverbullet --single -L 127.0.0.1 -p 3999 /tmp/sbspace &
make-avd && run-emulator &      # from the Nix shell; any emulator or phone works
adb reverse tcp:3999 tcp:3999   # the device's "localhost" is a secure origin, so offline mode works
```

Then enter `http://localhost:3999` on the app's setup screen. Leave out `SB_USER` for a server
without a login.

## How it fits together

| Part | Where |
|------|-------|
| WebView host, insets, theme, back handling | `MainActivity.kt` |
| Script injected into the SilverBullet page (keyboard height, bottom bar actions, theme reporting, sidebar and Back behaviour) | `app/src/main/assets/bridge.js` |
| Bottom bar entries and their settings | `BottomBar.kt` |
| Quick capture dialog, offline queue, sync job, `/.fs` client | `CaptureActivity.kt`, `CaptureQueue.kt`, `CaptureWorker.kt`, `SbClient.kt` |
| First-run screen, settings | `SetupActivity.kt`, `SettingsActivity.kt` |

`bridge.js` talks to SilverBullet's client (`window.client`), which is not a public API. After a
SilverBullet upgrade, re-test the bottom bar buttons, Back, the sidebar and the keyboard bar. Check
the script's syntax before building with `node --check app/src/main/assets/bridge.js`; a syntax error
silently disables all of it.

Never send synthetic key events to the page: SilverBullet treats a key press with no on-screen
keyboard as a hardware keyboard and then keeps its formatting bar docked permanently.

## Releases

Pushing a tag like `v1.2.3` runs `.github/workflows/release.yml`. It builds a signed APK, with the
version taken from the tag (versionCode `1*10000 + 2*100 + 3`), and attaches it to a GitHub
release, which Obtainium picks up.

The workflow needs these repository secrets: `SB_KEYSTORE_B64` (the release keystore,
base64-encoded), `SB_KEYSTORE_PASSWORD`, `SB_KEY_ALIAS` and `SB_KEY_PASSWORD`. Without those
variables, a local `./gradlew assembleRelease` is signed with the debug key.

Every update must be signed with the same key, or Android refuses to install it over the existing
app. Keep the keystore backed up.
