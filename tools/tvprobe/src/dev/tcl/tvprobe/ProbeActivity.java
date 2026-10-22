package dev.tcl.tvprobe;

import android.app.Activity;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.util.Map;
import java.util.TreeMap;

/**
 * Logs, per package, how many rows of the TV provider an app holding READ_TV_LISTINGS can read:
 * channels, preview programs (app rows) and Watch Next programs (Continue watching). Channel names are
 * logged; program titles are not, since they reveal what the household watches.
 * Output goes to logcat under the tag "TvProbe"; the activity shows nothing and finishes at once.
 */
public class ProbeActivity extends Activity {
    private static final String TAG = "TvProbe";

    /**
     * Runs the three queries and finishes.
     *
     * @param savedInstanceState unused
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "BEGIN");
        dump("channel", TvContract.Channels.CONTENT_URI, TvContract.Channels.COLUMN_DISPLAY_NAME);
        dump("preview_program", TvContract.PreviewPrograms.CONTENT_URI, null);
        dump("watch_next", TvContract.WatchNextPrograms.CONTENT_URI, null);
        watchNextTypes();
        intentShapes();
        programIds();
        Log.i(TAG, "END");
        finish();
    }

    /**
     * Logs the shape of the first preview program intent URI per package, with every digit and long
     * id masked, so a launcher can learn how an app wants its items opened. Image URIs get the same
     * treatment, with query values dropped, to see where artwork is served from.
     */
    private void intentShapes() {
        Map<String, String> shapes = new TreeMap<>();
        Map<String, String> images = new TreeMap<>();
        try (Cursor c = getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            if (c == null) {
                return;
            }
            int pkgCol = c.getColumnIndex("package_name");
            int intentCol = c.getColumnIndex(TvContract.PreviewPrograms.COLUMN_INTENT_URI);
            int posterCol = c.getColumnIndex(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI);
            int thumbCol = c.getColumnIndex(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI);
            while (c.moveToNext()) {
                String pkg = c.getString(pkgCol);
                if (!shapes.containsKey(pkg) && !c.isNull(intentCol)) {
                    shapes.put(pkg, c.getString(intentCol).replaceAll("[0-9a-f]{12,}", "<id>").replaceAll("[0-9]", "#"));
                }
                if (!images.containsKey(pkg)) {
                    images.put(pkg, "poster=" + imageShape(c, posterCol) + " thumb=" + imageShape(c, thumbCol));
                }
            }
        } catch (RuntimeException e) {
            return;
        }
        for (Map.Entry<String, String> e : shapes.entrySet()) {
            Log.i(TAG, "intent_shape " + e.getKey() + " " + e.getValue());
        }
        for (Map.Entry<String, String> e : images.entrySet()) {
            Log.i(TAG, "image_shape " + e.getKey() + " " + e.getValue());
        }
    }

    /**
     * Logs, per package, the lowest and highest preview program row id: apps that republish their rows
     * delete and insert them again, so new ids mean the rows were rewritten. A hash of the titles shows
     * whether the content changed without logging the titles.
     */
    private void programIds() {
        Map<String, long[]> ids = new TreeMap<>();
        Map<String, Integer> titles = new TreeMap<>();
        try (Cursor c = getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            if (c == null) {
                return;
            }
            int pkgCol = c.getColumnIndex("package_name");
            int idCol = c.getColumnIndex(TvContract.PreviewPrograms._ID);
            int titleCol = c.getColumnIndex(TvContract.PreviewPrograms.COLUMN_TITLE);
            while (c.moveToNext()) {
                long id = c.getLong(idCol);
                long[] r = ids.computeIfAbsent(c.getString(pkgCol), k -> new long[] {Long.MAX_VALUE, 0});
                r[0] = Math.min(r[0], id);
                r[1] = Math.max(r[1], id);
                titles.merge(c.getString(pkgCol), String.valueOf(c.getString(titleCol)).hashCode(), (a, b) -> a ^ b);
            }
        } catch (RuntimeException e) {
            return;
        }
        for (Map.Entry<String, long[]> e : ids.entrySet()) {
            Log.i(TAG, "program_ids " + e.getKey() + " " + e.getValue()[0] + ".." + e.getValue()[1]
                + " titles=" + Integer.toHexString(titles.get(e.getKey())));
        }
    }

    /**
     * @param c cursor on a program row
     * @param col the image column
     * @return scheme, host and masked path of the URI plus its query keys only, or "-"
     */
    private static String imageShape(Cursor c, int col) {
        if (col < 0 || c.isNull(col)) {
            return "-";
        }
        Uri u = Uri.parse(c.getString(col));
        String path = u.getPath() == null ? "" : u.getPath().replaceAll("[0-9a-fA-F]{12,}", "<id>").replaceAll("[0-9]", "#");
        return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + path + " keys=" + u.getQueryParameterNames();
    }

    /**
     * Logs, per package, how many Watch Next rows are of each type (continue, next episode, new episode,
     * watchlist) and how many carry a playback position, to tell real "keep watching" rows from
     * promotions an app files under Watch Next.
     */
    private void watchNextTypes() {
        Map<String, int[]> perPkg = new TreeMap<>();
        try (Cursor c = getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            if (c == null) {
                return;
            }
            int pkgCol = c.getColumnIndex("package_name");
            int typeCol = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE);
            int posCol = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
            while (c.moveToNext()) {
                int[] n = perPkg.computeIfAbsent(c.getString(pkgCol), k -> new int[6]);
                int type = c.isNull(typeCol) ? 4 : c.getInt(typeCol);
                n[type >= 0 && type <= 3 ? type : 4]++;
                if (!c.isNull(posCol) && c.getLong(posCol) > 0) {
                    n[5]++;
                }
            }
        } catch (RuntimeException e) {
            Log.i(TAG, "watch_next_types error=" + e);
            return;
        }
        for (Map.Entry<String, int[]> e : perPkg.entrySet()) {
            int[] n = e.getValue();
            Log.i(TAG, "watch_next_types " + e.getKey() + " continue=" + n[0] + " next=" + n[1] + " new=" + n[2]
                + " watchlist=" + n[3] + " other=" + n[4] + " withPosition=" + n[5]);
        }
    }

    /**
     * Reads a whole table (a selection would throw without ACCESS_ALL_EPG_DATA) and logs a row count
     * per package, plus every row name when a name column is given.
     *
     * @param label short table name for the log
     * @param uri table to read
     * @param titleColumn column holding a row's name, or null to log counts only
     */
    private void dump(String label, Uri uri, String titleColumn) {
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, StringBuilder> samples = new TreeMap<>();
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c == null) {
                Log.i(TAG, label + " null cursor");
                return;
            }
            int pkgCol = c.getColumnIndex("package_name");
            int titleCol = titleColumn != null ? c.getColumnIndex(titleColumn) : -1;
            while (c.moveToNext()) {
                String pkg = pkgCol >= 0 ? c.getString(pkgCol) : "?";
                int n = counts.getOrDefault(pkg, 0);
                counts.put(pkg, n + 1);
                if (titleCol >= 0) {
                    StringBuilder sb = samples.computeIfAbsent(pkg, k -> new StringBuilder());
                    sb.append(sb.length() > 0 ? " | " : "").append(c.getString(titleCol));
                }
            }
            Log.i(TAG, label + " total=" + c.getCount());
        } catch (RuntimeException e) {
            Log.i(TAG, label + " error=" + e);
            return;
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            StringBuilder sb = samples.get(e.getKey());
            Log.i(TAG, label + " " + e.getKey() + " rows=" + e.getValue() + (sb != null ? " names=" + sb : ""));
        }
    }
}
