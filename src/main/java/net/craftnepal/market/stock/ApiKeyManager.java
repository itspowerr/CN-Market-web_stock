package net.craftnepal.market.stock;

import net.craftnepal.market.Market;
import org.bukkit.Bukkit;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;

public final class ApiKeyManager {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int KEY_BYTE_LENGTH = 48;
    private static final int KEY_EXPIRY_DAYS = 7;
    private static final int SESSION_EXPIRY_HOURS = 24;
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCKOUT_MINUTES = 15;
    private static final int PBKDF2_ITERATIONS = 120000;

    public static final String PREFIX = "mk_";

    public static class ApiKeyData {
        public final String uuid;
        public final String keyHash;
        public final long createdAt;
        public final long expiresAt;
        public long lastUsedAt;
        public String sessionToken;
        public long sessionExpiresAt;
        public String totpSecret;
        public boolean totpEnabled;
        public String backupCodes;
        public int failedAttempts;
        public long lockedUntil;

        public ApiKeyData(String uuid, String keyHash, long createdAt, long expiresAt) {
            this.uuid = uuid;
            this.keyHash = keyHash;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }

        public boolean isLocked() {
            return System.currentTimeMillis() < lockedUntil;
        }

        public boolean hasValidSession() {
            if (sessionToken == null) return false;
            return System.currentTimeMillis() < sessionExpiresAt;
        }
    }

    public static String generateKey() {
        byte[] bytes = new byte[KEY_BYTE_LENGTH];
        RANDOM.nextBytes(bytes);
        return PREFIX + HexFormat.of().formatHex(bytes);
    }

    public static String hashKey(String plainKey) {
        try {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            KeySpec spec = new PBEKeySpec(plainKey.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return HexFormat.of().formatHex(salt) + ":" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash API key", e);
        }
    }

    public static boolean verifyKey(String plainKey, String storedHash) {
        try {
            String[] parts = storedHash.split(":");
            if (parts.length != 2) return false;
            byte[] salt = HexFormat.of().parseHex(parts[0]);
            byte[] expectedHash = HexFormat.of().parseHex(parts[1]);
            KeySpec spec = new PBEKeySpec(plainKey.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] actualHash = factory.generateSecret(spec).getEncoded();
            return java.security.MessageDigest.isEqual(expectedHash, actualHash);
        } catch (Exception e) {
            return false;
        }
    }

    public static String generateSessionToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static boolean hasApiKey(String uuid) {
        String sql = "SELECT 1 FROM api_keys WHERE uuid = ?";
        try (Connection conn = net.craftnepal.market.managers.DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public static String createApiKey(String uuid) {
        String plainKey = generateKey();
        String keyHash = hashKey(plainKey);
        long now = System.currentTimeMillis();
        long expiresAt = now + (KEY_EXPIRY_DAYS * 24L * 60L * 60L * 1000L);

        String sql = "INSERT OR REPLACE INTO api_keys (uuid, key_hash, created_at, expires_at) VALUES (?, ?, ?, ?)";
        try (Connection conn = net.craftnepal.market.managers.DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid);
            stmt.setString(2, keyHash);
            stmt.setLong(3, now);
            stmt.setLong(4, expiresAt);
            stmt.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[Market] Failed to create API key for " + uuid + ": " + e.getMessage());
            return null;
        }

        Bukkit.getLogger().info("[Market] API key generated for " + uuid + " - expires " + new java.util.Date(expiresAt));
        return plainKey;
    }

    public static ApiKeyData loadApiKey(String uuid) {
        String sql = "SELECT * FROM api_keys WHERE uuid = ?";
        try (Connection conn = net.craftnepal.market.managers.DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    ApiKeyData data = new ApiKeyData(
                            rs.getString("uuid"),
                            rs.getString("key_hash"),
                            rs.getLong("created_at"),
                            rs.getLong("expires_at")
                    );
                    data.lastUsedAt = rs.getLong("last_used_at");
                    data.sessionToken = rs.getString("session_token");
                    data.sessionExpiresAt = rs.getLong("session_expires_at");
                    data.totpSecret = rs.getString("totp_secret");
                    data.totpEnabled = rs.getInt("totp_enabled") == 1;
                    data.backupCodes = rs.getString("backup_codes");
                    data.failedAttempts = rs.getInt("failed_attempts");
                    data.lockedUntil = rs.getLong("locked_until");
                    return data;
                }
            }
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[Market] Failed to load API key for " + uuid + ": " + e.getMessage());
        }
        return null;
    }

    public static ApiKeyData loadApiKeyBySession(String sessionToken) {
        String sql = "SELECT * FROM api_keys WHERE session_token = ? AND session_expires_at > ?";
        try (Connection conn = net.craftnepal.market.managers.DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionToken);
            stmt.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return loadApiKey(rs.getString("uuid"));
                }
            }
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[Market] Failed to load session: " + e.getMessage());
        }
        return null;
    }

    public static boolean validateKey(String uuid, String plainKey) {
        ApiKeyData data = loadApiKey(uuid);
        if (data == null) return false;
        if (data.isExpired()) return false;
        if (data.isLocked()) return false;
        if (!verifyKey(plainKey, data.keyHash)) {
            incrementFailedAttempts(uuid);
            return false;
        }
        resetFailedAttempts(uuid);
        return true;
    }

    public static String createSession(String uuid) {
        String token = generateSessionToken();
        long expiresAt = System.currentTimeMillis() + (SESSION_EXPIRY_HOURS * 60L * 60L * 1000L);
        String sql = "UPDATE api_keys SET session_token = ?, session_expires_at = ?, last_used_at = ? WHERE uuid = ?";
        try (Connection conn = net.craftnepal.market.managers.DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, token);
            stmt.setLong(2, expiresAt);
            stmt.setLong(3, System.currentTimeMillis());
            stmt.setString(4, uuid);
            stmt.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[Market] Failed to create session for " + uuid + ": " + e.getMessage());
            return null;
        }
        return token;
    }

    public static void destroySession(String uuid) {
        String sql = "UPDATE api_keys SET session_token = NULL, session_expires_at = 0 WHERE uuid = ?";
        try (Connection conn = net.craftnepal.market.managers.DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid);
            stmt.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[Market] Failed to destroy session for " + uuid + ": " + e.getMessage());
        }
    }

    public static void revokeKey(String uuid) {
        String sql = "DELETE FROM api_keys WHERE uuid = ?";
        try (Connection conn = net.craftnepal.market.managers.DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid);
            stmt.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[Market] Failed to revoke API key for " + uuid + ": " + e.getMessage());
        }
    }

    public static void setupTOTP(String uuid, String secret, String[] backupCodes) {
        String codesJson = "[" + String.join(",", java.util.Arrays.stream(backupCodes)
                .map(c -> "\"" + hashKey(c) + "\"").toArray(String[]::new)) + "]";
        String sql = "UPDATE api_keys SET totp_secret = ?, totp_enabled = 1, backup_codes = ? WHERE uuid = ?";
        try (Connection conn = net.craftnepal.market.managers.DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, secret);
            stmt.setString(2, codesJson);
            stmt.setString(3, uuid);
            stmt.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[Market] Failed to setup TOTP for " + uuid + ": " + e.getMessage());
        }
    }

    public static boolean verifyBackupCode(String uuid, String code) {
        ApiKeyData data = loadApiKey(uuid);
        if (data == null || data.backupCodes == null) return false;
        try {
            String codes = data.backupCodes;
            int start = codes.indexOf("\"");
            while (start != -1) {
                int end = codes.indexOf("\"", start + 1);
                if (end == -1) break;
                String storedHash = codes.substring(start + 1, end);
                if (verifyKey(code, storedHash)) {
                    String updated = codes.substring(0, start) + codes.substring(end + 1);
                    String sql = "UPDATE api_keys SET backup_codes = ? WHERE uuid = ?";
                    try (Connection conn = net.craftnepal.market.managers.DatabaseManager.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, updated);
                        stmt.setString(2, uuid);
                        stmt.executeUpdate();
                    }
                    return true;
                }
                start = codes.indexOf("\"", end + 1);
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private static void incrementFailedAttempts(String uuid) {
        String sql = "UPDATE api_keys SET failed_attempts = failed_attempts + 1 WHERE uuid = ?";
        try (Connection conn = net.craftnepal.market.managers.DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid);
            stmt.executeUpdate();
        } catch (SQLException e) {
        }
        checkLockout(uuid);
    }

    private static void checkLockout(String uuid) {
        String sql = "SELECT failed_attempts FROM api_keys WHERE uuid = ?";
        try (Connection conn = net.craftnepal.market.managers.DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && rs.getInt("failed_attempts") >= MAX_FAILED_ATTEMPTS) {
                    long lockedUntil = System.currentTimeMillis() + (LOCKOUT_MINUTES * 60L * 1000L);
                    String lockSql = "UPDATE api_keys SET locked_until = ? WHERE uuid = ?";
                    try (PreparedStatement lockStmt = conn.prepareStatement(lockSql)) {
                        lockStmt.setLong(1, lockedUntil);
                        lockStmt.setString(2, uuid);
                        lockStmt.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
        }
    }

    private static void resetFailedAttempts(String uuid) {
        String sql = "UPDATE api_keys SET failed_attempts = 0, locked_until = 0 WHERE uuid = ?";
        try (Connection conn = net.craftnepal.market.managers.DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid);
            stmt.executeUpdate();
        } catch (SQLException e) {
        }
    }

    public static long getExpiryTimestamp(String uuid) {
        ApiKeyData data = loadApiKey(uuid);
        return data != null ? data.expiresAt : 0;
    }
}
