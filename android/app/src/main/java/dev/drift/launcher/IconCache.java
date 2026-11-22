package dev.drift.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Icons are decoded off the main thread; until one arrives callers draw a
 * placeholder. Decoded icons are also kept as small PNGs on disk so a cold start
 * (the launcher process is often killed while another app is in front) does not
 * have to ask the package manager to rasterise every icon again.
 */
final class IconCache {
    interface Listener {
        void onIconsLoaded();
    }

    private static final int MAX_DISK_FILES = 400;
    private static final int KEEP_DISK_FILES = 300;

    private final AppRepo repo;
    private final int sizePx;
    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Workers.fixed("drift-icons", 2);
    private final Set<String> pending = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    /** Keys whose disk icon was missing before the app list arrived; retried on resume. */
    private final Set<String> deferred = new HashSet<>();
    private final AtomicInteger diskWrites = new AtomicInteger();
    private boolean notifyQueued;
    private Listener listener;

    private final LruCache<String, Bitmap> cache = new LruCache<>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    IconCache(AppRepo repo, float density, File cacheDir) {
        this.repo = repo;
        this.sizePx = Math.max(48, Math.round(64f * density));
        this.dir = new File(cacheDir, "icons");
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Main thread only. Returns null while the icon is loading. */
    Bitmap get(String key) {
        if (key == null) return null;
        Bitmap b = cache.get(key);
        // A deferred key is retried as soon as its version becomes known; do not
        // keep showing a placeholder while PackageManager can already render it.
        if (b == null && deferred.contains(key) && repo.cachedIconVersion(key) <= 0) return null;
        // On a cold start the cards are drawn before the app list is ready. If a
        // previous run persisted the package's update time, the disk file name is
        // already known and the icon can load in parallel with the list query.
        if (b == null && !repo.isLoaded() && repo.cachedIconVersion(key) == 0) return null;
        // An icon can fail while the app list is still loading, or while a package
        // is being updated. Once the app is back in the list, retry instead of
        // keeping a black placeholder forever.
        if (b == null && failed.contains(key) && repo.has(key)) {
            failed.remove(key);
        }
        if (b == null && !failed.contains(key) && pending.add(key)) {
            deferred.remove(key); // version may have arrived since we deferred
            worker.execute(() -> {
                long version = repo.iconVersion(key);
                Bitmap loaded = loadFromDisk(key, version);
                if (loaded == null && repo.isLoaded()) {
                    loaded = repo.renderIcon(key, sizePx);
                    // Never write under version 0: that file could never be found again.
                    if (loaded != null && version > 0) saveToDisk(key, loaded, version);
                }
                final Bitmap result = loaded;
                final long loadedVersion = version;
                main.post(() -> {
                    pending.remove(key);
                    long current = repo.cachedIconVersion(key);
                    if (result == null) {
                        // Missing before the app list arrived is not a failure: wait
                        // for the list, otherwise every frame would retry the worker.
                        if (!repo.isLoaded() || current <= 0) deferred.add(key);
                        else failed.add(key);
                    } else if (loadedVersion > 0 && current > 0 && current != loadedVersion) {
                        // The app was updated while this icon was loading. Leave the
                        // stale bitmap out; the next draw requests the new version.
                    } else if (loadedVersion <= 0 || current <= 0) {
                        // Version unknown: the rendered drawable may be the grey
                        // default icon (app mid-update). Do not cache it; retry on a
                        // later draw once the version -- and a real icon -- appears.
                        deferred.add(key);
                    } else {
                        cache.put(key, result);
                    }
                    queueNotify();
                });
            });
        }
        return b;
    }

    /** Retry icons of installed apps that failed earlier (app list changed or resumed). */
    void retry() {
        failed.removeIf(repo::has);
        deferred.clear();
    }

    /** Drops cached icons of packages whose update time changed. Main thread only. */
    void invalidate(Set<String> packages) {
        if (packages == null || packages.isEmpty()) return;
        for (String key : cache.snapshot().keySet()) {
            if (packages.contains(ThumbStore.packageOf(key))) cache.remove(key);
        }
        failed.removeIf(key -> packages.contains(ThumbStore.packageOf(key)));
        deferred.removeIf(key -> packages.contains(ThumbStore.packageOf(key)));
    }

    // ---- disk cache (worker threads only) -------------------------------------

    private File fileFor(String key, long version) {
        String name = sha1(key + ':' + sizePx + ':' + version);
        if (name == null) name = Integer.toHexString((key + sizePx).hashCode());
        return new File(dir, name + ".png");
    }

    private Bitmap loadFromDisk(String key, long version) {
        if (version <= 0) return null;
        File f = fileFor(key, version);
        if (!f.isFile()) return null;
        try {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null && b.getWidth() > 0 && b.getHeight() > 0) return b;
        } catch (RuntimeException ignored) {
            // fall through to a fresh render
        }
        f.delete();
        return null;
    }

    private void saveToDisk(String key, Bitmap b, long version) {
        if (version <= 0) return;
        File f = fileFor(key, version);
        File tmp = new File(dir, f.getName() + ".tmp");
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                if (!b.compress(Bitmap.CompressFormat.PNG, 100, out)) return;
            }
            if (!tmp.renameTo(f)) tmp.delete();
            if (diskWrites.incrementAndGet() % 32 == 0) prune();
        } catch (IOException | RuntimeException ignored) {
            tmp.delete();
        }
    }

    private void prune() {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_DISK_FILES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - KEEP_DISK_FILES; i++) files[i].delete();
    }

    private static String sha1(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] bytes = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte x : bytes) sb.append(Character.forDigit((x >> 4) & 0xF, 16))
                .append(Character.forDigit(x & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void queueNotify() {
        if (notifyQueued) return;
        notifyQueued = true;
        main.post(() -> {
            notifyQueued = false;
            if (listener != null) listener.onIconsLoaded();
        });
    }
}
