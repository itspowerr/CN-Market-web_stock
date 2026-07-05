package net.craftnepal.market.stock;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

public final class TwoFactorAuth {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOTP_INTERVAL = 30;
    private static final int TOTP_DIGITS = 6;
    private static final String HMAC_ALGORITHM = "HmacSHA1";

    private static final String BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public static String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    public static boolean verifyTOTP(String secret, String code) {
        if (secret == null || code == null) return false;
        long counter = System.currentTimeMillis() / 1000 / TOTP_INTERVAL;
        String expected = generateTOTP(secret, counter);
        if (expected.equals(code)) return true;
        String expectedBefore = generateTOTP(secret, counter - 1);
        if (expectedBefore.equals(code)) return true;
        String expectedAfter = generateTOTP(secret, counter + 1);
        return expectedAfter.equals(code);
    }

    public static String generateTOTP(String base32Secret, long counter) {
        try {
            byte[] secretBytes = decodeBase32(base32Secret);
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec key = new SecretKeySpec(secretBytes, HMAC_ALGORITHM);
            mac.init(key);

            byte[] counterBytes = new byte[8];
            for (int i = 7; i >= 0; i--) {
                counterBytes[i] = (byte) (counter & 0xff);
                counter >>= 8;
            }

            byte[] hash = mac.doFinal(counterBytes);
            int offset = hash[hash.length - 1] & 0xf;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);

            int otp = binary % (int) Math.pow(10, TOTP_DIGITS);
            return String.format("%0" + TOTP_DIGITS + "d", otp);
        } catch (Exception e) {
            return null;
        }
    }

    public static String[] generateBackupCodes() {
        String[] codes = new String[5];
        for (int i = 0; i < 5; i++) {
            byte[] bytes = new byte[4];
            RANDOM.nextBytes(bytes);
            int n = ((bytes[0] & 0xff) << 24) | ((bytes[1] & 0xff) << 16)
                    | ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
            codes[i] = String.format("%04X-%04X", (n >>> 16) & 0xFFFF, n & 0xFFFF);
        }
        return codes;
    }

    public static String getQRCodeURL(String playerName, String secret) {
        String issuer = "CN-Market";
        return "otpauth://totp/" + issuer + ":" + playerName
                + "?secret=" + secret + "&issuer=" + issuer
                + "&algorithm=SHA1&digits=" + TOTP_DIGITS + "&period=" + TOTP_INTERVAL;
    }

    public static String encodeBase32(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                result.append(BASE32_CHARS.charAt((buffer >>> bitsLeft) & 0x1f));
            }
        }
        if (bitsLeft > 0) {
            result.append(BASE32_CHARS.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return result.toString();
    }

    public static byte[] decodeBase32(String base32) {
        String cleaned = base32.replaceAll("[^A-Z2-7]", "").toUpperCase();
        byte[] bytes = new byte[cleaned.length() * 5 / 8];
        int buffer = 0, bitsLeft = 0, index = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            buffer = (buffer << 5) | (BASE32_CHARS.indexOf(cleaned.charAt(i)) & 0x1f);
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                bytes[index++] = (byte) ((buffer >>> bitsLeft) & 0xff);
            }
        }
        return bytes;
    }
}
