package dev.tcl.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
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
    // The play link to send once Plex is ready, or null.
    private static volatile String plexPlayLink;
    private static volatile long plexPlayUntil;

    /**
     * Sends a Plex play link once Plex is ready, within PLEX_WAIT_MS.
     *
     * @param link the item's play link (Plex.appLink(item))
     */
    static void playWhenPlexReady(String link) {
        plexPlayLink = link;
        plexPlayUntil = SystemClock.uptimeMillis() + PLEX_WAIT_MS;
    }

    /** Drops a play link not sent yet, when Home is back on screen first. */
    static void cancelPlexPlay() {
        plexPlayLink = null;
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
                && getString(R.string.stock_home_activity).equals(String.valueOf(event.getClassName()))) {
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

    /** Brings TCL Home to the front. */
    private void openHome() {
        startActivity(new Intent(this, HomeActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    /** Nothing to stop: the service keeps no state. */
    @Override
    public void onInterrupt() {
    }
}
