package dev.tcl.home;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays the antenna tuner in a TvView behind Home, continuing with the channel last watched, here or in
 * Live TV. TCL's own "last played" setting does not follow Live TV and Live TV's own record is private,
 * so this keeps one record by channel number: written on every tune here, and by HomeRedirectService
 * from Live TV's channel banner. The service runs in its own process, so the record is a file read
 * fresh on every start: SharedPreferences would keep each process's own cached copy.
 */
final class LiveTv {
    private static final String LAST_FILE = "live_tv_last";

    private final TvView view;
    private final String inputId;
    private final Context context;
    private final List<Long> ids = new ArrayList<>();
    private final List<String> numbers = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private int index = -1;

    /** A program from the channel's guide. */
    static final class Program {
        String title;
        String description;
        long startMs;
        long endMs;
    }

    /**
     * @param context activity context, used for the TV provider and preferences
     * @param view the TvView that shows the picture
     * @param inputId TV input id of the antenna tuner
     */
    LiveTv(Context context, TvView view, String inputId) {
        this.context = context;
        this.view = view;
        this.inputId = inputId;
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
        index = Math.max(0, numbers.indexOf(last(context)));
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
     * Reads the program on air now from the guide. Reads the provider, so call it off the main thread.
     *
     * @param context any context of this app
     * @param channelId provider row id of the channel
     * @return the program, or null when the guide has none for now
     */
    static Program onAir(Context context, long channelId) {
        long now = System.currentTimeMillis();
        try (Cursor c = context.getContentResolver().query(TvContract.buildProgramsUriForChannel(channelId, now, now),
                null, null, null, null)) {
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            Program p = new Program();
            p.title = c.getString(c.getColumnIndex(TvContract.Programs.COLUMN_TITLE));
            p.description = c.getString(c.getColumnIndex(TvContract.Programs.COLUMN_SHORT_DESCRIPTION));
            p.startMs = c.getLong(c.getColumnIndex(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS));
            p.endMs = c.getLong(c.getColumnIndex(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS));
            return p.title != null ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Records a channel as the last one watched.
     *
     * @param context any context of this app
     * @param number the channel's display number, such as "21"
     */
    static void remember(Context context, String number) {
        if (number.equals(last(context))) {
            return;
        }
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), LAST_FILE));
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(number.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException e) {
            file.failWrite(out);
        }
    }

    /**
     * @param context any context of this app
     * @return the display number of the channel last watched, or an empty string when none is recorded
     */
    private static String last(Context context) {
        try {
            return new String(new AtomicFile(new File(context.getFilesDir(), LAST_FILE)).readFully(),
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
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
