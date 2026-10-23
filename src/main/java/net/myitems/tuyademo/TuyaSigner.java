package net.myitems.tuyademo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class TuyaSigner {
    private TuyaSigner() {}
    public static String stringToSign(String method, String pathAndSortedQuery, String body) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
            return method + "\n" + hash + "\n\n" + pathAndSortedQuery;
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public static String sign(String clientId, String secret, String token, String timestamp, String nonce, String stringToSign) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().withUpperCase().formatHex(mac.doFinal((clientId + token + timestamp + nonce + stringToSign).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("HMAC unavailable"); }
    }
}
