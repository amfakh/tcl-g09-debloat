package dev.tcl.home;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Talks to one Tuya device on the local network (TCP 6668), no cloud involved. Protocols 3.3 (AES-ECB,
 * CRC), 3.4 (session key, HMAC) and 3.5 (session key, AES-GCM) are supported; the version is the one
 * `tinytuya wizard` lists for the device. Every call opens a connection, does its one exchange and closes
 * it, so a failed call leaves nothing behind. Requests and replies are JSON text, left to the caller.
 */
final class Tuya {
    private static final int PORT = 6668;
    private static final int TIMEOUT_MS = 3_000;
    private static final int SESS_START = 3;
    private static final int SESS_RESP = 4;
    private static final int SESS_FINISH = 5;
    private static final int CONTROL = 7;
    private static final int STATUS = 8;
    private static final int DP_QUERY = 0x0a;
    private static final int CONTROL_NEW = 0x0d;
    private static final int DP_QUERY_NEW = 0x10;
    private static final int PREFIX_55AA = 0x000055AA;
    private static final int SUFFIX_55AA = 0x0000AA55;
    private static final int PREFIX_6699 = 0x00006699;
    private static final int SUFFIX_6699 = 0x00009966;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String host;
    private final String id;
    private final byte[] localKey;
    private final String version;

    /**
     * @param host IP address of the device
     * @param id the device id from `devices.json`
     * @param localKey the 16 character local key from `devices.json`
     * @param version protocol version: "3.3", "3.4" or "3.5"
     */
    Tuya(String host, String id, String localKey, String version) {
        this.host = host;
        this.id = id;
        this.localKey = localKey.getBytes(StandardCharsets.UTF_8);
        this.version = version;
    }

    /**
     * @return the device's current state, as the JSON it replies with ({"dps":{"1":true,...}})
     * @throws IOException when the device cannot be reached or answers wrongly
     */
    String query() throws IOException {
        long now = System.currentTimeMillis() / 1000;
        boolean modern = !version.equals("3.3");
        String body = modern ? "{}"
            : "{\"gwId\":\"" + id + "\",\"devId\":\"" + id + "\",\"uid\":\"" + id + "\",\"t\":\"" + now + "\"}";
        try (Session s = new Session()) {
            return s.exchange(modern ? DP_QUERY_NEW : DP_QUERY, body, false);
        }
    }

    /**
     * @param dps the data points to set, as JSON ({"1":true})
     * @throws IOException when the device cannot be reached or answers wrongly
     */
    void control(String dps) throws IOException {
        long now = System.currentTimeMillis() / 1000;
        boolean modern = !version.equals("3.3");
        String body = modern ? "{\"protocol\":5,\"t\":" + now + ",\"data\":{\"dps\":" + dps + "}}"
            : "{\"devId\":\"" + id + "\",\"uid\":\"" + id + "\",\"t\":\"" + now + "\",\"dps\":" + dps + "}";
        try (Session s = new Session()) {
            s.exchange(modern ? CONTROL_NEW : CONTROL, body, true);
        }
    }

    /** One TCP connection to the device, with the session key once the protocol needs one. */
    private final class Session implements Closeable {
        private final Socket socket = new Socket();
        private final DataInputStream in;
        private byte[] key = localKey;
        private int seq = 1;

        Session() throws IOException {
            socket.connect(new InetSocketAddress(host, PORT), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            in = new DataInputStream(socket.getInputStream());
            if (!version.equals("3.3")) {
                negotiate();
            }
        }

        /**
         * Sends one request and returns the reply to it, skipping the status pushes a device sends in between.
         *
         * @param cmd the Tuya command
         * @param json the request body
         * @param withVersionHeader whether the version header goes in front of the body (control, not query)
         * @return the reply body
         */
        String exchange(int cmd, String json, boolean withVersionHeader) throws IOException {
            send(cmd, json.getBytes(StandardCharsets.UTF_8), withVersionHeader);
            while (true) {
                Frame f = receive();
                if (f.cmd == cmd) {
                    return new String(f.body, StandardCharsets.UTF_8);
                }
            }
        }

        /** Swaps nonces with the device and derives the session key from them (3.4 and 3.5). */
        private void negotiate() throws IOException {
            try {
                byte[] local = new byte[16];
                RANDOM.nextBytes(local);
                send(SESS_START, local, false);
                Frame reply = receive();
                if (reply.cmd != SESS_RESP || reply.body.length < 48) {
                    throw new IOException("session key negotiation failed");
                }
                byte[] remote = Arrays.copyOfRange(reply.body, 0, 16);
                if (!Arrays.equals(hmac(localKey, local), Arrays.copyOfRange(reply.body, 16, 48))) {
                    throw new IOException("wrong local key");
                }
                send(SESS_FINISH, hmac(localKey, remote), false);
                byte[] mixed = new byte[16];
                for (int i = 0; i < 16; i++) {
                    mixed[i] = (byte) (local[i] ^ remote[i]);
                }
                if (version.equals("3.4")) {
                    Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
                    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(localKey, "AES"));
                    key = c.doFinal(mixed);
                } else {
                    byte[] iv = Arrays.copyOf(local, 12);
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(localKey, "AES"), new GCMParameterSpec(128, iv));
                    key = Arrays.copyOf(c.doFinal(mixed), 16);
                }
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
        }

        /**
         * Wraps the body in this protocol's frame (encrypting it) and writes it.
         *
         * @param withHeader whether the version header goes with the body: in front of the encrypted body on
         *     3.3, inside it on 3.4 and 3.5
         */
        private void send(int cmd, byte[] body, boolean withHeader) throws IOException {
            try {
                ByteBuffer out;
                if (version.equals("3.5")) {
                    byte[] plain = withHeader ? concat(versionHeader(), body) : body;
                    byte[] iv = new byte[12];
                    RANDOM.nextBytes(iv);
                    ByteBuffer aad = ByteBuffer.allocate(14);
                    aad.putShort((short) 0).putInt(seq++).putInt(cmd).putInt(plain.length + 28);
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
                    c.updateAAD(aad.array());
                    byte[] sealed = c.doFinal(plain);
                    out = ByteBuffer.allocate(4 + 14 + 12 + sealed.length + 4);
                    out.putInt(PREFIX_6699).put(aad.array()).put(iv).put(sealed).putInt(SUFFIX_6699);
                } else {
                    boolean signed = version.equals("3.4");
                    byte[] payload = signed && withHeader ? ecbEncrypt(key, concat(versionHeader(), body))
                        : ecbEncrypt(key, body);
                    if (!signed && withHeader) {
                        payload = concat(versionHeader(), payload);
                    }
                    int tail = signed ? 36 : 8;
                    out = ByteBuffer.allocate(16 + payload.length + tail);
                    out.putInt(PREFIX_55AA).putInt(seq++).putInt(cmd).putInt(payload.length + tail);
                    out.put(payload);
                    byte[] head = Arrays.copyOf(out.array(), out.position());
                    if (signed) {
                        out.put(hmac(key, head));
                    } else {
                        CRC32 crc = new CRC32();
                        crc.update(head);
                        out.putInt((int) crc.getValue());
                    }
                    out.putInt(SUFFIX_55AA);
                }
                socket.getOutputStream().write(out.array());
                socket.getOutputStream().flush();
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
        }

        /** Reads one frame and decrypts it, without the return code and the version header. */
        private Frame receive() throws IOException {
            try {
                int prefix = in.readInt();
                if (prefix == PREFIX_6699) {
                    byte[] aad = new byte[14];
                    in.readFully(aad);
                    ByteBuffer h = ByteBuffer.wrap(aad);
                    h.getShort();
                    h.getInt();
                    int cmd = h.getInt();
                    byte[] rest = new byte[h.getInt()];
                    in.readFully(rest);
                    in.readInt();
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, Arrays.copyOf(rest, 12)));
                    c.updateAAD(aad);
                    byte[] plain = c.doFinal(rest, 12, rest.length - 12);
                    return new Frame(cmd, stripHeader(Arrays.copyOfRange(plain, 4, plain.length)));
                }
                if (prefix != PREFIX_55AA) {
                    throw new IOException("unexpected frame");
                }
                in.readInt();
                int cmd = in.readInt();
                byte[] rest = new byte[in.readInt()];
                in.readFully(rest);
                int tail = version.equals("3.3") ? 8 : 36;
                byte[] body = Arrays.copyOfRange(rest, 4, rest.length - tail);
                if (version.equals("3.3") && cmd == STATUS) {
                    body = stripHeader(body);
                }
                if (body.length == 0) {
                    return new Frame(cmd, body);
                }
                body = ecbDecrypt(key, body);
                return new Frame(cmd, cmd == SESS_RESP ? body : stripHeader(unpad(body)));
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
        }

        /** @return "3.x" followed by twelve zero bytes, which control requests carry in front of the body */
        private byte[] versionHeader() {
            byte[] h = new byte[15];
            System.arraycopy(version.getBytes(StandardCharsets.UTF_8), 0, h, 0, 3);
            return h;
        }

        /** @return the body without the version header a device puts in front of some replies */
        private byte[] stripHeader(byte[] body) {
            byte[] v = version.getBytes(StandardCharsets.UTF_8);
            boolean has = body.length >= 15 && body[0] == v[0] && body[1] == v[1] && body[2] == v[2];
            return has ? Arrays.copyOfRange(body, 15, body.length) : body;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** A frame from the device: the command it answers and its decrypted body. */
    private static final class Frame {
        final int cmd;
        final byte[] body;

        Frame(int cmd, byte[] body) {
            this.cmd = cmd;
            this.body = body;
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] ecbEncrypt(byte[] key, byte[] data) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return c.doFinal(data);
    }

    private static byte[] ecbDecrypt(byte[] key, byte[] data) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return c.doFinal(data);
    }

    /** @return the data without its PKCS#7 padding, or as it is when it carries none */
    private static byte[] unpad(byte[] data) {
        int pad = data[data.length - 1];
        if (pad < 1 || pad > 16 || pad > data.length) {
            return data;
        }
        for (int i = data.length - pad; i < data.length; i++) {
            if (data[i] != pad) {
                return data;
            }
        }
        return Arrays.copyOf(data, data.length - pad);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(a.length + b.length);
        out.write(a, 0, a.length);
        out.write(b, 0, b.length);
        return out.toByteArray();
    }
}
