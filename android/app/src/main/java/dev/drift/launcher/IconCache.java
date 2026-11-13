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
        // On a cold start the cards are drawn before the app list is ready.
        // Asking for icons then can only fail, so wait for the first list.
        if (b == null && !repo.isLoaded()) return null;
        // An icon can fail while the app list is still loading, or while a package
        // is being updated. Once the app is back in the list, retry instead of
        // keeping a black placeholder forever.
        if (b == null && failed.contains(key) && repo.has(key)) {
            failed.remove(key);
        }
        if (b == null && !failed.contains(key) && pending.add(key)) {
            worker.execute(() -> {
                Bitmap loaded = loadFromDisk(key);
                if (loaded == null) {
                    loaded = repo.renderIcon(key, sizePx);
                    if (loaded != null) saveToDisk(key, loaded);
                }
                final Bitmap result = loaded;
                main.post(() -> {
                    pending.remove(key);
                    if (result != null) {
                        cache.put(key, result);
                    } else {
                        // Do not retry every frame for genuinely missing apps;
                        // get() clears this once the key appears in the app list.
                        failed.add(key);
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
    }

    /** Forget everything (an app was updated and its icon may differ). */
    void clear() {
        cache.evictAll();
        failed.clear();
    }

    // ---- disk cache (worker threads only) -------------------------------------

    private File fileFor(String key) {
        String name = sha1(key + ':' + sizePx + ':' + repo.iconVersion(key));
        if (name == null) name = Integer.toHexString((key + sizePx).hashCode());
        return new File(dir, name + ".png");
    }

    private Bitmap loadFromDisk(String key) {
        File f = fileFor(key);
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

    private void saveToDisk(String key, Bitmap b) {
        File f = fileFor(key);
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
