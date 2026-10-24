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
                    item.title = d.kind.equals("purifier") ? "Air purifier" : "Water fountain";
                    item.packageName = d.kind;
                    item.action = "smart:" + (read.size() - 1);
                    try {
                        JSONObject dps = new JSONObject(queryResolving(d)).getJSONObject("dps");
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
                    items.addAll(acItems());
                }
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
     * Changes one thing on the air conditioner through the cloud: power, temperature one degree up or down, the
     * next mode or the next fan speed. An IR remote reports nothing back, so the new value is only remembered
     * once the cloud has accepted it; a refusal is remembered too, so the card can say it was not sent.
     *
     * @param what "power", "up", "down", "mode" or "fan", from the card's action ("ac:up")
     * @throws IOException when the cloud does not take the command
     */
    void sendAc(String what) throws IOException {
        boolean power = prefs.getBoolean("ac_power", false);
        int temp = prefs.getInt("ac_temp", 24);
        int mode = prefs.getInt("ac_mode", 0);
        int fan = prefs.getInt("ac_fan", 0);
        try {
            JSONObject command = new JSONObject();
            switch (what) {
                case "power":
                    power = !power;
                    command.put("code", power ? "PowerOn" : "PowerOff").put("value", power ? "PowerOn" : "PowerOff");
                    break;
                case "up":
                case "down":
                    temp = Math.max(acMinTemp, Math.min(acMaxTemp, temp + (what.equals("up") ? 1 : -1)));
                    command.put("code", "T").put("value", temp);
                    break;
                case "mode":
                    mode = (mode + 1) % AC_MODES.length;
                    command.put("code", "M").put("value", mode);
                    break;
                default:
                    fan = (fan + 1) % AC_FANS.length;
                    command.put("code", "F").put("value", fan);
                    break;
            }
            cloud.sendCommands(acId, new JSONArray().put(command));
        } catch (JSONException e) {
            throw new IOException(e);
        } catch (IOException e) {
            prefs.edit().putBoolean("ac_failed", true).apply();
            throw e;
        }
        prefs.edit().putBoolean("ac_power", power).putInt("ac_temp", temp).putInt("ac_mode", mode).putInt("ac_fan", fan)
            .putBoolean("ac_failed", false).apply();
    }

    /** The cards of the air conditioner, from what was last sent to it. */
    private List<TvRows.Item> acItems() {
        boolean failed = prefs.getBoolean("ac_failed", false);
        String[][] cards = {
            {"power", "Power", failed ? "Not sent" : prefs.getBoolean("ac_power", false) ? "On" : "Off"},
            {"down", "", "−"},
            {"temp", "Temperature", prefs.getInt("ac_temp", 24) + "°C"},
            {"up", "", "+"},
            {"mode", "Mode", AC_MODES[prefs.getInt("ac_mode", 0)]},
            {"fan", "Fan", AC_FANS[prefs.getInt("ac_fan", 0)]},
        };
        List<TvRows.Item> items = new ArrayList<>();
        for (String[] card : cards) {
            TvRows.Item item = new TvRows.Item();
            item.action = "ac:" + card[0];
            item.title = card[1];
            item.description = card[2];
            items.add(item);
        }
        return items;
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

    /** Fills the detail line of a device that is on: air quality and filter, or UV and filter. */
    private static void detail(Device d, JSONObject dps, TvRows.Item item) {
        if (!d.on) {
            return;
        }
        if (d.kind.equals("purifier")) {
            int pm25 = dps.optInt("2");
            item.episode = "PM2.5 " + pm25 + "  ·  Filter " + dps.optInt("5") + "%";
            // The same pastel scale as the air quality pill at the top right of Home.
            item.accent = pm25 <= 12 ? 0xFFD9EAD3 : pm25 <= 35 ? 0xFFFFF2CC : pm25 <= 55 ? 0xFFFCE5CD : 0xFFF4CCCC;
        } else {
            item.episode = (dps.optBoolean("14") ? "UV on" : "UV off") + "  ·  Filter " + dps.optInt("7") + " days";
        }
    }
}
