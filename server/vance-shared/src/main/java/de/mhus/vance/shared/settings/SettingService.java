package de.mhus.vance.shared.settings;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.crypto.AesEncryptionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Setting CRUD and typed getters/setters. All reads and writes are scoped by
 * {@code (tenantId, referenceType, referenceId, key)}.
 *
 * <p>{@link SettingType#PASSWORD} values are encrypted on write and are never
 * returned via the generic {@link #getStringValue} path — use
 * {@link #getDecryptedPassword} explicitly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettingService {

    private final SettingRepository repository;
    private final AesEncryptionService encryption;

    // ──────────────────── Raw lookup ────────────────────

    public Optional<SettingDocument> find(
            String tenantId, String referenceType, String referenceId, String key) {
        return repository.findByTenantIdAndReferenceTypeAndReferenceIdAndKey(
                tenantId, referenceType, referenceId, key);
    }

    public List<SettingDocument> findAll(
            String tenantId, String referenceType, String referenceId) {
        return repository.findByTenantIdAndReferenceTypeAndReferenceId(
                tenantId, referenceType, referenceId);
    }

    /** All settings across scopes for a tenant sharing the same {@code key}. */
    public List<SettingDocument> findByKey(String tenantId, String key) {
        return repository.findByTenantIdAndKey(tenantId, key);
    }

    public boolean exists(
            String tenantId, String referenceType, String referenceId, String key) {
        return repository.existsByTenantIdAndReferenceTypeAndReferenceIdAndKey(
                tenantId, referenceType, referenceId, key);
    }

    public void delete(
            String tenantId, String referenceType, String referenceId, String key) {
        repository.deleteByTenantIdAndReferenceTypeAndReferenceIdAndKey(
                tenantId, referenceType, referenceId, key);
    }

    public long deleteAll(String tenantId, String referenceType, String referenceId) {
        return repository.deleteByTenantIdAndReferenceTypeAndReferenceId(
                tenantId, referenceType, referenceId);
    }

    // ──────────────────── Set / upsert ────────────────────

    /**
     * Sets {@code key} to {@code value} with the given type. For
     * {@link SettingType#PASSWORD} the caller must pass the already-encrypted
     * blob; higher-level password handling goes through
     * {@link #setEncryptedPassword}.
     */
    public SettingDocument set(
            String tenantId,
            String referenceType,
            String referenceId,
            String key,
            @Nullable String value,
            SettingType type,
            @Nullable String description) {
        SettingDocument doc = repository
                .findByTenantIdAndReferenceTypeAndReferenceIdAndKey(
                        tenantId, referenceType, referenceId, key)
                .orElseGet(() -> SettingDocument.builder()
                        .tenantId(tenantId)
                        .referenceType(referenceType)
                        .referenceId(referenceId)
                        .key(key)
                        .type(type)
                        .build());
        doc.setValue(value);
        doc.setType(type);
        if (description != null) {
            doc.setDescription(description);
        }
        return repository.save(doc);
    }

    // ──────────────────── Typed getters ────────────────────

    /**
     * Returns the raw string value, or {@code null} if the setting does not
     * exist. Refuses to read {@link SettingType#PASSWORD} — use
     * {@link #getDecryptedPassword} for those.
     */
    public @Nullable String getStringValue(
            String tenantId, String referenceType, String referenceId, String key) {
        Optional<SettingDocument> opt = find(tenantId, referenceType, referenceId, key);
        if (opt.isEmpty()) {
            return null;
        }
        SettingDocument doc = opt.get();
        if (doc.getType() == SettingType.PASSWORD) {
            log.warn("Refusing to read password setting via getStringValue: tenant='{}' ref='{}:{}' key='{}'",
                    tenantId, referenceType, referenceId, key);
            return null;
        }
        return doc.getValue();
    }

    public String getStringValue(
            String tenantId, String referenceType, String referenceId, String key,
            String defaultValue) {
        String v = getStringValue(tenantId, referenceType, referenceId, key);
        return v != null ? v : defaultValue;
    }

    public int getIntValue(
            String tenantId, String referenceType, String referenceId, String key,
            int defaultValue) {
        String v = getStringValue(tenantId, referenceType, referenceId, key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse int value for ref='{}:{}' key='{}': {}",
                    referenceType, referenceId, key, v);
            return defaultValue;
        }
    }

    public long getLongValue(
            String tenantId, String referenceType, String referenceId, String key,
            long defaultValue) {
        String v = getStringValue(tenantId, referenceType, referenceId, key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse long value for ref='{}:{}' key='{}': {}",
                    referenceType, referenceId, key, v);
            return defaultValue;
        }
    }

    public double getDoubleValue(
            String tenantId, String referenceType, String referenceId, String key,
            double defaultValue) {
        String v = getStringValue(tenantId, referenceType, referenceId, key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse double value for ref='{}:{}' key='{}': {}",
                    referenceType, referenceId, key, v);
            return defaultValue;
        }
    }

    public boolean getBooleanValue(
            String tenantId, String referenceType, String referenceId, String key,
            boolean defaultValue) {
        String v = getStringValue(tenantId, referenceType, referenceId, key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        String normalized = v.trim().toLowerCase();
        return "true".equals(normalized)
                || "1".equals(normalized)
                || "yes".equals(normalized)
                || "on".equals(normalized);
    }

    // ──────────────────── Typed setters ────────────────────

    public SettingDocument setStringValue(
            String tenantId, String referenceType, String referenceId, String key, String value) {
        return set(tenantId, referenceType, referenceId, key, value, SettingType.STRING, null);
    }

    public SettingDocument setIntValue(
            String tenantId, String referenceType, String referenceId, String key, int value) {
        return set(tenantId, referenceType, referenceId, key, Integer.toString(value), SettingType.INT, null);
    }

    public SettingDocument setLongValue(
            String tenantId, String referenceType, String referenceId, String key, long value) {
        return set(tenantId, referenceType, referenceId, key, Long.toString(value), SettingType.LONG, null);
    }

    public SettingDocument setDoubleValue(
            String tenantId, String referenceType, String referenceId, String key, double value) {
        return set(tenantId, referenceType, referenceId, key, Double.toString(value), SettingType.DOUBLE, null);
    }

    public SettingDocument setBooleanValue(
            String tenantId, String referenceType, String referenceId, String key, boolean value) {
        return set(tenantId, referenceType, referenceId, key, Boolean.toString(value), SettingType.BOOLEAN, null);
    }

    // ──────────────────── Password (encrypted) ────────────────────

    // ──────────────────── Cascade lookup ────────────────────

    /**
     * Reference-type strings used by the cascade resolvers. Mirrors the
     * scope hierarchy documented in {@code specification/settings-system.md}:
     * inner scopes override outer scopes. Account / team are not yet wired
     * because the codebase has no first-class user / team entity in v1.
     */
    public static final String SCOPE_TENANT = "tenant";
    public static final String SCOPE_PROJECT = "project";
    public static final String SCOPE_THINK_PROCESS = "think-process";

    /**
     * Resolves {@code key} along the cascade tenant → project →
     * think-process and returns the value of the innermost scope that
     * has it set. {@code null} if no scope defines the key. Password
     * settings are skipped (use {@link #getDecryptedPassword}).
     *
     * <p>{@code projectId} and {@code thinkProcessId} may be {@code null}
     * — the corresponding scope is then simply not consulted.
     */
    public @Nullable String getStringValueCascade(
            String tenantId,
            @Nullable String projectId,
            @Nullable String thinkProcessId,
            String key) {
        // Inner-to-outer: first hit wins.
        if (thinkProcessId != null && !thinkProcessId.isBlank()) {
            String v = getStringValue(tenantId, SCOPE_THINK_PROCESS, thinkProcessId, key);
            if (v != null) return v;
        }
        if (projectId != null && !projectId.isBlank()) {
            String v = getStringValue(tenantId, SCOPE_PROJECT, projectId, key);
            if (v != null) return v;
        }
        return getStringValue(tenantId, SCOPE_TENANT, tenantId, key);
    }

    /**
     * Returns all settings whose key starts with {@code keyPrefix}, merged
     * across the cascade tenant → project → think-process. Inner scopes
     * overwrite outer scopes per-key. Password settings are skipped.
     *
     * <p>The returned map is keyed by the full setting key (including the
     * prefix); callers can strip the prefix themselves if needed. Insertion
     * order is the cascade application order, which means callers
     * iterating the entries see "tenant entries first, then project
     * overrides, then think-process overrides" — though semantic meaning
     * is "the value the inner scope put last".
     */
    public Map<String, String> findByPrefixCascade(
            String tenantId,
            @Nullable String projectId,
            @Nullable String thinkProcessId,
            String keyPrefix) {
        Map<String, String> merged = new LinkedHashMap<>();
        applyPrefixScope(merged, tenantId, SCOPE_TENANT, tenantId, keyPrefix);
        if (projectId != null && !projectId.isBlank()) {
            applyPrefixScope(merged, tenantId, SCOPE_PROJECT, projectId, keyPrefix);
        }
        if (thinkProcessId != null && !thinkProcessId.isBlank()) {
            applyPrefixScope(merged, tenantId, SCOPE_THINK_PROCESS, thinkProcessId, keyPrefix);
        }
        return merged;
    }

    private void applyPrefixScope(
            Map<String, String> acc,
            String tenantId,
            String referenceType,
            String referenceId,
            String keyPrefix) {
        for (SettingDocument doc : findAll(tenantId, referenceType, referenceId)) {
            String key = doc.getKey();
            if (key == null || !key.startsWith(keyPrefix)) continue;
            if (doc.getType() == SettingType.PASSWORD) continue;
            acc.put(key, doc.getValue() == null ? "" : doc.getValue());
        }
    }

    /**
     * Stores {@code plaintext} encrypted. Passing {@code null} stores a
     * {@code null} value but keeps the setting as {@link SettingType#PASSWORD}.
     */
    public SettingDocument setEncryptedPassword(
            String tenantId, String referenceType, String referenceId, String key,
            @Nullable String plaintext) {
        String ciphertext = encryption.encrypt(plaintext);
        return set(tenantId, referenceType, referenceId, key, ciphertext, SettingType.PASSWORD, null);
    }

    /**
     * Decrypts and returns the password, or {@code null} if the setting does
     * not exist / is not a password / cannot be decrypted. A decrypt failure
     * is logged at warn level — callers must treat the result as the truth,
     * not assume success.
     */
    public @Nullable String getDecryptedPassword(
            String tenantId, String referenceType, String referenceId, String key) {
        Optional<SettingDocument> opt = find(tenantId, referenceType, referenceId, key);
        if (opt.isEmpty()) {
            return null;
        }
        SettingDocument doc = opt.get();
        if (doc.getType() != SettingType.PASSWORD) {
            log.warn("Setting is not a password: tenant='{}' ref='{}:{}' key='{}' type='{}'",
                    tenantId, referenceType, referenceId, key, doc.getType());
            return null;
        }
        try {
            return encryption.decrypt(doc.getValue());
        } catch (AesEncryptionService.EncryptionException e) {
            log.warn("Failed to decrypt password for tenant='{}' ref='{}:{}' key='{}': {}",
                    tenantId, referenceType, referenceId, key, e.getMessage());
            return null;
        }
    }
}
