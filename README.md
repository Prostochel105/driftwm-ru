# DriftWM RU

Russian localization and a redesigned settings screen for
[DriftWM for Android](https://github.com/StepaandAlex69/DriftWM-for-Android).

Based on upstream **V5**. Licensed under **GPL-3.0**, same as the original.

> Русская версия описания: [README-RU.md](README-RU.md)

## What this fork adds

- **Full Russian UI.** The language follows the system locale: Russian on a Russian phone,
  English everywhere else.
- **A real settings screen.** Live home-screen preview, six ready-made themes, colour pickers,
  background picture with static/infinite modes, and quick system shortcuts.
- **Status at a glance.** The settings screen shows whether the preview accessibility service is
  enabled and whether the launcher is exempt from battery optimisation, and opens the right system
  page in one tap.
- **Built-in gesture reference.** A Russian help sheet explains tap, double tap, drag, pinch and
  three-finger navigation without leaving the settings screen.
- **Updates that keep your layout.** The repository contains a fixed debug signing key
  (`android/app/drift-ru-debug.keystore`), so every APK built here installs **over** the previous
  one. No uninstall, no lost cards.
- **CI builds.** GitHub Actions builds the APK on every push and uploads it as an artifact.

## Settings screen

| Section | What you can do |
|---|---|
| **Theme** | Preview the home screen and apply one of six themes: Graphite, Midnight, Forest, Warm, Light, Rose |
| **Background** | Colours or your own picture; static wallpaper or an infinite tile attached to the canvas |
| **Interface** | One accent colour that derives cards, dock, buttons, text and the app list |
| **Quick actions** | Accessibility previews (with On/Off status), battery optimisation (with status), diagnostics, app info, gesture reference |
| **About** | Version, source link, license |

## Install

The upstream Drift is signed with a different key, so the first install requires removing it.

1. Set your stock launcher as the home app: **Settings → Apps → Default apps → Home app**.
2. Uninstall the original Drift. This deletes `workspace.json` (the card layout).
3. Install the APK from this repository (allow installation from unknown sources).
4. Set Drift as the home app again.
5. Open **Drift settings → Quick actions** and enable previews and unrestricted battery.

After that, updates install over the old version and keep all data.

## Build

### GitHub Actions

Push to `main`/`master` or run the **build-apk** workflow manually.
The APK appears in the `drift-ru-apk` artifact.

The workflow installs JDK 17, Android SDK 36, NDK r28 and Rust with the
`aarch64-linux-android` target, then runs `./gradlew assembleDebug`.

### Locally (Linux x86_64)

```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk --locked
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.2.13676358
cd android
./gradlew assembleDebug
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

If the Rust core is already built in `android/app/src/main/jniLibs/arm64-v8a/`, skip cargo-ndk:

```bash
./gradlew assembleDebug -PskipRust
```

## Updating from upstream

```bash
./sync-upstream.sh   # fetch + merge upstream/main, lists conflicts if any
git push
```

Or on GitHub: **Sync fork → Update branch**. Actions rebuilds the APK automatically.

Conflicts are usually limited to the files where the translation is wired in:
`L.java`, `SettingsActivity`, `DrawerView`, `CanvasView`, `LauncherActivity`,
`ColorPickerDialog`, `DiagnosticsActivity`.

## Project layout

```
android/app/src/main/java/dev/drift/launcher/
  L.java                 Russian string map + toast translation
  SettingsActivity.java  Redesigned settings UI (themes, preview, shortcuts)
  Appearance.java        Background, colours, palette derivation
  ...
android/app/src/main/res/values-ru/strings.xml   Localized accessibility strings
.github/workflows/build-apk.yml                  CI APK build
README-RU.md                                     Russian documentation
sync-upstream.sh                                 Upstream merge helper
```

## Signing

`android/app/drift-ru-debug.keystore` is a **debug** key, not a secret and not suitable for Google
Play. Keep it: replacing it makes future APKs impossible to install over the current one.
Alias `driftru`, store and key password `driftru`.

## Limitations

- The Rust core still emits some English toasts; the most common ones are translated in `L.toast`.
- System Recents (the gesture/overview screen) belong to the firmware and are not replaced by Drift.
- APKs are built for `arm64-v8a` only.

## Credits

- [DriftWM](https://github.com/malbiruk/driftwm) — the original project.
- [DriftWM for Android](https://github.com/StepaandAlex69/DriftWM-for-Android) — the Android port this fork is based on.
- Russian localization and settings redesign: this fork.
