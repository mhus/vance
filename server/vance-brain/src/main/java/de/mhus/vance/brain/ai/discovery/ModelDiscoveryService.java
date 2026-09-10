package de.mhus.vance.brain.ai.discovery;

import de.mhus.vance.brain.ai.AiModelProvider;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ai.DiscoveredModelInfo;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ProviderListingRequest;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.TlsInsecure;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
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
 * <p>What goes into the auto docs is decided by the observation
 * doctrine: everything the listing endpoint itself reports (wire name,
 * limits, owned-by). Pricing and {@code kind} are NOT written even
 * when an endpoint ships them (cortecs' listing carries a price
 * block) — prices are owned by a different source (the vendor's price
 * sheet → operator-managed manual layer), and the auto layer
 * outranks bundled, so an asserted price or classification would
 * shadow a correct curated value; see {@link DiscoveredModelInfo}.
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
    private static final java.util.regex.Pattern SAFE_SLUG_SEGMENT = java.util.regex.Pattern.compile("[A-Za-z0-9._-]+");

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

    private void discoverInScope(String tenantId, String projectId, DiscoveryResult.Builder result) {
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
                log.warn(
                        "ModelDiscoveryService: scope='{}/{}' instance='{}' failed: {}",
                        tenantId,
                        projectId,
                        instance,
                        e.toString());
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
        for (SettingDocument doc : settingService.findAll(tenantId, SettingService.SCOPE_PROJECT, projectId)) {
            String key = doc.getKey();
            if (key == null || !key.startsWith(PROVIDER_KEY_PREFIX)) continue;
            String rest = key.substring(PROVIDER_KEY_PREFIX.length());
            int dot = rest.indexOf('.');
            if (dot <= 0) continue;
            String instance = rest.substring(0, dot);
            String field = rest.substring(dot + 1);
            byInstance.computeIfAbsent(instance, i -> new LinkedHashMap<>()).put(field, doc);
        }
        Map<String, InstanceConfig> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, SettingDocument>> e : byInstance.entrySet()) {
            String instance = e.getKey();
            Map<String, SettingDocument> fields = e.getValue();
            // Determine the protocol type — same precedence as
            // AiModelResolver: `type` setting, then the `wireType` declared
            // by the instance's `_provider.yaml`, then instance == wireName.
            // Discovery has to agree with the resolver here: an instance the
            // resolver can chat with but discovery skips is an instance whose
            // "Discover AI Models" button silently does nothing.
            String typeWire = instance;
            SettingDocument typeDoc = fields.get("type");
            if (typeDoc != null
                    && typeDoc.getValue() != null
                    && !typeDoc.getValue().isBlank()) {
                typeWire = typeDoc.getValue().trim();
            } else {
                String declared = modelCatalog
                        .lookupProvider(tenantId, projectId, instance)
                        .map(spec -> spec.get("wireType"))
                        .filter(v -> v instanceof String s && !s.isBlank())
                        .map(v -> ((String) v).trim())
                        .orElse(null);
                if (declared != null) {
                    typeWire = declared;
                }
            }
            ProviderType type = ProviderType.fromWireName(typeWire).orElse(null);
            if (type == null) {
                log.debug(
                        "ModelDiscoveryService: scope='{}/{}' instance='{}' has unknown "
                                + "protocol type '{}' — skipping",
                        tenantId,
                        projectId,
                        instance,
                        typeWire);
                continue;
            }
            // ApiKey: an encrypted type; decrypt at this exact scope. For
            // providers that don't require auth (Ollama, LM Studio) a
            // blank apiKey is acceptable — they get an empty string.
            SettingDocument apiKeyDoc = fields.get("apiKey");
            String apiKey = "";
            if (apiKeyDoc != null && apiKeyDoc.getType().encrypted()) {
                String decrypted = settingService.getDecryptedPassword(
                        tenantId, SettingService.SCOPE_PROJECT, projectId, PROVIDER_KEY_PREFIX + instance + ".apiKey");
                if (decrypted != null) apiKey = decrypted;
            } else if (apiKeyDoc != null && apiKeyDoc.getValue() != null) {
                apiKey = apiKeyDoc.getValue();
            }
            if (apiKey.isBlank() && type.requiresApiKey()) {
                log.debug(
                        "ModelDiscoveryService: scope='{}/{}' instance='{}' has no apiKey "
                                + "but provider '{}' requires one — skipping",
                        tenantId,
                        projectId,
                        instance,
                        type.wireName());
                continue;
            }
            // Optional base URL.
            String baseUrl = null;
            SettingDocument urlDoc = fields.get("baseUrl");
            if (urlDoc != null
                    && urlDoc.getValue() != null
                    && !urlDoc.getValue().isBlank()) {
                baseUrl = urlDoc.getValue().trim();
            }
            // Sidecar TLS flag — same lookup the resolver uses, so listing and
            // chat agree on whether this instance skips TLS validation.
            boolean insecureTls = modelCatalog
                    .lookupProvider(tenantId, projectId, instance)
                    .map(TlsInsecure::flagOf)
                    .orElse(false);
            out.put(instance, new InstanceConfig(type, apiKey, baseUrl, insecureTls));
        }
        return out;
    }

    private void runOneInstance(
            String tenantId, String projectId, String instance, InstanceConfig cfg, DiscoveryResult.Builder result) {
        AiModelProvider provider = aiModelService.findProvider(cfg.type()).orElse(null);
        if (provider == null) {
            log.debug("ModelDiscoveryService: no provider bean for type '{}' (instance '{}')", cfg.type(), instance);
            result.instanceFailed(tenantId, projectId, instance, "No provider bean registered for type " + cfg.type());
            return;
        }
        ProviderListingRequest req =
                new ProviderListingRequest(instance, cfg.apiKey(), cfg.baseUrl(), cfg.insecureTls());
        List<DiscoveredModelInfo> models;
        try {
            models = provider.listAvailableModels(req);
        } catch (UnsupportedOperationException e) {
            log.debug(
                    "ModelDiscoveryService: provider '{}' does not implement listing — " + "skipping instance '{}'",
                    cfg.type(),
                    instance);
            result.instanceFailed(tenantId, projectId, instance, "Listing not supported by provider " + cfg.type());
            return;
        }
        for (DiscoveredModelInfo model : models) {
            try {
                writeAutoDoc(tenantId, projectId, instance, model);
                writeManualPricingDoc(tenantId, projectId, instance, model, result);
                result.modelWritten();
            } catch (RuntimeException e) {
                log.warn(
                        "ModelDiscoveryService: failed to write doc for '{}/{}': {}",
                        instance,
                        model.wireName(),
                        e.toString());
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
     *
     * <p>Only <em>observations</em> go in here — the wire name plus, if the
     * vendor reports them, context window, output limit and owned-by.
     * Pricing, {@code kind} and capabilities are never written — even
     * when the listing endpoint ships them: prices belong to a different
     * source (manual layer), and the auto
     * layer outranks the bundled layer in the catalog cascade, so an
     * asserted {@code kind: chat} would shadow a bundled
     * {@code kind: image} and drop that model out of every image picker.
     * See {@link DiscoveredModelInfo}.
     */
    private void writeAutoDoc(String tenantId, String projectId, String instance, DiscoveredModelInfo model) {
        String wireName = model.wireName();
        String slug = slugify(wireName);
        if (slug == null) {
            log.warn("ModelDiscoveryService: wire-name '{}' has no representable slug — skipping", wireName);
            return;
        }
        String path = AUTO_PATH_PREFIX + instance + "/" + slug + ".yaml";
        StringBuilder yaml = new StringBuilder();
        yaml.append("# Auto-discovered by model-discovery — overwritten on every run.\n");
        yaml.append("# Operator edits belong in _vance/model/")
                .append(instance)
                .append("/")
                .append(slug)
                .append(".yaml (manual layer).\n");
        if (!derivedNameMatches(slug, wireName)) {
            yaml.append("wireName: ").append(yamlString(wireName)).append('\n');
        }
        if (model.contextWindowTokens() != null) {
            yaml.append("contextWindowTokens: ")
                    .append(model.contextWindowTokens())
                    .append('\n');
        }
        if (model.maxOutputTokens() != null) {
            yaml.append("maxOutputTokens: ").append(model.maxOutputTokens()).append('\n');
        }
        if (model.ownedBy() != null) {
            yaml.append("ownedBy: ").append(yamlString(model.ownedBy())).append('\n');
        }
        yaml.append("discoveredBy: ").append(DISCOVERED_BY).append('\n');
        yaml.append("discoveredAt: \"").append(Instant.now()).append("\"\n");
        documentService.upsertText(
                tenantId,
                projectId,
                path,
                /* title */ instance + "/" + wireName,
                /* tags  */ List.of("ai-model", "discovery"),
                yaml.toString(),
                DOC_AUTHOR,
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);
    }

    /**
     * Write the endpoint-reported prices into the <b>manual</b> layer as
     * a machine-owned {@code auto: true} file. The auto-docs stay
     * pricing-free (they are overwritten wholesale on every run and sit
     * above bundled in the cascade); the manual layer is where pricing
     * lives, and this is its automation slot:
     * <ul>
     *   <li>No file at {@code _vance/model/<instance>/<slug>.yaml} — create
     *       one carrying {@code auto: true} and the {@code pricing:} block.</li>
     *   <li>File exists <b>without</b> the marker — operator-owned, never
     *       touched, whatever it says wins through the cascade.</li>
     *   <li>File exists <b>with</b> {@code auto: true} — still machine-owned,
     *       the pricing block is refreshed (prices change; nobody wants to
     *       re-type them). An operator claims the file by removing the
     *       marker.</li>
     * </ul>
     *
     * <p>Models the endpoint does not price get no file — nothing is guessed
     * or scraped; those stay unpriced until an operator writes them.
     */
    private void writeManualPricingDoc(
            String tenantId,
            String projectId,
            String instance,
            DiscoveredModelInfo model,
            DiscoveryResult.Builder result) {
        de.mhus.vance.brain.ai.ModelInfo.Pricing pricing = model.pricing();
        if (pricing == null) {
            return;
        }
        String wireName = model.wireName();
        String slug = slugify(wireName);
        if (slug == null) {
            return;
        }
        String path = ModelCatalog.MODEL_PATH_PREFIX + instance + "/" + slug + ".yaml";
        java.util.Optional<de.mhus.vance.shared.document.DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, path);
        boolean existed = existing.isPresent();
        if (existed) {
            String content = documentService.readContent(existing.get());
            if (!hasAutoMarker(content)) {
                log.debug(
                        "ModelDiscoveryService: manual pricing doc '{}' exists without auto marker — operator-owned, not touched",
                        path);
                return;
            }
        }
        StringBuilder yaml = new StringBuilder();
        yaml.append("# Machine-owned by model-discovery — refreshed on every run while auto: true stays.\n");
        yaml.append("# Remove the marker to take ownership; discovery then never touches this file.\n");
        yaml.append("# Prices are endpoint observations (see _vance/model-auto/ for limits).\n");
        yaml.append("auto: true\n");
        if (!derivedNameMatches(slug, wireName)) {
            yaml.append("wireName: ").append(yamlString(wireName)).append('\n');
        }
        yaml.append("pricing:\n");
        yaml.append("  currency: ").append(pricing.currency()).append('\n');
        yaml.append("  inputPerMTok: ").append(pricing.inputPerMTok()).append('\n');
        yaml.append("  outputPerMTok: ").append(pricing.outputPerMTok()).append('\n');
        if (pricing.cacheReadPerMTok() != null) {
            yaml.append("  cacheReadPerMTok: ")
                    .append(pricing.cacheReadPerMTok())
                    .append('\n');
        }
        if (pricing.cacheWritePerMTok() != null) {
            yaml.append("  cacheWritePerMTok: ")
                    .append(pricing.cacheWritePerMTok())
                    .append('\n');
        }
        documentService.upsertText(
                tenantId,
                projectId,
                path,
                /* title */ instance + "/" + wireName,
                /* tags  */ List.of("ai-model", "pricing-auto"),
                yaml.toString(),
                DOC_AUTHOR,
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);
        if (existed) {
            result.pricingDocUpdated();
        } else {
            result.pricingDocCreated();
        }
    }

    /**
     * True when the YAML content carries {@code auto: true} at the top
     * level — the machine-ownership marker of a manual pricing doc.
     */
    static boolean hasAutoMarker(@Nullable String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        try {
            Object parsed = new org.yaml.snakeyaml.Yaml().load(content);
            if (parsed instanceof Map<?, ?> map) {
                return Boolean.TRUE.equals(map.get("auto"));
            }
        } catch (RuntimeException e) {
            log.debug(
                    "ModelDiscoveryService: unparseable manual pricing doc — treating as operator-owned: {}",
                    e.toString());
        }
        return false;
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

    /** Per-instance config resolved from the settings + sidecar of one scope. */
    record InstanceConfig(
            ProviderType type, String apiKey, @Nullable String baseUrl, boolean insecureTls) {

        /** Back-compat for tests and callers predating the TLS flag. */
        InstanceConfig(ProviderType type, String apiKey, @Nullable String baseUrl) {
            this(type, apiKey, baseUrl, false);
        }

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
            int pricingDocsCreated,
            int pricingDocsUpdated,
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
            private int pricingCreated;
            private int pricingUpdated;
            private final Map<String, String> skipped = new LinkedHashMap<>();

            Builder(String tenantId) {
                this.tenantId = tenantId;
            }

            void scopeScanned() {
                scopes++;
            }

            void instanceScanned() {
                instances++;
            }

            void modelWritten() {
                written++;
            }

            void modelFailed() {
                failedModels++;
            }

            void pricingDocCreated() {
                pricingCreated++;
            }

            void pricingDocUpdated() {
                pricingUpdated++;
            }

            void instanceFailed(String tenant, String project, String instance, String why) {
                skipped.put(tenant + "/" + project + "/" + instance, why);
            }

            DiscoveryResult build(Instant start) {
                long ms = java.time.Duration.between(start, Instant.now()).toMillis();
                return new DiscoveryResult(
                        tenantId,
                        scopes,
                        instances,
                        written,
                        failedModels,
                        pricingCreated,
                        pricingUpdated,
                        Map.copyOf(skipped),
                        ms,
                        Instant.now());
            }
        }
    }
}
