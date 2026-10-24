package dev.tcl.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The smart devices (Tuya: BARDI and Smart Life), shown as cards on the Devices tab. The purifier and the
 * fountain are driven over the local network and switch on and off; the air conditioner is an IR remote that only
 * the Tuya cloud can drive, with power, temperature, mode and fan. The devices, their keys and the cloud session
 * come from smart_home.json in the app's files folder, put there by tools/smart-home.sh, so no key is in the APK or
 * the repo. Without that file there are no cards.
 */
final class SmartHome {
    private static final int REPORT_TRIES = 5;
    private static final long REPORT_WAIT_MS = 600;
    /** Mode and fan values in the order Tuya numbers them for an IR air conditioner. */
    private static final String[] AC_MODES = {"Cool", "Heat", "Auto", "Fan", "Dry"};
    private static final String[] AC_FANS = {"Auto", "Low", "Mid", "High"};
    /**
     * The air conditioner's card colour by mode: Cool blue, Heat red, Auto yellow, Fan green, Dry purple. None is
     * grey (that reads as off) and the two used most, Cool and Dry, are far apart.
     */
    private static final int[] AC_MODE_COLORS = {0xFF4285F4, 0xFFEA4335, 0xFFFBBC04, 0xFF34A853, 0xFFA142F4};
    /** How long the fountain's filter lasts, in days, as the BARDI app counts it. */
    private static final int FOUNTAIN_FILTER_DAYS = 30;
    /** How often the fountain wants cleaning, in days. */
    private static final int FOUNTAIN_CLEAN_DAYS = 7;
    private static final String TAG = "SmartHome";
    private static final String PREF_CLOUD = "cloud";
    private static final int TUYA_PORT = 6668;
    private static final int SWEEP_CONNECT_MS = 300;
    private static final int SWEEP_THREADS = 48;
    /** What a device card's description holds: the state it is in. */
    static final String STATE_ON = "on";
    static final String STATE_OFF = "off";
    static final String STATE_OFFLINE = "offline";

    private final File file;
    private final SharedPreferences prefs;
    private List<Device> devices = new ArrayList<>();
    private TuyaCloud cloud;
    private String acId;
    private int acMinTemp = 16;
    private int acMaxTemp = 30;

    /** One local device: what it is, how to reach it and whether it is on. */
    private static final class Device {
        String kind;
        String id;
        String key;
        String version;
        Tuya tuya;
        boolean on;
        /** The data points of the last read, by number, for the options panel. */
        JSONObject dps = new JSONObject();
    }

    /**
     * @param context used for the folder that holds smart_home.json
     */
    SmartHome(Context context) {
        file = new File(context.getExternalFilesDir(null), "smart_home.json");
        prefs = context.getSharedPreferences("smart_home", Context.MODE_PRIVATE);
    }

    /**
     * Reads the device list again and asks every local device for its state. A device that does not answer
     * gets a card that says so; the air conditioner shows what was last sent to it.
     *
     * @return the cards: one per local device, then the air conditioner's, empty without a device list
     */
    List<TvRows.Item> read() {
        List<Device> read = new ArrayList<>();
        List<TvRows.Item> items = new ArrayList<>();
        List<TvRows.Item> sensors = new ArrayList<>();
        acId = null;
        if (file.exists()) {
            try {
                JSONObject root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                cloud = openCloud(root.optJSONObject("cloud"));
                JSONArray list = root.getJSONArray("devices");
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.getJSONObject(i);
                    if (o.getString("kind").equals("ac")) {
                        acId = o.getString("id");
                        JSONObject temp = o.optJSONObject("T");
                        acMinTemp = temp != null ? temp.optInt("min", 16) : 16;
                        acMaxTemp = temp != null ? temp.optInt("max", 30) : 30;
                        continue;
                    }
                    Device d = new Device();
                    d.kind = o.getString("kind");
                    d.id = o.getString("id");
                    d.key = o.getString("key");
                    d.version = o.getString("version");
                    // The address found last time wins over the pushed one: DHCP may have moved the device.
                    d.tuya = new Tuya(prefs.getString("ip:" + d.id, o.getString("ip")), d.id, d.key, d.version);
                    read.add(d);
                    TvRows.Item item = new TvRows.Item();
                    if (d.kind.equals("sensor")) {
                        sensors.add(sensorItem(d));
                        continue;
                    }
                    item.title = d.kind.equals("purifier") ? "Air purifier" : "Water fountain";
                    item.packageName = d.kind;
                    item.action = "smart:" + (read.size() - 1);
                    try {
                        JSONObject dps = new JSONObject(queryResolving(d)).getJSONObject("dps");
                        d.dps = dps;
                        d.on = dps.getBoolean("1");
                        item.description = d.on ? STATE_ON : STATE_OFF;
                        detail(d, dps, item);
                    } catch (IOException | JSONException | RuntimeException e) {
                        Log.w(TAG, d.kind + " did not answer", e);
                        item.description = STATE_OFFLINE;
                    }
                    items.add(item);
                }
                if (acId != null) {
                    items.add(acItem());
                }
                // Last: Down from the tabs lands on the first card, which should be one that does something.
                items.addAll(sensors);
            } catch (IOException | JSONException e) {
                items.clear();
                read.clear();
            }
        }
        devices = read;
        return items;
    }

    /**
     * Switches a local device on or off, the opposite of what the last read saw.
     *
     * @param index the device, as numbered in its card's action ("smart:2")
     * @throws IOException when the device does not answer
     */
    void toggle(int index) throws IOException {
        Device d = devices.get(index);
        boolean want = !d.on;
        try {
            d.tuya.control("{\"1\":" + want + "}");
        } catch (IOException e) {
            if (!resolve(d)) {
                throw e;
            }
            d.tuya.control("{\"1\":" + want + "}");
        }
        // The purifier reports its new state a moment after it obeys; reading at once shows the old one.
        for (int i = 0; i < REPORT_TRIES; i++) {
            try {
                if (new JSONObject(d.tuya.query()).getJSONObject("dps").getBoolean("1") == want) {
                    return;
                }
                Thread.sleep(REPORT_WAIT_MS);
            } catch (JSONException e) {
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Changes one thing on the air conditioner through the cloud. An IR remote reports nothing back, so the new
     * value is only remembered once the cloud has accepted it; a refusal is remembered too, so the card can say
     * it was not sent.
     *
     * @param what "power" (the other one), "up" or "down" (one degree), "temp:N", "mode:N" or "fan:N" (the
     *     numbers Tuya uses for an IR air conditioner)
     * @throws IOException when the cloud does not take the command
     */
    void sendAc(String what) throws IOException {
        boolean power = prefs.getBoolean("ac_power", false);
        int temp = prefs.getInt("ac_temp", 24);
        int mode = prefs.getInt("ac_mode", 0);
        int fan = prefs.getInt("ac_fan", 0);
        try {
            JSONObject command = new JSONObject();
            if (what.startsWith("temp:")) {
                temp = Math.max(acMinTemp, Math.min(acMaxTemp, Integer.parseInt(what.substring("temp:".length()))));
                command.put("code", "T").put("value", temp);
            } else if (what.equals("power")) {
                power = !power;
                command.put("code", power ? "PowerOn" : "PowerOff").put("value", power ? "PowerOn" : "PowerOff");
            } else if (what.equals("up") || what.equals("down")) {
                temp = Math.max(acMinTemp, Math.min(acMaxTemp, temp + (what.equals("up") ? 1 : -1)));
                command.put("code", "T").put("value", temp);
            } else if (what.startsWith("mode:")) {
                mode = Integer.parseInt(what.substring("mode:".length()));
                command.put("code", "M").put("value", mode);
            } else {
                fan = Integer.parseInt(what.substring("fan:".length()));
                command.put("code", "F").put("value", fan);
            }
            cloud.sendCommands(acId, new JSONArray().put(command));
        } catch (JSONException e) {
            throw new IOException(e);
        } catch (IOException e) {
            Log.w(TAG, "the cloud did not take " + what, e);
            prefs.edit().putBoolean("ac_failed", true).apply();
            throw e;
        }
        prefs.edit().putBoolean("ac_power", power).putInt("ac_temp", temp).putInt("ac_mode", mode).putInt("ac_fan", fan)
            .putBoolean("ac_failed", false).apply();
    }

    /**
     * Sets one data point of a local device, such as the purifier's mode. A value that stays (a mode, a lock) is
     * waited for until the device reports it; a trigger such as a filter reset is not.
     *
     * @param index the device, as numbered in its card's action ("smart:2")
     * @param dp the data point number
     * @param value a Boolean or a String
     * @throws IOException when the device does not answer
     */
    void setLocal(int index, String dp, Object value) throws IOException {
        Device d = devices.get(index);
        String json = value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
        d.tuya.control("{\"" + dp + "\":" + json + "}");
        if (Boolean.TRUE.equals(value) && !dp.equals("7") && !dp.equals("1")) {
            return;
        }
        for (int i = 0; i < REPORT_TRIES; i++) {
            try {
                if (String.valueOf(value).equals(new JSONObject(d.tuya.query()).getJSONObject("dps").opt(dp).toString())) {
                    return;
                }
                Thread.sleep(REPORT_WAIT_MS);
            } catch (JSONException | NullPointerException e) {
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * The values the options panel of a device shows: the data points of its last read plus its kind, or for the
     * air conditioner what was last sent to it and its temperature limits.
     *
     * @param action the card's action: "smart:2" or "ac:power"
     * @return the values
     */
    JSONObject values(String action) {
        JSONObject v = new JSONObject();
        try {
            if (action.startsWith("ac:")) {
                return v.put("_kind", "ac").put("power", prefs.getBoolean("ac_power", false))
                    .put("temp", prefs.getInt("ac_temp", 24)).put("mode", prefs.getInt("ac_mode", 0))
                    .put("fan", prefs.getInt("ac_fan", 0)).put("min", acMinTemp).put("max", acMaxTemp);
            }
            Device d = devices.get(Integer.parseInt(action.split(":")[1]));
            return new JSONObject(d.dps.toString()).put("_kind", d.kind);
        } catch (JSONException e) {
            return v;
        }
    }

    /**
     * The IR hub's card: it only measures, so it has the room's temperature and humidity (data points 101, in tenths
     * of a degree, and 102) and nothing to switch.
     */
    private TvRows.Item sensorItem(Device d) {
        TvRows.Item item = new TvRows.Item();
        item.title = "Smart IR";
        item.packageName = d.kind;
        try {
            JSONObject dps = new JSONObject(queryResolving(d)).getJSONObject("dps");
            d.dps = dps;
            item.description = STATE_ON;
            item.statIcons = new int[] {Icon.THERMO, Icon.DROP};
            item.statTexts = new String[] {
                String.format(java.util.Locale.ROOT, "%.1f°C", dps.optInt("101") / 10.0), dps.optInt("102") + "%"};
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "sensor did not answer", e);
            item.description = STATE_OFFLINE;
        }
        return item;
    }

    /** The air conditioner's card, from what was last sent to it: an IR remote reports nothing back. */
    private TvRows.Item acItem() {
        TvRows.Item item = new TvRows.Item();
        item.title = "Air conditioner";
        item.packageName = "ac";
        item.action = "ac:power";
        if (prefs.getBoolean("ac_failed", false)) {
            item.description = STATE_OFFLINE;
        } else if (prefs.getBoolean("ac_power", false)) {
            item.description = STATE_ON;
            item.accent = AC_MODE_COLORS[prefs.getInt("ac_mode", 0)];
            item.statIcons = new int[] {-1, -1, Icon.WIND};
            item.statTexts = new String[] {AC_MODES[prefs.getInt("ac_mode", 0)], prefs.getInt("ac_temp", 24) + "°C",
                AC_FANS[prefs.getInt("ac_fan", 0)]};
        } else {
            item.description = STATE_OFF;
        }
        return item;
    }

    /** Asks the device for its state; when it does not answer where it was, looks for it on the network once. */
    private String queryResolving(Device d) throws IOException {
        try {
            return state(d.tuya);
        } catch (IOException e) {
            if (!resolve(d)) {
                throw e;
            }
            return state(d.tuya);
        }
    }

    /**
     * Asks a device for its state and checks the reply. A 3.3 device answers a wrong key, and another service
     * answers on a reused address, with noise instead of an error: only a reply that reads as a state counts.
     */
    private static String state(Tuya tuya) throws IOException {
        String reply = tuya.query();
        try {
            new JSONObject(reply).getJSONObject("dps");
            return reply;
        } catch (JSONException e) {
            throw new IOException("not the device", e);
        }
    }

    /**
     * Looks for a device that moved: tries every address of the /24 around its old one that accepts a connection
     * on the Tuya port, and keeps the one that answers to the device's id and local key. The purifier never
     * announces itself over UDP, so a sweep is the one way to find it that works for every protocol version.
     *
     * @return whether the device was found, at an address now remembered
     */
    private boolean resolve(Device d) {
        String old = d.tuya.host();
        String prefix = old.substring(0, old.lastIndexOf('.') + 1);
        ExecutorService pool = Executors.newFixedThreadPool(SWEEP_THREADS);
        try {
            List<Callable<String>> probes = new ArrayList<>();
            for (int i = 1; i < 255; i++) {
                String host = prefix + i;
                if (host.equals(old)) {
                    continue;
                }
                probes.add(() -> {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(host, TUYA_PORT), SWEEP_CONNECT_MS);
                    } catch (IOException e) {
                        return null;
                    }
                    try {
                        state(new Tuya(host, d.id, d.key, d.version));
                        return host;
                    } catch (IOException | RuntimeException e) {
                        // Another device, or something else that listens on this port.
                        return null;
                    }
                });
            }
            for (Future<String> found : pool.invokeAll(probes)) {
                String host = found.get();
                if (host != null) {
                    d.tuya = new Tuya(host, d.id, d.key, d.version);
                    prefs.edit().putString("ip:" + d.id, host).apply();
                    return true;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            // A probe that fails is a host that is not the device.
        } finally {
            pool.shutdownNow();
        }
        return false;
    }

    /**
     * The cloud session to use: the one this TV renewed last when it belongs to the same login as the pushed
     * one (the refresh token changes with every renewal, so the pushed copy goes stale), else the pushed one.
     */
    private TuyaCloud openCloud(JSONObject pushed) throws JSONException {
        if (pushed == null) {
            return null;
        }
        String saved = prefs.getString(PREF_CLOUD, null);
        JSONObject session = pushed;
        if (saved != null) {
            JSONObject kept = new JSONObject(saved);
            if (kept.optString("terminalId").equals(pushed.optString("terminalId"))) {
                session = kept;
            }
        }
        prefs.edit().putString(PREF_CLOUD, session.toString()).apply();
        return new TuyaCloud(session, s -> prefs.edit().putString(PREF_CLOUD, s.toString()).apply());
    }

    /**
     * Fills the detail line of a device that is on (air quality and filter, or UV and filter) and, on or off, how
     * much filter is left (and for the fountain also how long until cleaning), for the bars at the bottom of the
     * card.
     */
    private static void detail(Device d, JSONObject dps, TvRows.Item item) {
        boolean purifier = d.kind.equals("purifier");
        item.positionMs = purifier ? dps.optInt("5") : dps.optInt("7");
        item.durationMs = purifier ? 100 : FOUNTAIN_FILTER_DAYS;
        if (!purifier) {
            item.progress2 = Math.max(0, Math.min(1, dps.optInt("4") / (float) FOUNTAIN_CLEAN_DAYS));
        }
        if (!d.on) {
            return;
        }
        if (d.kind.equals("purifier")) {
            int pm25 = dps.optInt("2");
            item.statIcons = new int[] {Icon.PARTICLES, Icon.FILTER};
            item.statTexts = new String[] {String.valueOf(pm25), dps.optInt("5") + "%"};
            // The same pastel scale as the air quality pill at the top right of Home.
            item.accent = pm25 <= 12 ? 0xFFD9EAD3 : pm25 <= 35 ? 0xFFFFF2CC : pm25 <= 55 ? 0xFFFCE5CD : 0xFFF4CCCC;
        } else {
            item.statIcons = new int[] {Icon.FILTER, Icon.SPARKLE};
            item.statTexts = new String[] {dps.optInt("7") + " days", dps.optInt("4") + " days"};
            // Green while most of the filter is left, then yellow, then red when it is nearly used up.
            float left = item.progress();
            item.accent = left > 0.5f ? 0xFFD9EAD3 : left > 0.15f ? 0xFFFFF2CC : 0xFFF4CCCC;
        }
    }
}
