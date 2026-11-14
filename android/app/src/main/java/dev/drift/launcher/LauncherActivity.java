package dev.drift.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

/**
 * Home screen. Owns no workspace logic: it forwards touches to the Rust core,
 * draws its display list, and carries out the events the core emits (start an
 * app, open the app list, show the dock menu, save).
 */
public final class LauncherActivity extends Activity implements CanvasView.Host, DrawerView.Callback {
    private static final long SAVE_DEBOUNCE_MS = 400;
    /** Do not offer the way back after a single empty frame; wait 1.5 s. */
    private static final long HOME_PILL_DELAY_MS = 1500;

    private AppRepo repo;
    private IconCache icons;
    private ThumbCache thumbs;
    private CanvasView canvas;
    private DrawerView drawer;
    private FolderView folder;
    private FrameLayout root;
    private TextView homePill;
    private Toast toast;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable updateHomePill = this::updateHomePill;
    private boolean homePillWanted;
    private int visibleCards = -1;
    private int totalCards = -1;

    private HandlerThread saveThread;
    private Handler saveHandler;
    private final Runnable saveTask = Native::save;
    private OnBackInvokedCallback backCallback;

    private boolean ready;

    /** Which mode the app list was opened in, so a folder pick can reopen the folder. */
    private int drawerMode = DrawerView.MODE_NORMAL;
    private int drawerSlot = -1;

    private Appearance appearance = Appearance.defaults();
    private long appearanceRev = -1;
    private int appearanceGen;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Perf.created();
        installCrashRecorder();
        String previous = readCrash();
        if (previous != null) {
            showCrash(previous);
            return;
        }
        try {
            setUp();
            Perf.mark("onCreate done");
            ready = true;
            offerPreviews();
        } catch (Throwable t) {
            String text = describe(Thread.currentThread(), t);
            writeCrash(text);
            showCrash(text);
        }
    }

    private void setUp() {
        Window w = getWindow();
        w.setDecorFitsSystemWindows(false);
        w.setNavigationBarContrastEnforced(false);

        File stateFile = new File(getFilesDir(), "workspace.json");
        Perf.mark("Native.init start");
        int loaded = Native.init(stateFile.getAbsolutePath());
        Perf.mark("Native.init done (" + loaded + ")");
        if (loaded == 2) {
            toast("Workspace restored from backup");
        } else if (loaded == 3) {
            toast("Saved workspace was unreadable; started empty (old file kept)");
        }

        saveThread = new HandlerThread("drift-save", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        saveThread.start();
        saveHandler = new Handler(saveThread.getLooper());

        float density = getResources().getDisplayMetrics().density;
        repo = new AppRepo(this);
        icons = new IconCache(repo, density, getCacheDir());
        thumbs = new ThumbCache(this);
        ThumbStore.setListener(thumbs);
        thumbs.setListener(() -> canvas.invalidate());
        canvas = new CanvasView(this, icons, thumbs);
        drawer = new DrawerView(this, repo, icons);
        folder = new FolderView(this, repo, icons);
        canvas.setHost(this);
        drawer.setCallback(this);
        folder.setCallback(new FolderView.Callback() {
            @Override public void onLaunch(String key) {
                folder.hide();
                launch(key);
            }

            @Override public void onAdd(int slot) {
                // Go through the core: it records the pending "add to folder" pick
                // and emits the drawer event, so the chosen app lands in the folder.
                Native.dockAddApp(slot);
                canvas.invalidate();
            }

            @Override public void onRename(int slot, String currentName) {
                showRenameDialog(slot, currentName);
            }

            @Override public void onClose() {
                updateBars();
                canvas.invalidate();
            }
        });
        icons.setListener(() -> {
            canvas.invalidate();
            drawer.iconsLoaded();
            folder.iconsLoaded();
        });
        repo.setListener(() -> {
            icons.retry();
            drawer.refresh();
            folder.refresh();
            canvas.invalidate();
        });
        // Start the first app-list query before the view hierarchy is attached:
        // it runs on a background thread and lets card icons arrive sooner after
        // a cold start.
        repo.start();

        root = new FrameLayout(this);
        root.addView(canvas, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(drawer, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(folder, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        homePill = new TextView(this);
        homePill.setTextSize(14);
        homePill.setTypeface(Typeface.DEFAULT_BOLD);
        homePill.setGravity(Gravity.CENTER);
        homePill.setPadding(dp(18), dp(10), dp(18), dp(10));
        homePill.setClickable(true);
        homePill.setFocusable(true);
        homePill.setElevation(dp(8));
        homePill.setText(L.t("Back home", "Вернуться домой"));
        homePill.setContentDescription(L.t("Back home", "Вернуться домой"));
        homePill.setVisibility(View.GONE);
        homePill.setOnClickListener(v -> {
            hideHomePill();
            Native.returnHome();
            canvas.invalidate();
        });
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        pillLp.bottomMargin = dp(96);
        root.addView(homePill, pillLp);

        root.setOnApplyWindowInsetsListener((v, wi) -> {
            int bars = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
            Insets sb = wi.getInsets(bars);
            Insets sg = wi.getInsets(WindowInsets.Type.systemGestures());
            Insets ime = wi.getInsets(WindowInsets.Type.ime());
            // Keep the dock out of the back-gesture zones at the sides.
            ThumbStore.cropTop = sb.top;
            ThumbStore.cropBottom = sb.bottom;
            canvas.setInsets(Math.max(sb.left, sg.left), sb.top, Math.max(sb.right, sg.right), sb.bottom);
            drawer.setInsets(sb.left, sb.top, sb.right, Math.max(sb.bottom, ime.bottom));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) homePill.getLayoutParams();
            lp.bottomMargin = sb.bottom + dp(92);
            homePill.setLayoutParams(lp);
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
        Perf.mark("setContentView");
        refreshAppearance();

        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (!ready) return;
        Perf.resumed();
        Native.resetClock();
        // Came back from an app: put the canvas exactly where the user left it.
        Native.restoreReturnCamera();
        refreshAppearance(); // may have changed in Drift settings
        icons.retry();
        repo.refreshIfStale();
        canvas.invalidate();
        Perf.mark("onResume done");
    }

    @Override protected void onPause() {
        // The process can be killed while another app is in front: persist now,
        // but never fsync on the main thread — that made leaving the launcher
        // feel like a freeze on slower storage.
        if (ready) {
            Perf.paused();
            saveHandler.removeCallbacks(saveTask);
            saveHandler.post(saveTask);
        }
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!ready) return;
        // Home pressed while the launcher is already showing.
        if (folder.isShowing()) {
            folder.hide();
        }
        if (drawer.isShowing()) {
            drawer.hide();
        }
        Native.onHome();
        canvas.invalidate();
    }

    @Override protected void onDestroy() {
        uiHandler.removeCallbacks(updateHomePill);
        if (repo != null) repo.stop();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        ThumbStore.setListener(null);
        if (saveThread != null) saveThread.quitSafely();
        super.onDestroy();
    }

    @SuppressLint("GestureBackNavigation") // API 33+ is handled by backCallback in onCreate.
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        // Only reached below API 33 (or without the predictive-back callback).
        handleBack();
    }

    private void handleBack() {
        if (!ready) return;
        if (folder.isShowing()) {
            folder.hide();
        } else if (drawer.isShowing()) {
            drawer.hide();
        } else if (Native.onBack()) {
            canvas.invalidate();
        }
        // A home screen has nothing to go back to: swallow the gesture.
    }

    // ---- events from the core --------------------------------------------------

    @Override public void onCoreEvents(List<String> events) {
        for (String ev : events) {
            String[] p = ev.split("\t", 2);
            String arg = p.length > 1 ? p[1] : "";
            switch (p[0]) {
                case "launch":
                    launch(arg);
                    break;
                case "drawer": {
                    String[] d = arg.split("\t", -1);
                    int mode = d.length > 0 ? parseInt(d[0], DrawerView.MODE_NORMAL) : DrawerView.MODE_NORMAL;
                    int slot = d.length > 1 ? parseInt(d[1], -1) : -1;
                    showDrawer(mode, slot);
                    break;
                }
                case "dockmenu":
                    showDockMenu(parseInt(arg, 0));
                    break;
                case "emptydockmenu":
                    showEmptyDockMenu(parseInt(arg, 0));
                    break;
                case "foldermenu": {
                    String[] f = arg.split("\t", 2);
                    showFolderMenu(parseInt(f[0], 0), f.length > 1 ? f[1] : "");
                    break;
                }
                case "openfolder":
                    showFolder(parseInt(arg, -1));
                    break;
                case "renamefolder": {
                    String[] f = arg.split("\t", 2);
                    showRenameDialog(parseInt(f[0], 0), f.length > 1 ? f[1] : "");
                    break;
                }
                case "toast":
                    toast(arg);
                    break;
                case "haptic":
                    canvas.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    break;
                case "save":
                    saveHandler.removeCallbacks(saveTask);
                    saveHandler.postDelayed(saveTask, SAVE_DEBOUNCE_MS);
                    break;
                default:
                    break;
            }
        }
    }

    @Override public void onCardsVisibilityChanged(int visible, int total) {
        visibleCards = visible;
        totalCards = total;
        // A quick pan or zoom can leave the screen empty for a frame or two;
        // wait before offering the way back so the pill does not flicker.
        uiHandler.removeCallbacks(updateHomePill);
        uiHandler.postDelayed(updateHomePill, HOME_PILL_DELAY_MS);
    }

    private void updateHomePill() {
        if (!ready || homePill == null) return;
        boolean show = totalCards > 0 && visibleCards == 0
            && drawer != null && !drawer.isShowing();
        if (show == homePillWanted) return;
        homePillWanted = show;
        homePill.animate().cancel();
        if (show) {
            homePill.setVisibility(View.VISIBLE);
            homePill.setAlpha(0f);
            homePill.setTranslationY(dp(8));
            homePill.animate().alpha(1f).translationY(0f).setDuration(140).start();
        } else {
            homePill.animate().alpha(0f).translationY(dp(8)).setDuration(100)
                .withEndAction(() -> {
                    if (!homePillWanted) homePill.setVisibility(View.GONE);
                }).start();
        }
    }

    private void hideHomePill() {
        if (homePill == null) return;
        homePillWanted = false;
        uiHandler.removeCallbacks(updateHomePill);
        homePill.animate().cancel();
        homePill.setVisibility(View.GONE);
    }

    private void launch(String key) {
        if (drawer.isShowing()) {
            drawer.hide();
        }
        if (!repo.launch(key)) {
            Native.cancelReturnCamera();
            toast(L.t("Can't open ", "Не удалось открыть ") + repo.label(key));
            repo.scheduleRefresh();
        }
    }

    /** Reloads colours and the background picture when Drift settings changed them. */
    private void refreshAppearance() {
        final long rev = Appearance.rev(this);
        if (rev == appearanceRev) return;
        appearanceRev = rev;
        final int gen = ++appearanceGen; // any load still running for an older state is dropped
        Appearance quick = Appearance.read(this);
        // With a picture on screen, keep it until the new one is decoded instead of flashing plain colours.
        if (quick.source != Appearance.SOURCE_IMAGE || appearance.bitmap == null) applyAppearance(quick);
        if (quick.source != Appearance.SOURCE_IMAGE) return;
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            Appearance full = Appearance.readWithBitmap(app);
            runOnUiThread(() -> {
                if (!isDestroyed() && gen == appearanceGen) applyAppearance(full);
            });
        }, "drift-appearance").start();
    }

    private void applyAppearance(Appearance a) {
        appearance = a;
        canvas.setAppearance(a);
        drawer.setAppearance(a);
        folder.setAppearance(a);
        styleHomePill();
        updateBars();
    }

    private void styleHomePill() {
        if (homePill == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(appearance.pal.pill);
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), appearance.pal.stroke);
        homePill.setBackground(bg);
        homePill.setTextColor(appearance.pal.text);
    }

    /**
     * System-bar icons must stay readable: dark icons over a light background, light icons over a
     * dark one. While the app list is open it is the list's colour that sits behind the bars.
     * Must run after setContentView: before that the window has no decor view (NPE on some Samsung builds).
     */
    private void updateBars() {
        try {
            WindowInsetsController ic = getWindow().getInsetsController();
            if (ic == null) return;
            boolean lightStatus;
            boolean lightNav;
            if ((drawer != null && drawer.isShowing()) || (folder != null && folder.isShowing())) {
                lightStatus = appearance.pal.light;
                lightNav = lightStatus;
            } else {
                lightStatus = appearance.barLum > 0.6f;
                lightNav = (appearance.bitmap != null ? Appearance.lum(appearance.avg) : appearance.barLum) > 0.6f;
            }
            ic.setSystemBarsAppearance(lightStatus ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            ic.setSystemBarsAppearance(lightNav ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } catch (RuntimeException ignored) {
            // cosmetic only
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String tr(String en, String ru) {
        return java.util.Locale.getDefault().getLanguage().equals("ru") ? ru : en;
    }

    /** One-time (up to three times) explanation of the optional preview service. */
    private void offerPreviews() {
        if (ThumbService.isEnabled(this)) return;
        android.content.SharedPreferences prefs = getSharedPreferences("drift", MODE_PRIVATE);
        int asked = prefs.getInt("preview_prompts", 0);
        if (asked >= 3) return;
        prefs.edit().putInt("preview_prompts", asked + 1).apply();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(tr("App previews on cards", "Миниатюры приложений на карточках"))
            .setMessage(tr(
                "Android does not let a launcher see other apps. To show what each app looked like when you left it, "
                    + "Drift needs its optional \"Drift previews\" accessibility service. It only takes a small screenshot "
                    + "about a second after you use an app, cannot read text, passwords or notifications, "
                    + "skips apps that block screenshots, and keeps pictures on this phone.\n\n"
                    + "If the switch is greyed out: App info > menu > Allow restricted settings.",
                "Android не позволяет лаунчеру видеть другие приложения. Чтобы на карточке было видно, как выглядело "
                    + "приложение, когда вы из него вышли, Drift нужна необязательная служба «Drift previews» в "
                    + "специальных возможностях. Она делает небольшой снимок экрана примерно через секунду после "
                    + "ваших действий в приложении, не читает текст, пароли и уведомления, пропускает приложения, "
                    + "которые запрещают скриншоты, и хранит картинки только на этом телефоне.\n\n"
                    + "Если переключатель неактивен: Сведения о приложении > меню > Разрешить ограниченные настройки."))
            .setPositiveButton(tr("Open settings", "Открыть настройки"), (d, i) ->
                startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)))
            .setNeutralButton(tr("App info", "Сведения о приложении"), (d, i) ->
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName()))))
            .setNegativeButton(tr("Not now", "Не сейчас"), null)
            .show();
    }

    private void showDrawer(int mode, int slot) {
        hideHomePill();
        // The folder popup sits above the drawer in the view stack and dims the
        // whole screen; leaving it visible would hide the list being opened.
        if (folder.isShowing()) folder.hide();
        drawerMode = mode;
        drawerSlot = slot;
        drawer.show(mode, slot);
        updateBars();
    }

    private void showFolder(int slot) {
        if (slot < 0) return;
        hideHomePill();
        if (drawer.isShowing()) drawer.hide();
        folder.show(slot);
        updateBars();
    }

    private void showDockMenu(int slot) {
        String[] items = {L.t("Replace app"), L.t("Create folder"), L.t("Remove from dock")};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L.t("Dock slot ", "Слот дока ") + (slot + 1))
            .setItems(items, (d, which) -> {
                if (which == 0) {
                    Native.dockReplace(slot);
                } else if (which == 1) {
                    Native.dockCreateFolder(slot, L.t("Folder"));
                    showFolder(slot); // straight into the new folder to add more apps
                } else {
                    Native.dockRemove(slot);
                }
                canvas.invalidate();
            })
            .show();
    }

    /** Long-press on an empty dock slot: add an app or start a folder there. */
    private void showEmptyDockMenu(int slot) {
        String[] items = {L.t("Add app"), L.t("Create folder")};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L.t("Dock slot ", "Слот дока ") + (slot + 1))
            .setItems(items, (d, which) -> {
                if (which == 0) {
                    Native.dockReplace(slot);
                } else {
                    Native.dockCreateFolder(slot, L.t("Folder"));
                    showFolder(slot); // straight into the new folder to add apps
                }
                canvas.invalidate();
            })
            .show();
    }

    private void showFolderMenu(int slot, String name) {
        String[] items = {L.t("Rename folder"), L.t("Remove from dock")};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(name)
            .setItems(items, (d, which) -> {
                if (which == 0) {
                    showRenameDialog(slot, name);
                } else {
                    Native.dockRemove(slot);
                    canvas.invalidate();
                }
            })
            .show();
    }

    private void showRenameDialog(int slot, String current) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(current == null ? "" : current);
        input.setSelection(input.getText().length());
        int pad = dp(16);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, dp(8), pad, 0);
        wrap.addView(input, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L.t("Folder name"))
            .setView(wrap)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) name = L.t("Folder");
                Native.dockRenameFolder(slot, name);
                canvas.invalidate();
                folder.refresh();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---- drawer callbacks ------------------------------------------------------

    @Override public void onPick(String key) {
        // The core decides: jump to an existing card, create + launch, fill a dock
        // slot, or add to a dock folder.
        int mode = drawerMode;
        int slot = drawerSlot;
        Native.drawerPick(key);
        drawer.hide();
        canvas.invalidate();
        if (mode == DrawerView.MODE_FOLDER) {
            showFolder(slot); // back into the folder so more apps can be added
        }
    }

    @Override public void onClose() {
        Native.drawerClosed();
        updateBars();
        canvas.invalidate();
        uiHandler.removeCallbacks(updateHomePill);
        uiHandler.postDelayed(updateHomePill, HOME_PILL_DELAY_MS);
    }

    @Override public void onSettings() {
        drawer.hide();
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ---- crash report: a home app that dies silently leaves the user with no launcher ----

    private File crashFile() {
        return new File(getFilesDir(), "crash.txt");
    }

    private static String describe(Thread t, Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("Thread: " + t.getName());
        pw.println("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
            + Build.MANUFACTURER + " " + Build.MODEL + ", ABIs " + java.util.Arrays.toString(Build.SUPPORTED_ABIS));
        e.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private void writeCrash(String text) {
        try (FileWriter w = new FileWriter(crashFile())) {
            w.write(text);
        } catch (IOException ignored) {
            // nothing more we can do
        }
    }

    private void installCrashRecorder() {
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            writeCrash(describe(t, e));
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    private String readCrash() {
        File f = crashFile();
        if (!f.exists()) return null;
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private void showCrash(String text) {
        int p = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(p, p * 3, p, p * 3);
        col.setBackgroundColor(0xFF101216);
        TextView head = new TextView(this);
        head.setText(L.t("Drift crashed. Details (select and copy, or send to the developer):"));
        head.setTextColor(0xFFE5766B);
        head.setTextSize(15);
        col.addView(head);
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextColor(0xFFE6E8EC);
        body.setTextSize(11);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        Button retry = new Button(this);
        retry.setText(L.t("Clear report and retry"));
        retry.setOnClickListener(v -> {
            crashFile().delete();
            recreate();
        });
        col.addView(retry);
        setContentView(col);
    }

    // ---- helpers ---------------------------------------------------------------

    private void toast(String msg) {
        if (toast != null) toast.cancel();
        toast = Toast.makeText(this, L.toast(msg), Toast.LENGTH_SHORT);
        toast.show();
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
