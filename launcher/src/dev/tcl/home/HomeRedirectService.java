package dev.tcl.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Google TV keeps Home (its HOME priority beats any third-party launcher, and set-home-activity and
 * the Home role change nothing on this build). This service watches window changes and, when Google
 * TV's own home screen comes up, opens TCL Home over it. Other launcherx screens, such as the profile
 * chooser, are left alone. It never filters keys, which on this TCL family breaks volume repeat.
 *
 * It also reads Live TV's channel banner, so the live picture on Home continues with the channel last
 * watched in Live TV. Live TV keeps that channel in storage no other app can read.
 *
 * And it starts a Plex item a Home card opened. Plex plays an item from a link with autoplay, but a
 * Plex that has to start first tries before it has reached the server and shows "Can't Reach
 * Server". So Home opens the item's page, and this service sends the play link once Plex shows a
 * Resume, Watch or Play button, which it does only after reaching the server.
 */
public class HomeRedirectService extends AccessibilityService {
    // At boot the service binds after Google TV's home is already up, so no window change follows.
    // A rebind later on (rare) must not pull the viewer out of what they are watching.
    private static final long BOOT_WINDOW_MS = 5 * 60_000;
    private static final String LIVE_TV = "com.tcl.tv";
    // The number in the banner Live TV shows on every tune.
    private static final String BANNER_NUMBER = "com.tcl.tv:id/tvCurChannelNum";
    private static final String PLEX = "com.plexapp.android";
    // How long after a Home card opened Plex the play link may still be sent (a cold start of Plex
    // takes about 20 seconds to reach the server).
    private static final long PLEX_WAIT_MS = 40_000;
    // Commands from Home, which runs in another process: the service runs in its own (see the manifest).
    private static final String ACTION_ALLOW_STOCK_HOME = "dev.tcl.home.ALLOW_STOCK_HOME";
    private static final String ACTION_PLAY_WHEN_READY = "dev.tcl.home.PLAY_WHEN_READY";
    private static final String ACTION_HOME_SHOWN = "dev.tcl.home.HOME_SHOWN";
    private static final String ACTION_QUICK_SETTINGS = "dev.tcl.home.QUICK_SETTINGS";
    private static final String EXTRA_LINK = "link";
    // The play link to send once Plex is ready, or null.
    private String plexPlayLink;
    private long plexPlayUntil;
    // Until when Google TV's home, opened from Home's Google TV tile, stays on screen.
    private long stockHomeUntil;
    // Covers the window changes of the stock home opening; after that a new one means Home was pressed.
    private static final long STOCK_HOME_MS = 3_000;
    // The cover over Google TV's home comes off this long after Home reports its first frame, so that
    // frame is on screen first; and at the latest after COVER_MAX_MS, should Home never report.
    private static final long COVER_LINGER_MS = 120;
    private static final long COVER_MAX_MS = 6_000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable uncover = this::uncover;
    // A plain screen in Home's background color over Google TV's home while TCL Home opens, or null.
    private View cover;

    /**
     * Lets Google TV's home open once, without being replaced by TCL Home.
     *
     * @param context any context of this app
     */
    static void allowStockHome(Context context) {
        send(context, new Intent(ACTION_ALLOW_STOCK_HOME));
    }

    /**
     * Sends a Plex play link once Plex is ready, within PLEX_WAIT_MS.
     *
     * @param context any context of this app
     * @param link the item's play link (Plex.appLink(item))
     */
    static void playWhenPlexReady(Context context, String link) {
        send(context, new Intent(ACTION_PLAY_WHEN_READY).putExtra(EXTRA_LINK, link));
    }

    /**
     * Tells the service Home has drawn: the cover comes off, and a play link not sent yet is dropped
     * since Home is back on screen first.
     *
     * @param context any context of this app
     */
    static void homeShown(Context context) {
        send(context, new Intent(ACTION_HOME_SHOWN));
    }

    /**
     * Opens Google TV's quick settings panel. Its activity needs STATUS_BAR_SERVICE, which an app
     * cannot hold; the notifications global action of an accessibility service opens it instead.
     *
     * @param context any context of this app
     */
    static void openQuickSettings(Context context) {
        send(context, new Intent(ACTION_QUICK_SETTINGS));
    }

    /**
     * Delivers a command to the running service. Only this app can: the service needs
     * BIND_ACCESSIBILITY_SERVICE, which the system grants to the app's own uid.
     *
     * @param context any context of this app
     * @param command the command
     */
    private static void send(Context context, Intent command) {
        context.startService(command.setClass(context, HomeRedirectService.class));
    }

    /**
     * Runs a command from Home, then drops the started state: the system's accessibility binding keeps
     * the service alive, and without it (service switched off) nothing should keep running.
     *
     * @param intent the command
     * @param flags unused
     * @param startId this start, for stopSelf
     * @return START_NOT_STICKY, a lost command is not worth a restart
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_ALLOW_STOCK_HOME.equals(action)) {
            stockHomeUntil = SystemClock.uptimeMillis() + STOCK_HOME_MS;
        } else if (ACTION_PLAY_WHEN_READY.equals(action)) {
            plexPlayLink = intent.getStringExtra(EXTRA_LINK);
            plexPlayUntil = SystemClock.uptimeMillis() + PLEX_WAIT_MS;
        } else if (ACTION_HOME_SHOWN.equals(action)) {
            plexPlayLink = null;
            main.removeCallbacks(uncover);
            main.postDelayed(uncover, COVER_LINGER_MS);
        } else if (ACTION_QUICK_SETTINGS.equals(action)) {
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    /** Opens TCL Home once at boot, when Google TV's home is already showing before this service binds. */
    @Override
    protected void onServiceConnected() {
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) {
            openHome();
        }
    }

    /**
     * Opens TCL Home whenever the stock home activity takes the screen, and notes the channel in Live
     * TV's banner whenever Live TV's window changes.
     *
     * @param event a window change from Google TV or Live TV
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        String pkg = String.valueOf(event.getPackageName());
        if (LIVE_TV.equals(pkg)) {
            noteLiveTvChannel();
        } else if (PLEX.equals(pkg)) {
            String link = plexPlayLink;
            if (link != null && SystemClock.uptimeMillis() < plexPlayUntil && plexShowsPlayButton()) {
                plexPlayLink = null;
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(PLEX)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } else if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && getString(R.string.stock_home_activity).equals(String.valueOf(event.getClassName()))
                && SystemClock.uptimeMillis() >= stockHomeUntil) {
            openHome();
        }
    }

    /**
     * @return whether Plex's window shows a Resume, Watch or Play button, a sign Plex has reached the
     *     server
     */
    private boolean plexShowsPlayButton() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        List<AccessibilityNodeInfo> todo = new ArrayList<>();
        todo.add(root);
        while (!todo.isEmpty()) {
            AccessibilityNodeInfo node = todo.remove(todo.size() - 1);
            if (node == null) {
                continue;
            }
            CharSequence label = node.getContentDescription();
            if (label != null && node.isClickable() && label.toString().matches("(?s)^(Resume|Watch|Play)\\b.*")) {
                return true;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                todo.add(node.getChild(i));
            }
        }
        return false;
    }

    /** Saves the channel number in Live TV's banner, when the banner is on screen. */
    private void noteLiveTvChannel() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(BANNER_NUMBER);
        if (!found.isEmpty() && found.get(0).getText() != null) {
            LiveTv.remember(this, found.get(0).getText().toString().trim());
        }
    }

    /** Covers the screen, so Google TV's home does not show while TCL Home opens, and brings Home to the front. */
    private void openHome() {
        if (cover == null) {
            cover = new View(this);
            cover.setBackgroundColor(HomeActivity.BG);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Keys and focus stay with the window below.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
            getSystemService(WindowManager.class).addView(cover, lp);
        }
        main.removeCallbacks(uncover);
        main.postDelayed(uncover, COVER_MAX_MS);
        startActivity(new Intent(this, HomeActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    /** Takes the cover off the screen, if it is on. */
    private void uncover() {
        main.removeCallbacks(uncover);
        if (cover != null) {
            getSystemService(WindowManager.class).removeView(cover);
            cover = null;
        }
    }

    /** Nothing to stop: the service gives no spoken or haptic feedback. */
    @Override
    public void onInterrupt() {
    }
}
