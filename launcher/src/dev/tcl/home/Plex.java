package dev.tcl.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to Plex for the On Deck part of Continue watching. Signs in with a PIN (plex.tv/link), switches
 * to the Plex Home user set in config.xml (a managed user without a PIN), finds that user's servers and
 * reads their On Deck. Tokens stay in this app's private preferences. Every call blocks: use it off the
 * main thread.
 */
final class Plex {
    // A Plex item link: the app's own route (plex://libraries/...) or the older form Plex still writes
    // into its Android TV rows (plex://server://.../library/metadata/...). Groups: server id, ratingKey.
    private static final Pattern VIEW_OFFSET = Pattern.compile("[?&]viewOffset=(\\d+)");
    private static final Pattern LINK = Pattern.compile("^plex://(?:server://|libraries/)([^/]+)/.*/(\\d+)(?:\\?.*)?$");
    private static final String PREFS = "plex";
    private static final String KEY_CLIENT = "client_id";
    private static final String KEY_ACCOUNT = "account_token";
    private static final String KEY_USER = "user_token";
    private static final String KEY_SERVERS = "servers";
    private static final String KEY_THUMB = "user_thumb";
    /** On Deck: what the user is watching, plus the next episode of shows in progress. */
    static final String ON_DECK = "/library/onDeck";
    /** Newest additions across all libraries. */
    static final String RECENTLY_ADDED = "/library/recentlyAdded?X-Plex-Container-Start=0&X-Plex-Container-Size=20";
    private static final String PLEX_TV = "https://plex.tv/api/v2";
    private static final int TIMEOUT_MS = 6000;

    /** A sign-in PIN: the code to type at plex.tv/link and the id to poll. */
    static final class Pin {
        final long id;
        final String code;

        Pin(long id, String code) {
            this.id = id;
            this.code = code;
        }
    }

    private final SharedPreferences prefs;

    /**
     * @param context any context
     */
    Plex(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * @return whether a Plex account token is stored
     */
    boolean signedIn() {
        return prefs.getString(KEY_ACCOUNT, null) != null;
    }

    /**
     * Asks plex.tv for a short code to type at plex.tv/link.
     *
     * @return the PIN
     * @throws IOException when plex.tv cannot be reached
     */
    Pin newPin() throws IOException {
        JSONObject pin = json(request("POST", PLEX_TV + "/pins", null));
        return new Pin(pin.optLong("id"), pin.optString("code"));
    }

    /**
     * Checks whether the PIN was linked; stores the account token when it was.
     *
     * @param pin the PIN shown on screen
     * @return true once signed in
     * @throws IOException when plex.tv cannot be reached
     */
    boolean pollPin(Pin pin) throws IOException {
        String token = json(request("GET", PLEX_TV + "/pins/" + pin.id, null)).optString("authToken", "");
        if (token.isEmpty() || "null".equals(token)) {
            return false;
        }
        prefs.edit().putString(KEY_ACCOUNT, token).remove(KEY_USER).remove(KEY_SERVERS).apply();
        return true;
    }

    /**
     * @return the configured Plex user's avatar URL, once known, else null
     */
    String userThumb() {
        return prefs.getString(KEY_THUMB, null);
    }

    /**
     * Movies of the user's first movie library on each server, in random order.
     *
     * @param homeUser 1-based position of the user in the account's Plex Home list
     * @return the items; empty when signed out
     * @throws IOException when plex.tv cannot be reached at all
     */
    List<TvRows.Item> movies(int homeUser) throws IOException {
        return items(homeUser, null);
    }

    /**
     * Items at one library path of the configured Plex Home user, across that user's servers.
     * Servers that refuse the connection are skipped.
     *
     * @param homeUser 1-based position of the user in the account's Plex Home list
     * @param path ON_DECK or RECENTLY_ADDED; null for the first movie library
     * @return the items; empty when signed out
     * @throws IOException when plex.tv cannot be reached at all
     */
    List<TvRows.Item> items(int homeUser, String path) throws IOException {
        List<TvRows.Item> items = new ArrayList<>();
        if (!signedIn()) {
            return items;
        }
        if (prefs.getString(KEY_THUMB, null) == null) {
            // The avatar is read while switching users: switch again once to learn it.
            prefs.edit().remove(KEY_USER).apply();
            userToken(homeUser);
        }
        String servers = prefs.getString(KEY_SERVERS, "");
        if (servers.isEmpty()) {
            servers = findServers(userToken(homeUser));
            prefs.edit().putString(KEY_SERVERS, servers).apply();
        }
        boolean anyFailed = false;
        for (String line : servers.split("\n")) {
            String[] s = line.split(" ", 3);
            if (s.length < 3) {
                continue;
            }
            try {
                String where = path != null ? path : firstMovieLibrary(s[0], s[1]);
                if (where != null) {
                    readItems(s[0], s[1], s[2], where, items);
                }
            } catch (IOException e) {
                anyFailed = true;
            }
        }
        if (anyFailed && items.isEmpty()) {
            // Addresses or tokens went stale (a server moved, a token was revoked): look them up again next time.
            prefs.edit().remove(KEY_SERVERS).remove(KEY_USER).apply();
        }
        return items;
    }

    /**
     * The link that opens an item's page in the Plex app. Plex's app routes it to the item, also when
     * it has to start first; the older server:// form opens Plex's home instead. The slug segment is
     * not checked, and the movie route opens episodes too.
     *
     * @param serverId the server's machine identifier
     * @param ratingKey the item's ratingKey
     * @return the link
     */
    static String appLink(String serverId, String ratingKey) {
        return "plex://libraries/" + serverId + "/movie/x/" + ratingKey;
    }

    /**
     * The link that plays a Plex item right away, from where it was left: its page link with
     * autoplay=1 and offset in seconds (Plex reads offset as seconds; without it, playback starts at
     * the beginning even when the item has a resume point).
     *
     * @param item a Plex item, from the Plex API or from Plex's rows in the TV provider
     * @return the link, or the item's own link when it is not a Plex item link
     */
    static String appLink(TvRows.Item item) {
        Matcher m = item.intentUri != null ? LINK.matcher(item.intentUri) : null;
        if (m == null || !m.matches()) {
            return item.intentUri;
        }
        long offsetMs = item.positionMs;
        Matcher view = VIEW_OFFSET.matcher(item.intentUri);
        if (offsetMs <= 0 && view.find()) {
            // Plex's own rows carry the resume point in their link, in milliseconds.
            offsetMs = Long.parseLong(view.group(1));
        }
        return appLink(m.group(1), m.group(2)) + "?autoplay=1" + (offsetMs >= 1000 ? "&offset=" + offsetMs / 1000 : "");
    }

    /**
     * Gives Plex app rows read from the TV provider artwork from the server itself: the Plex app serves
     * their pictures from a content provider only the stock launcher may open. Each item's link names
     * its server and ratingKey; one request per server reads them all.
     *
     * @param homeUser 1-based position of the user in the account's Plex Home list
     * @param rows items from the Plex app's preview channel, changed in place
     * @throws IOException when plex.tv cannot be reached at all
     */
    void fillArt(int homeUser, List<TvRows.Item> rows) throws IOException {
        if (!signedIn() || rows.isEmpty()) {
            return;
        }
        String servers = prefs.getString(KEY_SERVERS, "");
        if (servers.isEmpty()) {
            servers = findServers(userToken(homeUser));
            prefs.edit().putString(KEY_SERVERS, servers).apply();
        }
        for (String line : servers.split("\n")) {
            String[] s = line.split(" ", 3);
            if (s.length < 3) {
                continue;
            }
            Map<String, TvRows.Item> byKey = new HashMap<>();
            for (TvRows.Item item : rows) {
                Matcher m = item.intentUri != null ? LINK.matcher(item.intentUri) : null;
                if (m != null && m.find() && m.group(1).equals(s[2])) {
                    byKey.put(m.group(2), item);
                }
            }
            if (byKey.isEmpty()) {
                continue;
            }
            List<TvRows.Item> found = new ArrayList<>();
            try {
                readItems(s[0], s[1], s[2], "/library/metadata/" + TextUtils.join(",", byKey.keySet()), found);
            } catch (IOException e) {
                continue;
            }
            for (TvRows.Item f : found) {
                Matcher m = LINK.matcher(f.intentUri);
                TvRows.Item item = m.find() ? byKey.get(m.group(2)) : null;
                if (item != null && f.imageUri != null) {
                    item.imageUri = f.imageUri;
                }
            }
        }
    }

    /**
     * Token of the Plex Home user, switching to it from the account token when not cached.
     *
     * @param homeUser 1-based position in the Plex Home user list
     * @return the user's token
     * @throws IOException when plex.tv refuses or the user does not exist
     */
    private String userToken(int homeUser) throws IOException {
        String cached = prefs.getString(KEY_USER, null);
        if (cached != null) {
            return cached;
        }
        String account = prefs.getString(KEY_ACCOUNT, null);
        try {
            // plex.tv answers either {"users": [...]} or a bare list.
            String body = request("GET", PLEX_TV + "/home/users", account).trim();
            JSONArray users = body.startsWith("[") ? new JSONArray(body) : new JSONObject(body).optJSONArray("users");
            if (users == null || homeUser < 1 || homeUser > users.length()) {
                throw new IOException("no Plex Home user #" + homeUser);
            }
            JSONObject user = users.getJSONObject(homeUser - 1);
            String uuid = user.getString("uuid");
            prefs.edit().putString(KEY_THUMB, user.optString("thumb", null)).apply();
            String token = json(request("POST", PLEX_TV + "/home/users/" + uuid + "/switch", account)).getString("authToken");
            prefs.edit().putString(KEY_USER, token).apply();
            return token;
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    /**
     * Finds every server the user can reach and the first working address of each.
     *
     * @param userToken the Plex Home user's token
     * @return one "address accessToken machineId" line per reachable server
     * @throws IOException when plex.tv cannot be reached
     */
    private String findServers(String userToken) throws IOException {
        StringBuilder out = new StringBuilder();
        try {
            JSONArray resources = new JSONArray(request("GET", PLEX_TV + "/resources?includeHttps=1&includeRelay=1", userToken));
            for (int i = 0; i < resources.length(); i++) {
                JSONObject r = resources.getJSONObject(i);
                if (!r.optString("provides").contains("server")) {
                    continue;
                }
                String token = r.optString("accessToken", userToken);
                JSONArray conns = r.optJSONArray("connections");
                List<JSONObject> ordered = new ArrayList<>();
                for (int pass = 0; pass < 3 && conns != null; pass++) {
                    for (int j = 0; j < conns.length(); j++) {
                        JSONObject c = conns.getJSONObject(j);
                        int rank = c.optBoolean("relay") ? 2 : c.optBoolean("local") ? 1 : 0;
                        if (rank == pass) {
                            ordered.add(c);
                        }
                    }
                }
                for (JSONObject c : ordered) {
                    String uri = c.getString("uri");
                    try {
                        request("GET", uri + "/identity", token);
                        out.append(uri).append(' ').append(token).append(' ').append(r.optString("clientIdentifier")).append('\n');
                        break;
                    } catch (IOException e) {
                        // Try the next address of this server.
                    }
                }
            }
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
        return out.toString();
    }

    /**
     * @param base server address
     * @param token access token for that server
     * @return the path listing the first movie library in random order, or null without one
     * @throws IOException when the server cannot be read
     */
    private String firstMovieLibrary(String base, String token) throws IOException {
        try {
            JSONArray dirs = json(request("GET", base + "/library/sections", token))
                .getJSONObject("MediaContainer").optJSONArray("Directory");
            for (int i = 0; dirs != null && i < dirs.length(); i++) {
                JSONObject d = dirs.getJSONObject(i);
                if ("movie".equals(d.optString("type"))) {
                    return "/library/sections/" + d.optString("key")
                        + "/all?sort=random&X-Plex-Container-Start=0&X-Plex-Container-Size=20";
                }
            }
            return null;
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    /**
     * Adds one server's items at a library path as cards that open in the Plex app.
     *
     * @param base server address
     * @param token access token for that server
     * @param machineId the server's id, used in the Plex app link
     * @param path library path to read
     * @param out where the items go
     * @throws IOException when the server cannot be read
     */
    private void readItems(String base, String token, String machineId, String path, List<TvRows.Item> out) throws IOException {
        try {
            JSONArray meta = json(request("GET", base + path, token))
                .getJSONObject("MediaContainer").optJSONArray("Metadata");
            for (int i = 0; meta != null && i < meta.length(); i++) {
                JSONObject m = meta.getJSONObject(i);
                TvRows.Item item = new TvRows.Item();
                boolean episode = "episode".equals(m.optString("type"));
                item.title = episode ? m.optString("grandparentTitle") : m.optString("title");
                item.episode = episode ? "S" + m.optInt("parentIndex") + " · E" + m.optInt("index") : null;
                item.packageName = "com.plexapp.android";
                item.description = m.optString("summary", null);
                long offset = m.optLong("viewOffset");
                item.positionMs = offset;
                item.durationMs = m.optLong("duration");
                // On Deck lists unstarted next episodes too.
                item.next = episode && offset == 0 && m.optLong("lastViewedAt") == 0;
                item.watchedAtMs = m.optLong("lastViewedAt") * 1000;
                item.sortKey = item.watchedAtMs > 0 ? item.watchedAtMs : m.optLong("addedAt") * 1000;
                if (m.has("year")) {
                    item.author = String.valueOf(m.optInt("year"));
                }
                // Episodes carry a 16:9 still in thumb; films a 16:9 backdrop in art.
                String image = episode ? m.optString("thumb") : m.optString("art", m.optString("thumb"));
                if (!image.isEmpty()) {
                    item.imageUri = base + "/photo/:/transcode?width=320&height=180&minSize=1&upscale=1&url="
                        + Uri.encode(image) + "&X-Plex-Token=" + token;
                }
                item.intentUri = appLink(machineId, m.optString("ratingKey"));
                out.add(item);
            }
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    /**
     * One HTTP call with the Plex client headers.
     *
     * @param method GET or POST
     * @param url absolute URL
     * @param token X-Plex-Token, or null
     * @return the response body
     * @throws IOException on a network error or a non-2xx answer
     */
    private String request(String method, String url, String token) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        try {
            http.setRequestMethod(method);
            http.setConnectTimeout(TIMEOUT_MS);
            http.setReadTimeout(TIMEOUT_MS);
            http.setRequestProperty("Accept", "application/json");
            http.setRequestProperty("X-Plex-Product", "TCL Home");
            http.setRequestProperty("X-Plex-Device-Name", "TCL TV");
            http.setRequestProperty("X-Plex-Platform", "Android");
            http.setRequestProperty("X-Plex-Client-Identifier", clientId());
            if (token != null) {
                http.setRequestProperty("X-Plex-Token", token);
            }
            if ("POST".equals(method)) {
                http.setDoOutput(true);
                http.getOutputStream().close();
            }
            int code = http.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = http.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    body.write(buf, 0, n);
                }
                return body.toString("UTF-8");
            }
        } finally {
            http.disconnect();
        }
    }

    /**
     * @param body a JSON object
     * @return the parsed object
     * @throws IOException when the body is not JSON
     */
    private static JSONObject json(String body) throws IOException {
        try {
            return new JSONObject(body);
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    /**
     * @return this install's stable Plex client id, created on first use
     */
    private String clientId() {
        String id = prefs.getString(KEY_CLIENT, null);
        if (id == null) {
            id = "tcl-home-" + UUID.randomUUID();
            prefs.edit().putString(KEY_CLIENT, id).apply();
        }
        return id;
    }
}
