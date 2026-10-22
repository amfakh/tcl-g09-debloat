package dev.tcl.home;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plex sign-in for a TV: shows a short code to type at plex.tv/link from a phone, polls until it is
 * linked, then closes. Back cancels. A code lives about 15 minutes; a new one is fetched when it expires.
 */
public class PlexLoginActivity extends Activity {
    private static final long POLL_MS = 2000;
    private static final long PIN_LIFETIME_MS = 14 * 60_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private Plex plex;
    private TextView code;
    private TextView status;
    private Plex.Pin pin;
    private long pinAt;
    private boolean polling;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!polling) {
                return;
            }
            background.execute(() -> {
                boolean done;
                try {
                    done = pin != null && plex.pollPin(pin);
                } catch (Exception e) {
                    done = false;
                }
                boolean signedIn = done;
                handler.post(() -> {
                    if (signedIn) {
                        status.setText(R.string.plex_signed_in);
                        polling = false;
                        handler.postDelayed(PlexLoginActivity.this::finish, 1500);
                    } else if (System.currentTimeMillis() - pinAt > PIN_LIFETIME_MS) {
                        requestPin();
                    } else {
                        handler.postDelayed(poll, POLL_MS);
                    }
                });
            });
        }
    };

    /**
     * Builds the code screen.
     *
     * @param savedInstanceState unused
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        plex = new Plex(this);
        float unit = getResources().getDisplayMetrics().widthPixels / 1920f;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.rgb(15, 16, 18));

        TextView title = label(48 * unit, Color.rgb(241, 243, 244), false);
        title.setText(R.string.plex_sign_in);
        box.addView(title);
        TextView how = label(26 * unit, Color.rgb(168, 171, 177), false);
        how.setText(R.string.plex_how);
        how.setPadding(0, Math.round(16 * unit), 0, Math.round(32 * unit));
        box.addView(how);
        code = label(120 * unit, Color.rgb(229, 160, 13), true);
        code.setLetterSpacing(0.3f);
        box.addView(code);
        status = label(22 * unit, Color.rgb(168, 171, 177), false);
        status.setPadding(0, Math.round(32 * unit), 0, 0);
        box.addView(status);
        setContentView(box);
    }

    /** Fetches a code and starts polling. */
    @Override
    protected void onResume() {
        super.onResume();
        polling = true;
        requestPin();
    }

    /** Stops polling while the screen is away. */
    @Override
    protected void onPause() {
        polling = false;
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    /** Asks plex.tv for a fresh code and shows it. */
    private void requestPin() {
        code.setText("····");
        status.setText(R.string.plex_waiting);
        background.execute(() -> {
            try {
                Plex.Pin fresh = plex.newPin();
                handler.post(() -> {
                    pin = fresh;
                    pinAt = System.currentTimeMillis();
                    code.setText(fresh.code);
                    handler.postDelayed(poll, POLL_MS);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    status.setText(R.string.plex_offline);
                    handler.postDelayed(this::requestPin, 10_000);
                });
            }
        });
    }

    /**
     * @param px text size in pixels
     * @param color text color
     * @param bold whether bold
     * @return a centered label
     */
    private TextView label(float px, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }
}
