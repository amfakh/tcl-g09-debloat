package dev.tcl.home;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Tuya cloud, through the Smart Life sharing API, for what has no local protocol: an air conditioner behind an
 * IR hub is a virtual remote that only the cloud can drive. The session comes from `tools/tuya-login.py`; its
 * tokens last about two hours, so a request renews them first, and the new session is handed to the listener to
 * be kept (the refresh token changes with every renewal).
 */
final class TuyaCloud {
    /** The client id of Home Assistant's Tuya integration, which is the one the QR login of Smart Life accepts. */
    private static final String CLIENT_ID = "HA_3y9q4ak7g4ephrvke";
    private static final int TIMEOUT_MS = 10_000;
    private static final String TOKEN_EXPIRED = "1010";

    /** Told whenever the tokens were renewed, so the new session can be kept. */
    interface Listener {
        void sessionChanged(JSONObject session);
    }

    /** A Tuya error answer, with its code. */
    static final class CloudException extends IOException {
        final String code;

        CloudException(String message, String code) {
            super(message);
            this.code = code;
        }
    }

    private final Listener listener;
    private JSONObject session;

    /**
     * @param session the session `tools/tuya-login.py` saved (endpoint, accessToken, refreshToken, expiresAt)
     * @param listener told when the tokens are renewed
     */
    TuyaCloud(JSONObject session, Listener listener) {
        this.session = session;
        this.listener = listener;
    }

    /**
     * Sends commands to a device by name, as Tuya's own app does.
     *
     * @param deviceId the device
     * @param commands [{"code":"T","value":23}, ...]
     * @throws IOException when the cloud refuses or cannot be reached
     */
    synchronized void sendCommands(String deviceId, JSONArray commands) throws IOException {
        try {
            request("POST", "/v1.1/m/thing/" + deviceId + "/commands", null, new JSONObject().put("commands", commands));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /**
     * One signed and encrypted request, after renewing the tokens when they are about to run out; a token the server
     * calls expired earlier than expected is renewed once and the request repeated.
     *
     * @param method GET or POST
     * @param path path after the regional endpoint
     * @param params query parameters, or null
     * @param body request body, or null
     * @return the whole answer, with `result` already decrypted and parsed
     */
    synchronized JSONObject request(String method, String path, JSONObject params, JSONObject body) throws IOException {
        try {
            if (session.getLong("expiresAt") - 60_000 < System.currentTimeMillis()) {
                refresh();
            }
            try {
                return signed(method, path, params, body, true);
            } catch (CloudException e) {
                if (!TOKEN_EXPIRED.equals(e.code)) {
                    throw e;
                }
                refresh();
                return signed(method, path, params, body, true);
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private void refresh() throws IOException, JSONException {
        // Without the old token: with it the server refuses its own renewal.
        JSONObject answer = signed("GET", "/v1.0/m/token/" + session.getString("refreshToken"), null, null, false);
        JSONObject token = answer.getJSONObject("result");
        session.put("accessToken", token.getString("accessToken"));
        session.put("refreshToken", token.getString("refreshToken"));
        session.put("expiresAt", answer.getLong("t") + token.getLong("expireTime") * 1000);
        listener.sessionChanged(new JSONObject(session.toString()));
    }

    private JSONObject signed(String method, String path, JSONObject params, JSONObject body, boolean withToken)
            throws IOException, JSONException {
        try {
            String requestId = UUID.randomUUID().toString();
            String key = TuyaSign.hashKey(requestId, session.getString("refreshToken"));
            String secret = TuyaSign.deriveSecret(requestId, key);
            String url = session.getString("endpoint") + path;
            String queryEnc = "";
            String bodyEnc = "";
            if (params != null && params.length() > 0) {
                queryEnc = TuyaSign.encrypt(params.toString(), secret);
                url += "?encdata=" + URLEncoder.encode(queryEnc, "UTF-8");
            }
            if (body != null && body.length() > 0) {
                bodyEnc = TuyaSign.encrypt(body.toString(), secret);
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("X-appKey", CLIENT_ID);
            headers.put("X-requestId", requestId);
            headers.put("X-sid", "");
            headers.put("X-time", String.valueOf(System.currentTimeMillis()));
            if (withToken) {
                headers.put("X-token", session.getString("accessToken"));
            }
            headers.put("X-sign", TuyaSign.sign(key, queryEnc, bodyEnc, headers));

            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod(method);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                c.setRequestProperty(h.getKey(), h.getValue());
            }
            if (!bodyEnc.isEmpty()) {
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(new JSONObject().put("encdata", bodyEnc).toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = c.getResponseCode();
            if (status != 200) {
                throw new CloudException("HTTP " + status + " " + path, String.valueOf(status));
            }
            JSONObject answer = new JSONObject(read(c.getInputStream()));
            if (!answer.optBoolean("success")) {
                throw new CloudException("Tuya " + answer.opt("code") + ": " + answer.opt("msg"), String.valueOf(answer.opt("code")));
            }
            Object result = answer.opt("result");
            if (result instanceof String && !((String) result).isEmpty()) {
                String plain = TuyaSign.decrypt((String) result, secret);
                answer.put("result", new JSONTokener(plain).nextValue());
            }
            return answer;
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n = stream.read(buffer); n >= 0; n = stream.read(buffer)) {
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        }
    }
}
