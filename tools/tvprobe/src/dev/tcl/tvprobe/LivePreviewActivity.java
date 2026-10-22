package dev.tcl.tvprobe;

import android.app.Activity;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * Checks whether an ordinary app may play the TV's antenna tuner in its own TvView, which a launcher
 * needs to show live TV behind its home screen. Tunes to the channel TCL's Live TV played last (the
 * system setting last_play_channel_infos.EN_TCL_DVBT holds its row id), else to the first tuner channel;
 * logs every TvView callback under "TvProbe" and closes itself after 12 seconds.
 */
public class LivePreviewActivity extends Activity {
    private static final String TAG = "TvProbe";
    private static final long RUN_MS = 12000;

    private TvView tvView;
    // Lines logged during onCreate never reach logcat on this TCL build, so they ride on the callbacks.
    private final StringBuilder setup = new StringBuilder();

    /**
     * Finds a tuner channel, tunes to it and schedules the close.
     *
     * @param savedInstanceState unused
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tvView = new TvView(this);
        setContentView(tvView);
        tvView.setCallback(new TvView.TvInputCallback() {
            @Override
            public void onConnectionFailed(String inputId) {
                Log.i(TAG, "live connectionFailed " + inputId);
            }

            @Override
            public void onDisconnected(String inputId) {
                Log.i(TAG, "live disconnected " + inputId);
            }

            @Override
            public void onChannelRetuned(String inputId, Uri channelUri) {
                Log.i(TAG, "live retuned " + channelUri + " | " + setup);
            }

            @Override
            public void onVideoAvailable(String inputId) {
                Log.i(TAG, "live videoAvailable " + inputId + " | " + setup);
            }

            @Override
            public void onVideoUnavailable(String inputId, int reason) {
                Log.i(TAG, "live videoUnavailable reason=" + reason);
            }

            @Override
            public void onContentBlocked(String inputId, android.media.tv.TvContentRating rating) {
                Log.i(TAG, "live contentBlocked " + rating);
            }
        });

        String last = Settings.System.getString(getContentResolver(), "last_play_channel_infos.EN_TCL_DVBT");
        setup.append("lastPlayedSetting=").append(last);
        String[] target = tunerChannel(last);
        if (target == null) {
            Log.i(TAG, "live no tuner channel readable");
            finish();
            return;
        }
        setup.append(" tuned=").append(target[1]).append(" (").append(target[2]).append(')');
        tvView.tune(target[0], TvContract.buildChannelUri(Long.parseLong(target[1])));
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, RUN_MS);
    }

    /**
     * Stops the session so the tuner is free again for the Live TV app.
     */
    @Override
    protected void onDestroy() {
        if (tvView != null) {
            tvView.reset();
        }
        Log.i(TAG, "live END");
        super.onDestroy();
    }

    /**
     * Returns {input id, channel row id, channel name} for the tuner channel whose row id is wanted,
     * else for the first readable tuner channel, or null when none is readable.
     *
     * @param wantedId channel row id to prefer, or null
     * @return the channel to tune, or null when none is readable
     */
    private String[] tunerChannel(String wantedId) {
        String[] first = null;
        try (Cursor c = getContentResolver().query(TvContract.Channels.CONTENT_URI, null, null, null, null)) {
            if (c == null) {
                return null;
            }
            int input = c.getColumnIndex(TvContract.Channels.COLUMN_INPUT_ID);
            int id = c.getColumnIndex(TvContract.Channels._ID);
            int name = c.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME);
            while (c.moveToNext()) {
                String inputId = c.getString(input);
                if (inputId == null || !inputId.contains("Tuner")) {
                    continue;
                }
                String[] row = {inputId, c.getString(id), c.getString(name)};
                // TCL may store its own id rather than the provider row id: report which column holds it.
                for (int col = 0; wantedId != null && col < c.getColumnCount(); col++) {
                    if (c.getType(col) != Cursor.FIELD_TYPE_BLOB && wantedId.equals(c.getString(col))) {
                        setup.append(" matched=").append(c.getColumnName(col));
                        return row;
                    }
                }
                if (first == null) {
                    first = row;
                }
            }
            if (wantedId != null) {
                setup.append(" matched=none");
            }
            return first;
        } catch (RuntimeException e) {
            Log.i(TAG, "live query error=" + e);
        }
        return null;
    }
}
