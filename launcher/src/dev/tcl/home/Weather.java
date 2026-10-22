package dev.tcl.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The weather, the chance of rain right now and the US air quality index at the configured
 * place, from Open-Meteo (no account or key). The only thing sent is the latitude and longitude in
 * config.xml. The last reading is kept so Home shows it at once.
 */
final class Weather {
    /** Read again after this long; after a failure, wait {@link #RETRY_MS} instead. */
    static final long REFRESH_MS = 30 * 60_000L;
    static final long RETRY_MS = 5 * 60_000L;
    private static final int TIMEOUT_MS = 8_000;

    /** Kinds of sky, one icon each. */
    static final int CLEAR = 0;
    static final int PARTLY = 1;
    static final int CLOUD = 2;
    static final int FOG = 3;
    static final int RAIN = 4;
    static final int STORM = 5;

    private final SharedPreferences prefs;
    private final String where;

    /**
     * @param context used for the saved reading and the configured place
     */
    Weather(Context context) {
        prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE);
        where = String.format(Locale.ROOT, "latitude=%s&longitude=%s",
            context.getString(R.string.weather_latitude), context.getString(R.string.weather_longitude));
    }

    /**
     * @return whether the saved reading is older than {@link #REFRESH_MS}, or missing, or lacks a value
     *     Home shows (the chance of rain)
     */
    boolean stale() {
        return System.currentTimeMillis() - prefs.getLong("at", 0) > REFRESH_MS || !prefs.contains("rain_now");
    }

    /**
     * @return whether a reading was ever saved
     */
    boolean known() {
        return prefs.contains("temp");
    }

    /**
     * @return the saved temperature in whole degrees Celsius
     */
    int tempC() {
        return prefs.getInt("temp", 0);
    }

    /**
     * @return the saved sky: CLEAR, PARTLY, CLOUD, FOG, RAIN or STORM
     */
    int sky() {
        return prefs.getInt("sky", CLEAR);
    }

    /**
     * @return the chance of rain in the current 15 minutes, in percent
     */
    int rainChance() {
        return prefs.getInt("rain_now", 0);
    }

    /**
     * @return the saved US AQI, or -1 when the air quality could not be read
     */
    int aqi() {
        return prefs.getInt("aqi", -1);
    }

    /**
     * @return whether the saved reading was taken in daylight (a sun rather than a moon)
     */
    boolean day() {
        return prefs.getBoolean("day", true);
    }

    /**
     * Reads the weather and the air quality now and saves them. The air quality is a separate service:
     * when only it fails, the weather is still saved, with the AQI unknown. Blocks: call off the main
     * thread.
     *
     * @throws IOException when the weather cannot be read
     */
    void refresh() throws IOException {
        try {
            JSONObject current = get("https://api.open-meteo.com/v1/forecast?" + where
                + "&current=temperature_2m,weather_code,is_day,precipitation_probability&timezone=auto")
                .getJSONObject("current");
            prefs.edit()
                .putInt("rain_now", current.optInt("precipitation_probability"))
                .putInt("temp", (int) Math.round(current.getDouble("temperature_2m")))
                .putInt("sky", sky(current.getInt("weather_code")))
                .putBoolean("day", current.getInt("is_day") == 1)
                .putInt("aqi", aqiOrUnknown())
                .putLong("at", System.currentTimeMillis())
                .apply();
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /**
     * @return the US AQI now, or -1 when it cannot be read
     */
    private int aqiOrUnknown() {
        try {
            JSONObject current = get("https://air-quality-api.open-meteo.com/v1/air-quality?" + where
                + "&current=us_aqi").getJSONObject("current");
            return current.isNull("us_aqi") ? -1 : current.getInt("us_aqi");
        } catch (IOException | JSONException e) {
            return -1;
        }
    }

    /**
     * Groups the WMO weather codes Open-Meteo uses into the kinds of sky Home draws. Snow codes count as
     * rain: it does not snow here.
     *
     * @param code WMO weather code
     * @return CLEAR, PARTLY, CLOUD, FOG, RAIN or STORM
     */
    static int sky(int code) {
        if (code <= 1) {
            return CLEAR;
        }
        if (code == 2) {
            return PARTLY;
        }
        if (code == 3) {
            return CLOUD;
        }
        if (code == 45 || code == 48) {
            return FOG;
        }
        return code >= 95 ? STORM : RAIN;
    }

    /**
     * @param url address to read
     * @return the JSON answer
     * @throws IOException when the request fails
     * @throws JSONException when the answer is not JSON
     */
    private static JSONObject get(String url) throws IOException, JSONException {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        http.setConnectTimeout(TIMEOUT_MS);
        http.setReadTimeout(TIMEOUT_MS);
        try (InputStream in = http.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            http.disconnect();
        }
    }
}
