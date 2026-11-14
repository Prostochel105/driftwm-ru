package dev.drift.launcher;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * Popup for a dock folder: a grid of the apps it holds. Tap an app to open it,
 * long-press and drag it onto another tile to reorder, drop it on the scrim
 * (outside the card) to take it out of the folder, tap the title to rename the
 * folder, and tap the "+" tile to add another app through the app list.
 */
final class FolderView extends FrameLayout {
    interface Callback {
        void onLaunch(String key);

        void onAdd(int slot);

        void onRename(int slot, String currentName);

        void onClose();
    }

    private static final int COLUMNS = 4;

    private final float density;
    private final AppRepo repo;
    private final IconCache icons;
    private final LinearLayout card;
    private final TextView title;
    private final GridLayout grid;
    private final CappedScrollView scroll;
    private final Map<String, ImageView> tiles = new HashMap<>();
    private int cardW;
    private int tileW;
    private Appearance.Palette pal = Appearance.defaults().pal;
    private Callback callback;
    private int slot = -1;
    /** Keys currently shown, in order; resolves a drag-out into a removal. */
    private String[] currentApps = new String[0];

    FolderView(Context context, AppRepo repo, IconCache icons) {
        super(context);
        this.repo = repo;
        this.icons = icons;
        density = context.getResources().getDisplayMetrics().density;
        setVisibility(GONE);
        setClickable(true);
        setBackgroundColor(0xB3000000); // dim the canvas behind the popup
        setOnClickListener(v -> hide()); // tapping the scrim closes it
        // Drag a tile onto another to reorder it; drop it on the scrim (outside the
        // card) to take the app out of the folder.
        setOnDragListener(this::onDrag);

        int p = dp(16);
        cardW = Math.max(dp(180),
            Math.min(dp(340), getResources().getDisplayMetrics().widthPixels - dp(32)));
        tileW = (cardW - 2 * p) / COLUMNS;

        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true); // taps inside never reach the scrim
        card.setPadding(p, dp(14), p, dp(16));
        // Drops inside the card but not on a tile change nothing (never a removal).
        card.setOnDragListener((v, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return event.getLocalState() instanceof Integer;
                case DragEvent.ACTION_DROP:
                case DragEvent.ACTION_DRAG_ENDED:
                    return true;
                default:
                    return false;
            }
        });

        title = new TextView(context);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(4), 0, dp(4), dp(12));
        title.setOnClickListener(v -> {
            if (callback != null && slot >= 0) callback.onRename(slot, title.getText().toString());
        });
        card.addView(title, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        grid = new GridLayout(context);
        grid.setColumnCount(COLUMNS);
        grid.setUseDefaultMargins(false);
        scroll = new CappedScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(grid, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        addView(card, lp);
        applyMetrics();
        applyPalette();
    }

    void setAppearance(Appearance a) {
        pal = a.pal;
        applyPalette();
        if (isShowing()) reload();
    }

    private void applyPalette() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(pal.card);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), pal.stroke);
        card.setBackground(bg);
        title.setTextColor(pal.text);
    }

    void setCallback(Callback c) {
        callback = c;
    }

    boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    void show(int slot) {
        this.slot = slot;
        applyMetrics();
        reload();
        scroll.scrollTo(0, 0);
        setAlpha(0f);
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(110).start();
    }

    void hide() {
        if (!isShowing()) return;
        animate().cancel();
        setVisibility(GONE);
        setAlpha(1f);
        if (callback != null) callback.onClose();
    }

    /** Folder contents changed underneath the open popup (rename, add, remove). */
    void refresh() {
        if (isShowing()) reload();
    }

    /** Icons arrived after the popup was built: fill only the missing tiles. */
    void iconsLoaded() {
        if (!isShowing()) return;
        for (Map.Entry<String, ImageView> e : tiles.entrySet()) {
            ImageView iv = e.getValue();
            if (iv.getDrawable() != null) continue;
            Bitmap icon = icons.get(e.getKey());
            if (icon != null) iv.setImageBitmap(icon);
        }
    }

    private void reload() {
        if (slot < 0) return;
        title.setText(Native.folderName(slot));
        grid.removeAllViews();
        tiles.clear();
        String[] apps = Native.folderApps(slot);
        if (apps == null) apps = new String[0];
        currentApps = apps;
        for (int i = 0; i < apps.length; i++) grid.addView(appTile(apps[i], i));
        grid.addView(addTile());
    }

    private View appTile(String key, int index) {
        LinearLayout col = tile();
        ImageView iv = new ImageView(getContext());
        Bitmap icon = icons.get(key);
        if (icon != null) {
            iv.setImageBitmap(icon);
        } else {
            tiles.put(key, iv);
        }
        col.addView(iv, new LinearLayout.LayoutParams(iconSize(), iconSize()));

        TextView name = label(repo.label(key));
        col.addView(name, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.setOnClickListener(v -> {
            if (callback != null) callback.onLaunch(key);
        });
        // Long-press starts a drag: drop it on another tile to reorder, or drop it
        // outside the card (on the scrim) to take the app out of the folder.
        col.setOnLongClickListener(v -> {
            ClipData data = ClipData.newPlainText("drift-folder-app", key);
            v.startDragAndDrop(data, new View.DragShadowBuilder(v), index, 0);
            return true;
        });
        col.setOnDragListener((v, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return event.getLocalState() instanceof Integer;
                case DragEvent.ACTION_DRAG_ENTERED:
                    v.setAlpha(0.55f);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    v.setAlpha(1f);
                    return true;
                case DragEvent.ACTION_DROP: {
                    v.setAlpha(1f);
                    Object ls = event.getLocalState();
                    if (ls instanceof Integer) {
                        int from = (Integer) ls;
                        if (from != index) {
                            Native.folderMove(slot, from, index);
                            reload();
                        }
                    }
                    return true;
                }
                case DragEvent.ACTION_DRAG_ENDED:
                    v.setAlpha(1f);
                    return true;
                default:
                    return false;
            }
        });
        return col;
    }

    private View addTile() {
        LinearLayout col = tile();
        TextView plus = new TextView(getContext());
        plus.setText("+");
        plus.setTextSize(26);
        plus.setGravity(Gravity.CENTER);
        plus.setTextColor(pal.accent);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(pal.cardDrag);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), pal.stroke);
        plus.setBackground(bg);
        col.addView(plus, new LinearLayout.LayoutParams(iconSize(), iconSize()));

        col.addView(label(L.t("Add")), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.setOnClickListener(v -> {
            if (callback != null && slot >= 0) callback.onAdd(slot);
        });
        return col;
    }

    private LinearLayout tile() {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(4), dp(6), dp(4), dp(6));
        col.setClickable(true);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = tileW;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        col.setLayoutParams(lp);
        return col;
    }

    private TextView label(String text) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(pal.textDim);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** Scrim drop target: a tile dropped outside the card leaves the folder. */
    private boolean onDrag(View v, DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return event.getLocalState() instanceof Integer;
            case DragEvent.ACTION_DROP: {
                Object ls = event.getLocalState();
                if (ls instanceof Integer) {
                    int from = (Integer) ls;
                    if (from >= 0 && from < currentApps.length) confirmRemove(currentApps[from]);
                }
                return true;
            }
            default:
                return false;
        }
    }

    private void confirmRemove(String key) {
        new AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(L.t("Take ") + repo.label(key) + L.t(" out of the folder?"))
            .setPositiveButton(L.t("Remove"), (d, w) -> {
                Native.dockRemoveFolderApp(slot, key);
                // The folder stays even when emptied, so the popup stays open on
                // its free "+" slot; hide it only if the slot is no longer a folder.
                String name = Native.folderName(slot);
                if (name == null || name.isEmpty()) {
                    hide();
                } else {
                    reload();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Recompute sizes from the current display metrics. The activity keeps its
     * views across rotation ({@code configChanges}), so a size captured in the
     * constructor would stay wrong after the screen turns.
     */
    private void applyMetrics() {
        int p = dp(16);
        int width = Math.max(dp(180),
            Math.min(dp(340), getResources().getDisplayMetrics().widthPixels - dp(32)));
        if (width != cardW) {
            cardW = width;
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            if (lp != null) {
                lp.width = cardW;
                card.setLayoutParams(lp);
            }
        }
        tileW = (cardW - 2 * p) / COLUMNS;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        scroll.setMaxHeight(Math.max(dp(160), (int) (screenH * 0.74f)));
    }

    @Override protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyMetrics();
        if (isShowing()) reload();
    }

    /**
     * ScrollView that never measures taller than the popup allows. The card is
     * centred with a WRAP_CONTENT height, so an uncapped folder with many apps
     * would push its last row — and the "+" tile — off the screen.
     */
    private static final class CappedScrollView extends ScrollView {
        private int maxHeight = Integer.MAX_VALUE;

        CappedScrollView(Context context) {
            super(context);
        }

        void setMaxHeight(int h) {
            if (h == maxHeight) return;
            maxHeight = h;
            requestLayout();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int capped = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
            super.onMeasure(widthSpec, capped);
        }
    }

    /** Keep the icon inside its tile even in a narrow split-screen window. */
    private int iconSize() {
        return Math.min(dp(48), Math.max(dp(24), tileW - dp(8)));
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
