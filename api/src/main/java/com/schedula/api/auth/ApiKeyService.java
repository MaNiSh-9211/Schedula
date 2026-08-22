package com.schedula.api.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant API keys (SECURITY.md §1). Format: sk_<tenantId>_<secret>. Only the SHA-256
 * hash of <secret> is stored; the plaintext exists solely in the creation response.
 * The admin master key comes from configuration and never touches the database.
 */
@Service
public class ApiKeyService {

    public static final String ADMIN_ROLE = "admin";
    public static final String OPERATOR_ROLE = "operator";

    private final JdbcTemplate jdbc;
    private final String adminKey;

    public ApiKeyService(JdbcTemplate jdbc,
                         @Value("${schedula.auth.admin-key:}") String adminKey) {
        this.jdbc = jdbc;
        this.adminKey = adminKey == null ? "" : adminKey;
        if (!this.adminKey.isBlank()) {
            System.setProperty("schedula.adminKeySet", "true");
        }
    }

    public record CreatedTenant(UUID tenantId, String apiKey) {
    }

    /** Creates a tenant and returns its plaintext key exactly once. */
    public CreatedTenant createTenant(String name) {
        UUID tenantId = com.schedula.common.ids.UuidV7.generate();
        String secret = randomSecret();
        String plaintext = "sk_" + tenantId + "_" + secret;
        jdbc.update("""
                        INSERT INTO tenants (id, name, api_key_hash, api_key_prefix)
                        VALUES (?, ?, ?, ?)
                        """,
                tenantId, name, sha256Hex(secret), prefixOf(plaintext));
        return new CreatedTenant(tenantId, plaintext);
    }

    public void seedTenantKey(UUID tenantId, String plaintextKeyIfProvided) {
        String key = plaintextKeyIfProvided == null || plaintextKeyIfProvided.isBlank()
                ? "sk_" + tenantId + "_" + randomSecret()
                : plaintextKeyIfProvided;
        String secret = extractSecret(key);
        jdbc.update("UPDATE tenants SET api_key_hash = ?, api_key_prefix = ? WHERE id = ?",
                sha256Hex(secret), prefixOf(key), tenantId);
        if (plaintextKeyIfProvided == null || plaintextKeyIfProvided.isBlank()) {
            // dev convenience: one-time visible bootstrap for the default tenant
            org.slf4j.LoggerFactory.getLogger(ApiKeyService.class)
                    .warn("bootstrapped default tenant API key (change or disable): {}", key);
        }
    }

    /** Returns the tenant id when the presented key is valid; empty otherwise. */
    public Optional<UUID> authenticate(String presentedKey) {
        if (presentedKey == null || !presentedKey.startsWith("sk_")) {
            return Optional.empty();
        }
        String[] parts = presentedKey.split("_", 3);
        if (parts.length != 3) return Optional.empty();
        UUID tenantId;
        try {
            tenantId = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String secret = parts[2];
        List<String> hashes = jdbc.query(
                "SELECT api_key_hash FROM tenants WHERE id = ? AND api_key_hash IS NOT NULL",
                (rs, i) -> rs.getString("api_key_hash"), tenantId);
        if (hashes.isEmpty()) return Optional.empty();
        return MessageDigest.isEqual(hashes.get(0).getBytes(StandardCharsets.UTF_8),
                sha256Hex(secret).getBytes(StandardCharsets.UTF_8))
                ? Optional.of(tenantId) : Optional.empty();
    }

    public boolean isAdminKey(String presentedKey) {
        return !adminKey.isBlank() && presentedKey != null
                && MessageDigest.isEqual(adminKey.getBytes(StandardCharsets.UTF_8),
                        presentedKey.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomSecret() {
        byte[] raw = new byte[24];
        new java.security.SecureRandom().nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    private static String extractSecret(String key) {
        String[] parts = key.split("_", 3);
        return parts.length == 3 ? parts[2] : "";
    }

    private static String prefixOf(String key) {
        return key.length() <= 12 ? key : key.substring(0, 12);
    }

    private static String sha256Hex(String value) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
