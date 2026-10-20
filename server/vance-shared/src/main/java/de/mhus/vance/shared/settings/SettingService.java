package de.mhus.vance.shared.settings;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.crypto.AesEncryptionService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Setting CRUD and typed getters/setters. All reads and writes are scoped by
 * {@code (tenantId, referenceType, referenceId, key)}.
 *
 * <h2>Write API</h2>
 * Two layers, by design:
 *
 * <ul>
 *   <li><b>{@code set}</b> — the public, policy-respecting write path. Brain
 *       and any user-facing caller go through here. Throws
 *       {@link SecretAccessDeniedException} when the key is in the
 *       {@code vancetope.*} namespace or matches one of the tenant/project
 *       deny lists (see {@link VancetopePolicy}).</li>
 *   <li><b>{@code forceSet}</b> — the operator escape hatch used by the
 *       {@code anus} admin shell. Bypasses the deny lists; only the
 *       hardcoded {@code vancetope.*}-prefix check remains, and only for
 *       non-meta keys ({@code vancetope.denied.*} is the one prefix
 *       {@code forceSet} accepts).</li>
 * </ul>
 *
 * <p>Encrypted types ({@link SettingType#encrypted()} — {@code PASSWORD} and
 * {@code HIDDEN}) are encrypted on write. {@code set} does that
 * transparently; {@code forceSet} expects ciphertext (use
 * {@code forceSetEncryptedSecret} for plaintext). No {@code set}
 * entry point rejects encrypted types anymore — the previous
 * {@code IllegalArgumentException} split into two methods was a leak, not a
 * feature.
 *
 * <h2>Read API</h2>
 * {@link #getStringValue} and the cascade resolvers filter out keys in the
 * {@code vancetope.*} namespace — those are operator-only and never appear in
 * user-facing reads. {@link #forceGetStringValue} bypasses the filter for
 * {@code anus} and the internal policy/cache code.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettingService {

    private final SettingRepository repository;
    private final MongoTemplate mongoTemplate;
    private final AesEncryptionService encryption;
    private final AuditService auditService;
    /**
     * Lazy, and it has to be: {@code MegadodoService} resolves its retention
     * through this very service's settings cascade, so a hard reference here
     * closes the loop and no context starts. Lazy on <em>this</em> side by
     * choice — settings are core infrastructure, the activity feed is
     * diagnostics, and the core must not hold the diagnostic service open.
     * Same trick {@code ProjectService} uses for the permission layer.
     */
    private final org.springframework.beans.factory.ObjectProvider<de.mhus.vance.shared.megadodo.MegadodoService>
            megadodoServiceProvider;

    private final AgentSettingKeyPolicy agentKeyPolicy;
    private final VancetopePolicy vancetopePolicy;

    /** Reserved namespace prefix — see {@link VancetopePolicy#SYSTEM_KEY_PREFIX}. */
    public static final String SYSTEM_KEY_PREFIX = VancetopePolicy.SYSTEM_KEY_PREFIX;

    // ──────────────────── Raw lookup ────────────────────

    public Optional<SettingDocument> find(String tenantId, String referenceType, String referenceId, String key) {
        return repository.findByTenantIdAndReferenceTypeAndReferenceIdAndKey(tenantId, referenceType, referenceId, key);
    }

    public List<SettingDocument> findAll(String tenantId, String referenceType, String referenceId) {
        List<SettingDocument> docs =
                repository.findByTenantIdAndReferenceTypeAndReferenceId(tenantId, referenceType, referenceId);
        // System-namespace filter applies to the per-scope listing too —
        // anus 'setting list' would otherwise expose the deny-list settings.
        return docs.stream()
                .filter(d -> !VancetopePolicy.isSystemKey(d.getKey()))
                .toList();
    }

    /** All settings across scopes for a tenant sharing the same {@code key}. */
    public List<SettingDocument> findByKey(String tenantId, String key) {
        if (VancetopePolicy.isSystemKey(key)) {
            // System keys are not part of any user-visible key lookup; the
            // cache loader and forceSet callers go through forceGetStringValue.
            return List.of();
        }
        return repository.findByTenantIdAndKey(tenantId, key);
    }

    /**
     * Every setting in one scope whose key starts with {@code keyPrefix} —
     * a single query, for readers that own a key family and poll it.
     */
    public List<SettingDocument> findByPrefix(
            String tenantId, String referenceType, String referenceId, String keyPrefix) {
        if (keyPrefix == null || keyPrefix.isEmpty()) return List.of();
        // System-namespace prefix: a caller asking for "vancetope." gets an
        // empty list — the filter on the read path is consistent with the
        // per-key filter in getStringValue, so user code cannot accidentally
        // enumerate operator settings.
        if (VancetopePolicy.SYSTEM_KEY_PREFIX.equals(keyPrefix)) return List.of();
        Query query = new Query(Criteria.where("tenantId")
                .is(tenantId)
                .and("referenceType")
                .is(referenceType)
                .and("referenceId")
                .is(referenceId)
                .and("key")
                .regex("^" + Pattern.quote(keyPrefix)));
        List<SettingDocument> docs = mongoTemplate.find(query, SettingDocument.class);
        // Defense in depth: if a caller asks for a prefix that is not itself
        // a system prefix but happens to overlap with one (e.g. "v" matches
        // "vancetope.x"), filter those rows out so they never escape this
        // method.
        return docs.stream()
                .filter(d -> !VancetopePolicy.isSystemKey(d.getKey()))
                .toList();
    }

    public boolean exists(String tenantId, String referenceType, String referenceId, String key) {
        return repository.existsByTenantIdAndReferenceTypeAndReferenceIdAndKey(
                tenantId, referenceType, referenceId, key);
    }

    public void delete(String tenantId, String referenceType, String referenceId, String key) {
        vancetopePolicy.requireWritable(tenantId, referenceType, referenceId, key);
        repository.deleteByTenantIdAndReferenceTypeAndReferenceIdAndKey(tenantId, referenceType, referenceId, key);
    }

    /**
     * Operator escape hatch for {@link #delete}. Bypasses the
     * {@link VancetopePolicy} deny lists; only the hardcoded
     * {@code vancetope.*}-prefix check remains, and even there only
     * {@link VancetopePolicy#isMetaDenyKey meta-deny keys} are accepted.
     */
    public void forceDelete(String tenantId, String referenceType, String referenceId, String key) {
        vancetopePolicy.requireForceWritable(key);
        repository.deleteByTenantIdAndReferenceTypeAndReferenceIdAndKey(tenantId, referenceType, referenceId, key);
    }

    public long deleteAll(String tenantId, String referenceType, String referenceId) {
        return repository.deleteByTenantIdAndReferenceTypeAndReferenceId(tenantId, referenceType, referenceId);
    }

    /**
     * Deletes every setting in the scope whose key starts with
     * {@code keyPrefix}. Returns the number of deleted documents.
     *
     * <p>Useful for cleaning up grouped keys whose exact suffixes aren't
     * known up front — the OAuth disconnect path uses this to wipe
     * {@code oauth.<providerId>.*} including any flat-extra projections
     * the connect flow may have written (cloud_id, site_url, …).
     */
    public long deleteByPrefix(String tenantId, String referenceType, String referenceId, String keyPrefix) {
        if (keyPrefix == null || keyPrefix.isEmpty()) return 0;
        // Prefix-deletion without per-key policy check is unsafe: a delete that
        // covers e.g. "oauth." in a project may remove deny-list-protected keys
        // like "oauth.provider.apiKey" without the operator knowing. The check
        // runs against the first concrete key in scope; if the policy would
        // refuse that key, the whole prefix is refused.
        vancetopePolicy.requireWritable(tenantId, referenceType, referenceId, keyPrefix);
        return forceDeleteByPrefix(tenantId, referenceType, referenceId, keyPrefix);
    }

    /**
     * Operator escape hatch for {@link #deleteByPrefix}. Skips the per-key
     * policy check; the hardcoded {@code vancetope.*}-prefix check still
     * applies via {@link VancetopePolicy#requireForceWritable}.
     */
    public long forceDeleteByPrefix(String tenantId, String referenceType, String referenceId, String keyPrefix) {
        if (keyPrefix == null || keyPrefix.isEmpty()) return 0;
        vancetopePolicy.requireForceWritable(keyPrefix);
        // Single atomic prefix-remove instead of scan-then-N-deletes: no
        // window for a concurrent write to slip a matching key past the delete,
        // and one round-trip instead of O(n). Pattern.quote keeps prefix dots
        // (e.g. "oauth.slack.") literal rather than regex wildcards.
        Query query = new Query(Criteria.where("tenantId")
                .is(tenantId)
                .and("referenceType")
                .is(referenceType)
                .and("referenceId")
                .is(referenceId)
                .and("key")
                .regex("^" + Pattern.quote(keyPrefix)));
        return mongoTemplate.remove(query, SettingDocument.class).getDeletedCount();
    }

    // ──────────────────── Set / upsert ────────────────────

    /**
     * Operator escape hatch for {@link #set}. Bypasses the
     * {@link VancetopePolicy} deny lists and writes the value as-is — for
     * encrypted types the caller is expected to have passed ciphertext.
     * Use {@link #forceSetEncryptedSecret} for plaintext secrets.
     *
     * <p>The only protection that remains is the hardcoded
     * {@code vancetope.*} prefix — and even there only
     * {@link VancetopePolicy#isMetaDenyKey meta-deny keys}
     * ({@code vancetope.denied.*}) are accepted, because otherwise the
     * configurable deny lists themselves could never be set up.
     *
     * <p>Intended for the {@code anus} admin shell only. Brain code must call
     * {@link #set} / {@link #setAs} so the policy runs.
     *
     * @throws SecretAccessDeniedException for vancetope.* keys outside the
     *         .denied. sub-prefix
     */
    public SettingDocument forceSet(
            String tenantId,
            String referenceType,
            String referenceId,
            String key,
            @Nullable String value,
            SettingType type,
            @Nullable String description) {
        return forceSetAs(tenantId, referenceType, referenceId, key, value, type, description, /*actor*/ null);
    }

    /**
     * {@link #forceSet} on behalf of a named actor — same actor semantics as
     * {@link #setAs}.
     */
    public SettingDocument forceSetAs(
            String tenantId,
            String referenceType,
            String referenceId,
            String key,
            @Nullable String value,
            SettingType type,
            @Nullable String description,
            @Nullable String actor) {
        vancetopePolicy.requireForceWritable(key);
        SettingDocument saved = setInternal(tenantId, referenceType, referenceId, key, value, type, description, actor);
        // Cache invalidation must run after the write succeeded — a failed
        // write has no observable effect on the next read.
        vancetopePolicy.onSettingChanged(tenantId, key);
        return saved;
    }

    /**
     * Policy-respecting write path. {@link VancetopePolicy#requireWritable}
     * blocks {@code vancetope.*} keys and anything matching the tenant or
     * project deny list before the write reaches {@link #setInternal}.
     * Encrypted types ({@link SettingType#encrypted()}) are encrypted on the
     * way in — callers pass plaintext, the service does the rest.
     *
     * <p>Records the change without an actor — see
     * {@link #setAs(String, String, String, String, String, SettingType, String, String)}
     * for what that means in the activity feed. Server-driven writes (OAuth
     * token refresh, bootstrap, kit apply) are the intended users of this
     * no-actor form.
     */
    public SettingDocument set(
            String tenantId,
            String referenceType,
            String referenceId,
            String key,
            @Nullable String value,
            SettingType type,
            @Nullable String description) {
        return setAs(tenantId, referenceType, referenceId, key, value, type, description, /*actor*/ null);
    }

    /**
     * {@link #set} on behalf of a named actor. Encrypted types are encrypted
     * here, plaintext for all others — the caller picks the type and the
     * service does the right thing. The previous split into {@code set} (plain
     * only) and {@code setEncryptedSecret} (encrypted only) was a leak: the
     * encrypted-only path bypassed the write policy. One path, one policy.
     *
     * <p>"Why does it behave differently since yesterday?" is the question the
     * {@code setting.change} feed row exists to answer, and the answer is
     * incomplete without a name. So every path that <em>knows</em> who is
     * acting has to say so — admin REST, setting forms, the profile editor,
     * the shell, a kit install.
     *
     * <p>A {@code null} actor is not "unknown", it is a statement: <b>no person
     * did this, the server did</b> — a token refresh, a bootstrap, a service
     * writing its own salt. That is worth seeing in the feed, which is why the
     * two forms exist side by side instead of one form with a mandatory
     * argument that half the callers would have to invent a value for.
     */
    public SettingDocument setAs(
            String tenantId,
            String referenceType,
            String referenceId,
            String key,
            @Nullable String value,
            SettingType type,
            @Nullable String description,
            @Nullable String actor) {
        vancetopePolicy.requireWritable(tenantId, referenceType, referenceId, key);
        String effectiveValue = value;
        if (type.encrypted()) {
            // Encrypt on the way in — callers pass plaintext, the service
            // stores ciphertext. Same path for PASSWORD and HIDDEN; the type
            // difference is purely a read-side and reference-resolution concern.
            effectiveValue = encryption.encrypt(value);
        }
        return setInternal(tenantId, referenceType, referenceId, key, effectiveValue, type, description, actor);
    }

    private SettingDocument setInternal(
            String tenantId,
            String referenceType,
            String referenceId,
            String key,
            @Nullable String value,
            SettingType type,
            @Nullable String description,
            @Nullable String actor) {
        SettingDocument doc = repository
                .findByTenantIdAndReferenceTypeAndReferenceIdAndKey(tenantId, referenceType, referenceId, key)
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
        SettingDocument saved = repository.save(doc);
        auditService.settingsUpdate(tenantId, referenceType, referenceId, key, type);
        megadodoServiceProvider
                .getObject()
                .settingChanged(
                        tenantId,
                        // Only a project-scoped setting belongs to a project feed;
                        // tenant and user settings are tenant-wide by nature.
                        SCOPE_PROJECT.equals(referenceType) ? referenceId : null,
                        referenceType,
                        referenceId,
                        key,
                        type.encrypted(),
                        actor);
        return saved;
    }

    // ──────────────────── Typed getters ────────────────────

    /**
     * Returns the raw string value, or {@code null} if the setting does not
     * exist. Refuses to read encrypted types ({@link SettingType#encrypted()})
     * — use {@link #getDecryptedPassword} for those. This is also what keeps
     * the script surface ({@code vance.settings.get}) blind to secrets of
     * either encrypted type.
     */
    public @Nullable String getStringValue(String tenantId, String referenceType, String referenceId, String key) {
        if (VancetopePolicy.isSystemKey(key)) {
            // System-namespace keys never appear in user-facing reads. This is
            // not a "denied" path — there is nothing to deny, the value is
            // simply absent as far as a non-operator caller is concerned. The
            // null return matches the "setting does not exist" case so the
            // cascade naturally walks past these.
            return null;
        }
        Optional<SettingDocument> opt = find(tenantId, referenceType, referenceId, key);
        if (opt.isEmpty()) {
            return null;
        }
        SettingDocument doc = opt.get();
        if (doc.getType().encrypted()) {
            log.warn(
                    "Refusing to read {} setting via getStringValue: tenant='{}' ref='{}:{}' key='{}'",
                    doc.getType(),
                    tenantId,
                    referenceType,
                    referenceId,
                    key);
            return null;
        }
        return doc.getValue();
    }

    /**
     * Operator-side counterpart to {@link #getStringValue}. Bypasses the
     * {@code vancetope.*}-system-namespace filter — the {@code anus} admin
     * shell and the {@link VancetopePolicy} cache loader use this to read
     * the deny-list settings themselves.
     */
    public @Nullable String forceGetStringValue(String tenantId, String referenceType, String referenceId, String key) {
        Optional<SettingDocument> opt = find(tenantId, referenceType, referenceId, key);
        if (opt.isEmpty()) {
            return null;
        }
        SettingDocument doc = opt.get();
        if (doc.getType().encrypted()) {
            log.warn(
                    "Refusing to read {} setting via forceGetStringValue: tenant='{}' ref='{}:{}' key='{}'",
                    doc.getType(),
                    tenantId,
                    referenceType,
                    referenceId,
                    key);
            return null;
        }
        return doc.getValue();
    }

    public String getStringValue(
            String tenantId, String referenceType, String referenceId, String key, String defaultValue) {
        String v = getStringValue(tenantId, referenceType, referenceId, key);
        return v != null ? v : defaultValue;
    }

    public int getIntValue(String tenantId, String referenceType, String referenceId, String key, int defaultValue) {
        String v = getStringValue(tenantId, referenceType, referenceId, key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse int value for ref='{}:{}' key='{}': {}", referenceType, referenceId, key, v);
            return defaultValue;
        }
    }

    public long getLongValue(String tenantId, String referenceType, String referenceId, String key, long defaultValue) {
        String v = getStringValue(tenantId, referenceType, referenceId, key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse long value for ref='{}:{}' key='{}': {}", referenceType, referenceId, key, v);
            return defaultValue;
        }
    }

    public double getDoubleValue(
            String tenantId, String referenceType, String referenceId, String key, double defaultValue) {
        String v = getStringValue(tenantId, referenceType, referenceId, key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse double value for ref='{}:{}' key='{}': {}", referenceType, referenceId, key, v);
            return defaultValue;
        }
    }

    public boolean getBooleanValue(
            String tenantId, String referenceType, String referenceId, String key, boolean defaultValue) {
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
     *       {@code _tenant} system project is the tenant-wide default
     *       layer; per-user {@code _user_<login>} projects carry user
     *       settings; user-created projects carry project settings.</li>
     *   <li>{@link #SCOPE_THINK_PROCESS} — settings owned by a single
     *       running think-process (innermost cascade layer).</li>
     * </ul>
     *
     * <p>{@link #SCOPE_TENANT} and {@link #SCOPE_USER} remain as
     * <b>wire-format aliases</b> for the admin REST API; the admin
     * controller maps them to {@code SCOPE_PROJECT} with
     * {@code _tenant} / {@code _user_<login>} as the reference id.
     */
    public static final String SCOPE_TENANT = "tenant";

    public static final String SCOPE_USER = "user";
    public static final String SCOPE_PROJECT = "project";
    public static final String SCOPE_THINK_PROCESS = "think-process";

    /**
     * Resolves {@code key} along the project cascade
     * {@code think-process → <projectId>-project → _tenant}
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
            String tenantId, @Nullable String projectId, @Nullable String thinkProcessId, String key) {
        if (thinkProcessId != null && !thinkProcessId.isBlank()) {
            String v = getStringValue(tenantId, SCOPE_THINK_PROCESS, thinkProcessId, key);
            if (v != null) return v;
        }
        if (projectId != null && !projectId.isBlank() && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            String v = getStringValue(tenantId, SCOPE_PROJECT, projectId, key);
            if (v != null) return v;
        }
        return getStringValue(tenantId, SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, key);
    }

    /**
     * Boolean cascade variant of {@link #getStringValueCascade}. Walks
     * {@code think-process → project → _tenant} and returns the first
     * scope that defines {@code key}, parsed with the same
     * {@code true/1/yes/on} semantics as {@link #getBooleanValue}.
     * Falls back to {@code defaultValue} when no scope holds the key.
     */
    public boolean getBooleanValueCascade(
            String tenantId,
            @Nullable String projectId,
            @Nullable String thinkProcessId,
            String key,
            boolean defaultValue) {
        String v = getStringValueCascade(tenantId, projectId, thinkProcessId, key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        String normalized = v.trim().toLowerCase();
        return "true".equals(normalized)
                || "1".equals(normalized)
                || "yes".equals(normalized)
                || "on".equals(normalized);
    }

    /**
     * Cascade variant of {@link #getDecryptedPassword} — walks
     * {@code think-process → project → _tenant} and returns the
     * decrypted plaintext of the innermost layer that holds the key.
     * Returns {@code null} when nothing is set or decryption fails.
     */
    public @Nullable String getDecryptedPasswordCascade(
            String tenantId, @Nullable String projectId, @Nullable String thinkProcessId, String key) {
        if (thinkProcessId != null && !thinkProcessId.isBlank()) {
            String v = getDecryptedPassword(tenantId, SCOPE_THINK_PROCESS, thinkProcessId, key);
            if (v != null) return v;
        }
        if (projectId != null && !projectId.isBlank() && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            String v = getDecryptedPassword(tenantId, SCOPE_PROJECT, projectId, key);
            if (v != null) return v;
        }
        return getDecryptedPassword(tenantId, SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, key);
    }

    /**
     * Returns all settings whose key starts with {@code keyPrefix},
     * merged across the project cascade
     * {@code _tenant → project → think-process}. Inner scopes overwrite
     * outer scopes per-key. Password settings are skipped.
     *
     * <p>The user-layer is <b>not</b> included — same rationale as
     * {@link #getStringValueCascade}.
     */
    public Map<String, String> findByPrefixCascade(
            String tenantId, @Nullable String projectId, @Nullable String thinkProcessId, String keyPrefix) {
        Map<String, String> merged = new LinkedHashMap<>();
        applyPrefixScope(merged, tenantId, SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, keyPrefix);
        if (projectId != null && !projectId.isBlank() && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            applyPrefixScope(merged, tenantId, SCOPE_PROJECT, projectId, keyPrefix);
        }
        if (thinkProcessId != null && !thinkProcessId.isBlank()) {
            applyPrefixScope(merged, tenantId, SCOPE_THINK_PROCESS, thinkProcessId, keyPrefix);
        }
        return merged;
    }

    // ──────────────────── Mixed user-project cascade ────────────────────

    /**
     * Cascade with explicit user-override:
     * {@code think-process → _user_<userId> → <projectId>-project → _tenant}.
     *
     * <p>Use only when user-overrides are <b>desired</b> for this key —
     * e.g. a recipe with {@code useUserCredentials=true} that wants
     * the user's personal API-key to win over the tenant default.
     * The default {@link #getStringValueCascade} keeps the user-layer
     * out for safety (Cost/Quota/Compliance); switching call-sites to
     * this variant is an explicit opt-in.
     */
    public @Nullable String getStringValueUserProjectCascade(
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String thinkProcessId,
            String key) {
        if (thinkProcessId != null && !thinkProcessId.isBlank()) {
            String v = getStringValue(tenantId, SCOPE_THINK_PROCESS, thinkProcessId, key);
            if (v != null) return v;
        }
        if (userId != null && !userId.isBlank()) {
            String v =
                    getStringValue(tenantId, SCOPE_PROJECT, HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + userId, key);
            if (v != null) return v;
        }
        if (projectId != null && !projectId.isBlank() && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            String v = getStringValue(tenantId, SCOPE_PROJECT, projectId, key);
            if (v != null) return v;
        }
        return getStringValue(tenantId, SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, key);
    }

    /**
     * Boolean variant of {@link #getStringValueUserProjectCascade} — same
     * cascade ({@code think-process → _user_<userId> → project → _tenant}),
     * parsed with the {@code true/1/yes/on} semantics of
     * {@link #getBooleanValue}. Falls back to {@code defaultValue} when no
     * scope holds the key.
     */
    public boolean getBooleanValueUserProjectCascade(
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String thinkProcessId,
            String key,
            boolean defaultValue) {
        String v = getStringValueUserProjectCascade(tenantId, userId, projectId, thinkProcessId, key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        String normalized = v.trim().toLowerCase();
        return "true".equals(normalized)
                || "1".equals(normalized)
                || "yes".equals(normalized)
                || "on".equals(normalized);
    }

    /**
     * Password variant of {@link #getStringValueUserProjectCascade} —
     * same cascade, decrypts the first matching layer's ciphertext.
     */
    public @Nullable String getDecryptedPasswordUserProjectCascade(
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String thinkProcessId,
            String key) {
        if (thinkProcessId != null && !thinkProcessId.isBlank()) {
            String v = getDecryptedPassword(tenantId, SCOPE_THINK_PROCESS, thinkProcessId, key);
            if (v != null) return v;
        }
        if (userId != null && !userId.isBlank()) {
            String v = getDecryptedPassword(
                    tenantId, SCOPE_PROJECT, HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + userId, key);
            if (v != null) return v;
        }
        if (projectId != null && !projectId.isBlank() && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            String v = getDecryptedPassword(tenantId, SCOPE_PROJECT, projectId, key);
            if (v != null) return v;
        }
        return getDecryptedPassword(tenantId, SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, key);
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
    public @Nullable String getUserStringValue(String tenantId, String userId, String key) {
        if (userId == null || userId.isBlank()) return null;
        return getStringValue(tenantId, SCOPE_PROJECT, HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + userId, key);
    }

    /**
     * User-private setting with a tenant-wide default fallback. If the
     * user has the key set, return that; otherwise return the
     * {@code _tenant}-project value (or {@code null} if neither layer
     * carries it). The {@code <projectId>}-layer is intentionally
     * skipped — that's the whole point of "user vs. project" being
     * separate cascades.
     */
    public @Nullable String getUserStringValueWithDefault(String tenantId, String userId, String key) {
        String v = getUserStringValue(tenantId, userId, key);
        if (v != null) return v;
        return getStringValue(tenantId, SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, key);
    }

    /**
     * Decrypts a user-private password setting. No fallback to other
     * scopes — passwords are explicit per-user secrets here.
     */
    public @Nullable String getDecryptedUserPassword(String tenantId, String userId, String key) {
        if (userId == null || userId.isBlank()) return null;
        return getDecryptedPassword(
                tenantId, SCOPE_PROJECT, HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + userId, key);
    }

    /**
     * All user-private settings whose key starts with the prefix.
     * Returns {@code Map<key, value>} from the {@code _user_<userId>}
     * project only — passwords skipped.
     */
    public Map<String, String> findUserSettingsByPrefix(String tenantId, String userId, String keyPrefix) {
        Map<String, String> out = new LinkedHashMap<>();
        if (userId == null || userId.isBlank()) return out;
        applyPrefixScope(
                out, tenantId, SCOPE_PROJECT, HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + userId, keyPrefix);
        return out;
    }

    private void applyPrefixScope(
            Map<String, String> acc, String tenantId, String referenceType, String referenceId, String keyPrefix) {
        for (SettingDocument doc : findAll(tenantId, referenceType, referenceId)) {
            String key = doc.getKey();
            if (key == null || !key.startsWith(keyPrefix)) continue;
            if (VancetopePolicy.isSystemKey(key)) continue;
            if (doc.getType().encrypted()) continue;
            acc.put(key, doc.getValue() == null ? "" : doc.getValue());
        }
    }

    /**
     * Stores {@code plaintext} encrypted as {@link SettingType#PASSWORD}.
     * Passing {@code null} stores a {@code null} value but keeps the type.
     *
     * <p>Deprecated alias for {@link #set} with {@link SettingType#PASSWORD}.
     * The unified {@link #set} now accepts encrypted types and encrypts on
     * the way in, so callers should call
     * {@code set(PASSWORD, plaintext)} directly. Kept so existing call sites
     * keep compiling while the migration happens.
     *
     * @deprecated use {@link #set} instead
     */
    @Deprecated
    public SettingDocument setEncryptedPassword(
            String tenantId, String referenceType, String referenceId, String key, @Nullable String plaintext) {
        return set(tenantId, referenceType, referenceId, key, plaintext, SettingType.PASSWORD, null);
    }

    /**
     * Stores a secret whose value passed through an agent's context. Enforces the
     * two agent-write rules that survived from
     * {@code planning/setting-type-hidden.md} §6 — W2 ("a value that passed
     * through the model context becomes HIDDEN") was deliberately withdrawn, see
     * the paragraph below:
     *
     * <ul>
     *   <li><b>W3</b> — a deny-listed key ({@link AgentSettingKeyPolicy}) is
     *       refused outright, whatever its type and whether or not it exists.</li>
     *   <li><b>W1</b> — an existing setting that an agent may not read back is
     *       never overwritten either. Otherwise an agent could clobber a
     *       credential with a value of its own and read it back; confidentiality
     *       would survive (it only reads its own value) but the real credential
     *       would be gone.</li>
     * </ul>
     *
     * <p><b>The type follows the use, not the origin.</b> The caller declares it:
     * a credential a connector will use is {@link SettingType#PASSWORD} even
     * though an agent triggered the write, because agents and scripts must not be
     * able to read it back. Only a secret that a script or a compose task has to
     * resolve itself is {@link SettingType#HIDDEN}. A value having passed through
     * the model context once is <em>not</em> a reason to weaken it forever.
     *
     * @throws SecretAccessDeniedException on a W1 or W3 violation
     * @throws IllegalArgumentException if {@code type} is not an encrypted type
     */
    public SettingDocument setAgentSecret(
            String tenantId,
            String referenceType,
            String referenceId,
            String key,
            @Nullable String plaintext,
            SettingType type) {
        agentKeyPolicy.requireAgentWritable(key);
        Optional<SettingDocument> existing = find(tenantId, referenceType, referenceId, key);
        // The threshold is referenceReadable(), not encrypted(): W1 protects
        // exactly what an agent may not read back, and HIDDEN has to stay
        // overwritable (vault_secret_set / vault_secret_generate write it).
        // Spelled as a predicate rather than `== PASSWORD` because a constant
        // comparison silently excludes any future level above PASSWORD — the
        // whole reason SettingType exposes two monotone thresholds instead of
        // asking call sites to enumerate constants.
        if (existing.isPresent() && !existing.get().getType().referenceReadable()) {
            log.warn(
                    "Refusing agent-originated overwrite of PASSWORD setting: " + "tenant='{}' ref='{}:{}' key='{}'",
                    tenantId,
                    referenceType,
                    referenceId,
                    key);
            throw new SecretAccessDeniedException(
                    SecretAccessDeniedException.Reason.AGENT_OVERWRITE_PASSWORD,
                    key,
                    "Setting '" + key
                            + "' is reserved: an existing PASSWORD setting cannot be overwritten by an agent. "
                            + "Reason: W1 — agents may not read PASSWORD-typed values, so allowing them to overwrite "
                            + "one would let an agent replace a credential it cannot see, and the original is then lost. "
                            + "Resolution: a human has to change this setting through the settings editor, the "
                            + "admin REST endpoint, or the anus admin shell.");
        }
        // Write via forceSet: agent-write path has already passed agentKeyPolicy
        // (W3) and the W1 PASSWORD-overwrite guard. The encrypted value is
        // built here because we cannot route through set() (which would re-run
        // VancetopePolicy — already settled) and we want the same
        // activity-feed row as a regular write. Use forceSetEncryptedSecret so
        // the encrypt step is shared with that path.
        return forceSetEncryptedSecret(tenantId, referenceType, referenceId, key, plaintext, type);
    }

    /**
     * Deprecated alias for {@link #set} with an encrypted {@code type}. The
     * unified {@link #set} now accepts encrypted types and encrypts on the way
     * in, so callers should call {@code set(type, plaintext)} directly. This
     * alias is kept so existing call sites and tests keep compiling while the
     * migration happens.
     *
     * @deprecated use {@link #set} instead
     */
    @Deprecated
    public SettingDocument setEncryptedSecret(
            String tenantId,
            String referenceType,
            String referenceId,
            String key,
            @Nullable String plaintext,
            SettingType type) {
        return set(tenantId, referenceType, referenceId, key, plaintext, type, null);
    }

    /**
     * Deprecated alias for {@link #setAs} with an encrypted {@code type}. Use
     * {@link #setAs} instead.
     *
     * @deprecated use {@link #setAs} instead
     */
    @Deprecated
    public SettingDocument setEncryptedSecretAs(
            String tenantId,
            String referenceType,
            String referenceId,
            String key,
            @Nullable String plaintext,
            SettingType type,
            @Nullable String actor) {
        return setAs(tenantId, referenceType, referenceId, key, plaintext, type, null, actor);
    }

    /**
     * Deprecated alias for {@link #setAs} with description and encrypted
     * {@code type}. Use {@link #setAs} instead.
     *
     * @deprecated use {@link #setAs} instead
     */
    @Deprecated
    public SettingDocument setEncryptedSecretAs(
            String tenantId,
            String referenceType,
            String referenceId,
            String key,
            @Nullable String plaintext,
            SettingType type,
            @Nullable String description,
            @Nullable String actor) {
        return setAs(tenantId, referenceType, referenceId, key, plaintext, type, description, actor);
    }

    /**
     * Convenience for {@link #forceSet} with an encrypted {@code type} and
     * plaintext. Encrypts on the way in, then writes via the force path
     * (bypassing {@link VancetopePolicy} deny lists). This is the
     * {@code anus}-side counterpart to {@link #set} for encrypted types —
     * no policy, no encrypted-type-rejection, just the hardcoded
     * {@code vancetope.*} prefix check.
     */
    public SettingDocument forceSetEncryptedSecret(
            String tenantId,
            String referenceType,
            String referenceId,
            String key,
            @Nullable String plaintext,
            SettingType type) {
        if (!type.encrypted()) {
            throw new IllegalArgumentException("forceSetEncryptedSecret() requires an encrypted type, got " + type);
        }
        vancetopePolicy.requireForceWritable(key);
        String ciphertext = encryption.encrypt(plaintext);
        SettingDocument saved = setInternal(
                tenantId, referenceType, referenceId, key, ciphertext, type, /*description*/ null, /*actor*/ null);
        vancetopePolicy.onSettingChanged(tenantId, key);
        return saved;
    }

    /**
     * Convenience alias for {@link #forceSetEncryptedSecret} with
     * {@link SettingType#PASSWORD}. Mirrors the historical
     * {@link #setEncryptedPassword} (now an alias on the policy-respecting
     * side).
     */
    public SettingDocument forceSetEncryptedPassword(
            String tenantId, String referenceType, String referenceId, String key, @Nullable String plaintext) {
        return forceSetEncryptedSecret(tenantId, referenceType, referenceId, key, plaintext, SettingType.PASSWORD);
    }

    /**
     * Decrypts and returns the secret, or {@code null} if the setting does not
     * exist / is not an encrypted type / cannot be decrypted. A decrypt failure
     * is logged at warn level — callers must treat the result as the truth,
     * not assume success.
     *
     * <p>Reads both encrypted types on purpose: this is the path compiled
     * server code uses with a fixed key, and it must keep working regardless of
     * whether the operator classified the value as PASSWORD or HIDDEN. The
     * PASSWORD-vs-HIDDEN gate lives on the reference-resolution path instead —
     * see {@code planning/setting-type-hidden.md} §5.
     */
    public @Nullable String getDecryptedPassword(
            String tenantId, String referenceType, String referenceId, String key) {
        Optional<SettingDocument> opt = find(tenantId, referenceType, referenceId, key);
        if (opt.isEmpty()) {
            return null;
        }
        SettingDocument doc = opt.get();
        if (!doc.getType().encrypted()) {
            log.warn(
                    "Setting is not an encrypted secret: tenant='{}' ref='{}:{}' key='{}' type='{}'",
                    tenantId,
                    referenceType,
                    referenceId,
                    key,
                    doc.getType());
            return null;
        }
        return decryptSecret(doc, tenantId, referenceType, referenceId, key);
    }

    private @Nullable String decryptSecret(
            SettingDocument doc, String tenantId, String referenceType, String referenceId, String key) {
        try {
            String plaintext = encryption.decrypt(doc.getValue());
            auditService.settingsPasswordRead(tenantId, referenceType, referenceId, key);
            return plaintext;
        } catch (AesEncryptionService.EncryptionException e) {
            log.warn(
                    "Failed to decrypt secret for tenant='{}' ref='{}:{}' key='{}': {}",
                    tenantId,
                    referenceType,
                    referenceId,
                    key,
                    e.getMessage());
            return null;
        }
    }

    // ──────────────────── Reference-resolution reads ────────────────────
    //
    // The read path behind an *authored* {{secret:…}} reference — a tool
    // document, a compose manifest, a script. All three are agent-writable, so
    // unlike getDecryptedPassword (compiled callers, fixed keys) these refuse
    // PASSWORD-typed values and only hand out HIDDEN ones.
    // See planning/setting-type-hidden.md §5.

    /**
     * Reads an encrypted setting on behalf of a secret reference at one exact
     * scope.
     *
     * @return the plaintext for a reference-readable ({@code HIDDEN}) setting;
     *         {@code null} when the setting does not exist, is not an encrypted
     *         type, or cannot be decrypted
     * @throws SecretAccessDeniedException when the setting exists as
     *         {@link SettingType#PASSWORD}
     */
    public @Nullable String getReferenceSecret(String tenantId, String referenceType, String referenceId, String key) {
        return readReferenceSecretAt(tenantId, referenceType, referenceId, key);
    }

    /**
     * Cascade variant of {@link #getReferenceSecret}: walks
     * {@code think-process → project → _tenant} and returns the first layer that
     * carries {@code key} as a reference-readable secret.
     *
     * <p><b>A PASSWORD layer stops the walk</b> rather than being skipped. The
     * cascade means "innermost wins", so if the innermost layer binds the key as
     * PASSWORD then that is the configured value and it is denied — falling
     * through to an outer HIDDEN would silently resolve a <em>different</em>
     * value than the one configured. A non-encrypted layer is treated as absent
     * and the walk continues, unchanged from {@link #getDecryptedPasswordCascade}.
     */
    public @Nullable String getReferenceSecretCascade(
            String tenantId, @Nullable String projectId, @Nullable String thinkProcessId, String key) {
        if (thinkProcessId != null && !thinkProcessId.isBlank()) {
            String v = readReferenceSecretAt(tenantId, SCOPE_THINK_PROCESS, thinkProcessId, key);
            if (v != null) return v;
        }
        if (projectId != null && !projectId.isBlank() && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            String v = readReferenceSecretAt(tenantId, SCOPE_PROJECT, projectId, key);
            if (v != null) return v;
        }
        return readReferenceSecretAt(tenantId, SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, key);
    }

    /**
     * User-scope variant of {@link #getReferenceSecret} — reads from the
     * {@code _user_<userId>} hub project. No fallback to other scopes, matching
     * {@link #getDecryptedUserPassword}.
     */
    public @Nullable String getReferenceUserSecret(String tenantId, @Nullable String userId, String key) {
        if (userId == null || userId.isBlank()) return null;
        return readReferenceSecretAt(
                tenantId, SCOPE_PROJECT, HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + userId, key);
    }

    private @Nullable String readReferenceSecretAt(
            String tenantId, String referenceType, String referenceId, String key) {
        Optional<SettingDocument> opt = find(tenantId, referenceType, referenceId, key);
        if (opt.isEmpty()) {
            return null;
        }
        SettingDocument doc = opt.get();
        if (!doc.getType().encrypted()) {
            // Not a secret here — indistinguishable from absent for this path,
            // so a cascade keeps walking outward.
            return null;
        }
        if (!doc.getType().referenceReadable()) {
            log.warn(
                    "Refusing to resolve {} setting through a secret reference: " + "tenant='{}' ref='{}:{}' key='{}'",
                    doc.getType(),
                    tenantId,
                    referenceType,
                    referenceId,
                    key);
            throw new SecretAccessDeniedException(key, doc.getType());
        }
        return decryptSecret(doc, tenantId, referenceType, referenceId, key);
    }

    // ──────────────────── Transcrypt for kit export/import ────────────────────

    /**
     * Reads an encrypted setting, decrypts with the server key, re-encrypts with
     * a user-supplied {@code vaultPassword}, and returns the portable blob.
     * The plaintext never leaves this method.
     *
     * <p>Used by the kit subsystem to export encrypted settings into a portable
     * git repository — the resulting ciphertext is decryptable only with the
     * same vault passphrase, not with any server key.
     *
     * <p>Returns {@code null} when the setting does not exist, is not an
     * encrypted type, or cannot be decrypted with the server key.
     */
    public @Nullable String decryptForExport(
            String tenantId, String referenceType, String referenceId, String key, String vaultPassword) {
        String plaintext = getDecryptedPassword(tenantId, referenceType, referenceId, key);
        if (plaintext == null) {
            return null;
        }
        try {
            return AesEncryptionService.encryptWith(plaintext, vaultPassword);
        } catch (AesEncryptionService.EncryptionException e) {
            log.warn(
                    "Failed to re-encrypt password with vault key for ref='{}:{}' key='{}': {}",
                    referenceType,
                    referenceId,
                    key,
                    e.getMessage());
            return null;
        }
    }

    /**
     * Decrypts a portable vault-blob with {@code vaultPassword}, then persists
     * it under {@code type} (re-encrypted with the server key). Counterpart to
     * {@link #decryptForExport}.
     *
     * <p>{@code type} comes from the kit's own declaration and is preserved on
     * purpose: a kit that ships a HIDDEN setting must not have it silently
     * promoted to PASSWORD (it would stop resolving through the tool documents
     * the same kit installs), and a kit that ships a PASSWORD setting must not
     * have it demoted to HIDDEN (the value never passed through anyone's
     * context — demoting it would make an unseen secret agent-readable). See
     * {@code planning/setting-type-hidden.md} §6.2.
     *
     * <p>A decrypt failure is logged at warn level and reported as
     * {@link SecretImportOutcome#FAILED} rather than thrown — kit imports
     * continue with the next setting.
     */
    public SecretImportOutcome encryptFromImport(
            String tenantId,
            String referenceType,
            String referenceId,
            String key,
            String vaultPassword,
            @Nullable String vaultCiphertext,
            SettingType type) {
        if (vaultCiphertext == null) {
            setEncryptedSecret(tenantId, referenceType, referenceId, key, null, type);
            return SecretImportOutcome.WRITTEN;
        }
        String plaintext;
        try {
            plaintext = AesEncryptionService.decryptWith(vaultCiphertext, vaultPassword);
        } catch (AesEncryptionService.EncryptionException e) {
            log.warn(
                    "Failed to decrypt vault blob for ref='{}:{}' key='{}': {}",
                    referenceType,
                    referenceId,
                    key,
                    e.getMessage());
            return SecretImportOutcome.FAILED;
        }
        // Asked here rather than by the caller, so the decrypted value never
        // leaves this class on the vault path.
        if (encryptedSecretEquals(tenantId, referenceType, referenceId, key, plaintext)) {
            return SecretImportOutcome.UNCHANGED;
        }
        setEncryptedSecret(tenantId, referenceType, referenceId, key, plaintext, type);
        return SecretImportOutcome.WRITTEN;
    }

    /**
     * Persists a blob that is <b>already</b> encrypted with this server's key,
     * verbatim — no decrypt, no re-encrypt.
     *
     * <p>For content that never left the deployment: a kit built out of one
     * project of this installation for another. The value in the source row and
     * the value in the target row are read with the same key, so producing the
     * plaintext in between would buy nothing and cost the one exposure that
     * matters here — the kit tree is assembled in a temporary directory on
     * disk, and a credential that is never decrypted cannot be written into
     * it.
     *
     * <p>Verified before it is stored, and that check is the reason this is a
     * method rather than a call to {@code setAs}: a blob that does not open
     * with the server key would otherwise be persisted as an encrypted setting
     * that nothing can ever read, and the first symptom would be an opaque
     * failure from whatever consumes it. {@link SecretImportOutcome#FAILED}
     * says so at install time instead.
     *
     * <p>The no-op comparison happens inside this class for the same reason as
     * on the vault path: an unattended update that rewrites an unchanged secret
     * fills the audit trail with "credential changed" for one that did not, and
     * asking the question from outside would mean a method that hands
     * credentials to callers.
     *
     * @param ciphertext a blob produced by this deployment's server key
     * @param type the kit's own declaration, preserved — never promoted to
     *     PASSWORD nor demoted to HIDDEN, see {@link #encryptFromImport}
     */
    public SecretImportOutcome storeServerEncrypted(
            String tenantId,
            String referenceType,
            String referenceId,
            String key,
            @Nullable String ciphertext,
            SettingType type) {
        if (!type.encrypted()) {
            throw new IllegalArgumentException("storeServerEncrypted() requires an encrypted type, got " + type);
        }
        if (ciphertext == null) {
            setEncryptedSecret(tenantId, referenceType, referenceId, key, null, type);
            return SecretImportOutcome.WRITTEN;
        }
        String plaintext;
        try {
            plaintext = encryption.decrypt(ciphertext);
        } catch (AesEncryptionService.EncryptionException e) {
            log.warn(
                    "Server-encrypted blob for ref='{}:{}' key='{}' does not open with this" + " server's key: {}",
                    referenceType,
                    referenceId,
                    key,
                    e.getMessage());
            return SecretImportOutcome.FAILED;
        }
        if (encryptedSecretEquals(tenantId, referenceType, referenceId, key, plaintext)) {
            return SecretImportOutcome.UNCHANGED;
        }
        // The blob itself, not a re-encryption of what it held: the two are
        // equivalent, and this way the plaintext above is only ever compared.
        setInternal(tenantId, referenceType, referenceId, key, ciphertext, type, null, null);
        return SecretImportOutcome.WRITTEN;
    }

    /** What {@link #encryptFromImport} did. */
    public enum SecretImportOutcome {
        /** The value was stored. */
        WRITTEN,
        /** The setting already held exactly this value; nothing was written. */
        UNCHANGED,
        /** The vault blob could not be opened with the supplied password. */
        FAILED
    }

    /**
     * Whether the encrypted setting at this exact scope already holds
     * {@code plaintext}.
     *
     * <p>A predicate rather than a getter, so a caller can avoid a pointless
     * write without ever holding the credential. That matters more than it
     * looks: an unattended update that rewrites an unchanged secret every few
     * hours fills the audit log with "credential changed" for a credential
     * that did not, and an audit trail that cries wolf is worse than none.
     *
     * <p>Reads both encrypted types, like {@link #getDecryptedPassword} and
     * for the same reason — this is compiled server code with the fixed key,
     * not a reference-resolution path.
     *
     * <p>Decrypts <b>without</b> writing a read to the audit trail, which is
     * the point of the whole method. Routed through
     * {@link #getDecryptedPassword} it swapped one row for another: the
     * unattended round stopped logging "credential changed" and started
     * logging "password read", at the same cadence, and the noise this exists
     * to end was merely renamed. Nothing to record either: the plaintext never
     * leaves this frame, only a boolean does.
     *
     * @return {@code false} when the setting is absent, is not an encrypted
     *         type, or cannot be decrypted; "cannot tell" is reported as
     *         "not equal", which costs a redundant write rather than a
     *         skipped rotation
     */
    public boolean encryptedSecretEquals(
            String tenantId, String referenceType, String referenceId, String key, @Nullable String plaintext) {
        if (plaintext == null) return false;
        Optional<SettingDocument> opt = find(tenantId, referenceType, referenceId, key);
        if (opt.isEmpty()) return false;
        SettingDocument doc = opt.get();
        if (!doc.getType().encrypted()) return false;
        String current;
        try {
            current = encryption.decrypt(doc.getValue());
        } catch (AesEncryptionService.EncryptionException e) {
            log.warn(
                    "Failed to decrypt secret for comparison: tenant='{}' ref='{}:{}' key='{}': {}",
                    tenantId,
                    referenceType,
                    referenceId,
                    key,
                    e.getMessage());
            return false;
        }
        return MessageDigest.isEqual(
                current.getBytes(StandardCharsets.UTF_8), plaintext.getBytes(StandardCharsets.UTF_8));
    }
}
