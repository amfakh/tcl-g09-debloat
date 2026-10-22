package dev.tcl.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Google TV keeps Home (its HOME priority beats any third-party launcher, and set-home-activity and
 * the Home role change nothing on this build). This service watches window changes and, when Google
 * TV's own home screen comes up, opens TCL Home over it. Other launcherx screens, such as the profile
 * chooser, are left alone. It never filters keys, which on this TCL family breaks volume repeat.
 *
 * It also reads Live TV's channel banner, so the live picture on Home continues with the channel last
 * watched in Live TV. Live TV keeps that channel in storage no other app can read.
 */
public class HomeRedirectService extends AccessibilityService {
    // At boot the service binds after Google TV's home is already up, so no window change follows.
    // A rebind later on (rare) must not pull the viewer out of what they are watching.
    private static final long BOOT_WINDOW_MS = 5 * 60_000;
    private static final String LIVE_TV = "com.tcl.tv";
    // The number in the banner Live TV shows on every tune.
    private static final String BANNER_NUMBER = "com.tcl.tv:id/tvCurChannelNum";

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
        if (LIVE_TV.equals(String.valueOf(event.getPackageName()))) {
            noteLiveTvChannel();
        } else if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && getString(R.string.stock_home_activity).equals(String.valueOf(event.getClassName()))) {
            openHome();
        }
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
