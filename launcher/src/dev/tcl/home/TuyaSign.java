package dev.tcl.home;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signing and encryption of the requests to the Smart Life sharing API, as the official tuya_sharing SDK does
 * them (`tools/tuya-login.py` has the same functions in Python). Only the pieces that must match the server to
 * the byte; no network and no JSON here.
 */
final class TuyaSign {
    private static final String NONCE_ALPHABET = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";
    private static final String[] SIGNED_HEADERS = {"X-appKey", "X-requestId", "X-sid", "X-time", "X-token"};
    private static final SecureRandom RANDOM = new SecureRandom();

    private TuyaSign() {
    }

    /**
     * @param requestId the id of this request
     * @param refreshToken the session's refresh token
     * @return the key that signs this request: md5 of the two, in hex
     */
    static String hashKey(String requestId, String refreshToken) throws GeneralSecurityException {
        byte[] md5 = MessageDigest.getInstance("MD5").digest((requestId + refreshToken).getBytes(StandardCharsets.UTF_8));
        return hex(md5);
    }

    /**
     * @param requestId the id of this request
     * @param hashKeyHex the request's hash key
     * @return the 16 character AES key of this request
     */
    static String deriveSecret(String requestId, String hashKeyHex) throws GeneralSecurityException {
        return hex(hmac(requestId, hashKeyHex)).substring(0, 16);
    }

    /**
     * @param hashKeyHex the request's hash key
     * @param queryEnc the encrypted query, or empty
     * @param bodyEnc the encrypted body, or empty
     * @param headers the request headers; only the signed ones that are not empty count
     * @return the value of the X-sign header
     */
    static String sign(String hashKeyHex, String queryEnc, String bodyEnc, Map<String, String> headers)
            throws GeneralSecurityException {
        StringBuilder text = new StringBuilder();
        for (String name : SIGNED_HEADERS) {
            String value = headers.get(name);
            if (value != null && !value.isEmpty()) {
                text.append(text.length() > 0 ? "||" : "").append(name).append('=').append(value);
            }
        }
        return hex(hmac(hashKeyHex, text + queryEnc + bodyEnc));
    }

    /**
     * @param json the text to protect
     * @param secret the request's AES key
     * @return base64 of the nonce followed by base64 of the AES-GCM ciphertext and tag
     */
    static String encrypt(String json, String secret) throws GeneralSecurityException {
        StringBuilder nonce = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            nonce.append(NONCE_ALPHABET.charAt(RANDOM.nextInt(NONCE_ALPHABET.length())));
        }
        return encrypt(json, secret, nonce.toString());
    }

    /** Same as {@link #encrypt(String, String)} with a chosen nonce, for tests against the official SDK. */
    static String encrypt(String json, String secret, String nonce) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = nonce.getBytes(StandardCharsets.UTF_8);
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "AES"), new GCMParameterSpec(128, iv));
        byte[] sealed = c.doFinal(json.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(iv) + Base64.getEncoder().encodeToString(sealed);
    }

    /**
     * @param b64 the server's answer: base64 of the nonce (12 bytes), the ciphertext and the tag
     * @param secret the request's AES key
     * @return the decrypted text
     */
    static String decrypt(String b64, String secret) throws GeneralSecurityException {
        byte[] raw = Base64.getDecoder().decode(b64);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "AES"),
            new GCMParameterSpec(128, raw, 0, 12));
        return new String(c.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8);
    }

    private static byte[] hmac(String key, String message) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }
}
