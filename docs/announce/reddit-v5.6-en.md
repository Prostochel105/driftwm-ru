# Reddit announcement — DriftWM RU V5.6.1

Suggested subreddits: r/androidapps, r/Android, r/fossdroid.
Suggested title: **I made a Russian fork of a launcher with an infinite canvas and cards instead of icons — it's smooth as butter now**

---

Hey r/androidapps!

I've been maintaining a Russian fork of **DriftWM for Android** — a launcher where
your home screen is an infinite canvas with app *cards* instead of a rigid icon
grid. I'm releasing **V5.6.1**, a performance and animation update on top of V5.6.

**What it is**

- An endless canvas: pan and zoom freely, group cards together, resize them.
- A dock with 3–8 slots, with real folders you can drag icons around in.
- A settings screen with a live preview, six themes, background pictures
  (static or infinite tile), and an accent colour that recolours the whole UI.
- Full Russian UI that follows your system language (English falls back naturally).
- Rust core over JNI for the workspace model, gestures, snapping and persistence.

**New in V5.6.1**

- **Butter-smooth panning.** Removed the per-card blur shadows and thousands of
  per-frame point draws that capped the canvas at ~30 fps. Touch redraws are now
  synced to the display frame, so several move events collapse into one frame.
- **Satisfying drag animations.** A held card lifts and scales slightly, then
  glides into place when released (no spring, no bounce). Inside folders, an
  accent divider shows the insertion slot and icons swap places *live* while you
  drag, easing into their new positions.
- **Icons are there when you get home.** Disk-cache versions survive restarts, so
  a cold start loads them immediately instead of waiting on PackageManager.
- All core toasts translated to Russian; clippy warning cleaned up.

**Download**

https://github.com/Prostochel105/driftwm-ru/releases/latest

Requirements: Android 11+ (API 30), arm64-v8a only. The APK is signed with a
committed debug key (same key for every build, so updates install over each other
without losing your layout). Because that key is public, **only install from the
repository's Releases page**. If you're coming from the original Drift, you'll
need to uninstall it first — it uses a different signature.

Licensed under GPL-3.0, same as upstream:
https://github.com/StepaandAlex69/DriftWM-for-Android

Happy to answer questions about the gestures or the Rust/JNI architecture!
