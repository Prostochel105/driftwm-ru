package dev.drift.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * "Drift settings": colours, background picture, themes and quick system shortcuts.
 * Everything here is presentation; the home screen picks the new look up when it comes back to the
 * front (see LauncherActivity.onResume). Reachable only from the "Drift settings" row of the app list.
 */
public final class SettingsActivity extends Activity {
    static final String PROJECT_URL = "https://github.com/StepaandAlex69/DriftWM-for-Android";
    private static final int REQ_IMAGE = 7001;

    /** bg, dots, accent for the ready-made themes. */
    private static final int[][] PRESETS = {
        {0xFF101216, 0xFF343944, 0xFF7AA2FF}, // default Drift
        {0xFF07080B, 0xFF1E2430, 0xFF8B7CFF}, // midnight
        {0xFF0D1512, 0xFF21352C, 0xFF5ED6A0}, // forest
        {0xFF171310, 0xFF3A302A, 0xFFFFB86B}, // warm
        {0xFFF4F5F7, 0xFFC9CDD6, 0xFF3A5BD9}, // light
        {0xFF150D12, 0xFF3A2233, 0xFFFF7AB6}, // rose
    };
    private static final String[] PRESET_NAMES = {
        "Graphite", "Midnight", "Forest", "Warm", "Light", "Rose",
    };

    private float d;
    private Appearance a;
    private Appearance.Palette pal;
    private ScrollView scroll;
    private LinearLayout root;
    private LinearLayout content;
    private boolean firstResume = true;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        d = getResources().getDisplayMetrics().density;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener((v, wi) -> {
            android.graphics.Insets b = wi.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
        rebuild();
    }

    @Override protected void onResume() {
        super.onResume();
        // Coming back from system settings: refresh the previews/battery statuses.
        if (firstResume) {
            firstResume = false;
            return;
        }
        rebuild();
    }

    // ---- page ------------------------------------------------------------------

    private void rebuild() {
        a = Appearance.read(this);
        pal = a.pal;
        root.setBackgroundColor(pal.base);
        try {
            WindowInsetsController ic = getWindow().getInsetsController();
            if (ic != null) {
                int bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ic.setSystemBarsAppearance(pal.light ? bars : 0, bars);
            }
        } catch (RuntimeException ignored) {
            // cosmetic only
        }
        int keep = scroll.getScrollY();
        content.removeAllViews();
        int p = dp(16);
        content.setPadding(p, dp(16), p, dp(24));

        addHeader();
        content.addView(caption("Changes apply when you go back to the home screen."), lp(0, 8));

        addTheme();
        addBackground();
        addInterface();
        addQuickActions();
        addAbout();

        scroll.post(() -> scroll.scrollTo(0, keep));
    }

    private void addHeader() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView logo = text("D", 24, pal.ink, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(rounded(pal.accent, 16));
        head.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        titles.addView(text("Drift settings", 23, pal.text, true));
        titles.addView(text("Drift Launcher · " + versionName(), 13, pal.textDim, false));
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(head, lp(0, 10));
    }

    private void addTheme() {
        content.addView(section("Theme"), lp(10, 6));
        content.addView(new Preview(this), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(176)));
        content.addView(caption("Presets"), lp(8, 6));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(2), 0, dp(2));
        for (int i = 0; i < PRESETS.length; i++) {
            int[] preset = PRESETS[i];
            boolean on = a.source == Appearance.SOURCE_COLORS
                && a.bg == preset[0] && a.dot == preset[1] && a.ui == preset[2];
            LinearLayout chip = presetChip(L.t(PRESET_NAMES[i]), preset, on, () -> {
                Appearance.putInt(this, Appearance.K_BG, preset[0]);
                Appearance.putInt(this, Appearance.K_DOT, preset[1]);
                Appearance.putInt(this, Appearance.K_UI, preset[2]);
                Appearance.putInt(this, Appearance.K_SOURCE, Appearance.SOURCE_COLORS);
                rebuild();
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.rightMargin = dp(8);
            row.addView(chip, cp);
        }
        hs.addView(row);
        content.addView(hs, lp(0, 4));
    }

    private void addBackground() {
        content.addView(section("Background"), lp(12, 6));
        boolean image = a.source == Appearance.SOURCE_IMAGE;
        content.addView(segmented("Colors", !image, () -> {
            Appearance.putInt(this, Appearance.K_SOURCE, Appearance.SOURCE_COLORS);
            rebuild();
        }, "Image", image, () -> {
            if (Appearance.hasImage(this)) {
                Appearance.putInt(this, Appearance.K_SOURCE, Appearance.SOURCE_IMAGE);
                rebuild();
            } else {
                pickImage();
            }
        }), lp(0, 8));

        if (!image) {
            content.addView(colorRow("Background color", a.bg, Appearance.DEFAULT_BG, Appearance.K_BG), lp(0, 8));
            content.addView(colorRow("Dots color", a.dot, Appearance.DEFAULT_DOT, Appearance.K_DOT), lp(0, 8));
        } else {
            content.addView(row("Picture", Appearance.hasImage(this) ? "Tap to choose another" : "Not chosen", null,
                v -> pickImage()), lp(0, 8));
            boolean tiled = a.imageMode == Appearance.IMAGE_INFINITE;
            content.addView(segmented("Static", !tiled, () -> {
                Appearance.putInt(this, Appearance.K_IMAGE_MODE, Appearance.IMAGE_STATIC);
                rebuild();
            }, "Infinite", tiled, () -> {
                Appearance.putInt(this, Appearance.K_IMAGE_MODE, Appearance.IMAGE_INFINITE);
                rebuild();
            }), lp(0, 6));
            content.addView(caption(tiled
                ? "Infinite: the picture repeats forever and is attached to the canvas. It moves and zooms with the cards."
                : "Static: like ordinary wallpaper. It stays where it is while the canvas moves."), lp(0, 8));
            content.addView(row("Remove picture", "Go back to plain colors", null, v -> {
                //noinspection ResultOfMethodCallIgnored
                Appearance.imageFile(this).delete();
                Appearance.putInt(this, Appearance.K_SOURCE, Appearance.SOURCE_COLORS);
                rebuild();
            }), lp(0, 8));
        }
    }

    private void addInterface() {
        content.addView(section("Interface"), lp(12, 6));
        content.addView(colorRow("Interface color", a.ui, Appearance.DEFAULT_UI, Appearance.K_UI), lp(0, 6));
        content.addView(caption("One color for cards, dock, buttons, text and the app list."), lp(0, 8));
        content.addView(row("Reset appearance", "Default colors, no picture", null, v ->
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(L.t("Reset colors and remove the background picture?"))
                .setPositiveButton(L.t("Reset"), (dlg, w) -> {
                    Appearance.reset(this);
                    rebuild();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show()), lp(0, 8));
    }

    private void addQuickActions() {
        content.addView(section("Quick actions"), lp(12, 6));

        boolean previews = previewsEnabled();
        content.addView(row("Previews", "Accessibility service",
            status(previews ? "On" : "Off", previews),
            v -> openSystem(Settings.ACTION_ACCESSIBILITY_SETTINGS)), lp(0, 8));

        boolean battery = batteryUnrestricted();
        content.addView(row("Battery", "Keep the launcher alive",
            status(battery ? "Unrestricted" : "Restricted", battery),
            v -> openSystem(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)), lp(0, 8));

        content.addView(row("Diagnostics", "Check system navigation", null, v -> {
            try {
                startActivity(new Intent(this, DiagnosticsActivity.class));
            } catch (RuntimeException e) {
                Toast.makeText(this, L.t("Couldn't open settings"), Toast.LENGTH_LONG).show();
            }
        }), lp(0, 8));

        content.addView(row("App info", "Permissions, battery, uninstall", null, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException e) {
                Toast.makeText(this, L.t("Couldn't open settings"), Toast.LENGTH_LONG).show();
            }
        }), lp(0, 8));

        content.addView(row("Gestures", "Tap, double tap, drag, pinch", null,
            v -> showGestures()), lp(0, 8));
    }

    private void showGestures() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L.t("Gestures"))
            .setMessage(L.t("Single tap — select a card.\n"
                + "Double tap — open the app.\n"
                + "Long press — select and raise the card.\n"
                + "Drag a card — move it; bring it close to another card to form a group.\n"
                + "Drag empty space — pan the canvas.\n"
                + "Pinch — zoom the canvas; pinch a selected card to resize it.\n"
                + "Double tap empty space — go home and back.\n"
                + "Three-finger swipe — jump to the nearest card.\n"
                + "Back — deselect the card or close the app list."))
            .setPositiveButton(L.t("Close"), null)
            .show();
    }

    private void addAbout() {
        content.addView(section("About"), lp(12, 6));
        content.addView(row("Version", versionName(), null, null), lp(0, 8));
        content.addView(row("About", PROJECT_URL.replace("https://", ""), null, v -> openProject()), lp(0, 8));
        content.addView(row("License", "GPL-3.0", null, null), lp(0, 8));
    }

    // ---- system shortcuts ------------------------------------------------------

    private boolean previewsEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            String full = getPackageName() + "/" + ThumbService.class.getName();
            String shortForm = getPackageName() + "/." + ThumbService.class.getSimpleName();
            return enabled.contains(full) || enabled.contains(shortForm);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean batteryUnrestricted() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void openSystem(String action) {
        try {
            startActivity(new Intent(action));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, L.t("Couldn't open settings"), Toast.LENGTH_LONG).show();
        }
    }

    private String versionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null || v.isEmpty() ? "V5" : v;
        } catch (Exception e) {
            return "V5";
        }
    }

    private void openProject() {
        Uri uri = Uri.parse(PROJECT_URL);
        // Ask for the default browser itself, so an installed GitHub app does not take the link.
        Intent browser = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER);
        browser.setData(uri);
        browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(browser);
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // no app registered as the browser: fall through to a plain VIEW
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, L.t("No browser found"), Toast.LENGTH_LONG).show();
        }
    }

    // ---- picture ---------------------------------------------------------------

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*");
        try {
            startActivityForResult(i, REQ_IMAGE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, L.t("No app to pick a picture"), Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_IMAGE || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        Toast.makeText(this, L.t("Loading picture..."), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = importImage(uri);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (ok) {
                    Appearance.putInt(this, Appearance.K_SOURCE, Appearance.SOURCE_IMAGE);
                    rebuild();
                } else {
                    Toast.makeText(this, L.t("Couldn't read that picture"), Toast.LENGTH_LONG).show();
                }
            });
        }, "drift-image-import").start();
    }

    /**
     * Copies the picked image into the app's own storage, shrunk to a size that is enough for any
     * screen. After this Drift never needs the original Uri or any storage permission again.
     */
    private boolean importImage(Uri uri) {
        File tmp = new File(getFilesDir(), Appearance.IMAGE_FILE + ".tmp");
        try {
            Bitmap b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri), (dec, info, src) -> {
                dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int w = info.getSize().getWidth();
                int h = info.getSize().getHeight();
                int m = Math.max(w, h);
                if (m > Appearance.STATIC_MAX_SIDE) {
                    float k = (float) Appearance.STATIC_MAX_SIDE / m;
                    dec.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
                }
            });
            boolean saved;
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                saved = b.hasAlpha()
                    ? b.compress(Bitmap.CompressFormat.PNG, 100, out)
                    : b.compress(Bitmap.CompressFormat.JPEG, 92, out);
            }
            b.recycle();
            if (!saved) return false;
            File dest = Appearance.imageFile(this);
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            return tmp.renameTo(dest);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return false;
        } finally {
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    // ---- widgets ---------------------------------------------------------------

    private int dp(float v) {
        return Math.round(v * d);
    }

    private static LinearLayout.LayoutParams lp(int topDp, int bottomDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        float d = android.content.res.Resources.getSystem().getDisplayMetrics().density;
        p.topMargin = Math.round(topDp * d);
        p.bottomMargin = Math.round(bottomDp * d);
        return p;
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(L.t(s));
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView section(String s) {
        return text(s, 13, pal.accent, true);
    }

    private TextView caption(String s) {
        return text(s, 13, pal.textDim, false);
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable roundedStroke(int fill, int stroke, float strokeDp) {
        GradientDrawable g = rounded(fill, 14);
        g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private LinearLayout row(String title, String subtitle, View trailing, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(13), dp(16), dp(13));
        r.setBackground(new RippleDrawable(ColorStateList.valueOf((pal.text & 0x00FFFFFF) | 0x30000000),
            rounded(pal.card, 14), null));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 16, pal.text, false));
        if (subtitle != null) col.addView(text(subtitle, 13, pal.textDim, false));
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (trailing != null) r.addView(trailing);
        r.setOnClickListener(click);
        r.setClickable(click != null);
        return r;
    }

    private TextView status(String s, boolean good) {
        TextView t = text(s, 12, good ? pal.ink : pal.textDim, true);
        t.setPadding(dp(9), dp(4), dp(9), dp(4));
        t.setBackground(rounded(good ? pal.accent : pal.cardDrag, 9));
        return t;
    }

    private LinearLayout colorRow(String title, int color, int defaultColor, String key) {
        View swatch = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(1.5f), pal.stroke);
        swatch.setBackground(g);
        swatch.setLayoutParams(new LinearLayout.LayoutParams(dp(30), dp(30)));
        String hex = String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
        return row(title, hex, swatch, v ->
            ColorPickerDialog.show(this, title, color, defaultColor, c -> {
                Appearance.putInt(this, key, c);
                rebuild();
            }));
    }

    private LinearLayout presetChip(String label, int[] preset, boolean selected, Runnable click) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER_HORIZONTAL);
        chip.setPadding(dp(10), dp(8), dp(10), dp(8));
        chip.setBackground(selected
            ? roundedStroke(pal.card, pal.accent, 2)
            : roundedStroke(pal.card, pal.stroke, 1));

        View swatch = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(preset[2]);
        g.setStroke(dp(3), preset[0]);
        swatch.setBackground(g);
        chip.addView(swatch, new LinearLayout.LayoutParams(dp(38), dp(38)));

        TextView name = text(label, 11, selected ? pal.accent : pal.textDim, selected);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(6);
        chip.addView(name, np);
        chip.setOnClickListener(v -> click.run());
        return chip;
    }

    private LinearLayout segmented(String left, boolean leftOn, Runnable onLeft, String right, boolean rightOn, Runnable onRight) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));
        box.setBackground(rounded(pal.card, 14));
        box.addView(segment(left, leftOn, onLeft), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(segment(right, rightOn, onRight), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return box;
    }

    private TextView segment(String label, boolean on, Runnable click) {
        TextView t = text(label, 15, on ? pal.ink : pal.text, on);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(10), dp(8), dp(10));
        t.setBackground(on ? rounded(pal.accent, 11) : null);
        t.setOnClickListener(v -> {
            if (!on) click.run();
        });
        return t;
    }

    /** Miniature of the home screen: wallpaper, dots, two cards and the dock. */
    private final class Preview extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Preview(Context c) {
            super(c);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float r = dp(18);
            p.setStyle(Paint.Style.FILL);

            p.setColor(a.bg);
            c.drawRoundRect(new RectF(0, 0, w, h), r, r, p);

            p.setColor(a.dot);
            float gap = dp(24);
            for (float x = gap / 2; x < w; x += gap) {
                for (float y = gap / 2; y < h; y += gap) {
                    c.drawCircle(x, y, dp(1.3f), p);
                }
            }

            float cw = Math.min(dp(54), w * 0.22f);
            float ch = cw * 1.55f;
            drawCard(c, w * 0.30f, h * 0.36f, cw, ch, true);
            drawCard(c, w * 0.64f, h * 0.30f, cw, ch, false);

            float dw = Math.min(w - dp(48), dp(280));
            float dh = dp(42);
            float dx = (w - dw) / 2;
            float dy = h - dh - dp(14);
            p.setColor(pal.nav);
            c.drawRoundRect(new RectF(dx, dy, dx + dw, dy + dh), dh / 2, dh / 2, p);
            float dotR = dp(6.5f);
            float step = (dw - dh) / 4f;
            for (int i = 0; i < 5; i++) {
                p.setColor(i == 0 ? pal.accent : pal.textDim);
                c.drawCircle(dx + dh / 2 + i * step, dy + dh / 2, dotR, p);
            }

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(pal.stroke);
            c.drawRoundRect(new RectF(dp(.5f), dp(.5f), w - dp(.5f), h - dp(.5f)), r, r, p);
            p.setStyle(Paint.Style.FILL);
        }

        private void drawCard(Canvas c, float cx, float cy, float cw, float ch, boolean selected) {
            float l = cx - cw / 2;
            float t = cy - ch / 2;
            float rr = dp(12);
            RectF rf = new RectF(l, t, l + cw, t + ch);

            p.setColor(pal.card);
            c.drawRoundRect(rf, rr, rr, p);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(selected ? dp(1.6f) : dp(1));
            p.setColor(selected ? pal.accent : pal.stroke);
            c.drawRoundRect(rf, rr, rr, p);
            p.setStyle(Paint.Style.FILL);

            p.setColor(selected ? pal.accent : pal.textDim);
            c.drawCircle(l + cw * 0.30f, t + ch * 0.26f, cw * 0.14f, p);

            p.setColor(pal.text);
            p.setAlpha(60);
            c.drawRoundRect(new RectF(l + cw * 0.16f, t + ch * 0.52f, l + cw * 0.84f, t + ch * 0.59f), dp(2), dp(2), p);
            p.setColor(pal.textDim);
            p.setAlpha(80);
            c.drawRoundRect(new RectF(l + cw * 0.16f, t + ch * 0.68f, l + cw * 0.70f, t + ch * 0.74f), dp(2), dp(2), p);
            p.setAlpha(255);
        }
    }
}
