package dev.tcl.home;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen, laid out like Google TV (see the MagicPath design): a top bar, a hero that shows live
 * antenna TV (the LIVE stage) or three picks from Plex, and rows below. Moving into the rows swaps the
 * live picture for the focused card's artwork, title and description.
 */
public class HomeActivity extends Activity {
    private static final long CLOCK_TICK_MS = 15_000;
    private static final long TV_ROWS_SETTLE_MS = 2_000;
    private static final int TEXT = Color.rgb(241, 243, 244);
    private static final int MUTED = Color.rgb(168, 171, 177);
    private static final int BG = Color.rgb(15, 16, 18);
    private static final int PLEX = Color.rgb(229, 160, 13);
    private static final int YOUTUBE = Color.rgb(255, 0, 51);
    private static final int LIVE_RED = Color.rgb(232, 69, 60);
    private static final int GRID_COLUMNS = 6;
    private static final String PLEX_PKG = "com.plexapp.android";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private Images images;
    private Plex plex;
    private LiveTv live;
    private TvView tvView;
    private boolean liveOn;

    // Row data; the Plex lists keep the last copy so the next Home shows them at once.
    private List<TvRows.Item> watchNext = new ArrayList<>();
    private List<TvRows.Item> plexRecs = new ArrayList<>();
    private List<List<TvRows.Item>> youtube = new ArrayList<>();
    private List<TvRows.Item> plexOnDeck = new ArrayList<>();
    private List<TvRows.Item> plexRecent = new ArrayList<>();
    private List<TvRows.Item> plexMovies = new ArrayList<>();
    private String[] youtubeRows;

    private ImageView heroArt;
    private TextView ghost;
    private ImageView heroLogo;
    private TextView heroSource;
    private TextView heroTitle;
    private TextView heroMeta;
    private TextView heroDesc;
    private final View[] stageDots = new View[4];
    private int stageSlot;
    private List<TvRows.Item> picks = Collections.emptyList();

    private TextView clock;
    private ImageView avatarPhoto;
    private LinearLayout rowsTop;
    private LinearLayout rowsBottom;
    private LinearLayout appRow;
    private ScrollView rowsScroll;
    private View homePane;
    private ScrollView appsPane;
    private LinearLayout appsGrid;
    private View homeTab;
    private View appsTab;
    private boolean showingApps;
    private View topBarView;
    private View stageView;
    // Card left when Up moved out of the rows, so Down returns to it.
    private View lastCard;

    private final Runnable reloadTvRows = () -> loadRows(false);
    // An app writes its rows one program at a time: wait for the writes to settle before reading them.
    private final ContentObserver tvRowsChanged = new ContentObserver(handler) {
        /**
         * Schedules a reload of the TV provider rows, pushed back by every further change.
         *
         * @param selfChange unused
         */
        @Override
        public void onChange(boolean selfChange) {
            handler.removeCallbacks(reloadTvRows);
            handler.postDelayed(reloadTvRows, TV_ROWS_SETTLE_MS);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            clock.setText(DateFormat.format("HH:mm", System.currentTimeMillis()) + "   |   " + getString(R.string.tab_home));
            handler.postDelayed(this, CLOCK_TICK_MS);
        }
    };

    /**
     * Builds the screen in code: the background layer (live TV or the hero artwork), scrims, then the
     * top bar, the hero with its stage, and the rows.
     *
     * @param savedInstanceState unused
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        images = new Images(this);
        plex = new Plex(this);
        youtubeRows = getResources().getStringArray(R.array.youtube_rows);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        if (getResources().getBoolean(R.bool.show_live_tv)) {
            tvView = new TvView(this);
            tvView.setFocusable(false);
            root.addView(tvView, new FrameLayout.LayoutParams(-1, -1));
            live = new LiveTv(this, tvView, getString(R.string.live_tv_input));
        }
        heroArt = new ImageView(this);
        heroArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroArt.setVisibility(View.GONE);
        root.addView(heroArt, new FrameLayout.LayoutParams(-1, -1));

        ghost = text(150, Color.argb(46, 241, 243, 244), true);
        // Clipped, not ellipsized: an ellipsis there reads as extra stage dots.
        ghost.setSingleLine(true);
        ghost.setGravity(Gravity.END);
        FrameLayout.LayoutParams ghostLp = new FrameLayout.LayoutParams(px(1000), -2, Gravity.END | Gravity.TOP);
        ghostLp.setMargins(0, px(250), px(96), 0);
        root.addView(ghost, ghostLp);

        View scrimSide = new View(this);
        scrimSide.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] {Color.argb(235, 15, 16, 18), Color.argb(120, 15, 16, 18), Color.argb(10, 15, 16, 18)}));
        root.addView(scrimSide, new FrameLayout.LayoutParams(-1, -1));
        View scrimBottom = new View(this);
        scrimBottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {Color.argb(120, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(200, 15, 16, 18), BG}));
        root.addView(scrimBottom, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(px(96), px(48), 0, 0);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        topBarView = topBar();
        overlay.addView(topBarView, new LinearLayout.LayoutParams(-1, -2));

        // Home and Apps share the space under the top bar; the tabs switch between them.
        FrameLayout panes = new FrameLayout(this);
        overlay.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        homePane = home;
        panes.addView(home, new FrameLayout.LayoutParams(-1, -1));
        home.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));
        home.addView(hero(), new LinearLayout.LayoutParams(-1, -2));

        rowsScroll = new ScrollView(this);
        rowsScroll.setVerticalScrollBarEnabled(false);
        rowsScroll.setFocusable(false);
        rowsScroll.setPadding(0, px(8), 0, 0);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rowsScroll.addView(rows);
        LinearLayout.LayoutParams rowsLp = new LinearLayout.LayoutParams(-1,
            Math.round(getResources().getDisplayMetrics().heightPixels * 0.42f));
        rowsLp.topMargin = px(20);
        home.addView(rowsScroll, rowsLp);

        rowsTop = column();
        rows.addView(rowsTop);
        appRow = new LinearLayout(this);
        rows.addView(section(getString(R.string.row_apps), null, new HorizontalScrollView(this), appRow));
        rowsBottom = column();
        rows.addView(rowsBottom);

        appsPane = new ScrollView(this);
        appsPane.setVerticalScrollBarEnabled(false);
        appsPane.setFocusable(false);
        appsPane.setVisibility(View.GONE);
        appsGrid = column();
        appsGrid.setPadding(px(12), px(56), 0, px(48));
        appsPane.addView(appsGrid);
        panes.addView(appsPane, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    /** Back to the LIVE stage with focus on it, then refreshes the apps and every row. */
    @Override
    protected void onResume() {
        super.onResume();
        tick.run();
        loadApps();
        loadRows(true);
        // Apps rewrite their rows a few seconds after they close (YouTube does): show the new copy.
        getContentResolver().registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, tvRowsChanged);
        getContentResolver().registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, tvRowsChanged);
        if (!showingApps) {
            showStage(0);
            stageDots[0].requestFocus();
        }
    }

    /** Frees the tuner and stops the clock while another app is on screen. */
    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadTvRows);
        getContentResolver().unregisterContentObserver(tvRowsChanged);
        setLive(false);
        super.onPause();
    }

    /**
     * Home pressed while Home is already showing: back to the Home tab, the LIVE stage, rows at the top.
     *
     * @param intent the HOME intent
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        showApps(false);
        rowsScroll.scrollTo(0, 0);
        showStage(0);
        stageDots[0].requestFocus();
    }

    // ---- Hero and stage -------------------------------------------------------------------------

    /**
     * The hero: where it comes from, a large title, a meta line and a description, with the stage
     * (LIVE and three Plex picks) at the bottom right.
     *
     * @return the hero block
     */
    private View hero() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(0, 0, px(96), 0);
        row.setClipChildren(false);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout source = new LinearLayout(this);
        source.setGravity(Gravity.CENTER_VERTICAL);
        heroLogo = new ImageView(this);
        heroLogo.setClipToOutline(true);
        source.addView(heroLogo, new LinearLayout.LayoutParams(px(30), px(30)));
        heroSource = text(22, TEXT, false);
        heroSource.setPadding(px(12), 0, 0, 0);
        source.addView(heroSource);
        texts.addView(source);
        heroTitle = text(84, TEXT, false);
        heroTitle.setSingleLine(true);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setLetterSpacing(-0.02f);
        texts.addView(heroTitle);
        heroMeta = text(30, TEXT, false);
        heroMeta.setSingleLine(true);
        heroMeta.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(heroMeta);
        heroDesc = text(23, MUTED, false);
        heroDesc.setMaxLines(2);
        heroDesc.setEllipsize(TextUtils.TruncateAt.END);
        heroDesc.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(px(1060), -2);
        descLp.topMargin = px(12);
        texts.addView(heroDesc, descLp);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout stage = new LinearLayout(this);
        stage.setGravity(Gravity.CENTER_VERTICAL);
        stage.setClipChildren(false);
        stageView = stage;
        for (int i = 0; i < stageDots.length; i++) {
            stageDots[i] = stageDot(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = i == 0 ? 0 : px(10);
            stage.addView(stageDots[i], lp);
        }
        lockRightEdge(stage);
        row.addView(stage, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    /**
     * One stage button: the LIVE pill or a dot for a Plex pick, with a ring while focused.
     *
     * @param slot 0 for LIVE, 1-3 for the picks
     * @return the focusable button
     */
    private View stageDot(int slot) {
        FrameLayout ring = new FrameLayout(this);
        ring.setFocusable(true);
        ring.setPadding(px(6), px(6), px(6), px(6));
        GradientDrawable ringBg = new GradientDrawable();
        ringBg.setCornerRadius(px(30));
        ring.setBackground(ringBg);
        View inner;
        if (slot == 0) {
            TextView pill = text(18, Color.WHITE, true);
            pill.setText(R.string.live_badge);
            pill.setPadding(px(16), px(4), px(16), px(4));
            inner = pill;
            ring.addView(pill);
        } else {
            inner = new View(this);
            ring.addView(inner, new FrameLayout.LayoutParams(px(22), px(22)));
        }
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(px(20));
        inner.setBackground(fill);
        ring.setTag(fill);
        ring.setOnFocusChangeListener((v, has) -> {
            ringBg.setStroke(has ? px(4) : 0, TEXT);
            if (has) {
                showStage(slot);
            }
        });
        ring.setOnClickListener(v -> {
            if (slot == 0) {
                openLiveTv();
            } else if (slot - 1 < picks.size()) {
                open(picks.get(slot - 1));
            }
        });
        return ring;
    }

    /**
     * Shows the stage slot in the hero: live TV, or one of the Plex picks.
     *
     * @param slot 0 for LIVE, 1-3 for the picks
     */
    private void showStage(int slot) {
        stageSlot = slot;
        for (int i = 0; i < stageDots.length; i++) {
            GradientDrawable fill = (GradientDrawable) stageDots[i].getTag();
            boolean on = i == slot;
            fill.setColor(i == 0 ? (on ? LIVE_RED : Color.argb(90, 241, 243, 244)) : (on ? TEXT : Color.argb(90, 241, 243, 244)));
            stageDots[i].setVisibility(i == 0 || i - 1 < picks.size() ? View.VISIBLE : View.GONE);
        }
        if (slot == 0 || slot - 1 >= picks.size()) {
            showLive();
        } else {
            showItem(picks.get(slot - 1), getString(R.string.plex_recommendations));
        }
    }

    /** The LIVE stage: the antenna channel full screen, its name in the hero. */
    private void showLive() {
        setLive(true);
        heroLogo.setImageDrawable(null);
        GradientDrawable dot = new GradientDrawable();
        dot.setColor(LIVE_RED);
        dot.setShape(GradientDrawable.OVAL);
        heroLogo.setBackground(dot);
        heroSource.setText(R.string.live_source);
        if (live != null) {
            heroTitle.setText(live.name());
            heroMeta.setText(live.number().isEmpty() ? "" : "CH " + live.number());
        } else {
            heroTitle.setText(R.string.no_tuner);
            heroMeta.setText("");
        }
        heroDesc.setText(R.string.live_hint);
    }

    /**
     * Shows an item in the hero: its artwork behind, a ghost title on the right, where it comes from,
     * its title, meta line and description. Live TV stops while an item is shown.
     *
     * @param item the item
     * @param rowTitle the row it comes from
     */
    private void showItem(TvRows.Item item, String rowTitle) {
        if (item.action != null) {
            return;
        }
        setLive(false);
        String uri = item.imageUri;
        if (uri != null && uri.contains("/photo/:/transcode")) {
            uri = uri.replace("width=320&height=180", "width=1280&height=720");
        }
        if (uri == null || !uri.equals(heroArt.getTag())) {
            heroArt.setImageDrawable(null);
            images.load(uri, heroArt, 1280);
        }
        ghost.setText(item.title);
        // The app's logo says where it comes from, so neither line repeats the app's name.
        heroLogo.setBackground(logoShape());
        heroLogo.setImageDrawable(appIcon(item.packageName));
        heroSource.setText(rowTitle);
        heroTitle.setText(item.title);
        List<String> meta = new ArrayList<>();
        if (item.episode != null) {
            meta.add(item.episode);
        } else if (item.author != null) {
            meta.add(item.author);
        }
        String badge = item.badge();
        if (badge != null) {
            meta.add("Next".equals(badge) ? "Next episode" : badge);
        }
        if (item.watchedAtMs > 0) {
            meta.add(ago(item.watchedAtMs));
        }
        heroMeta.setText(TextUtils.join("   ·   ", meta));
        heroDesc.setText(item.description != null ? item.description : "");
    }

    /**
     * Turns the live picture on or off. Off stops the tuner, so no sound plays behind an item.
     *
     * @param on whether live TV shows
     */
    private void setLive(boolean on) {
        if (on && !liveOn) {
            heroArt.setVisibility(View.GONE);
            ghost.setVisibility(View.GONE);
            if (live != null) {
                tvView.setVisibility(View.VISIBLE);
                live.start();
            }
        } else if (!on && liveOn) {
            if (live != null) {
                live.stop();
                tvView.setVisibility(View.INVISIBLE);
            }
            heroArt.setVisibility(View.VISIBLE);
            ghost.setVisibility(View.VISIBLE);
        }
        liveOn = on;
    }

    /**
     * @return the rounded square an app logo is clipped to
     */
    private GradientDrawable logoShape() {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(px(7));
        return d;
    }

    /**
     * @param pkg package name, or null
     * @return the app's icon, or null when unknown
     */
    private Drawable appIcon(String pkg) {
        try {
            return pkg != null ? getPackageManager().getApplicationIcon(pkg) : null;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Opens the antenna channel on screen full screen in the TV's Live TV app. */
    private void openLiveTv() {
        long id = live != null ? live.channelId() : -1;
        if (id >= 0) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception e) {
                // No app answers the channel URI: open Live TV as it is.
            }
        }
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage("com.tcl.tv");
        if (launch != null) {
            start(launch);
        }
    }

    /**
     * @param ms a time in the past
     * @return "12m ago", "3h ago", "Yesterday" or "4d ago"
     */
    private static String ago(long ms) {
        long m = Math.max(0, (System.currentTimeMillis() - ms) / 60_000);
        if (m < 60) {
            return m + "m ago";
        }
        if (m < 24 * 60) {
            return (m / 60) + "h ago";
        }
        return m < 48 * 60 ? "Yesterday" : (m / (24 * 60)) + "d ago";
    }

    // ---- Keys -----------------------------------------------------------------------------------

    /**
     * Vertical moves between the bands, in the order top bar, stage, rows. Plain geometric focus search
     * misses the small stage and the top bar from cards far to the right.
     *
     * @param event the key event
     * @return true when handled here
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && !showingApps) {
            View f = getCurrentFocus();
            int key = event.getKeyCode();
            if (f != null && key == KeyEvent.KEYCODE_DPAD_UP && inTopRow(f)) {
                lastCard = f;
                stageDots[stageSlot].requestFocus();
                return true;
            }
            if (f != null && key == KeyEvent.KEYCODE_DPAD_UP && isInside(f, stageView)) {
                homeTab.requestFocus();
                return true;
            }
            if (f != null && key == KeyEvent.KEYCODE_DPAD_DOWN && isInside(f, topBarView)) {
                stageDots[stageSlot].requestFocus();
                return true;
            }
            if (f != null && key == KeyEvent.KEYCODE_DPAD_DOWN && isInside(f, stageView)) {
                View target = lastCard != null && lastCard.isAttachedToWindow() && lastCard.isShown() ? lastCard : firstCard();
                if (target != null) {
                    target.requestFocus();
                    return true;
                }
            }
        } else if (event.getAction() == KeyEvent.ACTION_DOWN && showingApps
                && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN && isInside(getCurrentFocus(), topBarView)) {
            View first = firstGridTile();
            if (first != null) {
                first.requestFocus();
                return true;
            }
        } else if (event.getAction() == KeyEvent.ACTION_DOWN && showingApps
                && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP && inFirstGridLine(getCurrentFocus())) {
            appsTab.requestFocus();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * CH+ and CH- change the live channel while live TV shows; Back does nothing on Home.
     *
     * @param keyCode the key pressed
     * @param event the key event
     * @return true when the key was handled here
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (live != null && liveOn && (keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN)) {
            live.step(keyCode == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1);
            showLive();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * @param v a focused view
     * @return whether it sits in the top row of the Home rows
     */
    private boolean inTopRow(View v) {
        View first = firstCard();
        return first != null && v.getParent() == first.getParent();
    }

    /**
     * @param v a focused view, or null
     * @return whether it sits in the first line of the Apps grid
     */
    private boolean inFirstGridLine(View v) {
        View first = firstGridTile();
        return v != null && first != null && v.getParent() == first.getParent();
    }

    /**
     * @return the first card of the top row, or the first app when there are no content rows
     */
    private View firstCard() {
        for (int i = 0; i < rowsTop.getChildCount(); i++) {
            LinearLayout row = rowOf(rowsTop.getChildAt(i));
            if (row.getChildCount() > 0) {
                return row.getChildAt(0);
            }
        }
        return appRow.getChildCount() > 0 ? appRow.getChildAt(0) : null;
    }

    /**
     * @return the first tile of the Apps grid, or null
     */
    private View firstGridTile() {
        LinearLayout line = appsGrid.getChildCount() > 0 ? (LinearLayout) appsGrid.getChildAt(0) : null;
        return line != null && line.getChildCount() > 0 ? line.getChildAt(0) : null;
    }

    /**
     * @param section a view made by section()
     * @return its row of cards
     */
    private static LinearLayout rowOf(View section) {
        return (LinearLayout) ((HorizontalScrollView) ((LinearLayout) section).getChildAt(1)).getChildAt(0);
    }

    /**
     * @param v a view, or null
     * @param ancestor a possible ancestor
     * @return whether v is ancestor or inside it
     */
    private static boolean isInside(View v, View ancestor) {
        for (View x = v; x != null; x = x.getParent() instanceof View ? (View) x.getParent() : null) {
            if (x == ancestor) {
                return true;
            }
        }
        return false;
    }

    // ---- Rows -----------------------------------------------------------------------------------

    /**
     * Reads the TV provider rows off the main thread and shows them, then the Plex rows from the
     * server, which take a second or more each, showing the rows again as each arrives.
     *
     * @param plexRows whether to read the Plex rows from the server too, not just the TV provider
     */
    private void loadRows(boolean plexRows) {
        Set<String> excluded = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.watch_next_excluded)));
        String ytPkg = getString(R.string.youtube_package);
        String recsName = getString(R.string.plex_recommendations);
        boolean plexOn = getResources().getBoolean(R.bool.plex_enabled);
        int plexUser = getResources().getInteger(R.integer.plex_home_user);
        background.execute(() -> {
            List<TvRows.Item> wn = TvRows.continueWatching(this, excluded);
            List<TvRows.Item> recs = TvRows.channel(this, PLEX_PKG, recsName);
            List<List<TvRows.Item>> yt = new ArrayList<>();
            for (String name : youtubeRows) {
                yt.add(TvRows.channel(this, ytPkg, name));
            }
            handler.post(() -> {
                watchNext = wn;
                plexRecs = recs;
                youtube = yt;
                showRows();
            });
            if (!plexOn || !plex.signedIn()) {
                return;
            }
            try {
                plex.fillArt(plexUser, recs);
                handler.post(this::showRows);
                if (!plexRows) {
                    return;
                }
                List<TvRows.Item> deck = plex.items(plexUser, Plex.ON_DECK);
                handler.post(() -> {
                    plexOnDeck = deck;
                    showRows();
                    loadAvatar();
                });
                List<TvRows.Item> recent = plex.items(plexUser, Plex.RECENTLY_ADDED);
                handler.post(() -> {
                    plexRecent = recent;
                    showRows();
                });
                List<TvRows.Item> movies = plex.movies(plexUser);
                handler.post(() -> {
                    plexMovies = movies;
                    showRows();
                });
            } catch (Exception e) {
                // Server or plex.tv unreachable: keep the last Plex rows on screen.
            }
        });
    }

    /**
     * Rebuilds the content rows in the design's order, keeping the focused card when it is still there:
     * Continue watching, Recommendations from Plex, YouTube's first row, Recently added, then (after the
     * apps) YouTube's second row and Movies in your library.
     */
    private void showRows() {
        View focused = getCurrentFocus();
        boolean inRows = focused != null && (isInside(focused, rowsTop) || isInside(focused, rowsBottom));
        Object focusedTag = inRows ? focused.getTag() : null;
        boolean lastWasCard = lastCard != null && (isInside(lastCard, rowsTop) || isInside(lastCard, rowsBottom));
        Object lastTag = lastWasCard ? lastCard.getTag() : null;

        picks = plexRecs.size() > 0 ? plexRecs.subList(0, Math.min(3, plexRecs.size()))
            : plexRecent.subList(0, Math.min(3, plexRecent.size()));
        rowsTop.removeAllViews();
        rowsBottom.removeAllViews();
        String ytPkg = getString(R.string.youtube_package);
        addRow(rowsTop, getString(R.string.row_continue), null, continueItems(), true);
        addRow(rowsTop, getString(R.string.plex_recommendations), PLEX_PKG, plexRecs, false);
        if (youtubeRows.length > 0 && youtube.size() > 0) {
            addRow(rowsTop, youtubeRows[0], ytPkg, youtube.get(0), false);
        }
        addRow(rowsTop, getString(R.string.row_recently_added), PLEX_PKG, plexRecent, false);
        for (int i = 1; i < youtubeRows.length && i < youtube.size(); i++) {
            addRow(rowsBottom, youtubeRows[i], ytPkg, youtube.get(i), false);
        }
        addRow(rowsBottom, getString(R.string.row_movies), PLEX_PKG, plexMovies, false);

        for (int i = 1; i < stageDots.length; i++) {
            stageDots[i].setVisibility(i - 1 < picks.size() ? View.VISIBLE : View.GONE);
        }
        if (lastTag != null) {
            View again = findCard(lastTag);
            lastCard = again;
        }
        if (focused != null && isInside(focused, stageView) && stageSlot > 0) {
            // The picks were just rebuilt: show the new copy (it may have artwork now).
            showStage(stageSlot);
        }
        if (focusedTag != null) {
            View again = findCard(focusedTag);
            View target = again != null ? again : firstCard();
            if (target != null) {
                target.requestFocus();
            }
        }
    }

    /**
     * @param tag a card tag
     * @return the card with that tag in either row block, or null
     */
    private View findCard(Object tag) {
        View v = rowsTop.findViewWithTag(tag);
        return v != null ? v : rowsBottom.findViewWithTag(tag);
    }

    /**
     * Continue watching: Watch Next plus the Plex On Deck, most recent first, ending with the Plex
     * sign-in card while signed out.
     *
     * @return the row's items
     */
    private List<TvRows.Item> continueItems() {
        List<TvRows.Item> all = new ArrayList<>(watchNext);
        boolean plexOn = getResources().getBoolean(R.bool.plex_enabled);
        if (plexOn && plex.signedIn()) {
            all.addAll(plexOnDeck);
        }
        all.sort((a, b) -> Long.compare(b.sortKey, a.sortKey));
        if (plexOn && !plex.signedIn()) {
            TvRows.Item signIn = new TvRows.Item();
            signIn.action = "plex-login";
            signIn.title = getString(R.string.plex_card);
            signIn.packageName = PLEX_PKG;
            all.add(signIn);
        }
        return all;
    }

    /**
     * Adds one row of content cards; an empty row is left out.
     *
     * @param into the block that takes the row
     * @param title row title
     * @param logoPkg the app whose logo follows the title, or null for a row that mixes apps
     * @param items the cards
     * @param namesApp whether each card names the app it opens (rows that mix apps)
     */
    private void addRow(LinearLayout into, String title, String logoPkg, List<TvRows.Item> items, boolean namesApp) {
        if (items.isEmpty()) {
            return;
        }
        LinearLayout row = new LinearLayout(this);
        for (TvRows.Item item : items) {
            row.addView(card(item, title, namesApp));
        }
        into.addView(section(title, logoPkg, new HorizontalScrollView(this), row));
    }

    /**
     * Wraps a horizontal row with its title and, for a row from one app, that app's logo.
     *
     * @param title row title
     * @param logoPkg the app whose logo follows the title, or null
     * @param scroll the row's scroller
     * @param row the row's card container
     * @return the section view
     */
    private View section(String title, String logoPkg, HorizontalScrollView scroll, LinearLayout row) {
        LinearLayout box = column();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(26, TEXT, false);
        t.setText(title);
        head.addView(t);
        Drawable icon = appIcon(logoPkg);
        if (icon != null) {
            ImageView logo = new ImageView(this);
            logo.setImageDrawable(icon);
            logo.setBackground(logoShape());
            logo.setClipToOutline(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(28), px(28));
            lp.leftMargin = px(14);
            head.addView(logo, lp);
        }
        box.addView(head);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        scroll.setPadding(px(12), px(16), px(96), px(24));
        row.setClipChildren(false);
        scroll.addView(row);
        // Right on a row's last card stays put instead of jumping to another row.
        row.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                lockRightEdge(row);
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
                lockRightEdge(row);
            }
        });
        lockRightEdge(row);
        box.addView(scroll);
        return box;
    }

    /**
     * Makes Right on the last child of a row go nowhere.
     *
     * @param row a row of focusable views
     */
    private static void lockRightEdge(LinearLayout row) {
        int n = row.getChildCount();
        for (int i = 0; i < n; i++) {
            View child = row.getChildAt(i);
            if (child.getId() == View.NO_ID) {
                child.setId(View.generateViewId());
            }
            child.setNextFocusRightId(i == n - 1 ? child.getId() : View.NO_ID);
        }
    }

    /**
     * A 16:9 content card as in the design: artwork, a label top left (the app, channel or year), a
     * corner badge top right (time left, "Next" or the length), the title at the bottom and a progress
     * bar in the source's color. Focus shows the item in the hero; OK opens it.
     *
     * @param item what to show
     * @param rowTitle the row it sits in, for the hero
     * @param namesApp whether to name the app on the card
     * @return the focusable card
     */
    private View card(TvRows.Item item, String rowTitle, boolean namesApp) {
        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setTag(item.packageName + "|" + item.intentUri + "|" + item.title);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(16));
        card.setForeground(ring);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Color.rgb(42, 44, 49));
        fill.setCornerRadius(px(16));
        card.setBackground(fill);
        card.setClipToOutline(true);

        boolean isPlex = PLEX_PKG.equals(item.packageName);
        boolean isYoutube = getString(R.string.youtube_package).equals(item.packageName);
        if (item.action != null) {
            fill.setColor(Color.rgb(31, 31, 31));
        }
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(art, new FrameLayout.LayoutParams(-1, -1));
        images.load(item.imageUri, art);

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {Color.argb(150, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(210, 0, 0, 0)}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        String label = namesApp || item.action != null ? appLabel(item.packageName)
            : isYoutube ? item.author : isPlex ? item.author : null;
        if (label != null) {
            TextView kicker = text(15, Color.argb(220, 255, 255, 255), true);
            kicker.setText(label.toUpperCase());
            kicker.setLetterSpacing(0.06f);
            kicker.setSingleLine(true);
            kicker.setEllipsize(TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
            lp.setMargins(px(14), px(12), px(110), 0);
            card.addView(kicker, lp);
        }
        String badge = item.action == null ? item.badge() : null;
        if (badge != null) {
            TextView b = text(15, Color.WHITE, true);
            b.setText(badge);
            b.setPadding(px(8), px(2), px(8), px(2));
            GradientDrawable bbg = new GradientDrawable();
            bbg.setColor(Color.argb(185, 0, 0, 0));
            bbg.setCornerRadius(px(6));
            b.setBackground(bbg);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            lp.setMargins(0, px(10), px(12), 0);
            card.addView(b, lp);
        }

        float progress = item.progress();
        TextView title = text(item.action != null ? 20 : 24, Color.WHITE, true);
        title.setText(item.title);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        titleLp.setMargins(px(14), 0, px(14), progress >= 0 ? px(22) : px(14));
        card.addView(title, titleLp);

        if (progress >= 0) {
            View track = new View(this);
            track.setBackgroundColor(Color.argb(80, 255, 255, 255));
            FrameLayout.LayoutParams trackLp = new FrameLayout.LayoutParams(-1, px(4), Gravity.BOTTOM);
            trackLp.setMargins(px(14), 0, px(14), px(12));
            card.addView(track, trackLp);
            View bar = new View(this);
            bar.setBackgroundColor(isPlex ? PLEX : isYoutube ? YOUTUBE : Color.WHITE);
            FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(Math.round(px(292) * progress), px(4), Gravity.BOTTOM);
            barLp.setMargins(px(14), 0, 0, px(12));
            card.addView(bar, barLp);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(320), px(180));
        lp.rightMargin = px(28);
        card.setLayoutParams(lp);
        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(150).start();
            ring.setStroke(has ? px(4) : 0, TEXT);
            if (has) {
                lastCard = v;
                showItem(item, rowTitle);
            }
        });
        card.setOnClickListener(v -> open(item));
        return card;
    }

    /**
     * Opens an item the way its app asked (its intent URI), else just launches the app. The Plex
     * sign-in card opens the sign-in screen.
     *
     * @param item the card's item
     */
    private void open(TvRows.Item item) {
        if ("plex-login".equals(item.action)) {
            startActivity(new Intent(this, PlexLoginActivity.class));
            return;
        }
        try {
            if (item.intentUri != null) {
                startActivity(Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
        } catch (Exception e) {
            // Fall through to launching the app.
        }
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(item.packageName);
        if (launch != null) {
            start(launch);
        }
    }

    /**
     * @param pkg package name
     * @return the app's name, or null when unknown
     */
    private String appLabel(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return getPackageManager().getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Apps -----------------------------------------------------------------------------------

    /**
     * Fills the app row and the Apps grid: dock_apps in their order, then the other TV apps A to Z,
     * minus hidden_apps.
     */
    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, ResolveInfo> byPkg = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            byPkg.putIfAbsent(ri.activityInfo.packageName, ri);
        }
        Set<String> hidden = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());

        List<ResolveInfo> ordered = new ArrayList<>();
        for (String pkg : getResources().getStringArray(R.array.dock_apps)) {
            ResolveInfo ri = byPkg.remove(pkg);
            if (ri != null && !hidden.contains(pkg)) {
                ordered.add(ri);
            }
        }
        List<ResolveInfo> rest = new ArrayList<>(byPkg.values());
        rest.removeIf(ri -> hidden.contains(ri.activityInfo.packageName));
        rest.sort((a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        ordered.addAll(rest);

        appRow.removeAllViews();
        for (ResolveInfo ri : ordered) {
            appRow.addView(tile(pm, ri));
        }
        appsGrid.removeAllViews();
        LinearLayout line = null;
        for (int n = 0; n < ordered.size(); n++) {
            if (n % GRID_COLUMNS == 0) {
                line = new LinearLayout(this);
                line.setClipChildren(false);
                LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-2, -2);
                lineLp.bottomMargin = px(28);
                appsGrid.addView(line, lineLp);
            }
            line.addView(tile(pm, ordered.get(n)));
        }
    }

    /**
     * One app card: the app's TV banner (or its icon), its name under it, a ring and a lift on focus.
     *
     * @param pm package manager for labels and artwork
     * @param ri the app's launcher activity
     * @return the focusable tile
     */
    private View tile(PackageManager pm, ResolveInfo ri) {
        String pkg = ri.activityInfo.packageName;
        LinearLayout cell = column();
        cell.setFocusable(true);
        cell.setTag(pkg);

        FrameLayout card = new FrameLayout(this);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Color.rgb(42, 44, 49));
        fill.setCornerRadius(px(16));
        card.setBackground(fill);
        card.setClipToOutline(true);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(16));
        card.setForeground(ring);
        ImageView art = new ImageView(this);
        Drawable banner = ri.activityInfo.loadBanner(pm);
        if (banner != null) {
            art.setImageDrawable(banner);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(art, new FrameLayout.LayoutParams(-1, -1));
        } else {
            art.setImageDrawable(ri.loadIcon(pm));
            card.addView(art, new FrameLayout.LayoutParams(px(96), px(96), Gravity.CENTER));
        }
        cell.addView(card, new LinearLayout.LayoutParams(px(240), px(135)));

        TextView name = text(19, MUTED, false);
        name.setText(ri.loadLabel(pm));
        name.setSingleLine(true);
        name.setPadding(px(6), px(10), 0, 0);
        cell.addView(name, new LinearLayout.LayoutParams(px(240), -2));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = px(28);
        cell.setLayoutParams(lp);
        cell.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(150).start();
            ring.setStroke(has ? px(4) : 0, TEXT);
            name.setTextColor(has ? TEXT : MUTED);
            if (has && isInside(v, rowsScroll)) {
                lastCard = v;
            }
        });
        cell.setOnClickListener(v -> {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch != null) {
                start(launch);
            }
        });
        return cell;
    }

    // ---- Top bar --------------------------------------------------------------------------------

    /**
     * The top bar from the design: profile, a pill with Search, Home and Apps, a pill with ambient mode
     * and Settings, and the clock on the right.
     *
     * @return the bar
     */
    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, px(96), 0);
        bar.setClipChildren(false);
        bar.addView(avatar(), new LinearLayout.LayoutParams(px(80), px(80)));

        LinearLayout nav = pill();
        nav.addView(pillButton(null, Icon.SEARCH, v -> start(new Intent("android.search.action.GLOBAL_SEARCH"))));
        homeTab = pillButton(getString(R.string.tab_home), -1, v -> showApps(false));
        appsTab = pillButton(getString(R.string.tab_apps), -1, v -> showApps(true));
        nav.addView(homeTab);
        nav.addView(appsTab);
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(-2, -2);
        navLp.leftMargin = px(28);
        bar.addView(nav, navLp);

        LinearLayout tools = pill();
        tools.addView(pillButton(null, Icon.PICTURE,
            v -> start(new Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator"))));
        tools.addView(pillButton(null, Icon.GEAR, v -> start(new Intent(android.provider.Settings.ACTION_SETTINGS))));
        LinearLayout.LayoutParams toolsLp = new LinearLayout.LayoutParams(-2, -2);
        toolsLp.leftMargin = px(28);
        bar.addView(tools, toolsLp);

        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        clock = text(40, Color.argb(200, 241, 243, 244), false);
        bar.addView(clock);
        markTabs();
        return bar;
    }

    /**
     * The round profile picture: the Plex user's picture once known, the configured letter before that.
     * Not focusable: there is no profile switching from Home.
     *
     * @return the picture
     */
    private View avatar() {
        FrameLayout avatar = new FrameLayout(this);
        GradientDrawable face = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[] {Color.rgb(207, 214, 221), Color.rgb(107, 118, 130)});
        face.setShape(GradientDrawable.OVAL);
        avatar.setBackground(face);
        avatar.setClipToOutline(true);
        TextView letter = text(32, Color.rgb(27, 29, 32), true);
        letter.setText(getString(R.string.profile_initial));
        letter.setGravity(Gravity.CENTER);
        avatar.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        avatarPhoto = new ImageView(this);
        avatarPhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.addView(avatarPhoto, new FrameLayout.LayoutParams(-1, -1));
        return avatar;
    }

    /** Puts the Plex user's picture on the profile button once Plex has told us where it is. */
    private void loadAvatar() {
        String thumb = plex.userThumb();
        if (thumb != null && !thumb.isEmpty()) {
            images.load(thumb, avatarPhoto, px(80));
        }
    }

    /**
     * @return an empty rounded pill that holds top-bar buttons
     */
    private LinearLayout pill() {
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(px(8), px(8), px(8), px(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(217, 42, 44, 49));
        bg.setCornerRadius(px(48));
        pill.setBackground(bg);
        return pill;
    }

    /**
     * One button inside a pill: a label or an icon. Focus turns it light with dark content; the tab on
     * screen keeps a soft highlight.
     *
     * @param label text, or null for an icon button
     * @param icon Icon kind when label is null
     * @param onClick what OK does
     * @return the button
     */
    private View pillButton(String label, int icon, View.OnClickListener onClick) {
        FrameLayout b = new FrameLayout(this);
        b.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(40));
        b.setBackground(bg);
        TextView t = label != null ? text(32, TEXT, false) : null;
        Icon i = label == null ? new Icon(this, icon) : null;
        if (t != null) {
            t.setText(label);
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(40), 0, px(40), 0);
            b.addView(t, new FrameLayout.LayoutParams(-2, -1));
        } else {
            i.setColor(TEXT);
            b.addView(i, new FrameLayout.LayoutParams(px(34), px(34), Gravity.CENTER));
        }
        b.setTag(R.id.tab_selected, false);
        b.setOnFocusChangeListener((v, has) -> paintPillButton(b, bg, t, i));
        b.setOnClickListener(onClick);
        b.setMinimumWidth(px(80));
        b.setLayoutParams(new LinearLayout.LayoutParams(-2, px(80)));
        paintPillButton(b, bg, t, i);
        return b;
    }

    /**
     * Colors a pill button for its focus and selected state.
     *
     * @param b the button
     * @param bg its background
     * @param t its label, or null
     * @param i its icon, or null
     */
    private void paintPillButton(View b, GradientDrawable bg, TextView t, Icon i) {
        boolean focused = b.isFocused();
        boolean selected = Boolean.TRUE.equals(b.getTag(R.id.tab_selected));
        bg.setColor(focused ? Color.rgb(232, 234, 237) : selected ? Color.argb(56, 232, 234, 237) : Color.TRANSPARENT);
        int fg = focused ? Color.rgb(23, 24, 27) : TEXT;
        if (t != null) {
            t.setTextColor(fg);
        }
        if (i != null) {
            i.setColor(fg);
        }
    }

    /** Gives the Home or Apps button the selected highlight. */
    private void markTabs() {
        homeTab.setTag(R.id.tab_selected, !showingApps);
        appsTab.setTag(R.id.tab_selected, showingApps);
        homeTab.getOnFocusChangeListener().onFocusChange(homeTab, homeTab.isFocused());
        appsTab.getOnFocusChangeListener().onFocusChange(appsTab, appsTab.isFocused());
    }

    /**
     * Switches between Home and the Apps grid. The Apps grid has no live picture or hero: the tuner
     * stops while it shows.
     *
     * @param apps true for the Apps grid
     */
    private void showApps(boolean apps) {
        showingApps = apps;
        homePane.setVisibility(apps ? View.GONE : View.VISIBLE);
        appsPane.setVisibility(apps ? View.VISIBLE : View.GONE);
        if (apps) {
            setLive(false);
            heroArt.setVisibility(View.GONE);
            ghost.setVisibility(View.GONE);
        } else if (stageSlot == 0) {
            showStage(0);
        }
        markTabs();
    }

    /**
     * Starts another app's screen; a missing app is ignored.
     *
     * @param intent what to open
     */
    private void start(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            // Nothing to open on this TV.
        }
    }

    // ---- Helpers --------------------------------------------------------------------------------

    /**
     * @return a vertical LinearLayout that lets focused children grow past it
     */
    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setClipChildren(false);
        return l;
    }

    /**
     * @param size text size in 1080p design pixels
     * @param color text color
     * @param bold whether to use the bold weight
     * @return a styled TextView
     */
    private TextView text(float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    /**
     * Sizes follow a 1920x1080 design, scaled to the real screen width: this TV reports density 2.0,
     * which would double dp-based sizes on a 1080p panel.
     *
     * @param value size in 1080p design pixels
     * @return the size in real pixels
     */
    private int px(float value) {
        return Math.round(value * getResources().getDisplayMetrics().widthPixels / 1920f);
    }
}
