package dev.tcl.home;

import android.app.Activity;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.net.Uri;
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
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen, laid out like Google TV (see the MagicPath design): a top bar, a hero that shows live
 * antenna TV, and rows below. Moving into the rows swaps the live picture for the focused card's
 * artwork, title and description.
 */
public class HomeActivity extends Activity {
    private static final long CLOCK_TICK_MS = 15_000;
    private static final long TV_ROWS_SETTLE_MS = 2_000;
    private static final long GUIDE_SETTLE_MS = 500;
    private static final long PLEX_RETRY_MS = 15_000;
    // How much of the rows above and below the focused row shows, in 1080p design pixels.
    private static final int ROW_PEEK = 44;
    private static final int TEXT = Color.rgb(241, 243, 244);
    private static final int MUTED = Color.rgb(168, 171, 177);
    private static final int BG = Color.rgb(15, 16, 18);
    private static final int PLEX = Color.rgb(229, 160, 13);
    private static final int YOUTUBE = Color.rgb(255, 0, 51);
    private static final int GRID_COLUMNS = 6;
    private static final int MOVE_RING = Color.rgb(0xff, 0xe5, 0x99);
    private static final String PLEX_PKG = "com.plexapp.android";
    private static final String LIVE_TV_PKG = "com.tcl.tv";
    // Tag of the All apps tile that ends the Your apps row.
    private static final String ALL_APPS = "all-apps";
    private static final String STOCK_HOME = "stock-home";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    // The guide lookup gets its own thread: background can be busy for seconds with Plex requests.
    private final ExecutorService guide = Executors.newSingleThreadExecutor();
    // When the program shown in the hero ends, so the clock tick reads the next one; 0 for none.
    private long programEndMs;
    private Images images;
    private Plex plex;
    private LiveTv live;
    private TvView tvView;
    // Whether the hero shows live TV (image layer faded out).
    private boolean liveOn;
    // Whether the tuner plays in tvView. It keeps playing, with sound, while the hero shows something
    // else, so live TV is back at once; it is released only when Home leaves the screen.
    private boolean tuned;

    // Row data; the Plex lists keep the last copy so the next Home shows them at once.
    private List<TvRows.Item> watchNext = new ArrayList<>();
    private List<TvRows.Item> plexRecs = new ArrayList<>();
    private List<List<TvRows.Item>> youtube = new ArrayList<>();
    private List<TvRows.Item> plexOnDeck = new ArrayList<>();
    private List<TvRows.Item> plexRecent = new ArrayList<>();
    private List<TvRows.Item> plexMovies = new ArrayList<>();
    private List<TvRows.Item> spotify = new ArrayList<>();
    private String[] youtubeRows;

    // Over live TV: a dark layer holding two artwork views that take turns, for crossfades.
    private FrameLayout heroLayer;
    private ImageView heroArt;
    private ImageView heroArtBack;
    private View heroTexts;
    private ImageView heroLogo;
    private TextView heroSource;
    private TextView heroTitle;
    private TextView heroMeta;
    private TextView heroDesc;

    private TextView clock;
    private Icon weatherIcon;
    private TextView weatherText;
    private TextView aqiPill;
    // Repaint each top-bar pill button for the current focus.
    private final List<Runnable> pillPainters = new ArrayList<>();
    // The fill that slides between the Home and Apps tabs, its color now, and its running animation.
    private View tabHighlight;
    private GradientDrawable tabHighlightBg;
    private int tabHighlightColor;
    private ValueAnimator tabHighlightAnim;
    // The last button of the top bar; Right on it opens quick settings.
    private View settingsButton;
    // The name shown under a focused tool button.
    private TextView tip;
    private View weatherLine;
    private TextView rainText;
    private Weather weather;
    private long weatherTriedAt;
    private ImageView avatarPhoto;
    private LinearLayout rowsTop;
    private LinearLayout rowsBottom;
    private LinearLayout appRow;
    private View appSection;
    // The column of row sections inside rowsScroll.
    private LinearLayout rowsContent;
    // The app tile being moved along the row (held OK on it), or null.
    private View movingTile;
    // Set when OK or Back ended a move, so its key-up does not also open the app.
    private boolean swallowKeyUp;
    private boolean rowsExpanded = true;
    private ScrollView rowsScroll;
    private View homePane;
    private ScrollView appsPane;
    private LinearLayout appsGrid;
    private View homeTab;
    private View appsTab;
    private boolean showingApps;
    private View topBarView;
    // Card left when Up moved out of the rows, so Down returns to it.
    private View lastCard;

    private final Runnable reloadTvRows = () -> loadRows(false);
    private final Runnable retryPlexRows = () -> loadRows(true);
    // Whether Home is on screen: Plex rows are only retried then.
    private boolean resumed;
    // Whether another app hid Home since it was last on screen (true until the first onResume).
    private boolean hidden = true;
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

    private final Runnable reloadGuide = () -> {
        if (liveOn) {
            showOnAir();
        }
    };
    // The tuner writes the guide as it receives it, often after the tune: show it as soon as it lands.
    private final ContentObserver guideChanged = new ContentObserver(handler) {
        /**
         * Schedules a fresh read of the program on air, pushed back by every further change.
         *
         * @param selfChange unused
         */
        @Override
        public void onChange(boolean selfChange) {
            handler.removeCallbacks(reloadGuide);
            handler.postDelayed(reloadGuide, GUIDE_SETTLE_MS);
        }
    };

    // Whether live TV is muted because another app plays sound (see muteLiveFor).
    private boolean liveMuted;
    // Another app playing music or video behind Home (Spotify) mutes live TV until it stops.
    private final AudioManager.AudioPlaybackCallback otherAudio = new AudioManager.AudioPlaybackCallback() {
        /**
         * Mutes or unmutes live TV for what plays now.
         *
         * @param configs every player on the device
         */
        @Override
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            muteLiveFor(configs);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            clock.setText(DateFormat.format("HH:mm", System.currentTimeMillis()));
            long now = System.currentTimeMillis();
            if (weather.stale() && now - weatherTriedAt > Weather.RETRY_MS) {
                weatherTriedAt = now;
                background.execute(() -> {
                    try {
                        weather.refresh();
                        handler.post(HomeActivity.this::showWeather);
                    } catch (Exception e) {
                        // Offline: keep the last reading and try again after RETRY_MS.
                    }
                });
            }
            if (liveOn && programEndMs > 0 && now >= programEndMs) {
                showOnAir();
            }
            handler.postDelayed(this, CLOCK_TICK_MS);
        }
    };

    /**
     * Builds the screen in code: the background layer (live TV or the hero artwork), scrims, then the
     * top bar, the hero, and the rows.
     *
     * @param savedInstanceState unused
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        images = new Images(this);
        weather = new Weather(this);
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
        heroLayer = new FrameLayout(this);
        heroLayer.setBackgroundColor(BG);
        heroArt = new ImageView(this);
        heroArtBack = new ImageView(this);
        for (ImageView art : new ImageView[] {heroArtBack, heroArt}) {
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setAlpha(0f);
            heroLayer.addView(art, new FrameLayout.LayoutParams(-1, -1));
        }
        heroLayer.setAlpha(live != null ? 0f : 1f);
        root.addView(heroLayer, new FrameLayout.LayoutParams(-1, -1));

        View scrimSide = new View(this);
        scrimSide.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] {Color.argb(240, 15, 16, 18), Color.argb(170, 15, 16, 18), Color.argb(40, 15, 16, 18)}));
        root.addView(scrimSide, new FrameLayout.LayoutParams(-1, -1));
        View scrimBottom = new View(this);
        scrimBottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {Color.argb(130, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(170, 15, 16, 18), Color.argb(240, 15, 16, 18)}));
        root.addView(scrimBottom, new FrameLayout.LayoutParams(-1, -1));
        // Behind the top bar: keeps the clock and the pills readable over bright live TV or artwork.
        View scrimTop = new View(this);
        scrimTop.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {Color.argb(200, 0, 0, 0), Color.argb(120, 0, 0, 0), Color.argb(0, 0, 0, 0)}));
        root.addView(scrimTop, new FrameLayout.LayoutParams(-1, px(380), Gravity.TOP));

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
        // Collapsed, the rows stay at the top: the scroller moves the focused app into view by itself
        // when it resizes, which would hide the apps title.
        rowsScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (!rowsExpanded && y != 0) {
                v.scrollTo(0, 0);
            }
        });
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rowsScroll.addView(rows);
        rowsContent = rows;
        // Room under the last row, so it too can sit at the same place as every other row.
        rows.setPadding(0, 0, 0, px(ROW_PEEK));
        LinearLayout.LayoutParams rowsLp = new LinearLayout.LayoutParams(-1, 0);
        rowsLp.topMargin = px(20);
        home.addView(rowsScroll, rowsLp);

        appRow = new LinearLayout(this);
        appSection = section(getString(R.string.row_apps), null, new HorizontalScrollView(this), appRow);
        rows.addView(appSection);
        rowsTop = column();
        rows.addView(rowsTop);
        rowsBottom = column();
        rows.addView(rowsBottom);

        appsPane = new ScrollView(this);
        appsPane.setVerticalScrollBarEnabled(false);
        appsPane.setFocusable(false);
        appsPane.setVisibility(View.GONE);
        // The pane starts at the 96 px left margin; the same right padding centers the grid on screen.
        // The top gap is the pane's own padding, so scrolling never pushes tiles up against the top bar.
        appsPane.setPadding(0, px(48), px(96), 0);
        appsGrid = column();
        // Each tile carries a 28 px right margin; the same on the left keeps the grid symmetric. The top
        // padding leaves room for a focused tile's lift.
        appsGrid.setPadding(px(28), px(16), 0, px(48));
        appsGrid.setClipToPadding(false);
        appsPane.addView(appsGrid, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL));
        panes.addView(appsPane, new FrameLayout.LayoutParams(-1, -1));

        tip = text(22, TEXT, false);
        tip.setAlpha(0f);
        root.addView(tip, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        setContentView(root);
        // Only the apps show under the hero until focus moves into the content rows below them.
        setRowsExpanded(false);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            setRowsExpanded(newFocus != null && (isInside(newFocus, rowsTop) || isInside(newFocus, rowsBottom)));
            // Pill colors depend on where focus is, also outside the pill (dimmed text).
            for (Runnable paint : pillPainters) {
                paint.run();
            }
        });
    }

    /**
     * Refreshes the rows. Back from another app (Home was hidden): also the apps, and live TV with
     * focus on the first app. Back from a panel drawn over Home (quick settings, a dialog) or from
     * the Home key, Home stayed visible and stays as it was.
     */
    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        HomeRedirectService.cancelPlexPlay();
        tick.run();
        loadRows(true);
        // Apps rewrite their rows a few seconds after they close (YouTube does): show the new copy.
        getContentResolver().registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, tvRowsChanged);
        getContentResolver().registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, tvRowsChanged);
        getContentResolver().registerContentObserver(TvContract.Programs.CONTENT_URI, true, guideChanged);
        AudioManager audio = getSystemService(AudioManager.class);
        audio.registerAudioPlaybackCallback(otherAudio, handler);
        muteLiveFor(audio.getActivePlaybackConfigurations());
        if (hidden) {
            hidden = false;
            loadApps();
            View first = firstCard();
            if (!showingApps) {
                // The Home key already did this in onNewIntent.
                if (!liveOn) {
                    showLive();
                }
                if (first != null) {
                    first.requestFocus();
                }
            }
        }
    }

    /**
     * Saves a tile move in progress and stops the clock. The tuner keeps playing: the Home key pauses
     * Home only for the moment Google TV's home takes to send it back, and quick settings draw over it.
     */
    @Override
    protected void onPause() {
        if (movingTile != null) {
            finishMove();
        }
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadTvRows);
        handler.removeCallbacks(retryPlexRows);
        resumed = false;
        getContentResolver().unregisterContentObserver(tvRowsChanged);
        getContentResolver().unregisterContentObserver(guideChanged);
        handler.removeCallbacks(reloadGuide);
        getSystemService(AudioManager.class).unregisterAudioPlaybackCallback(otherAudio);
        super.onPause();
    }

    /**
     * Mutes live TV while another app plays music, video or a game, and gives it its sound back once
     * none does. The tuner's own track is told apart by its hardware A/V sync flag, which app players
     * do not set. A player's state is only public in its description ("state:started"): apps get
     * every player, anonymized, from the system. The TCL tuner keeps its sound off after volume 0
     * until it tunes again, so unmuting also starts a new session on the same channel.
     *
     * @param configs every player on the device
     */
    private void muteLiveFor(List<AudioPlaybackConfiguration> configs) {
        if (tvView == null) {
            return;
        }
        boolean other = false;
        for (AudioPlaybackConfiguration c : configs) {
            AudioAttributes a = c.getAudioAttributes();
            int usage = a.getUsage();
            if ((a.getFlags() & AudioAttributes.FLAG_HW_AV_SYNC) == 0
                    && (usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME)
                    && c.toString().contains("state:started")) {
                other = true;
                break;
            }
        }
        if (other == liveMuted) {
            return;
        }
        liveMuted = other;
        tvView.setStreamVolume(other ? 0f : 1f);
        if (!other && tuned) {
            // Tuning the channel it plays is ignored ("same program"): a new session it is.
            live.stop();
            tuned = live.start();
        }
    }

    /** Another app covers Home: frees the tuner for Live TV and other apps. */
    @Override
    protected void onStop() {
        releaseTuner();
        hidden = true;
        super.onStop();
    }

    /**
     * Stops live TV in the hero and gives the tuner back. Opening Live TV calls it first, so Live TV
     * never waits for Home to be hidden to get the tuner.
     */
    private void releaseTuner() {
        if (tuned) {
            live.stop();
            tuned = false;
        }
        liveOn = false;
    }

    /**
     * Home pressed while Home is already showing: back to the top (see backToTop).
     *
     * @param intent the HOME intent
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        backToTop();
    }

    /**
     * Back to the Home tab with focus on the first app and the rows at the top. Live TV keeps playing
     * when the hero already shows it, so it is not tuned again.
     */
    private void backToTop() {
        if (showingApps) {
            showApps(false);
        }
        rowsScroll.scrollTo(0, 0);
        View first = firstCard();
        if (first != null) {
            first.requestFocus();
        }
        if (!liveOn) {
            showLive();
        }
    }

    /**
     * Shows the rows area for one content row with a peek of its neighbors (focus in the content rows),
     * or only the apps row under the hero (the default view, so live TV or the pick fills most of the
     * screen) with the next row's title and the top of its cards peeking at the bottom edge, as a hint
     * that there is more below.
     *
     * @param expanded true while focus is in the content rows
     */
    private void setRowsExpanded(boolean expanded) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) rowsScroll.getLayoutParams();
        int height;
        if (expanded) {
            // One content row with a small peek of the rows above and below it.
            View sample = rowsTop.getChildCount() > 0 ? rowsTop.getChildAt(0)
                : rowsBottom.getChildCount() > 0 ? rowsBottom.getChildAt(0) : appSection;
            sample.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            height = rowsScroll.getPaddingTop() + px(ROW_PEEK) + sample.getMeasuredHeight() + px(ROW_PEEK);
        } else {
            appSection.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            height = rowsScroll.getPaddingTop() + appSection.getMeasuredHeight() + px(96);
        }
        int bottom = 0;
        // Set first: both sizes can come out the same, and the scroll pin depends on this.
        rowsExpanded = expanded;
        if (lp.height == height && lp.bottomMargin == bottom) {
            return;
        }
        lp.height = height;
        lp.bottomMargin = bottom;
        rowsScroll.setLayoutParams(lp);
        if (!expanded) {
            rowsScroll.scrollTo(0, 0);
        }
    }

    // ---- Hero -------------------------------------------------------------------------------

    /**
     * The hero: where it comes from, a large title, a meta line and a description.
     *
     * @return the hero block
     */
    private View hero() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(0, 0, px(96), 0);
        row.setClipChildren(false);

        LinearLayout texts = new LinearLayout(this);
        heroTexts = texts;
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
        return row;
    }

    /** Live TV: the antenna channel full screen, its name in the hero. */
    private void showLive() {
        setLive(true);
        CharSequence before = heroTitle.getText();
        heroLogo.setBackground(logoShape());
        heroLogo.setImageDrawable(appIcon(LIVE_TV_PKG));
        heroSource.setText(R.string.live_source);
        if (live != null) {
            heroTitle.setText(live.name());
            heroMeta.setText(live.number().isEmpty() ? "" : "CH " + live.number());
        } else {
            heroTitle.setText(R.string.no_tuner);
            heroMeta.setText("");
        }
        heroDesc.setText("");
        reveal(before);
        showOnAir();
    }

    /**
     * Fills the hero description with the program on air on the live channel, from the guide: title,
     * start and end time, and its short description. Read off the main thread; dropped when the hero
     * moved on before it arrived.
     */
    private void showOnAir() {
        programEndMs = 0;
        long channel = live != null ? live.channelId() : -1;
        if (channel < 0) {
            return;
        }
        guide.execute(() -> {
            LiveTv.Program p = LiveTv.onAir(this, channel);
            handler.post(() -> {
                if (p == null || !liveOn || live.channelId() != channel) {
                    return;
                }
                programEndMs = p.endMs;
                String text = p.title + "   ·   " + DateFormat.format("HH:mm", p.startMs) + "–"
                    + DateFormat.format("HH:mm", p.endMs);
                String about = p.description != null ? p.description.trim() : "";
                heroDesc.setText(about.isEmpty() ? text : text + "\n" + about);
            });
        });
    }

    /**
     * Fades the hero's text in and lifts it into place, when it now shows something else.
     *
     * @param titleBefore the hero title before the change
     */
    private void reveal(CharSequence titleBefore) {
        if (titleBefore.toString().contentEquals(heroTitle.getText())) {
            return;
        }
        heroTexts.setAlpha(0f);
        heroTexts.setTranslationY(px(18));
        heroTexts.animate().alpha(1f).translationY(0f).setDuration(260)
            .setInterpolator(new DecelerateInterpolator()).start();
    }

    /**
     * Crossfades the hero artwork to an image: the back view loads it and fades in over the front one,
     * then they swap roles. Requests overtaken by a newer one are dropped by Images.
     *
     * @param uri the image, or null for none (the dark layer only)
     */
    private void showHeroImage(String uri) {
        if (uri != null && uri.equals(heroArt.getTag()) && heroArt.getAlpha() > 0f) {
            return;
        }
        ImageView next = heroArtBack;
        next.animate().cancel();
        next.setAlpha(0f);
        next.setImageDrawable(null);
        if (uri == null) {
            next.setTag(null);
            heroArt.animate().alpha(0f).setDuration(200).start();
            return;
        }
        next.bringToFront();
        images.load(uri, next, 1280, () -> {
            next.animate().alpha(1f).setDuration(320).setInterpolator(new DecelerateInterpolator()).start();
            heroArtBack = heroArt;
            heroArt = next;
        });
    }

    /**
     * Shows an item in the hero: its artwork behind, where it comes from,
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
        CharSequence before = heroTitle.getText();
        String uri = item.imageUri;
        if (uri != null && uri.contains("/photo/:/transcode")) {
            uri = uri.replace("width=320&height=180", "width=1280&height=720");
        }
        showHeroImage(uri);
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
        reveal(before);
    }

    /**
     * Shows live TV in the hero, or covers it with the artwork layer. The tuner starts on first use
     * and keeps playing, with its sound, while the layer covers it; releaseTuner frees it.
     *
     * @param on whether live TV shows
     */
    private void setLive(boolean on) {
        if (live == null) {
            return;
        }
        if (!tuned) {
            tuned = live.start();
        }
        if (on && !liveOn) {
            heroLayer.animate().alpha(0f).setDuration(320).start();
        } else if (!on && (liveOn || heroLayer.getAlpha() < 1f)) {
            // Over live TV: the dark layer fades in, and the item's artwork fades in on top of it.
            heroArt.animate().cancel();
            heroArt.setAlpha(0f);
            heroArt.setTag(null);
            heroLayer.animate().alpha(1f).setDuration(320).start();
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

    /**
     * Opens Live TV full screen on the channel Home plays. Live TV opened any other way starts on its
     * own last channel, which only Home follows (see HomeRedirectService).
     */
    private void openLiveTv() {
        long id = live != null ? live.channelId() : -1;
        releaseTuner();
        if (id >= 0) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception e) {
                // No app answers the channel URI: open Live TV as it is.
            }
        }
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(LIVE_TV_PKG);
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
     * Moving an app tile while one is held, and vertical moves between the top bar and the rows. Plain
     * geometric focus search misses the top bar from cards far to the right.
     *
     * @param event the key event
     * @return true when handled here
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
            || code == KeyEvent.KEYCODE_BACK;
        if (movingTile != null) {
            // Moving a tile: Left and Right move it, a fresh OK or Back ends the move, every other key
            // (and the held OK that started it) is swallowed.
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    moveTile(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                } else if (ok && event.getRepeatCount() == 0) {
                    finishMove();
                    swallowKeyUp = true;
                }
            }
            return true;
        }
        if (swallowKeyUp && ok && event.getAction() == KeyEvent.ACTION_UP) {
            swallowKeyUp = false;
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT
                && event.getRepeatCount() == 0 && getCurrentFocus() == settingsButton) {
            start(new Intent().setComponent(ComponentName.unflattenFromString(getString(R.string.quick_settings_activity))));
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && !showingApps) {
            View f = getCurrentFocus();
            int key = event.getKeyCode();
            if (f != null && key == KeyEvent.KEYCODE_DPAD_DOWN && isInside(f, rowsScroll)
                    && f.focusSearch(View.FOCUS_DOWN) == null) {
                // The last row: nothing to move to, and the scroller would only nudge the rows.
                return true;
            }
            if (f != null && key == KeyEvent.KEYCODE_DPAD_UP && inTopRow(f)) {
                lastCard = f;
                homeTab.requestFocus();
                return true;
            }
            if (f != null && key == KeyEvent.KEYCODE_DPAD_DOWN && isInside(f, topBarView)) {
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
     * CH+ and CH- change the live channel while live TV shows; Back goes back to the top of Home.
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
            backToTop();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * Places the rows around a focused card or app: its row scrolls so the card stays clear of the row's
     * edges (a focused card is enlarged and its ring would be cut), back to the start for the first
     * card; and, while all rows show, the focused row always sits at the same place, a ROW_PEEK below
     * the top, with a peek of the rows above and below it. Runs after the scrollers' own scroll to the
     * focused view.
     *
     * @param v the focused card or app tile
     */
    private void settleRows(View v) {
        v.post(() -> {
            View row = (View) v.getParent();
            if (row != null && row.getParent() instanceof HorizontalScrollView) {
                HorizontalScrollView h = (HorizontalScrollView) row.getParent();
                int margin = px(40);
                int x = row.getLeft() + v.getLeft() - h.getScrollX();
                if (((ViewGroup) row).indexOfChild(v) == 0) {
                    h.smoothScrollTo(0, 0);
                } else if (x < margin) {
                    h.smoothScrollBy(x - margin, 0);
                } else if (x + v.getWidth() > h.getWidth() - margin) {
                    h.smoothScrollBy(x + v.getWidth() - (h.getWidth() - margin), 0);
                }
            }
            if (!rowsExpanded) {
                return;
            }
            // The section holding v: a child of rowsTop, rowsBottom or (the apps) of rowsContent.
            View section = v;
            while (section.getParent() != rowsTop && section.getParent() != rowsBottom
                    && section.getParent() != rowsContent) {
                if (!(section.getParent() instanceof View)) {
                    return;
                }
                section = (View) section.getParent();
            }
            int top = 0;
            for (View x = section; x != rowsContent; x = (View) x.getParent()) {
                top += x.getTop();
            }
            rowsScroll.smoothScrollTo(0, Math.max(0, top - px(ROW_PEEK)));
        });
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
     * Scrolls the Apps grid so a focused tile's whole line shows with room for its lift: the first line
     * at the very top, a section's first line with the section title above it, and the last line down
     * to the end of the grid, so one Down shows the bottom. Runs after the scroller's own scroll, which
     * only brings the tile itself into view.
     *
     * @param v the focused tile
     */
    private void settleGrid(View v) {
        v.post(() -> {
            if (inFirstGridLine(v)) {
                appsPane.smoothScrollTo(0, 0);
                return;
            }
            View line = (View) v.getParent();
            int at = appsGrid.indexOfChild(line);
            View above = appsGrid.getChildAt(at - 1);
            int top = above instanceof TextView ? above.getTop() : line.getTop() - px(16);
            int bottom = at == appsGrid.getChildCount() - 1 ? appsGrid.getHeight() : line.getBottom() + px(16);
            int view = appsPane.getHeight() - appsPane.getPaddingTop() - appsPane.getPaddingBottom();
            int y = appsPane.getScrollY();
            if (bottom > y + view) {
                appsPane.smoothScrollTo(0, bottom - view);
            } else if (top < y) {
                appsPane.smoothScrollTo(0, top);
            }
        });
    }

    /**
     * @return the first app, or the first content card when there are no apps
     */
    private View firstCard() {
        if (appRow.getChildCount() > 0) {
            return appRow.getChildAt(0);
        }
        for (int i = 0; i < rowsTop.getChildCount(); i++) {
            LinearLayout row = rowOf(rowsTop.getChildAt(i));
            if (row.getChildCount() > 0) {
                return row.getChildAt(0);
            }
        }
        return null;
    }

    /**
     * @return the first tile of the Apps grid, or null
     */
    private View firstGridTile() {
        for (int i = 0; i < appsGrid.getChildCount(); i++) {
            View child = appsGrid.getChildAt(i);
            if (child instanceof LinearLayout && ((LinearLayout) child).getChildCount() > 0) {
                return ((LinearLayout) child).getChildAt(0);
            }
        }
        return null;
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
     * server, which take a second or more each, showing the rows again as each arrives. When the Plex
     * server cannot be reached, it tries again every PLEX_RETRY_MS while Home is on screen.
     *
     * @param plexRows whether to read the Plex rows from the server too, not just the TV provider
     */
    private void loadRows(boolean plexRows) {
        Set<String> excluded = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.watch_next_excluded)));
        String ytPkg = getString(R.string.youtube_package);
        String recsName = getString(R.string.plex_recommendations);
        String spotifyPkg = getString(R.string.spotify_package);
        String spotifyName = getString(R.string.spotify_row);
        boolean plexOn = getResources().getBoolean(R.bool.plex_enabled);
        int plexUser = getResources().getInteger(R.integer.plex_home_user);
        background.execute(() -> {
            List<TvRows.Item> wn = TvRows.continueWatching(this, excluded);
            List<TvRows.Item> recs = TvRows.channel(this, PLEX_PKG, recsName);
            List<List<TvRows.Item>> yt = new ArrayList<>();
            for (String name : youtubeRows) {
                yt.add(TvRows.channel(this, ytPkg, name));
            }
            List<TvRows.Item> sp = TvRows.channel(this, spotifyPkg, spotifyName);
            handler.post(() -> {
                watchNext = wn;
                plexRecs = recs;
                youtube = yt;
                spotify = sp;
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
                // Server or plex.tv unreachable (right after boot the network is not up yet): keep the
                // last Plex rows on screen and try again while Home shows.
                handler.post(() -> {
                    if (resumed) {
                        handler.postDelayed(retryPlexRows, PLEX_RETRY_MS);
                    }
                });
            }
        });
    }

    /**
     * Rebuilds the content rows, keeping the focused card when it is still there. Continue watching
     * always comes first (after the apps). The rows below it are ordered by when their app was last
     * used (see lastUsed), most recent first; rows of apps used at the same time keep the design's
     * order: Recommendations from Plex, YouTube's first row, Recently added, YouTube's second row,
     * Spotify and Movies in your library.
     */
    private void showRows() {
        View focused = getCurrentFocus();
        boolean inRows = focused != null && (isInside(focused, rowsTop) || isInside(focused, rowsBottom));
        Object focusedTag = inRows ? focused.getTag() : null;
        boolean lastWasCard = lastCard != null && (isInside(lastCard, rowsTop) || isInside(lastCard, rowsBottom));
        Object lastTag = lastWasCard ? lastCard.getTag() : null;

        rowsTop.removeAllViews();
        rowsBottom.removeAllViews();
        String ytPkg = getString(R.string.youtube_package);
        List<TvRows.Item> continueItems = continueItems();
        addRow(rowsTop, getString(R.string.row_continue), null, continueItems, true);
        // Title, app and items of each row below Continue watching, in the design's order.
        List<Object[]> below = new ArrayList<>();
        below.add(new Object[] {getString(R.string.plex_recommendations), PLEX_PKG, plexRecs});
        if (youtubeRows.length > 0 && youtube.size() > 0) {
            below.add(new Object[] {youtubeRows[0], ytPkg, youtube.get(0)});
        }
        below.add(new Object[] {getString(R.string.row_recently_added), PLEX_PKG, plexRecent});
        for (int i = 1; i < youtubeRows.length && i < youtube.size(); i++) {
            below.add(new Object[] {youtubeRows[i], ytPkg, youtube.get(i)});
        }
        below.add(new Object[] {getString(R.string.spotify_row), getString(R.string.spotify_package), spotify});
        below.add(new Object[] {getString(R.string.row_movies), PLEX_PKG, plexMovies});
        Map<String, Long> used = lastUsed(continueItems);
        // A stable sort: rows of apps used at the same time keep the design's order.
        below.sort((a, b) -> Long.compare(used.getOrDefault((String) b[1], 0L), used.getOrDefault((String) a[1], 0L)));
        for (Object[] row : below) {
            @SuppressWarnings("unchecked")
            List<TvRows.Item> items = (List<TvRows.Item>) row[2];
            addRow(rowsBottom, (String) row[0], (String) row[1], items, false);
        }

        if (lastTag != null) {
            View again = findCard(lastTag);
            lastCard = again;
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
     * When each app was last used: opened from Home (noteUsed) or, for what was watched elsewhere, the
     * latest watch time in Continue watching, whichever is later.
     *
     * @param continueItems the Continue watching items
     * @return epoch ms by package
     */
    private Map<String, Long> lastUsed(List<TvRows.Item> continueItems) {
        Map<String, Long> used = new HashMap<>();
        for (Map.Entry<String, ?> e : getSharedPreferences("used", MODE_PRIVATE).getAll().entrySet()) {
            used.put(e.getKey(), (Long) e.getValue());
        }
        for (TvRows.Item item : continueItems) {
            if (item.packageName != null) {
                used.merge(item.packageName, item.watchedAtMs, Math::max);
            }
        }
        return used;
    }

    /**
     * Notes that an app was opened from Home now, for the order of the rows.
     *
     * @param pkg the app's package
     */
    private void noteUsed(String pkg) {
        getSharedPreferences("used", MODE_PRIVATE).edit().putLong(pkg, System.currentTimeMillis()).apply();
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
     * @param mixedRow whether the row mixes apps; its cards carry no corner label
     */
    private void addRow(LinearLayout into, String title, String logoPkg, List<TvRows.Item> items, boolean mixedRow) {
        if (items.isEmpty()) {
            return;
        }
        LinearLayout row = new LinearLayout(this);
        for (TvRows.Item item : items) {
            row.addView(card(item, title, mixedRow));
        }
        into.addView(section(title, logoPkg, new HorizontalScrollView(this), row));
    }

    /**
     * Wraps a horizontal row with its title and, for a row from one app, that app's logo before it.
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
        Drawable icon = appIcon(logoPkg);
        if (icon != null) {
            ImageView logo = new ImageView(this);
            logo.setImageDrawable(icon);
            logo.setBackground(logoShape());
            logo.setClipToOutline(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(28), px(28));
            lp.rightMargin = px(12);
            head.addView(logo, lp);
        }
        TextView t = text(26, TEXT, false);
        t.setText(title);
        head.addView(t);
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
     * A 16:9 content card (square for Spotify) as in the design: artwork, a label top left (channel or year, none in mixed rows), a
     * corner badge top right (time left, "Next" or the length), the title at the bottom and a progress
     * bar in the source's color. Focus shows the item in the hero; OK opens it.
     *
     * @param item what to show
     * @param rowTitle the row it sits in, for the hero
     * @param mixedRow whether the card sits in a row that mixes apps (no corner label)
     * @return the focusable card
     */
    private View card(TvRows.Item item, String rowTitle, boolean mixedRow) {
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

        // Rows that mix apps leave the corner empty: the hero names the app once the card is focused.
        String label = mixedRow || item.action != null ? null : isYoutube || isPlex ? item.author : null;
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

        // Spotify's playlists and podcasts have square covers.
        boolean square = getString(R.string.spotify_package).equals(item.packageName);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(square ? 180 : 320), px(180));
        lp.rightMargin = px(28);
        card.setLayoutParams(lp);
        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(150).start();
            ring.setStroke(has ? px(4) : 0, TEXT);
            if (has) {
                lastCard = v;
                showItem(item, rowTitle);
                settleRows(v);
            }
        });
        card.setOnClickListener(v -> open(item));
        return card;
    }

    /**
     * Opens an item the way its app asked (its intent URI), else just launches the app. Plex links
     * open the item's page, and the item then plays from where it was left (see HomeRedirectService).
     * The Plex sign-in card opens the sign-in screen.
     *
     * @param item the card's item
     */
    private void open(TvRows.Item item) {
        if ("plex-login".equals(item.action)) {
            startActivity(new Intent(this, PlexLoginActivity.class));
            return;
        }
        noteUsed(item.packageName);
        try {
            String uri = item.intentUri;
            String play = PLEX_PKG.equals(item.packageName) ? Plex.appLink(item) : null;
            if (play != null && !play.equals(item.intentUri)) {
                // The item's page first; the redirect service plays it once Plex has reached the server.
                uri = play.substring(0, play.indexOf('?'));
                HomeRedirectService.playWhenPlexReady(play);
            }
            if (uri != null) {
                startActivity(Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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

    // ---- Apps -----------------------------------------------------------------------------------

    /**
     * Fills the Your apps row with the favorites in their order, ending with All apps and Google TV, and the
     * Apps grid with a Favorites section and an All apps section A to Z. Favorites are dock_apps until
     * they are first changed from the held-OK menu. hidden_apps never show.
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

        byPkg.keySet().removeAll(hidden);
        List<ResolveInfo> favorites = new ArrayList<>();
        String saved = getSharedPreferences("apps", MODE_PRIVATE).getString("favorites", null);
        for (String pkg : saved != null ? saved.split("\n") : getResources().getStringArray(R.array.dock_apps)) {
            ResolveInfo ri = byPkg.get(pkg);
            if (ri != null) {
                favorites.add(ri);
            }
        }
        List<ResolveInfo> all = new ArrayList<>(byPkg.values());
        all.sort((a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));

        appRow.removeAllViews();
        for (ResolveInfo ri : favorites) {
            appRow.addView(tile(pm, ri, true, true));
        }
        if (!favorites.isEmpty()) {
            // A thin rule between the favorites and the All apps tile, centered on the cards.
            View rule = new View(this);
            rule.setBackgroundColor(Color.argb(90, 241, 243, 244));
            LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(px(2), px(96));
            ruleLp.topMargin = px((135 - 96) / 2f);
            ruleLp.rightMargin = px(28);
            appRow.addView(rule, ruleLp);
        }
        appRow.addView(allAppsTile());
        appRow.addView(stockHomeTile());
        appsGrid.removeAllViews();
        if (!favorites.isEmpty()) {
            addGridSection(getString(R.string.grid_favorites), pm, favorites);
        }
        addGridSection(getString(R.string.all_apps), pm, all);
        if (!rowsExpanded) {
            // The collapsed rows are sized to the apps row, which just changed.
            setRowsExpanded(false);
        }
    }

    /**
     * Adds a titled block of app tiles to the Apps grid, GRID_COLUMNS to a line.
     *
     * @param title the block's title
     * @param pm package manager for labels and artwork
     * @param apps the apps, in order
     */
    private void addGridSection(String title, PackageManager pm, List<ResolveInfo> apps) {
        TextView head = text(26, TEXT, false);
        head.setText(title);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(-2, -2);
        headLp.bottomMargin = px(20);
        appsGrid.addView(head, headLp);
        LinearLayout line = null;
        for (int n = 0; n < apps.size(); n++) {
            if (n % GRID_COLUMNS == 0) {
                line = new LinearLayout(this);
                line.setClipChildren(false);
                LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-2, -2);
                lineLp.bottomMargin = px(28);
                appsGrid.addView(line, lineLp);
            }
            line.addView(tile(pm, apps.get(n), false, isFavorite(apps.get(n))));
        }
        if (line != null) {
            ((LinearLayout.LayoutParams) line.getLayoutParams()).bottomMargin = px(48);
        }
    }

    /**
     * One app card: the app's TV banner (or its icon) and its name. OK opens the app (Live TV on Home's
     * channel); holding OK opens the app's menu.
     *
     * @param pm package manager for labels and artwork
     * @param ri the app's launcher activity
     * @param movable whether the menu offers moving it along its row (favorites on Home)
     * @param favorite whether the app is a favorite
     * @return the focusable tile
     */
    private View tile(PackageManager pm, ResolveInfo ri, boolean movable, boolean favorite) {
        String pkg = ri.activityInfo.packageName;
        ImageView art = new ImageView(this);
        Drawable banner = ri.activityInfo.loadBanner(pm);
        art.setImageDrawable(banner != null ? banner : ri.loadIcon(pm));
        if (banner != null) {
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        }
        // The Your apps row shows no names: scrolled down, only the names would peek above the next row.
        LinearLayout cell = tileShell(pkg, art, banner != null, movable ? null : ri.loadLabel(pm).toString(), false);
        cell.setOnClickListener(v -> {
            noteUsed(pkg);
            if (LIVE_TV_PKG.equals(pkg)) {
                openLiveTv();
                return;
            }
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch != null) {
                start(launch);
            }
        });
        cell.setOnLongClickListener(v -> {
            showTileMenu(v, ri, movable, favorite);
            return true;
        });
        return cell;
    }

    /**
     * The tile that ends the Your apps row and opens the Apps grid.
     *
     * @return the focusable tile
     */
    private View allAppsTile() {
        Icon art = new Icon(this, Icon.GRID);
        art.setColor(TEXT);
        LinearLayout cell = tileShell(ALL_APPS, art, false, null, true);
        cell.setOnClickListener(v -> {
            showApps(true);
            View first = firstGridTile();
            if (first != null) {
                first.requestFocus();
            }
        });
        return cell;
    }

    /**
     * The tile after All apps that opens Google TV's own home screen, for what only it offers.
     *
     * @return the focusable tile
     */
    private View stockHomeTile() {
        String pkg = "com.google.android.apps.tv.launcherx";
        // Google TV's home has no icon of its own.
        Icon art = new Icon(this, Icon.HOME);
        art.setColor(TEXT);
        LinearLayout cell = tileShell(STOCK_HOME, art, false, null, true);
        cell.setOnClickListener(v -> {
            HomeRedirectService.allowStockHome();
            start(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(new ComponentName(pkg, getString(R.string.stock_home_activity))));
        });
        return cell;
    }

    /**
     * What every app tile looks like: a 16:9 card (square for the All apps and Google TV tiles) with a
     * name under it, a ring and a lift on focus.
     *
     * @param tag the tile's tag (its package)
     * @param art what fills the card
     * @param fills whether art fills the card (a banner) rather than sitting in its middle (an icon)
     * @param label the name under the card, or null for none
     * @param square whether the card is square, with a smaller icon
     * @return the focusable tile, without click handlers
     */
    private LinearLayout tileShell(String tag, View art, boolean fills, String label, boolean square) {
        LinearLayout cell = column();
        cell.setFocusable(true);
        cell.setTag(tag);

        FrameLayout card = new FrameLayout(this);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Color.rgb(42, 44, 49));
        fill.setCornerRadius(px(16));
        card.setBackground(fill);
        card.setClipToOutline(true);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(16));
        card.setForeground(ring);
        card.addView(art, fills ? new FrameLayout.LayoutParams(-1, -1)
            : new FrameLayout.LayoutParams(px(square ? 64 : 96), px(square ? 64 : 96), Gravity.CENTER));
        // Shown only while the tile is being moved, toward the sides it can still move to.
        card.addView(chevron(Icon.CHEVRON_LEFT), chevronLp(Gravity.START));
        card.addView(chevron(Icon.CHEVRON_RIGHT), chevronLp(Gravity.END));
        cell.addView(card, new LinearLayout.LayoutParams(px(square ? 135 : 240), px(135)));

        TextView name = text(19, MUTED, false);
        if (label != null) {
            name.setText(label);
            name.setSingleLine(true);
            name.setPadding(px(6), px(10), 0, 0);
            cell.addView(name, new LinearLayout.LayoutParams(px(240), -2));
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = px(28);
        cell.setLayoutParams(lp);
        cell.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(150).start();
            ring.setStroke(has ? px(4) : 0, TEXT);
            name.setTextColor(has ? TEXT : MUTED);
            if (has && isInside(v, rowsScroll)) {
                lastCard = v;
                settleRows(v);
            }
            if (has && v.getParent() == appRow && !liveOn) {
                // Back from the content rows: live TV again. Moving along the apps while it already
                // shows leaves the hero alone, or its text would blank and fade in again.
                showLive();
            }
            if (has && isInside(v, appsGrid)) {
                settleGrid(v);
            }
        });
        return cell;
    }

    /**
     * The menu for a held app tile: Move (favorites on Home only), Add to or Remove from favorites,
     * App info, and Uninstall for apps that can be removed. Uninstall opens the system's own
     * confirmation.
     *
     * @param cell the held tile
     * @param ri the app's launcher activity
     * @param movable whether the tile can be moved along its row
     * @param favorite whether the app is a favorite
     */
    private void showTileMenu(View cell, ResolveInfo ri, boolean movable, boolean favorite) {
        String pkg = ri.activityInfo.packageName;
        ApplicationInfo ai = ri.activityInfo.applicationInfo;
        boolean removable = (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0
            || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (movable) {
            labels.add(getString(R.string.tile_move));
            actions.add(() -> {
                movingTile = cell;
                paintMoving(cell, true);
            });
        }
        labels.add(getString(favorite ? R.string.tile_remove_favorite : R.string.tile_add_favorite));
        actions.add(() -> {
            List<String> favorites = favorites();
            if (favorite) {
                favorites.remove(pkg);
            } else {
                favorites.add(pkg);
            }
            saveFavorites(favorites, cell);
        });
        labels.add(getString(R.string.tile_info));
        actions.add(() -> start(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", pkg, null))));
        if (removable) {
            labels.add(getString(R.string.tile_uninstall));
            actions.add(() -> start(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))));
        }
        new AlertDialog.Builder(this)
            .setTitle(ri.loadLabel(getPackageManager()))
            .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
            .show();
    }

    /**
     * A chevron over a dark gradient the card's full height, fading in from that edge; hidden until a
     * tile is moved.
     *
     * @param kind Icon.CHEVRON_LEFT or Icon.CHEVRON_RIGHT, also its tag
     * @return the edge shade
     */
    private View chevron(int kind) {
        boolean left = kind == Icon.CHEVRON_LEFT;
        FrameLayout shade = new FrameLayout(this);
        shade.setTag(kind);
        shade.setBackground(new GradientDrawable(
            left ? GradientDrawable.Orientation.LEFT_RIGHT : GradientDrawable.Orientation.RIGHT_LEFT,
            new int[] {Color.argb(210, 0, 0, 0), Color.argb(0, 0, 0, 0)}));
        Icon icon = new Icon(this, kind);
        icon.setColor(MOVE_RING);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(px(36), px(36),
            (left ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL);
        iconLp.setMarginStart(px(6));
        iconLp.setMarginEnd(px(6));
        shade.addView(icon, iconLp);
        shade.setVisibility(View.GONE);
        return shade;
    }

    /**
     * @param side Gravity.START or Gravity.END
     * @return layout for a chevron shade along that edge of a card
     */
    private FrameLayout.LayoutParams chevronLp(int side) {
        return new FrameLayout.LayoutParams(px(72), -1, side);
    }

    /**
     * Marks the tile being moved: a yellow ring, and chevrons on the card's edges toward the sides it
     * can still move to.
     *
     * @param cell a tile made by tile()
     * @param moving whether it is being moved
     */
    private void paintMoving(View cell, boolean moving) {
        LinearLayout c = (LinearLayout) cell;
        GradientDrawable ring = (GradientDrawable) c.getChildAt(0).getForeground();
        ring.setStroke(px(moving ? 6 : 4), moving ? MOVE_RING : TEXT);
        ViewGroup card = (ViewGroup) c.getChildAt(0);
        int at = appRow.indexOfChild(cell);
        card.findViewWithTag(Icon.CHEVRON_LEFT).setVisibility(moving && at > 0 ? View.VISIBLE : View.GONE);
        card.findViewWithTag(Icon.CHEVRON_RIGHT).setVisibility(
            moving && at < favorites().size() - 1 ? View.VISIBLE : View.GONE);
    }

    /**
     * Swaps the tile being moved with its neighbor. The neighbor moves rather than the tile, so the
     * tile keeps focus.
     *
     * @param step -1 for left, 1 for right
     */
    private void moveTile(int step) {
        int i = appRow.indexOfChild(movingTile);
        int j = i + step;
        // Favorites only: the rule and the All apps tile always stay last.
        if (j < 0 || j >= favorites().size()) {
            return;
        }
        View other = appRow.getChildAt(j);
        appRow.removeView(other);
        appRow.addView(other, i);
        paintMoving(movingTile, true);
        movingTile.post(() -> {
            Rect r = new Rect();
            movingTile.getDrawingRect(r);
            movingTile.requestRectangleOnScreen(r);
        });
    }

    /** Ends a move: saves the favorites in the row's new order. */
    private void finishMove() {
        View cell = movingTile;
        paintMoving(cell, false);
        movingTile = null;
        saveFavorites(favorites(), cell);
    }

    /**
     * @return the favorites in the order the Your apps row shows them
     */
    private List<String> favorites() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < appRow.getChildCount(); i++) {
            Object tag = appRow.getChildAt(i).getTag();
            if (tag != null && !ALL_APPS.equals(tag) && !STOCK_HOME.equals(tag)) {
                out.add((String) tag);
            }
        }
        return out;
    }

    /**
     * @param ri an app
     * @return whether it is a favorite (in the Your apps row)
     */
    private boolean isFavorite(ResolveInfo ri) {
        return appRow.findViewWithTag(ri.activityInfo.packageName) != null;
    }

    /**
     * Saves the favorites, rebuilds the row and the grid, and puts focus back where the change was made:
     * on the same app when it is still in the same place, else on its neighbor there.
     *
     * @param favorites the favorites, in order
     * @param cell the tile the change was made from
     */
    private void saveFavorites(List<String> favorites, View cell) {
        getSharedPreferences("apps", MODE_PRIVATE).edit().putString("favorites", TextUtils.join("\n", favorites)).apply();
        ViewGroup line = (ViewGroup) cell.getParent();
        int at = line.indexOfChild(cell);
        boolean onHome = line == appRow;
        Object pkg = cell.getTag();
        loadApps();
        View again = (onHome ? appRow : appsGrid).findViewWithTag(pkg);
        if (again == null && onHome) {
            int count = favorites().size();
            again = count > 0 ? appRow.getChildAt(Math.min(at, count - 1)) : appRow.findViewWithTag(ALL_APPS);
        }
        if (again != null) {
            again.requestFocus();
        }
    }

    // ---- Top bar --------------------------------------------------------------------------------

    /**
     * The top bar from the design: profile, a pill with the Home and Apps tabs (they switch as soon as
     * they are focused, and one highlight slides between them), a pill with Search and Settings (both
     * open on OK), and the clock with the weather under it on the right. Left and Right stop at the
     * bar's ends instead of jumping into the rows.
     *
     * @return the bar
     */
    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, px(96), 0);
        bar.setClipChildren(false);
        bar.addView(avatar(), new LinearLayout.LayoutParams(px(80), px(80)));

        // The tabs sit on the sliding highlight, inside the pill.
        FrameLayout nav = new FrameLayout(this);
        nav.setPadding(px(8), px(8), px(8), px(8));
        nav.setBackground(pillBackground());
        tabHighlight = new View(this);
        tabHighlightBg = new GradientDrawable();
        tabHighlightBg.setCornerRadius(px(40));
        tabHighlight.setBackground(tabHighlightBg);
        nav.addView(tabHighlight, new FrameLayout.LayoutParams(0, px(80)));
        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        nav.addView(tabs);
        homeTab = pillButton(getString(R.string.tab_home), -1, null, v -> showApps(false));
        homeTab.setId(View.generateViewId());
        homeTab.setNextFocusLeftId(homeTab.getId());
        appsTab = pillButton(getString(R.string.tab_apps), -1, null, v -> showApps(true));
        // Like Google TV, focusing a tab switches to it; OK is not needed.
        homeTab.setOnFocusChangeListener((v, has) -> {
            if (has && showingApps) {
                showApps(false);
            }
        });
        appsTab.setOnFocusChangeListener((v, has) -> {
            if (has && !showingApps) {
                showApps(true);
            }
        });
        tabs.addView(homeTab);
        tabs.addView(appsTab);
        pillPainters.add(this::placeTabHighlight);
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(-2, -2);
        navLp.leftMargin = px(28);
        bar.addView(nav, navLp);

        LinearLayout tools = pill();
        tools.addView(pillButton(null, Icon.SEARCH, getString(R.string.tip_search),
            v -> start(new Intent("android.search.action.GLOBAL_SEARCH"))));
        LinearLayout.LayoutParams gearLp = new LinearLayout.LayoutParams(-2, px(80));
        gearLp.leftMargin = px(8);
        settingsButton = pillButton(null, Icon.GEAR, getString(R.string.tip_settings),
            v -> start(new Intent(android.provider.Settings.ACTION_SETTINGS)));
        // Right on Settings opens quick settings (see dispatchKeyEvent) instead of moving focus.
        settingsButton.setId(View.generateViewId());
        settingsButton.setNextFocusRightId(settingsButton.getId());
        tools.addView(settingsButton, gearLp);
        LinearLayout.LayoutParams toolsLp = new LinearLayout.LayoutParams(-2, -2);
        toolsLp.leftMargin = px(28);
        bar.addView(tools, toolsLp);

        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        // The clock, and under it the sky, temperature, chance of rain and air quality.
        int clockColor = Color.argb(220, 241, 243, 244);
        LinearLayout corner = column();
        corner.setGravity(Gravity.END);
        clock = text(56, clockColor, false);
        clock.setIncludeFontPadding(false);
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(-2, -2);
        clockLp.gravity = Gravity.END;
        corner.addView(clock, clockLp);
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        weatherLine = line;
        weatherIcon = new Icon(this, Icon.SUN);
        weatherIcon.setColor(clockColor);
        line.addView(weatherIcon, new LinearLayout.LayoutParams(px(32), px(32)));
        weatherText = text(26, clockColor, false);
        weatherText.setPadding(px(8), 0, px(20), 0);
        line.addView(weatherText);
        Icon drop = new Icon(this, Icon.DROP);
        drop.setColor(clockColor);
        line.addView(drop, new LinearLayout.LayoutParams(px(26), px(26)));
        rainText = text(26, clockColor, false);
        rainText.setPadding(px(6), 0, px(20), 0);
        line.addView(rainText);
        aqiPill = text(20, Color.rgb(23, 24, 27), true);
        aqiPill.setPadding(px(12), px(3), px(12), px(3));
        line.addView(aqiPill);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-2, -2);
        lineLp.topMargin = px(6);
        lineLp.gravity = Gravity.END;
        corner.addView(line, lineLp);
        bar.addView(corner);
        showWeather();
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
     * Shows the last weather reading under the clock (sky, temperature, chance of rain, and the air
     * quality as a pill tinted by its category), or nothing before the first reading.
     */
    private void showWeather() {
        weatherLine.setVisibility(weather.known() ? View.VISIBLE : View.GONE);
        if (!weather.known()) {
            return;
        }
        int aqi = weather.aqi();
        aqiPill.setVisibility(aqi < 0 ? View.GONE : View.VISIBLE);
        if (aqi >= 0) {
            // The US AQI categories (good, moderate, unhealthy for sensitive groups, unhealthy, very
            // unhealthy, hazardous) in soft tints of their usual hues, with dark text.
            int color = aqi <= 50 ? Color.rgb(0xd9, 0xea, 0xd3) : aqi <= 100 ? Color.rgb(0xff, 0xf2, 0xcc)
                : aqi <= 150 ? Color.rgb(0xfc, 0xe5, 0xcd) : aqi <= 200 ? Color.rgb(0xf4, 0xcc, 0xcc)
                : aqi <= 300 ? Color.rgb(0xd9, 0xd2, 0xe9) : Color.rgb(0xe6, 0xb8, 0xaf);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color);
            bg.setCornerRadius(px(20));
            aqiPill.setBackground(bg);
            aqiPill.setText("AQI " + aqi);
        }
        rainText.setText(weather.rainChance() + "%");
        int sky = weather.sky();
        weatherIcon.setKind(sky == Weather.CLEAR ? (weather.day() ? Icon.SUN : Icon.MOON)
            : sky == Weather.PARTLY ? (weather.day() ? Icon.PARTLY : Icon.PARTLY_NIGHT) : sky == Weather.CLOUD ? Icon.CLOUD
            : sky == Weather.FOG ? Icon.FOG : sky == Weather.RAIN ? Icon.RAIN : Icon.STORM);
        weatherIcon.setVisibility(View.VISIBLE);
        weatherText.setText(weather.tempC() + "°");
    }

    /**
     * @return an empty rounded pill that holds top-bar buttons
     */
    private LinearLayout pill() {
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(px(8), px(8), px(8), px(8));
        pill.setBackground(pillBackground());
        return pill;
    }

    /**
     * @return the dark rounded fill of a top-bar pill
     */
    private GradientDrawable pillBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 0x28, 0x2A, 0x2C));
        bg.setCornerRadius(px(48));
        return bg;
    }

    /**
     * Slides the tab highlight under the focused tab (pale), or under the tab on screen while focus is
     * elsewhere (grey), animating its place, width and color from where it is now. Waits for the first
     * layout, when the tabs have no width yet.
     */
    private void placeTabHighlight() {
        View focus = getCurrentFocus();
        boolean inTabs = focus == homeTab || focus == appsTab;
        View target = inTabs ? focus : showingApps ? appsTab : homeTab;
        if (target.getWidth() == 0) {
            target.post(this::placeTabHighlight);
            return;
        }
        float toX = target.getLeft();
        int toWidth = target.getWidth();
        int toColor = inTabs ? Color.rgb(0xCF, 0xDF, 0xED) : Color.rgb(0x37, 0x3E, 0x42);
        ViewGroup.LayoutParams lp = tabHighlight.getLayoutParams();
        if (lp.width == 0) {
            // First placement: nothing to slide from.
            tabHighlight.setTranslationX(toX);
            lp.width = toWidth;
            tabHighlight.setLayoutParams(lp);
            tabHighlightBg.setColor(toColor);
            tabHighlightColor = toColor;
            return;
        }
        if (tabHighlight.getTranslationX() == toX && lp.width == toWidth && tabHighlightColor == toColor) {
            return;
        }
        if (tabHighlightAnim != null) {
            tabHighlightAnim.cancel();
        }
        float fromX = tabHighlight.getTranslationX();
        int fromWidth = lp.width;
        int fromColor = tabHighlightColor;
        ArgbEvaluator argb = new ArgbEvaluator();
        tabHighlightAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(220);
        tabHighlightAnim.setInterpolator(new DecelerateInterpolator());
        tabHighlightAnim.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            tabHighlight.setTranslationX(fromX + (toX - fromX) * f);
            lp.width = Math.round(fromWidth + (toWidth - fromWidth) * f);
            tabHighlight.setLayoutParams(lp);
            tabHighlightColor = (int) argb.evaluate(f, fromColor, toColor);
            tabHighlightBg.setColor(tabHighlightColor);
        });
        tabHighlightAnim.start();
    }

    /**
     * One button inside a pill: a label (Home, Apps) or an icon (Search, Settings). Colors follow
     * Google TV's own top bar (see paintPillButton); an icon button shows its name under it while
     * focused.
     *
     * @param label text, or null for an icon button
     * @param icon Icon kind when label is null
     * @param tip the name shown under an icon button while focused, or null
     * @param onClick what OK does
     * @return the button
     */
    private View pillButton(String label, int icon, String tip, View.OnClickListener onClick) {
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
            b.addView(i, new FrameLayout.LayoutParams(px(34), px(34), Gravity.CENTER));
        }
        pillPainters.add(() -> paintPillButton(b, bg, t, i));
        if (tip != null) {
            b.setOnFocusChangeListener((v, has) -> showTip(has ? v : null, tip));
        }
        b.setOnClickListener(onClick);
        b.setMinimumWidth(px(80));
        b.setLayoutParams(new LinearLayout.LayoutParams(-2, px(80)));
        paintPillButton(b, bg, t, i);
        return b;
    }

    /**
     * Colors a pill button the way Google TV's top bar does: dark content while focused, a near white
     * circle behind a focused tool button and a faint one behind an idle one, and dimmed text in a pill
     * the focus is not in. The tabs have no fill of their own: the sliding highlight under them
     * (placeTabHighlight) is blue-grey while a tab is focused and grey under the tab on screen. Colors
     * change with a short transition, starting from the colors shown now.
     *
     * @param b the button
     * @param bg its background
     * @param t its label (a tab), or null
     * @param i its icon (a tool button), or null
     */
    private void paintPillButton(View b, GradientDrawable bg, TextView t, Icon i) {
        boolean focused = b.isFocused();
        boolean groupFocused = isInside(getCurrentFocus(), (View) b.getParent());
        int toBg = t != null ? Color.TRANSPARENT : focused ? Color.rgb(0xE4, 0xF3, 0xFF) : Color.rgb(0x31, 0x32, 0x34);
        int toFg = focused ? Color.rgb(0x0E, 0x0E, 0x0F) : groupFocused || b.getParent() == null ? TEXT
            : Color.rgb(0xAD, 0xB0, 0xB3);
        // {shown bg, shown fg, target bg, target fg}: a transition starts from what is on screen, also
        // halfway through another one, and none starts when the target is unchanged.
        int[] c = (int[]) b.getTag(R.id.pill_colors);
        if (c == null) {
            b.setTag(R.id.pill_colors, new int[] {toBg, toFg, toBg, toFg});
            paintPillColors(bg, t, i, toBg, toFg);
            return;
        }
        if (c[2] == toBg && c[3] == toFg) {
            return;
        }
        c[2] = toBg;
        c[3] = toFg;
        ValueAnimator running = (ValueAnimator) b.getTag(R.id.pill_anim);
        if (running != null) {
            running.cancel();
        }
        int fromBg = c[0];
        int fromFg = c[1];
        ArgbEvaluator argb = new ArgbEvaluator();
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f).setDuration(180);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            c[0] = (int) argb.evaluate(f, fromBg, toBg);
            c[1] = (int) argb.evaluate(f, fromFg, toFg);
            paintPillColors(bg, t, i, c[0], c[1]);
        });
        b.setTag(R.id.pill_anim, anim);
        anim.start();
    }

    /**
     * @param bg a pill button's background
     * @param t its label, or null
     * @param i its icon, or null
     * @param bgColor background color
     * @param fgColor label or icon color
     */
    private void paintPillColors(GradientDrawable bg, TextView t, Icon i, int bgColor, int fgColor) {
        bg.setColor(bgColor);
        if (t != null) {
            t.setTextColor(fgColor);
        }
        if (i != null) {
            i.setColor(fgColor);
        }
    }

    /**
     * Shows a tool button's name centered under it, or hides it.
     *
     * @param button the focused button, or null to hide the name
     * @param name the button's name
     */
    private void showTip(View button, String name) {
        tip.animate().cancel();
        if (button == null) {
            tip.animate().alpha(0f).setDuration(120).start();
            return;
        }
        tip.setText(name);
        tip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        button.getLocationInWindow(at);
        tip.setTranslationX(at[0] + button.getWidth() / 2f - tip.getMeasuredWidth() / 2f);
        tip.setTranslationY(at[1] + button.getHeight() + px(12));
        tip.animate().alpha(1f).setDuration(160).start();
    }

    /** Gives the Home or Apps button the selected highlight. */
    private void markTabs() {
        for (Runnable paint : pillPainters) {
            paint.run();
        }
    }

    /**
     * Switches between Home and the Apps grid. The Apps grid has no live picture or hero: the tuner
     * stops while it shows.
     *
     * @param apps true for the Apps grid
     */
    private void showApps(boolean apps) {
        showingApps = apps;
        View in = apps ? appsPane : homePane;
        View out = apps ? homePane : appsPane;
        in.animate().cancel();
        out.animate().cancel();
        if (in.getVisibility() != View.VISIBLE) {
            in.setAlpha(0f);
            in.setVisibility(View.VISIBLE);
        }
        in.animate().alpha(1f).setDuration(200).start();
        out.animate().alpha(0f).setDuration(200).withEndAction(() -> out.setVisibility(View.GONE)).start();
        appsPane.scrollTo(0, 0);
        if (apps) {
            setLive(false);
            showHeroImage(null);
        } else {
            showLive();
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
