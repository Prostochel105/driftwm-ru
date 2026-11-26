package dev.drift.launcher;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Launchable apps via the public LauncherApps API: no QUERY_ALL_PACKAGES, works
 * for work profiles, and package events arrive through a callback (no polling).
 * A card/dock key is the flattened component name, plus "#serial" for apps in
 * another user profile.
 */
final class AppRepo {
    static final class Entry {
        final String key;
        final String label;
        final ComponentName component;
        final UserHandle user;
        final LauncherActivityInfo info;

        Entry(String key, String label, ComponentName component, UserHandle user, LauncherActivityInfo info) {
            this.key = key;
            this.label = label;
            this.component = component;
            this.user = user;
            this.info = info;
        }
    }

    interface Listener {
        /**
         * Main thread; the list was replaced and pushed to the core.
         * {@code changedIconPackages} holds packages whose icon version changed.
         */
        void onAppsChanged(Set<String> changedIconPackages);
    }

    private final Context ctx;
    private final LauncherApps launcherApps;
    private final UserManager userManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Workers.single("drift-app-list");
    private volatile Map<String, Entry> entries = new HashMap<>();
    private volatile boolean loaded;
    private final Map<String, Long> iconVersions = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Icon files are named by package update time. Persisting these timestamps lets
     * a cold start hit the disk cache immediately, before the app-list query has
     * even finished: the launcher process is often killed while another app is in
     * front, and re-asking PackageManager for every icon was why they appeared late.
     */
    private final SharedPreferences iconPrefs;
    private Listener listener;
    private boolean refreshQueued;
    private long lastRefreshRequest;
    private final Runnable refreshTask = this::refreshNow;

    private final LauncherApps.Callback callback = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String p, UserHandle u) { scheduleRefresh(); }
        @Override public void onPackageAdded(String p, UserHandle u) { scheduleRefresh(); }
        @Override public void onPackageChanged(String p, UserHandle u) { scheduleRefresh(); }
        @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean replacing) { scheduleRefresh(); }
        @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean replacing) { scheduleRefresh(); }
    };

    AppRepo(Context context) {
        ctx = context.getApplicationContext();
        launcherApps = (LauncherApps) ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        userManager = (UserManager) ctx.getSystemService(Context.USER_SERVICE);
        iconPrefs = ctx.getSharedPreferences("icon_versions", Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : iconPrefs.getAll().entrySet()) {
            if (e.getValue() instanceof Number) {
                iconVersions.put(e.getKey(), ((Number) e.getValue()).longValue());
            }
        }
    }

    void setListener(Listener l) {
        listener = l;
    }

    void start() {
        launcherApps.registerCallback(callback, main);
        scheduleRefresh(0);
    }

    void stop() {
        launcherApps.unregisterCallback(callback);
    }

    /** True once the first complete app-list query has been published. */
    boolean isLoaded() {
        return loaded;
    }

    /**
     * Returning from another app does not change the installed-app list, and the
     * package callbacks below already cover real changes. Re-querying everything
     * on every resume made the home screen feel slow, so do it at most once a minute.
     */
    void refreshIfStale() {
        if (SystemClock.uptimeMillis() - lastRefreshRequest < 60_000L) return;
        scheduleRefresh();
    }

    /** Coalesces bursts (installing an app fires several callbacks). */
    void scheduleRefresh() {
        scheduleRefresh(150);
    }

    private void scheduleRefresh(long delayMs) {
        if (refreshQueued) return;
        refreshQueued = true;
        lastRefreshRequest = SystemClock.uptimeMillis();
        main.postDelayed(refreshTask, delayMs);
    }

    private void refreshNow() {
        refreshQueued = false;
        worker.execute(() -> {
            Map<String, Entry> map = new HashMap<>();
            List<String> keys = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            UserHandle me = Process.myUserHandle();
            try {
                for (UserHandle user : userManager.getUserProfiles()) {
                    boolean mine = user.equals(me);
                    long serial = userManager.getSerialNumberForUser(user);
                    for (LauncherActivityInfo info : launcherApps.getActivityList(null, user)) {
                        ComponentName cn = info.getComponentName();
                        // Hide the launcher itself, but keep its diagnostics screen reachable from Drift.
                        if (cn.getPackageName().equals(ctx.getPackageName())
                            && !cn.getClassName().equals(DiagnosticsActivity.class.getName())) continue;
                        String key = cn.flattenToString() + (mine ? "" : "#" + serial);
                        String label = String.valueOf(info.getLabel());
                        if (!mine) {
                            label = String.valueOf(ctx.getPackageManager().getUserBadgedLabel(label, user));
                        }
                        if (map.put(key, new Entry(key, label, cn, user, info)) == null) {
                            keys.add(key);
                            labels.add(label);
                        }
                    }
                }
            } catch (RuntimeException e) {
                return; // keep the previous list rather than publishing a partial one
            }
            String[] k = keys.toArray(new String[0]);
            String[] l = labels.toArray(new String[0]);
            Set<String> pkgs = new HashSet<>();
            for (Entry en : map.values()) {
                // Our own screens are never preview targets.
                if (!en.component.getPackageName().equals(ctx.getPackageName())) pkgs.add(en.component.getPackageName());
            }
            // Refresh the update time of every installed package. This runs on the
            // worker thread, and the list is re-queried rarely (start, package
            // events, at most once a minute on resume), so the cost is invisible
            // to the user while an updated app is guaranteed to get a fresh icon.
            Map<String, Long> fresh = new HashMap<>();
            for (String pkg : pkgs) {
                long version = queryIconVersion(pkg);
                if (version > 0) fresh.put(pkg, version);
            }
            final Map<String, Long> versions = fresh;
            main.post(() -> {
                ThumbStore.setKnownPackages(pkgs);
                Set<String> changed = rememberIconVersions(versions);
                // Refresh the Entry objects (and their LauncherActivityInfo) whenever
                // the installed set changed OR an icon version changed. A stale
                // LauncherActivityInfo returns the grey default icon after an app
                // update, so keeping it is what made icons turn into grey robots.
                boolean listChanged = !sameApps(entries, map);
                // Always publish fresh Entry objects (with fresh LauncherActivityInfo)
                // after any app-list refresh. A stale LauncherActivityInfo can keep
                // returning the themed/default grey robot long after an app update,
                // even when lastUpdateTime did not change (system/Play apps).
                entries = map;
                if (listChanged) Native.setApps(k, l);
                loaded = true;
                Perf.mark("apps loaded (" + map.size() + ")");
                // Notify even when the list is unchanged: the first query may
                // complete after the cards were already drawn, and their icon
                // requests were deliberately skipped until now.
                if (listener != null) listener.onAppsChanged(changed);
            });
        });
    }

    /**
     * Stores the package update times that name the icon cache files. Returns the
     * packages whose version actually changed, so only their icons are evicted.
     */
    private Set<String> rememberIconVersions(Map<String, Long> versions) {
        Set<String> changed = new HashSet<>();
        SharedPreferences.Editor edit = null;
        for (Map.Entry<String, Long> en : versions.entrySet()) {
            long version = en.getValue();
            if (version <= 0) continue;
            Long old = iconVersions.put(en.getKey(), version);
            if (old == null || old.longValue() != version) {
                changed.add(en.getKey());
                if (edit == null) edit = iconPrefs.edit();
                edit.putLong(en.getKey(), version);
            }
        }
        if (edit != null) edit.apply();
        return changed;
    }

    private static boolean sameApps(Map<String, Entry> a, Map<String, Entry> b) {
        if (a.size() != b.size()) return false;
        for (Map.Entry<String, Entry> e : b.entrySet()) {
            Entry old = a.get(e.getKey());
            if (old == null || !old.label.equals(e.getValue().label)) return false;
        }
        return true;
    }

    Entry get(String key) {
        return entries.get(key);
    }

    /** Whether the app is currently in the launchable-app list. */
    boolean has(String key) {
        return entries.containsKey(key);
    }

    String label(String key) {
        Entry e = entries.get(key);
        return e == null ? key : e.label;
    }

    /** Standard Android launch; false when the app cannot be started. */
    boolean launch(String key) {
        Entry e = entries.get(key);
        if (e == null) return false;
        try {
            if (!launcherApps.isActivityEnabled(e.component, e.user)) return false;
            launcherApps.startMainActivity(e.component, e.user, null, null);
            return true;
        } catch (SecurityException | ActivityNotFoundException | IllegalStateException ex) {
            return false;
        }
    }

    /**
     * Changing value that invalidates a cached icon (app update time), 0 if unknown.
     * Derived from the key itself, so a cold start can find disk icons before the
     * app-list query has published {@link Entry} objects.
     */
    long iconVersion(String key) {
        if (key == null) return 0L;
        String pkg = ThumbStore.packageOf(key);
        Long known = iconVersions.get(pkg);
        if (known != null) return known;
        long version = queryIconVersion(pkg);
        if (version > 0) {
            iconVersions.put(pkg, version);
            iconPrefs.edit().putLong(pkg, version).apply();
        }
        return version;
    }

    /** Known version without touching PackageManager; 0 when it has not been learned yet. */
    long cachedIconVersion(String key) {
        if (key == null) return 0L;
        Long known = iconVersions.get(ThumbStore.packageOf(key));
        return known == null ? 0L : known;
    }

    private long queryIconVersion(String pkg) {
        try {
            return ctx.getPackageManager().getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (Exception ex) {
            return 0L;
        }
    }

    /** Renders the app icon; call off the main thread. */
    Bitmap renderIcon(String key, int sizePx) {
        Entry e = entries.get(key);
        if (e == null) return null;
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        try {
            // Try several icon sources. Some firmwares/themed-icon packs return the
            // generic "robot" from LauncherActivityInfo for certain apps; the raw
            // ApplicationInfo icon is often the real one.
            Drawable[] sources = new Drawable[3];
            sources[0] = e.info.getIcon(0);
            try { sources[1] = pm.getApplicationIcon(e.component.getPackageName()); }
            catch (Exception ignored) {}
            try { sources[2] = e.info.getApplicationInfo().loadIcon(pm); }
            catch (Exception ignored) {}
            Bitmap fallback = renderDefault(pm, sizePx);
            for (Drawable d : sources) {
                if (d == null) continue;
                Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                d.setBounds(0, 0, sizePx, sizePx);
                d.draw(c);
                // Skip the generic default/robot icon — never cache it.
                if (fallback != null && similar(bmp, fallback)) continue;
                return bmp;
            }
            return null; // every source returned the default robot; retry later
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Bitmap renderDefault(android.content.pm.PackageManager pm, int sizePx) {
        try {
            Drawable d = pm.getDefaultActivityIcon();
            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, sizePx, sizePx);
            d.draw(c);
            return bmp;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Coarse RGB comparison: true when two icons are essentially the same image. */
    private static boolean similar(Bitmap a, Bitmap b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        int n = 8;
        long diff = 0;
        for (int gy = 0; gy < n; gy++) {
            for (int gx = 0; gx < n; gx++) {
                int x = gx * a.getWidth() / n + a.getWidth() / (n * 2);
                int y = gy * a.getHeight() / n + a.getHeight() / (n * 2);
                int pa = a.getPixel(x, y);
                int pb = b.getPixel(x, y);
                diff += Math.abs(((pa >> 16) & 0xFF) - ((pb >> 16) & 0xFF));
                diff += Math.abs(((pa >> 8) & 0xFF) - ((pb >> 8) & 0xFF));
                diff += Math.abs((pa & 0xFF) - (pb & 0xFF));
            }
        }
        return diff / (n * n * 3) < 45;
    }
}
