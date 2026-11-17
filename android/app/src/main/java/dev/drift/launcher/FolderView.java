package dev.drift.launcher;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Popup for a dock folder: a grid of the apps it holds. Tap an app to open it;
 * long-press it to highlight it and open its menu ("Open" / "Remove from
 * folder"); start dragging the held app to dismiss the menu and move it — drop
 * it on another tile to reorder, or drop it on the scrim to take it out. Tap the
 * title to rename the folder, and tap the "+" tile to add another app.
 *
 * Drag feedback is animated: the dragged app gets a lifted shadow with an accent
 * border, the target tiles ease out of the way, and a reorder animates the icons
 * to their new places (FLIP) instead of snapping the grid into shape.
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
    private final int touchSlop;
    private final Interpolator easeOut = new DecelerateInterpolator(1.7f);
    private final Interpolator pop = new OvershootInterpolator(1.25f);
    private final AppRepo repo;
    private final IconCache icons;
    private final LinearLayout card;
    private final TextView title;
    private final GridLayout grid;
    private final CappedScrollView scroll;
    /** Tiles waiting on an icon, keyed by app key. */
    private final Map<String, ImageView> tiles = new HashMap<>();
    /** Tile views by app key, reused across reloads so reorder can animate them. */
    private final Map<String, View> tileByKey = new HashMap<>();
    private int cardW;
    private int tileW;
    private Appearance.Palette pal = Appearance.defaults().pal;
    private Callback callback;
    private int slot = -1;
    /** Keys currently shown, in order; resolves a drag-out into a removal. */
    private String[] currentApps = new String[0];
    /** Tile whose long-press menu is open, or -1. */
    private int menuIndex = -1;
    private View menuView;
    private View menuAnchor;
    private View dragSource;
    /** Current position of the dragged app; updated after every live reorder. */
    private int dragCurrentIndex = -1;
    /** Slot the divider currently marks, to avoid re-posting on every move event. */
    private int dropIndicatorIndex = -1;
    /** Accent divider that slides to the insertion slot while dragging. */
    private final View dropIndicator;
    private boolean hidePending;
    private float touchDownX;
    private float touchDownY;

    FolderView(Context context, AppRepo repo, IconCache icons) {
        super(context);
        this.repo = repo;
        this.icons = icons;
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setVisibility(GONE);
        setClickable(true);
        setBackgroundColor(0xB3000000); // dim the canvas behind the popup
        // A tap on the scrim first closes an open menu; otherwise it closes the folder.
        setOnClickListener(v -> {
            if (!dismissMenu()) hide();
        });
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
        card.setOnClickListener(v -> dismissMenu()); // tapping the card closes the menu
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
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(4), 0, dp(4), dp(12));
        title.setOnClickListener(v -> {
            dismissMenu();
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

        // Android-style insertion divider: a thin accent bar shown in the gap
        // between slots. It is positioned from the cell's layout index, never from
        // its animated position, so it cannot ride a FLIP animation or land on an
        // icon. Hidden until a drag starts.
        dropIndicator = new View(context);
        GradientDrawable indBg = new GradientDrawable();
        indBg.setColor(pal.accent);
        indBg.setCornerRadius(dp(1.5f));
        dropIndicator.setBackground(indBg);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(2.5f), dp(40));
        ilp.gravity = Gravity.TOP | Gravity.START;
        addView(dropIndicator, ilp);
        dropIndicator.setVisibility(INVISIBLE);

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
        if (dropIndicator != null && dropIndicator.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) dropIndicator.getBackground()).setColor(pal.accent);
        }
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
        hidePending = false;
        dragCurrentIndex = -1;
        hideDropIndicator();
        animate().cancel();
        setAlpha(0f);
        setScaleX(0.94f);
        setScaleY(0.94f);
        setVisibility(VISIBLE);
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180)
            .setInterpolator(pop).start();
    }

    void hide() {
        if (!isShowing() || hidePending) return;
        hidePending = true;
        dismissMenu();
        hideDropIndicator();
        animate().cancel();
        animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(130)
            .withEndAction(() -> {
                setVisibility(GONE);
                setAlpha(1f);
                setScaleX(1f);
                setScaleY(1f);
                hidePending = false;
                if (callback != null) callback.onClose();
            }).start();
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
        dismissMenu();
        title.setText(Native.folderName(slot));
        String[] apps = Native.folderApps(slot);
        if (apps == null) apps = new String[0];
        currentApps = apps;
        tiles.clear();

        // Reuse tile views by key: a reorder only changes their order, so the
        // existing views can be animated to their new positions afterwards.
        List<View> spare = new ArrayList<>(tileByKey.values());
        tileByKey.clear();
        grid.removeAllViews();
        for (String key : apps) {
            View tile = null;
            for (View candidate : spare) {
                if (key.equals(candidate.getTag())) {
                    tile = candidate;
                    spare.remove(candidate);
                    break;
                }
            }
            if (tile == null) {
                tile = appTile(key);
            } else {
                tile.animate().cancel();
                tile.setAlpha(1f);
                tile.setScaleX(1f);
                tile.setScaleY(1f);
                tile.setTranslationX(0f);
                tile.setTranslationY(0f);
                ImageView iv = (ImageView) ((LinearLayout) tile).getChildAt(0);
                if (iv.getDrawable() == null) tiles.put(key, iv);
            }
            tileByKey.put(key, tile);
            grid.addView(tile);
        }
        grid.addView(addTile());
    }

    private View appTile(String key) {
        LinearLayout col = tile();
        col.setTag(key); // stable identity across reloads
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
            dismissMenu();
            if (callback != null) callback.onLaunch(key);
        });
        // Long-press selects the tile and opens its menu. If the finger then moves,
        // the menu closes and the tile is dragged (reorder, or drop on the scrim to
        // take the app out).
        col.setOnLongClickListener(v -> {
            showAppMenu(indexOfKey(key), v, key);
            return true;
        });
        col.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchDownX = e.getRawX();
                    touchDownY = e.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (menuIndex == indexOfKey(key) && menuView != null) {
                        float dx = e.getRawX() - touchDownX;
                        float dy = e.getRawY() - touchDownY;
                        if (dx * dx + dy * dy > (float) touchSlop * touchSlop) {
                            dismissMenu();
                            dragSource = v;
                            dragCurrentIndex = indexOfKey(key);
                            // The source becomes a ghost while the lifted copy follows the finger.
                            v.animate().alpha(0.35f).scaleX(0.92f).scaleY(0.92f)
                                .setDuration(120).setInterpolator(easeOut).start();
                            v.startDragAndDrop(
                                ClipData.newPlainText("drift-folder-app", key),
                                new AppDragShadow(icons.get(key), repo.label(key), pal, density, iconSize()),
                                indexOfKey(key), 0);
                        }
                    }
                    break;
                default:
                    break;
            }
            return false;
        });
        col.setOnDragListener((v, event) -> {
            int index = indexOfKey(key);
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return event.getLocalState() instanceof Integer;
                case DragEvent.ACTION_DRAG_ENTERED:
                    // Live reorder: move the app under the finger into this slot
                    // immediately and animate everybody into their new places.
                    if (index >= 0 && index != dragCurrentIndex && dragCurrentIndex >= 0) {
                        reorderWithAnimation(dragCurrentIndex, index);
                        dragCurrentIndex = index;
                    }
                    showDropIndicator(index);
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION:
                    showDropIndicator(index);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    return true;
                case DragEvent.ACTION_DROP:
                    // The order was already applied during DRAG_ENTERED; just clean up.
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    return true;
                default:
                    return false;
            }
        });
        return col;
    }

    private int indexOfKey(String key) {
        for (int i = 0; i < currentApps.length; i++) {
            if (key.equals(currentApps[i])) return i;
        }
        return -1;
    }

    /**
     * Show the divider in the gap to the LEFT of the hovered slot. Position comes
     * from layout coordinates (getLeft/getTop), which ignore FLIP translations, so
     * the bar is always exactly where the slot actually sits. No slide animation —
     * it appears/disappears with a short fade to avoid chasing animated icons.
     */
    private void showDropIndicator(final int index) {
        if (dropIndicator == null || index < 0 || index == dropIndicatorIndex) return;
        dropIndicatorIndex = index;
        // After a live reorder the grid is rebuilt; wait for the layout pass so
        // getLeft()/getTop() reflect the new cell positions.
        grid.post(() -> {
            if (!isShowing() || dragCurrentIndex < 0 || index >= grid.getChildCount()) return;
            View cell = grid.getChildAt(index);
            int x = cell.getLeft();
            int y = cell.getTop();
            // Walk up to FolderView; getLeft/getTop ignore translation animations.
            for (View p = (View) cell.getParent(); p != FolderView.this; p = (View) p.getParent()) {
                x += p.getLeft();
                y += p.getTop();
            }
            // Sit in the padding gap at the cell's left edge, vertically centred.
            int barX = x - dp(2);
            int barY = y + cell.getHeight() / 2 - dp(20);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) dropIndicator.getLayoutParams();
            lp.leftMargin = barX;
            lp.topMargin = barY;
            dropIndicator.setLayoutParams(lp);
            if (dropIndicator.getVisibility() != VISIBLE) {
                dropIndicator.setAlpha(0f);
                dropIndicator.setVisibility(VISIBLE);
                dropIndicator.animate().alpha(1f).setDuration(90).start();
            }
        });
    }

    private void hideDropIndicator() {
        dropIndicatorIndex = -1;
        if (dropIndicator == null || dropIndicator.getVisibility() != VISIBLE) return;
        dropIndicator.animate().cancel();
        dropIndicator.animate().alpha(0f).setDuration(90)
            .withEndAction(() -> dropIndicator.setVisibility(INVISIBLE)).start();
    }

    /**
     * Move the app, rebuild the grid reusing the same views, then animate each
     * view from its old position to its new one (FLIP) on the next pre-draw.
     */
    private void reorderWithAnimation(int from, int to) {
        Map<View, int[]> before = new HashMap<>();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View v = grid.getChildAt(i);
            before.put(v, new int[] {v.getLeft(), v.getTop()});
        }
        Native.folderMove(slot, from, to);
        reload();
        // reload() resets reused tile alpha to 1 — keep the dragged one a ghost.
        if (dragSource != null) {
            dragSource.setAlpha(0.35f);
            dragSource.setScaleX(0.92f);
            dragSource.setScaleY(0.92f);
        }
        grid.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                grid.getViewTreeObserver().removeOnPreDrawListener(this);
                for (int i = 0; i < grid.getChildCount(); i++) {
                    View v = grid.getChildAt(i);
                    int[] old = before.get(v);
                    if (old == null) continue;
                    float dx = old[0] - v.getLeft();
                    float dy = old[1] - v.getTop();
                    if (Math.abs(dx) < 1f && Math.abs(dy) < 1f) continue;
                    v.setTranslationX(dx);
                    v.setTranslationY(dy);
                    v.animate().translationX(0f).translationY(0f)
                        .setDuration(260).setInterpolator(easeOut).start();
                }
                return true;
            }
        });
        grid.invalidate();
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
            dismissMenu();
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
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** Highlight a tile and pop its menu just above it (below if there is no room). */
    private void showAppMenu(int index, View anchor, String key) {
        dismissMenu();
        menuIndex = index;
        menuAnchor = anchor;
        select(anchor, true);
        anchor.animate().scaleX(1.06f).scaleY(1.06f).setDuration(130)
            .setInterpolator(pop).start();
        // Hold the gesture: without this the ScrollView would steal the move and
        // start a scroll instead of the drag.
        anchor.getParent().requestDisallowInterceptTouchEvent(true);

        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClickable(true);
        box.setElevation(dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(pal.card);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), pal.stroke);
        box.setBackground(bg);
        int pad = dp(4);
        box.setPadding(pad, pad, pad, pad);
        box.addView(menuItem(L.t("Open"), () -> {
            dismissMenu();
            if (callback != null) callback.onLaunch(key);
        }));
        box.addView(menuItem(L.t("Remove from folder"), () -> {
            dismissMenu();
            confirmRemove(key);
        }));

        box.measure(MeasureSpec.makeMeasureSpec(cardW, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int mw = box.getMeasuredWidth();
        int mh = box.getMeasuredHeight();

        int[] a = new int[2];
        anchor.getLocationInWindow(a);
        int[] r = new int[2];
        getLocationInWindow(r);
        int ax = a[0] - r[0];
        int ay = a[1] - r[1];
        int x = ax + anchor.getWidth() / 2 - mw / 2;
        int y = ay - mh - dp(6);
        if (y < dp(4)) y = ay + anchor.getHeight() + dp(6);
        x = Math.max(dp(4), Math.min(x, Math.max(dp(4), getWidth() - mw - dp(4))));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = x;
        lp.topMargin = y;
        addView(box, lp);
        menuView = box;
        box.setAlpha(0f);
        box.setScaleX(0.86f);
        box.setScaleY(0.86f);
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170)
            .setInterpolator(pop).start();
    }

    private TextView menuItem(String text, Runnable action) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(pal.text);
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        t.setClickable(true);
        t.setOnClickListener(v -> action.run());
        return t;
    }

    /** Remove the open menu, if any. Returns whether one was open. */
    private boolean dismissMenu() {
        if (menuView == null) return false;
        final View box = menuView;
        menuView = null;
        box.animate().cancel();
        box.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(110)
            .withEndAction(() -> removeView(box)).start();
        if (menuAnchor != null) {
            final View a = menuAnchor;
            a.animate().scaleX(1f).scaleY(1f).setDuration(130).setInterpolator(easeOut).start();
            select(a, false);
        }
        menuAnchor = null;
        menuIndex = -1;
        return true;
    }

    private void select(View v, boolean on) {
        if (!on) {
            v.setBackground(null);
            return;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(pal.cardDrag);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(2), pal.accent);
        v.setBackground(bg);
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
            case DragEvent.ACTION_DRAG_ENDED:
                hideDropIndicator();
                if (dragSource != null) {
                    dragSource.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(160).setInterpolator(easeOut).start();
                    dragSource = null;
                }
                dragCurrentIndex = -1;
                return true;
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

    /** Keep the icon inside its tile even in a narrow split-screen window. */
    private int iconSize() {
        return Math.min(dp(48), Math.max(dp(24), tileW - dp(8)));
    }

    private int dp(float v) {
        return Math.round(v * density);
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

    /**
     * A lifted copy of the app tile that follows the finger during a drag. The
     * platform's default DragShadowBuilder is a flat bitmap with no depth, which
     * made reordering feel like a bug; this one scales the icon up, adds a soft
     * drop shadow and an accent ring, and keeps a short label under it.
     */
    private static final class AppDragShadow extends View.DragShadowBuilder {
        private final Bitmap icon;
        private final String label;
        private final Appearance.Palette pal;
        private final float density;
        private final int w;
        private final int h;
        private final float labelBand;

        AppDragShadow(Bitmap icon, String label, Appearance.Palette pal, float density, int iconSize) {
            this.icon = icon;
            this.label = label == null ? "" : label;
            this.pal = pal;
            this.density = density;
            this.w = Math.round(iconSize * 1.5f) + dp(16);
            this.labelBand = dp(26);
            this.h = Math.round(iconSize * 1.5f) + dp(16) + Math.round(labelBand);
        }

        @Override public void onProvideShadowMetrics(Point shadowSize, Point shadowTouchPoint) {
            shadowSize.set(w, h);
            // Keep the icon under the finger, not the label strip.
            shadowTouchPoint.set(w / 2, (w - dp(16)) / 2 + dp(8));
        }

        @Override public void onDrawShadow(Canvas canvas) {
            float pad = dp(8);
            float rad = dp(16);
            RectF face = new RectF(pad, pad, w - pad, h - labelBand);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            // Soft offset shadow, painted rather than blurred so the drag stays smooth.
            p.setColor(0x66000000);
            RectF sh = new RectF(face.left + dp(2), face.top + dp(5),
                face.right + dp(2), face.bottom + dp(5));
            canvas.drawRoundRect(sh, rad, rad, p);
            p.setColor(pal.cardDrag);
            canvas.drawRoundRect(face, rad, rad, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(pal.accent);
            canvas.drawRoundRect(face, rad, rad, p);

            if (icon != null) {
                float s = Math.min(face.width(), face.height()) * 0.64f;
                float cx = face.centerX();
                float cy = face.centerY();
                Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                canvas.drawBitmap(icon, null,
                    new RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2), ip);
            }

            p.setStyle(Paint.Style.FILL);
            p.setColor(pal.text);
            p.setTextSize(dp(12));
            p.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(label, w / 2f, h - dp(7), p);
        }

        private int dp(float v) {
            return Math.round(v * density);
        }
    }
}
