package dev.tcl.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * Google TV keeps Home (its HOME priority beats any third-party launcher, and set-home-activity and
 * the Home role change nothing on this build). This service watches window changes only and, when
 * Google TV's own home screen comes up, opens TCL Home over it. Other launcherx screens, such as the
 * profile chooser, are left alone. It never filters keys, which on this TCL family breaks volume repeat.
 */
public class HomeRedirectService extends AccessibilityService {
    // At boot the service binds after Google TV's home is already up, so no window change follows.
    // A rebind later on (rare) must not pull the viewer out of what they are watching.
    private static final long BOOT_WINDOW_MS = 5 * 60_000;

    /** Opens TCL Home once at boot, when Google TV's home is already showing before this service binds. */
    @Override
    protected void onServiceConnected() {
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) {
            openHome();
        }
    }

    /**
     * Opens TCL Home whenever the stock home activity takes the screen.
     *
     * @param event a window state change
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        if (getString(R.string.stock_home_activity).equals(String.valueOf(event.getClassName()))) {
            openHome();
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
