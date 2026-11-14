package dev.drift.launcher;

/** JNI surface of the Rust core (libdrift_core.so). All workspace logic lives there. */
final class Native {
    private Native() {}

    static {
        System.loadLibrary("drift_core");
    }

    /** Loads saved state. 0 = none yet, 1 = main file, 2 = backup, 3 = unreadable (reset), -1 = failed. */
    static native int init(String statePath);

    static native void setViewport(int w, int h, float density, int left, int top, int right, int bottom);

    static native void setApps(String[] keys, String[] labels);

    /** kind: 0 down, 1 move, 2 up, 3 cancel. Coordinates in raw pixels. */
    static native void touch(int kind, int id, float x, float y, long timeMs);

    /** Advances animations; true while another frame is needed. */
    static native boolean tick(long nowMs);

    static native void resetClock();

    /** Fills dst with the display list; returns its length (nothing copied when it exceeds dst.length). */
    static native int render(float[] dst);

    static native int layoutVersion();

    static native String stringTable();

    /** Next pending event ("kind\tfield..."), or null. */
    static native String pollEvent();

    static native String[] searchApps(String query);

    static native void drawerPick(String key);

    static native void drawerClosed();

    static native void dockRemove(int slot);

    static native void dockReplace(int slot);

    /** Dock slot count from settings (3..8). */
    static native void setDockCount(int count);

    /** Current dock slot count. */
    static native int dockCount();

    /** Turn the app in a dock slot into a folder with the given (localized) name. */
    static native void dockCreateFolder(int slot, String name);

    static native void dockRenameFolder(int slot, String name);

    /** Remove one app from a dock folder. */
    static native void dockRemoveFolderApp(int slot, String key);

    /** App keys inside a dock folder, in order. */
    static native String[] folderApps(int slot);

    static native String folderName(int slot);

    static native void onHome();

    /** Returning to the launcher: snap back to the view the user had before launching an app. */
    static native void restoreReturnCamera();

    /** The app could not be launched: forget the pending return view. */
    static native void cancelReturnCamera();

    /** Fit every card on screen after the camera was panned into empty space. */
    static native void returnHome();

    /** True when something was dismissed (a card selection). */
    static native boolean onBack();

    static native boolean save();
}
