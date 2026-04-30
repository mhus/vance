package de.mhus.vance.shared.settings;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.crypto.AesEncryptionService;
import de.mhus.vance.shared.home.HomeBootstrapService;
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
     * Reference-type strings used by the cascade resolvers. With the
     * settings-as-project-attributes model there are only two
     * persisted reference types:
     *
     * <ul>
     *   <li>{@link #SCOPE_PROJECT} — settings owned by a project. The
     *       {@code _vance} system project is the tenant-wide default
     *       layer; per-user {@code _user_<login>} projects carry user
     *       settings; user-created projects carry project settings.</li>
     *   <li>{@link #SCOPE_THINK_PROCESS} — settings owned by a single
     *       running think-process (innermost cascade layer).</li>
     * </ul>
     *
     * <p>{@link #SCOPE_TENANT} and {@link #SCOPE_USER} remain as
     * <b>wire-format aliases</b> for the admin REST API; the admin
     * controller maps them to {@code SCOPE_PROJECT} with
     * {@code _vance} / {@code _user_<login>} as the reference id.
     */
    public static final String SCOPE_TENANT = "tenant";
    public static final String SCOPE_USER = "user";
    public static final String SCOPE_PROJECT = "project";
    public static final String SCOPE_THINK_PROCESS = "think-process";

    /**
     * Resolves {@code key} along the project cascade
     * {@code think-process → <projectId>-project → _vance-project}
     * and returns the value of the innermost scope that has it set.
     * {@code null} if no scope defines the key. Password settings are
     * skipped (use {@link #getDecryptedPasswordCascade}).
     *
     * <p>The user-layer is <b>not</b> consulted by this cascade — that
     * keeps tenant-/project-controlled keys (LLM provider, API keys,
     * memory hints) safe from per-user overrides. Use
     * {@link #getUserStringValue} when you explicitly want a per-user
     * setting (language, telegram-conversation-id, …).
     *
     * <p>{@code projectId} and {@code thinkProcessId} may be
     * {@code null} — the corresponding scope is then simply not
     * consulted.
     */
    public @Nullable String getStringValueCascade(
            String tenantId,
            @Nullable String projectId,
            @Nullable String thinkProcessId,
            String key) {
        if (thinkProcessId != null && !thinkProcessId.isBlank()) {
            String v = getStringValue(tenantId, SCOPE_THINK_PROCESS, thinkProcessId, key);
            if (v != null) return v;
        }
        if (projectId != null && !projectId.isBlank()
                && !HomeBootstrapService.VANCE_PROJECT_NAME.equals(projectId)) {
            String v = getStringValue(tenantId, SCOPE_PROJECT, projectId, key);
            if (v != null) return v;
        }
        return getStringValue(tenantId, SCOPE_PROJECT,
                HomeBootstrapService.VANCE_PROJECT_NAME, key);
    }

    /**
     * Cascade variant of {@link #getDecryptedPassword} — walks
     * {@code think-process → project → _vance} and returns the
     * decrypted plaintext of the innermost layer that holds the key.
     * Returns {@code null} when nothing is set or decryption fails.
     */
    public @Nullable String getDecryptedPasswordCascade(
            String tenantId,
            @Nullable String projectId,
            @Nullable String thinkProcessId,
            String key) {
        if (thinkProcessId != null && !thinkProcessId.isBlank()) {
            String v = getDecryptedPassword(tenantId, SCOPE_THINK_PROCESS, thinkProcessId, key);
            if (v != null) return v;
        }
        if (projectId != null && !projectId.isBlank()
                && !HomeBootstrapService.VANCE_PROJECT_NAME.equals(projectId)) {
            String v = getDecryptedPassword(tenantId, SCOPE_PROJECT, projectId, key);
            if (v != null) return v;
        }
        return getDecryptedPassword(tenantId, SCOPE_PROJECT,
                HomeBootstrapService.VANCE_PROJECT_NAME, key);
    }

    /**
     * Returns all settings whose key starts with {@code keyPrefix},
     * merged across the project cascade
     * {@code _vance → project → think-process}. Inner scopes overwrite
     * outer scopes per-key. Password settings are skipped.
     *
     * <p>The user-layer is <b>not</b> included — same rationale as
     * {@link #getStringValueCascade}.
     */
    public Map<String, String> findByPrefixCascade(
            String tenantId,
            @Nullable String projectId,
            @Nullable String thinkProcessId,
            String keyPrefix) {
        Map<String, String> merged = new LinkedHashMap<>();
        applyPrefixScope(merged, tenantId, SCOPE_PROJECT,
                HomeBootstrapService.VANCE_PROJECT_NAME, keyPrefix);
        if (projectId != null && !projectId.isBlank()
                && !HomeBootstrapService.VANCE_PROJECT_NAME.equals(projectId)) {
            applyPrefixScope(merged, tenantId, SCOPE_PROJECT, projectId, keyPrefix);
        }
        if (thinkProcessId != null && !thinkProcessId.isBlank()) {
            applyPrefixScope(merged, tenantId, SCOPE_THINK_PROCESS, thinkProcessId, keyPrefix);
        }
        return merged;
    }

    // ──────────────────── User-only settings ────────────────────

    /**
     * Reads a user-private setting from the per-user
     * {@code _user_<userId>} system project. <b>No fallback</b> —
     * returns {@code null} when the user has not set the key.
     *
     * <p>Use this for per-user preferences that should not mix with
     * the project cascade: language, telegram-conversation-id,
     * notification toggles, terminal theme, …
     */
    public @Nullable String getUserStringValue(
            String tenantId, String userId, String key) {
        if (userId == null || userId.isBlank()) return null;
        return getStringValue(tenantId, SCOPE_PROJECT,
                HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + userId, key);
    }

    /**
     * User-private setting with a tenant-wide default fallback. If the
     * user has the key set, return that; otherwise return the
     * {@code _vance}-project value (or {@code null} if neither layer
     * carries it). The {@code <projectId>}-layer is intentionally
     * skipped — that's the whole point of "user vs. project" being
     * separate cascades.
     */
    public @Nullable String getUserStringValueWithDefault(
            String tenantId, String userId, String key) {
        String v = getUserStringValue(tenantId, userId, key);
        if (v != null) return v;
        return getStringValue(tenantId, SCOPE_PROJECT,
                HomeBootstrapService.VANCE_PROJECT_NAME, key);
    }

    /**
     * Decrypts a user-private password setting. No fallback to other
     * scopes — passwords are explicit per-user secrets here.
     */
    public @Nullable String getDecryptedUserPassword(
            String tenantId, String userId, String key) {
        if (userId == null || userId.isBlank()) return null;
        return getDecryptedPassword(tenantId, SCOPE_PROJECT,
                HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + userId, key);
    }

    /**
     * All user-private settings whose key starts with the prefix.
     * Returns {@code Map<key, value>} from the {@code _user_<userId>}
     * project only — passwords skipped.
     */
    public Map<String, String> findUserSettingsByPrefix(
            String tenantId, String userId, String keyPrefix) {
        Map<String, String> out = new LinkedHashMap<>();
        if (userId == null || userId.isBlank()) return out;
        applyPrefixScope(out, tenantId, SCOPE_PROJECT,
                HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + userId, keyPrefix);
        return out;
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
