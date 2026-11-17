# Reddit announcement — DriftWM RU V5.6

Suggested subreddits: r/androidapps, r/Android, r/fossdroid.
Suggested title: **I made a Russian fork of a launcher with an infinite canvas and cards instead of icons — folders in the dock now work properly**

---

Hey r/androidapps!

I've been maintaining a Russian fork of **DriftWM for Android** — a launcher where
your home screen is an infinite canvas with app *cards* instead of a rigid icon
grid. Today I'm releasing **V5.6**.

**What it is**

- An endless canvas: pan and zoom freely, group cards together, resize them.
- A dock with 3–8 slots, now with **real folders**.
- A settings screen with a live preview, six themes, background pictures
  (static or infinite tile), and an accent colour that recolours the whole UI.
- Full Russian UI that follows your system language (English falls back naturally).
- Rust core over JNI for the workspace model, gestures, snapping and persistence.

**New in V5.6**

- **Empty folders in the dock.** Long-press an empty slot to create one; it opens
  right away with a free "+" slot.
- **Drag icons inside a folder.** Long-press an app to highlight it and open its
  menu; start moving and the icon follows your finger. Drop it on another tile to
  reorder, or on the dimmed area to take it out.
- **Big folders scroll**, removing a folder's last app leaves the folder with a
  free slot, and shrinking the dock asks for confirmation instead of silently
  dropping your apps.
- Release build, `versionCode` starting at 101, state format v2 (old layouts still
  load).

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
