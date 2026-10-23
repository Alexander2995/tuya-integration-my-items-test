package net.myitems.tuyademo;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Wire format and key derivation verified against Tuya SDK commit 4b9a250.
 * See THIRD_PARTY_NOTICES.md. No fallback to ECB when GCM is selected. */
public final class TuyaQueueCrypto {
    private TuyaQueueCrypto() {}
    public static String authentication(String id, String secret) {
        return "{\"username\":\"" + id + "\",\"password\":\"" + md5(id + md5(secret)).substring(8, 24) + "\"}";
    }
    private static String md5(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("MD5 unavailable"); }
    }
    public static String decrypt(String encoded, String mode, String secret) throws GeneralSecurityException {
        if (!"aes_gcm".equals(mode)) throw new GeneralSecurityException("Unsupported message encryption");
        if (secret.length() < 24) throw new GeneralSecurityException("Invalid key length");
        final byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { throw new GeneralSecurityException("Invalid Base64"); }
        if (bytes.length < 28 || bytes.length > 1_048_576) throw new GeneralSecurityException("Invalid message length");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret.substring(8, 24).getBytes(StandardCharsets.UTF_8), "AES"), new GCMParameterSpec(128, bytes, 0, 12));
        return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
    }
}
