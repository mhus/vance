package de.mhus.vance.brain.ai.discovery;

import de.mhus.vance.brain.ai.AiModelProvider;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ai.DiscoveredModelInfo;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ProviderListingRequest;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Model-catalog discovery — scans every {@code (tenant, project)}
 * scope for {@code ai.provider.<instance>.apiKey} settings, calls
 * each provider's {@link AiModelProvider#listAvailableModels}, and
 * writes one YAML doc per discovered model under
 * {@code _vance/model-auto/<instance>/<slug>.yaml} in the <i>same</i>
 * project where the credentials live.
 *
 * <p>Symmetry rule (intentional): credentials in project {@code P}
 * produce auto-docs in project {@code P}. No cross-scope inheritance —
 * the catalog cascade in {@link ModelCatalog} handles the merge at
 * lookup time. This keeps it impossible for a tenant-default credential
 * to leak auto-docs into an unrelated project's view.
 *
 * <p>The auto layer is at a separate path ({@code model-auto}) from the
 * manual layer ({@code model}), so discovery is free to overwrite any
 * file it owns — manual edits live elsewhere and survive untouched.
 *
 * <p>Pricing is intentionally NOT in the auto docs. Provider listing
 * APIs don't return prices; the manual layer (bundled + operator
 * edits) carries them and inherits through the cascade.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelDiscoveryService {

    /** Path prefix every auto-discovered model doc sits under. */
    public static final String AUTO_PATH_PREFIX = "_vance/model-auto/";

    /** Setting-key prefix that identifies provider-instance config. */
    private static final String PROVIDER_KEY_PREFIX = "ai.provider.";

    /** Discovery-marker baked into every auto-doc. */
    private static final String DISCOVERED_BY = "discovery-job";

    /** Filename slug must match this — colons / slashes get encoded. */
    private static final java.util.regex.Pattern SAFE_SLUG_SEGMENT =
            java.util.regex.Pattern.compile("[A-Za-z0-9._-]+");

    /** Author label written into the doc's {@code createdBy}. */
    private static final String DOC_AUTHOR = "model-discovery";

    private final TenantService tenantService;
    private final ProjectService projectService;
    private final SettingService settingService;
    private final AiModelService aiModelService;
    private final DocumentService documentService;
    private final ModelCatalog modelCatalog;

    /**
     * Run discovery for one tenant. The REST endpoint hits this
     * variant; cross-tenant discovery is intentionally not exposed
     * (a tenant only refreshes its own scope-tree).
     */
    public DiscoveryResult discoverForTenant(String tenantId) {
        Instant start = Instant.now();
        DiscoveryResult.Builder result = DiscoveryResult.builder(tenantId);
        TenantDocument tenant = tenantService.findByName(tenantId).orElse(null);
        if (tenant == null) {
            log.warn("ModelDiscoveryService: unknown tenant '{}' — skipping", tenantId);
            return result.build(start);
        }
        // Enumerate every project (incl. _tenant) — the symmetry rule
        // says creds and auto-docs live in the same scope, so we have
        // to visit every project, not just _tenant.
        List<ProjectDocument> projects = projectService.all(tenantId);
        for (ProjectDocument project : projects) {
            String projectId = project.getName();
            if (projectId == null) continue;
            discoverInScope(tenantId, projectId, result);
        }
        // Catalog refresh so the freshly-written auto docs become
        // visible in the next lookup without waiting for the 30-min
        // scheduled refresh.
        modelCatalog.refresh();
        return result.build(start);
    }

    private void discoverInScope(
            String tenantId, String projectId, DiscoveryResult.Builder result) {
        Map<String, InstanceConfig> instances = collectInstances(tenantId, projectId);
        if (instances.isEmpty()) return;
        result.scopeScanned();
        for (Map.Entry<String, InstanceConfig> entry : instances.entrySet()) {
            String instance = entry.getKey();
            InstanceConfig cfg = entry.getValue();
            result.instanceScanned();
            try {
                runOneInstance(tenantId, projectId, instance, cfg, result);
            } catch (RuntimeException e) {
                log.warn("ModelDiscoveryService: scope='{}/{}' instance='{}' failed: {}",
                        tenantId, projectId, instance, e.toString());
                result.instanceFailed(tenantId, projectId, instance, e.toString());
            }
        }
    }

    /**
     * Group all {@code ai.provider.<instance>.*} settings in
     * {@code (tenantId, "project", projectId)} by instance and resolve
     * each into a {@link InstanceConfig}. Excludes instances that have
     * no usable apiKey (provider-side validation decides whether the
     * blank key is still callable — Ollama / LM Studio don't care).
     */
    private Map<String, InstanceConfig> collectInstances(String tenantId, String projectId) {
        Map<String, Map<String, SettingDocument>> byInstance = new TreeMap<>();
        for (SettingDocument doc : settingService.findAll(
                tenantId, SettingService.SCOPE_PROJECT, projectId)) {
            String key = doc.getKey();
            if (key == null || !key.startsWith(PROVIDER_KEY_PREFIX)) continue;
            String rest = key.substring(PROVIDER_KEY_PREFIX.length());
            int dot = rest.indexOf('.');
            if (dot <= 0) continue;
            String instance = rest.substring(0, dot);
            String field = rest.substring(dot + 1);
            byInstance.computeIfAbsent(instance, i -> new LinkedHashMap<>())
                    .put(field, doc);
        }
        Map<String, InstanceConfig> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, SettingDocument>> e : byInstance.entrySet()) {
            String instance = e.getKey();
            Map<String, SettingDocument> fields = e.getValue();
            // Determine the protocol type. Default: instance == wireName.
            String typeWire = instance;
            SettingDocument typeDoc = fields.get("type");
            if (typeDoc != null && typeDoc.getValue() != null && !typeDoc.getValue().isBlank()) {
                typeWire = typeDoc.getValue().trim();
            }
            ProviderType type = ProviderType.fromWireName(typeWire).orElse(null);
            if (type == null) {
                log.debug("ModelDiscoveryService: scope='{}/{}' instance='{}' has unknown "
                                + "protocol type '{}' — skipping",
                        tenantId, projectId, instance, typeWire);
                continue;
            }
            // ApiKey: PASSWORD-typed; decrypt at this exact scope. For
            // providers that don't require auth (Ollama, LM Studio) a
            // blank apiKey is acceptable — they get an empty string.
            SettingDocument apiKeyDoc = fields.get("apiKey");
            String apiKey = "";
            if (apiKeyDoc != null && apiKeyDoc.getType() == SettingType.PASSWORD) {
                String decrypted = settingService.getDecryptedPassword(
                        tenantId, SettingService.SCOPE_PROJECT, projectId,
                        PROVIDER_KEY_PREFIX + instance + ".apiKey");
                if (decrypted != null) apiKey = decrypted;
            } else if (apiKeyDoc != null && apiKeyDoc.getValue() != null) {
                apiKey = apiKeyDoc.getValue();
            }
            if (apiKey.isBlank() && type.requiresApiKey()) {
                log.debug("ModelDiscoveryService: scope='{}/{}' instance='{}' has no apiKey "
                                + "but provider '{}' requires one — skipping",
                        tenantId, projectId, instance, type.wireName());
                continue;
            }
            // Optional base URL.
            String baseUrl = null;
            SettingDocument urlDoc = fields.get("baseUrl");
            if (urlDoc != null && urlDoc.getValue() != null && !urlDoc.getValue().isBlank()) {
                baseUrl = urlDoc.getValue().trim();
            }
            out.put(instance, new InstanceConfig(type, apiKey, baseUrl));
        }
        return out;
    }

    private void runOneInstance(
            String tenantId, String projectId, String instance,
            InstanceConfig cfg, DiscoveryResult.Builder result) {
        AiModelProvider provider = aiModelService.findProvider(cfg.type()).orElse(null);
        if (provider == null) {
            log.debug("ModelDiscoveryService: no provider bean for type '{}' (instance '{}')",
                    cfg.type(), instance);
            result.instanceFailed(tenantId, projectId, instance,
                    "No provider bean registered for type " + cfg.type());
            return;
        }
        ProviderListingRequest req = new ProviderListingRequest(instance, cfg.apiKey(), cfg.baseUrl());
        List<DiscoveredModelInfo> models;
        try {
            models = provider.listAvailableModels(req);
        } catch (UnsupportedOperationException e) {
            log.debug("ModelDiscoveryService: provider '{}' does not implement listing — "
                            + "skipping instance '{}'",
                    cfg.type(), instance);
            result.instanceFailed(tenantId, projectId, instance,
                    "Listing not supported by provider " + cfg.type());
            return;
        }
        for (DiscoveredModelInfo model : models) {
            try {
                writeAutoDoc(tenantId, projectId, instance, model);
                result.modelWritten();
            } catch (RuntimeException e) {
                log.warn("ModelDiscoveryService: failed to write doc for '{}/{}': {}",
                        instance, model.wireName(), e.toString());
                result.modelFailed();
            }
        }
    }

    /**
     * Write (or overwrite) the per-model auto-doc. Path layout mirrors
     * the manual layer: provider-directory + slug. Wire-names with
     * {@code ':'} become {@code '-'} in the filename and the original
     * is preserved via the YAML {@code wireName:} field; wire-names
     * with {@code '/'} (HF-style) become nested subdirectories
     * losslessly.
     */
    private void writeAutoDoc(
            String tenantId, String projectId, String instance, DiscoveredModelInfo model) {
        String wireName = model.wireName();
        String slug = slugify(wireName);
        if (slug == null) {
            log.warn("ModelDiscoveryService: wire-name '{}' has no representable slug — skipping",
                    wireName);
            return;
        }
        String path = AUTO_PATH_PREFIX + instance + "/" + slug + ".yaml";
        StringBuilder yaml = new StringBuilder();
        yaml.append("# Auto-discovered by model-discovery — overwritten on every run.\n");
        yaml.append("# Operator edits belong in _vance/model/").append(instance)
                .append("/").append(slug).append(".yaml (manual layer).\n");
        if (!derivedNameMatches(slug, wireName)) {
            yaml.append("wireName: ").append(yamlString(wireName)).append('\n');
        }
        if (model.contextWindowTokens() != null) {
            yaml.append("contextWindowTokens: ").append(model.contextWindowTokens()).append('\n');
        }
        if (model.kind() != null) {
            yaml.append("kind: ").append(model.kind()).append('\n');
        }
        yaml.append("discoveredBy: ").append(DISCOVERED_BY).append('\n');
        yaml.append("discoveredAt: \"").append(Instant.now()).append("\"\n");
        documentService.upsertText(
                tenantId, projectId, path,
                /* title */ instance + "/" + wireName,
                /* tags  */ List.of("ai-model", "discovery"),
                yaml.toString(),
                DOC_AUTHOR,
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);
    }

    /**
     * Translate a wire-name into a filesystem-safe relative path under
     * the provider directory. {@code '/'} stays (becomes a subdir);
     * {@code ':'} is replaced with {@code '-'} (Ollama tag style);
     * any segment that fails {@link #SAFE_SLUG_SEGMENT} causes the
     * caller to skip the model with a WARN.
     */
    static @Nullable String slugify(String wireName) {
        String normalised = wireName.replace(':', '-');
        String[] parts = normalised.split("/");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String segment = parts[i];
            if (segment.isEmpty()) return null;
            if (!SAFE_SLUG_SEGMENT.matcher(segment).matches()) return null;
            if (i > 0) out.append('/');
            out.append(segment);
        }
        return out.toString();
    }

    /**
     * True when the slug, joined with {@code '/'}, equals the original
     * wire-name — i.e. no substitution happened, so the wireName can
     * be reconstructed from the file path alone and the {@code wireName:}
     * field is redundant. Drives whether the field is emitted.
     */
    static boolean derivedNameMatches(String slug, String wireName) {
        return slug.equals(wireName);
    }

    private static String yamlString(String raw) {
        // Quote with double quotes and escape only the bare minimum
        // (backslash + double quote) — the wire-name set is constrained
        // enough that this is safe in practice.
        String escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    /** Per-instance config resolved from the settings of one scope. */
    record InstanceConfig(ProviderType type, String apiKey, @Nullable String baseUrl) {

        InstanceConfig {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(apiKey, "apiKey");
        }
    }

    /**
     * Counters returned by {@link #discoverForTenant}. Carries enough
     * detail for the admin UI to render a meaningful toast plus a
     * list of skipped instances so operators know what to fix.
     */
    public record DiscoveryResult(
            String tenantId,
            int scopesScanned,
            int instancesScanned,
            int modelsWritten,
            int modelsFailed,
            Map<String, String> skippedInstances,
            long durationMs,
            Instant finishedAt) {

        static Builder builder(String tenantId) {
            return new Builder(tenantId);
        }

        /** Mutable accumulator used by the service while it walks scopes. */
        public static final class Builder {
            private final String tenantId;
            private int scopes;
            private int instances;
            private int written;
            private int failedModels;
            private final Map<String, String> skipped = new LinkedHashMap<>();

            Builder(String tenantId) {
                this.tenantId = tenantId;
            }

            void scopeScanned() { scopes++; }
            void instanceScanned() { instances++; }
            void modelWritten() { written++; }
            void modelFailed() { failedModels++; }

            void instanceFailed(String tenant, String project, String instance, String why) {
                skipped.put(tenant + "/" + project + "/" + instance, why);
            }

            DiscoveryResult build(Instant start) {
                long ms = java.time.Duration.between(start, Instant.now()).toMillis();
                return new DiscoveryResult(
                        tenantId, scopes, instances, written, failedModels,
                        Map.copyOf(skipped), ms, Instant.now());
            }
        }
    }
}
