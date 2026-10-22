package dev.tcl.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvView;

import java.util.ArrayList;
import java.util.List;

/**
 * Plays the antenna tuner in a TvView behind Home, continuing with the channel last watched, here or in
 * Live TV. TCL's own "last played" setting does not follow Live TV and Live TV's own record is private,
 * so this keeps one record by channel number: written on every tune here, and by HomeRedirectService
 * from Live TV's channel banner.
 */
final class LiveTv {
    private static final String PREFS = "live_tv";
    private static final String KEY_LAST = "last_number";

    private final TvView view;
    private final String inputId;
    private final SharedPreferences prefs;
    private final Context context;
    private final List<Long> ids = new ArrayList<>();
    private final List<String> numbers = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private int index = -1;

    /**
     * @param context activity context, used for the TV provider and preferences
     * @param view the TvView that shows the picture
     * @param inputId TV input id of the antenna tuner
     */
    LiveTv(Context context, TvView view, String inputId) {
        this.context = context;
        this.view = view;
        this.inputId = inputId;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Tunes to the channel last watched, here or in Live TV, or the first channel the first time.
     *
     * @return false when no tuner channel is readable
     */
    boolean start() {
        if (ids.isEmpty()) {
            loadChannels();
        }
        if (ids.isEmpty()) {
            return false;
        }
        index = Math.max(0, numbers.indexOf(prefs.getString(KEY_LAST, "")));
        tune();
        return true;
    }

    /** Releases the tuner, so Live TV and other apps can use it while Home is not on screen. */
    void stop() {
        view.reset();
    }

    /**
     * Moves up or down the channel list, wrapping at either end.
     *
     * @param delta +1 for the next channel, -1 for the previous one
     */
    void step(int delta) {
        if (ids.isEmpty()) {
            return;
        }
        index = (index + delta + ids.size()) % ids.size();
        tune();
    }

    /**
     * @return the name of the channel on screen, or an empty string before the first tune
     */
    String name() {
        return index >= 0 && index < names.size() ? names.get(index) : "";
    }

    /**
     * @return the number of the channel on screen, or an empty string before the first tune
     */
    String number() {
        return index >= 0 && index < numbers.size() ? numbers.get(index) : "";
    }

    /**
     * @return the provider row id of the channel on screen, or -1
     */
    long channelId() {
        return index >= 0 && index < ids.size() ? ids.get(index) : -1;
    }

    /**
     * Records a channel as the last one watched.
     *
     * @param context any context of this app
     * @param number the channel's display number, such as "21"
     */
    static void remember(Context context, String number) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!number.equals(prefs.getString(KEY_LAST, null))) {
            prefs.edit().putString(KEY_LAST, number).apply();
        }
    }

    /** Tunes the TvView to the current channel and records it as the last one watched. */
    private void tune() {
        view.tune(inputId, TvContract.buildChannelUri(ids.get(index)));
        remember(context, numbers.get(index));
    }

    /**
     * Reads the tuner's channels, ordered by channel number. The whole table is read and filtered in
     * code: a selection throws for apps without ACCESS_ALL_EPG_DATA.
     */
    private void loadChannels() {
        List<Object[]> rows = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(TvContract.Channels.CONTENT_URI, null, null, null, null)) {
            if (c == null) {
                return;
            }
            int input = c.getColumnIndex(TvContract.Channels.COLUMN_INPUT_ID);
            int id = c.getColumnIndex(TvContract.Channels._ID);
            int number = c.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NUMBER);
            int name = c.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME);
            while (c.moveToNext()) {
                if (inputId.equals(c.getString(input))) {
                    rows.add(new Object[] {c.getLong(id), c.getString(number), c.getString(name)});
                }
            }
        } catch (RuntimeException e) {
            return;
        }
        rows.sort((a, b) -> Double.compare(numberOf((String) a[1]), numberOf((String) b[1])));
        for (Object[] r : rows) {
            ids.add((Long) r[0]);
            numbers.add(r[1] != null ? (String) r[1] : "");
            names.add(r[2] != null ? (String) r[2] : "");
        }
    }

    /**
     * @param displayNumber channel number such as "1" or "21-2"
     * @return a sortable number, with unparseable numbers last
     */
    private static double numberOf(String displayNumber) {
        try {
            return Double.parseDouble(displayNumber.replace('-', '.'));
        } catch (RuntimeException e) {
            return Double.MAX_VALUE;
        }
    }
}
