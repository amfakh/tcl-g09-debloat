package dev.tcl.home;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the rows other apps publish to the TV provider: the system Watch Next list and an app's
 * preview channels. Needs READ_TV_LISTINGS, which shows the launcher every row an app marks searchable.
 * Whole tables are read and filtered in code: a selection throws without ACCESS_ALL_EPG_DATA.
 */
final class TvRows {
    /** One card: what to show, what the hero says about it, and what to open. */
    static final class Item {
        String title;
        String packageName;
        String imageUri;
        String intentUri;
        /** Set for launcher actions instead of content, such as the Plex sign-in card. */
        String action;
        /** Who made it, such as a YouTube channel. */
        String author;
        /** "S1 · E4" for an episode. */
        String episode;
        String description;
        /** The next episode of something being watched, not started yet. */
        boolean next;
        long positionMs;
        long durationMs;
        /** When it was last watched, or 0. */
        long watchedAtMs;
        long sortKey;
        /** Colour of the dot before the detail line of a smart device card, or 0 for no dot. */
        int accent;

        /**
         * @return the fraction watched, or -1 when nothing was watched yet
         */
        float progress() {
            return positionMs > 0 && durationMs > 0 ? Math.min(1f, positionMs / (float) durationMs) : -1;
        }

        /**
         * @return the corner label: "Next", time left, or the length; null when unknown
         */
        String badge() {
            if (next) {
                return "Next";
            }
            if (durationMs <= 0) {
                return null;
            }
            return positionMs > 0 ? minutes(durationMs - positionMs) + " left" : clock(durationMs);
        }
    }

    private TvRows() {
    }

    /**
     * Continue watching from the system Watch Next list: only "continue" and "next episode" entries
     * (apps also file promotions there as "new"), most recently engaged first.
     *
     * @param context any context
     * @param excluded packages to leave out (for example a browser that files articles there)
     * @return the items, newest first
     */
    static List<Item> continueWatching(Context context, Set<String> excluded) {
        List<Item> items = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            if (c == null) {
                return items;
            }
            int type = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE);
            int engaged = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
            int position = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
            int season = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
            int episode = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
            while (c.moveToNext()) {
                int t = c.isNull(type) ? -1 : c.getInt(type);
                Item item = read(c);
                if ((t != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                        && t != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)
                        || excluded.contains(item.packageName)) {
                    continue;
                }
                item.next = t == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT;
                item.positionMs = c.isNull(position) ? 0 : c.getLong(position);
                String s = c.isNull(season) ? null : c.getString(season);
                String e = c.isNull(episode) ? null : c.getString(episode);
                item.episode = e != null ? (s != null ? "S" + s + " · " : "") + "E" + e : null;
                item.watchedAtMs = c.isNull(engaged) ? 0 : c.getLong(engaged);
                item.sortKey = item.watchedAtMs;
                items.add(item);
            }
        } catch (RuntimeException e) {
            return items;
        }
        items.sort((a, b) -> Long.compare(b.sortKey, a.sortKey));
        return items;
    }

    /**
     * The programs of one preview channel an app publishes, such as YouTube's "Recommended for you".
     *
     * @param context any context
     * @param packageName the publishing app
     * @param channelName the channel's display name
     * @return the items in the app's own order
     */
    static List<Item> channel(Context context, String packageName, String channelName) {
        Set<Long> ids = new HashSet<>();
        List<Item> items = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(TvContract.Channels.CONTENT_URI, null, null, null, null)) {
            if (c == null) {
                return items;
            }
            int pkg = c.getColumnIndex(TvContract.Channels.COLUMN_PACKAGE_NAME);
            int name = c.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME);
            int id = c.getColumnIndex(TvContract.Channels._ID);
            while (c.moveToNext()) {
                if (packageName.equals(c.getString(pkg)) && channelName.equals(c.getString(name))) {
                    ids.add(c.getLong(id));
                }
            }
        } catch (RuntimeException e) {
            return items;
        }
        if (ids.isEmpty()) {
            return items;
        }
        try (Cursor c = context.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            if (c == null) {
                return items;
            }
            int channel = c.getColumnIndex(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID);
            int weight = c.getColumnIndex(TvContract.PreviewPrograms.COLUMN_WEIGHT);
            while (c.moveToNext()) {
                if (!ids.contains(c.getLong(channel))) {
                    continue;
                }
                Item item = read(c);
                item.sortKey = c.isNull(weight) ? 0 : c.getLong(weight);
                items.add(item);
            }
        } catch (RuntimeException e) {
            return items;
        }
        // Apps rank programs by weight, highest first.
        items.sort((a, b) -> Long.compare(b.sortKey, a.sortKey));
        return items;
    }

    /**
     * @param ms a length of time
     * @return "26m" or "1h 19m"
     */
    static String minutes(long ms) {
        long m = Math.max(1, ms / 60_000);
        return m >= 60 ? (m / 60) + "h " + (m % 60) + "m" : m + "m";
    }

    /**
     * @param ms a length of time
     * @return "24:18" or "1:12:30"
     */
    static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
    }

    /**
     * Copies the columns every program row shares.
     *
     * @param c a cursor on a program row
     * @return a new item
     */
    private static Item read(Cursor c) {
        Item item = new Item();
        item.title = string(c, TvContract.PreviewPrograms.COLUMN_TITLE);
        item.packageName = string(c, "package_name");
        item.intentUri = string(c, TvContract.PreviewPrograms.COLUMN_INTENT_URI);
        item.author = string(c, TvContract.PreviewPrograms.COLUMN_AUTHOR);
        item.description = string(c, TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION);
        String duration = string(c, TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS);
        item.durationMs = duration != null ? Long.parseLong(duration) : 0;
        String thumb = string(c, TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI);
        item.imageUri = thumb != null ? thumb : string(c, TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI);
        return item;
    }

    /**
     * @param c cursor on a row
     * @param column column name
     * @return the value, or null when the column is missing or empty
     */
    private static String string(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }
}
