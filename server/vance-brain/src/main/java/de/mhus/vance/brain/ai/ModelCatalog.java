package de.mhus.vance.brain.ai;

import de.mhus.vance.brain.ai.image.ImageModelInfo;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Catalog of {@link ModelInfo} entries indexed by {@code (providerInstance,
 * modelName)}. Each model is a separate YAML document under
 * {@code _vance/model/<providerInstance>/<filenameSlug>.yaml}; provider
 * metadata sits beside it as {@code _provider.yaml}. The bundled layer
 * mirrors the same structure under classpath
 * {@code vance-defaults/_vance/model/...}.
 *
 * <p>Override cascade (outermost → innermost, innermost wins per
 * field):
 *
 * <ol>
 *   <li>Bundled (classpath)</li>
 *   <li>System tenant ({@code _vance / _tenant})</li>
 *   <li>Per-tenant default project ({@code <tenant> / _tenant})</li>
 *   <li>Per-project ({@code <tenant> / <project>}) when the lookup
 *       carries a {@code projectId} that isn't {@code _tenant}</li>
 * </ol>
 *
 * <p>Merge is <b>deep per field</b>: an override only carries the
 * fields it changes; everything else inherits from the next outer
 * layer. Lists (e.g. {@code capabilities}) are replaced as a whole —
 * owners must be able to <i>remove</i> a capability, not just append.
 *
 * <h2>Wire-name encoding</h2>
 * Model wire-names with {@code '/'} (HF-style: {@code
 * google/gemma-4-31B-it}) are encoded by nesting the YAML file under
 * matching subdirectories — the relative path under the provider
 * directory <i>is</i> the wire name. Names with {@code ':'} (Ollama
 * tags: {@code qwen3:30b}) carry an explicit {@code wireName: "..."}
 * field at the top of the YAML; the filename then uses a
 * filesystem-safe slug.
 *
 * <h2>Cache lifecycle — atomic snapshot</h2>
 * The full merged catalog is built once at boot, then refreshed every
 * 30 minutes plus on-demand via {@link #refresh()}. Each refresh
 * builds a new {@link Snapshot} on a worker thread, then publishes it
 * with a single volatile write — readers never see a half-built
 * catalog. There is intentionally <b>no</b> {@code DocumentChangedEvent}
 * listener: model catalogs change on the scale of days, not seconds.
 *
 * <p>Unknown {@code (provider, modelName)} pairs resolve via
 * {@link #lookupOrDefault} to a conservative fallback (8K context, 4K
 * output) and a WARN log line so the gap is visible.
 */
@Service
@Slf4j
public class ModelCatalog {

    /** Document path prefix every <b>manual</b> (operator-managed) model + provider YAML sits under. */
    public static final String MODEL_PATH_PREFIX = "_vance/model/";

    /**
     * Document path prefix every <b>auto</b>-discovered model YAML
     * sits under. Written by {@code ModelDiscoveryService}; never
     * touched by hand. Within one scope, manual fields always beat
     * auto fields at lookup time — operators own the prices,
     * discovery owns the inventory.
     */
    public static final String AUTO_MODEL_PATH_PREFIX = "_vance/model-auto/";

    /** Classpath prefix mirroring {@link DocumentService#RESOURCE_PREFIX}. */
    private static final String BUNDLED_CLASSPATH_PREFIX =
            DocumentService.RESOURCE_PREFIX + MODEL_PATH_PREFIX;

    /** Reserved tenant id whose {@code _tenant} project is the global override layer. */
    static final String SYSTEM_TENANT = "_vance";

    /**
     * Filename of the provider-metadata sidecar (one per provider
     * directory). The leading underscore reserves the name so it can't
     * collide with a model called {@code provider}.
     */
    private static final String PROVIDER_FILE = "_provider.yaml";

    private static final Pattern PROVIDER_NAME_RE = Pattern.compile("[a-z0-9._-]+");
    private static final Pattern FILE_SLUG_RE = Pattern.compile("[A-Za-z0-9._-]+");

    private static final ModelInfo FALLBACK_TEMPLATE = new ModelInfo(
            "?", "?", 8192, 4096, ModelSize.LARGE, Set.of(),
            ModelInfo.DEFAULT_TIMEOUT_SECONDS,
            ModelInfo.DEFAULT_ACTION_LOOP_CORRECTIONS,
            false,
            /*pricing*/ null);

    private final DocumentService documentService;
    private final ResourcePatternResolver resourcePatternResolver =
            new PathMatchingResourcePatternResolver();

    /** Atomic-swap target — readers see a fully-built snapshot or the previous one. */
    private volatile Snapshot snapshot = Snapshot.empty();

    public ModelCatalog(DocumentService documentService) {
        this.documentService = documentService;
        try {
            // Eager initial load — covers both Spring-managed boot and
            // direct test construction. A Mongo glitch can't keep the
            // brain from booting; the scheduled refresh will retry.
            refresh();
        } catch (RuntimeException e) {
            log.error("ModelCatalog: initial refresh failed — catalog stays empty until next refresh", e);
        }
    }

    /**
     * Scheduled refresh. Default 30 minutes; tunable via
     * {@code vance.modelCatalog.refresh.interval}. Misfires are
     * harmless — the next refresh will pick up the latest state.
     */
    @Scheduled(fixedDelayString = "${vance.modelCatalog.refresh.interval:PT30M}",
            initialDelayString = "PT30M")
    public void scheduledRefresh() {
        try {
            refresh();
        } catch (RuntimeException e) {
            log.error("ModelCatalog: scheduled refresh failed — keeping previous snapshot", e);
        }
    }

    /**
     * Build a fresh snapshot from classpath + Mongo and publish it
     * atomically. Returns counters that the admin REST endpoint
     * surfaces to operators.
     */
    public RefreshResult refresh() {
        Instant start = Instant.now();
        Snapshot built = buildSnapshot();
        snapshot = built;
        Duration elapsed = Duration.between(start, Instant.now());
        int overrideScopes = built.uniqueScopeCount();
        log.info("ModelCatalog: refreshed in {} ms — {} bundled, {} override scopes "
                        + "({} manual + {} auto), {} providers",
                elapsed.toMillis(),
                countModels(built.bundled),
                overrideScopes,
                built.perScopeManual.size(),
                built.perScopeAuto.size(),
                countProviders(built.bundled));
        return new RefreshResult(
                Instant.now(),
                countModels(built.bundled),
                countProviders(built.bundled),
                overrideScopes,
                elapsed.toMillis());
    }

    // ──────────────────── Scoped lookups (preferred) ────────────────────

    public Optional<ModelInfo> lookup(
            @Nullable String tenantId, @Nullable String projectId,
            String provider, String modelName) {
        if (provider == null || modelName == null) {
            return Optional.empty();
        }
        Map<String, Map<String, Object>> view = snapshot.viewFor(tenantId, projectId);
        Map<String, Object> spec = view.get(key(provider, modelName));
        if (spec == null || !isChatKind(spec)) {
            return Optional.empty();
        }
        return Optional.of(buildInfo(provider, modelName, spec));
    }

    public Optional<ImageModelInfo> lookupImage(
            @Nullable String tenantId, @Nullable String projectId,
            String provider, String modelName) {
        if (provider == null || modelName == null) {
            return Optional.empty();
        }
        Map<String, Map<String, Object>> view = snapshot.viewFor(tenantId, projectId);
        Map<String, Object> spec = view.get(key(provider, modelName));
        if (spec == null || !isImageKind(spec)) {
            return Optional.empty();
        }
        return Optional.of(buildImageInfo(provider, modelName, spec));
    }

    public ModelInfo lookupOrDefault(
            @Nullable String tenantId, @Nullable String projectId,
            String provider, String modelName) {
        return lookup(tenantId, projectId, provider, modelName)
                .orElseGet(() -> fallback(provider, modelName));
    }

    /** Cascade-aware lookup with named-instance → protocol-type fallback (see §3 spec). */
    public ModelInfo lookupOrDefault(
            @Nullable String tenantId, @Nullable String projectId,
            String providerInstance, String protocolType, String modelName) {
        Optional<ModelInfo> direct = lookup(tenantId, projectId, providerInstance, modelName);
        if (direct.isPresent()) {
            return direct.get();
        }
        if (!providerInstance.equals(protocolType)) {
            Optional<ModelInfo> viaType = lookup(tenantId, projectId, protocolType, modelName);
            if (viaType.isPresent()) {
                return viaType.get();
            }
        }
        return fallback(providerInstance, modelName);
    }

    // ──────────────────── Bundled-only convenience ────────────────────

    public Optional<ModelInfo> lookup(String provider, String modelName) {
        return lookup(null, null, provider, modelName);
    }

    public ModelInfo lookupOrDefault(String provider, String modelName) {
        return lookupOrDefault(null, null, provider, modelName);
    }

    // ──────────────────── Enumeration ────────────────────

    public List<ModelInfo> listAll(@Nullable String tenantId, @Nullable String projectId) {
        Map<String, Map<String, Object>> view = snapshot.viewFor(tenantId, projectId);
        List<ModelInfo> out = new ArrayList<>(view.size());
        for (Map.Entry<String, Map<String, Object>> entry : view.entrySet()) {
            Map<String, Object> spec = entry.getValue();
            if (!isChatKind(spec)) continue;
            String[] parts = splitKey(entry.getKey());
            if (parts == null) continue;
            out.add(buildInfo(parts[0], originalCaseName(spec, parts[1]), spec));
        }
        return out;
    }

    public List<ImageModelInfo> listAllImages(
            @Nullable String tenantId, @Nullable String projectId) {
        Map<String, Map<String, Object>> view = snapshot.viewFor(tenantId, projectId);
        List<ImageModelInfo> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : view.entrySet()) {
            Map<String, Object> spec = entry.getValue();
            if (!isImageKind(spec)) continue;
            String[] parts = splitKey(entry.getKey());
            if (parts == null) continue;
            out.add(buildImageInfo(parts[0], originalCaseName(spec, parts[1]), spec));
        }
        return out;
    }

    /** Provider metadata ({@code _provider.yaml}) for a scoped instance, or empty. */
    public Optional<Map<String, Object>> lookupProvider(
            @Nullable String tenantId, @Nullable String projectId,
            String providerInstance) {
        Map<String, Map<String, Object>> view = snapshot.providerViewFor(tenantId, projectId);
        return Optional.ofNullable(view.get(providerInstance.toLowerCase(Locale.ROOT)));
    }

    // ──────────────────── Snapshot build ────────────────────

    private Snapshot buildSnapshot() {
        Layer bundled = readClasspathLayer();
        Map<TenantProject, Layer> manual = readOverrideLayers(MODEL_PATH_PREFIX);
        Map<TenantProject, Layer> auto = readOverrideLayers(AUTO_MODEL_PATH_PREFIX);
        return new Snapshot(bundled, manual, auto);
    }

    private Layer readClasspathLayer() {
        Map<String, Map<String, Map<String, Object>>> models = new LinkedHashMap<>();
        Map<String, Map<String, Object>> providers = new LinkedHashMap<>();

        Resource[] resources;
        try {
            resources = resourcePatternResolver.getResources(
                    "classpath*:" + BUNDLED_CLASSPATH_PREFIX + "**/*.yaml");
        } catch (IOException e) {
            log.warn("ModelCatalog: classpath scan failed for {}: {}",
                    BUNDLED_CLASSPATH_PREFIX, e.toString());
            return Layer.empty();
        }
        for (Resource resource : resources) {
            String uri = resource.toString();
            int idx = uri.indexOf(BUNDLED_CLASSPATH_PREFIX);
            if (idx < 0) continue;
            String relPath = uri.substring(idx + BUNDLED_CLASSPATH_PREFIX.length());
            // strip trailing ']' from "URL [...]" toString forms.
            int closing = relPath.indexOf(']');
            if (closing >= 0) relPath = relPath.substring(0, closing);
            relPath = relPath.replace('\\', '/');
            String content = readResourceContent(resource);
            if (content == null) continue;
            ingestFile(relPath, content, models, providers, "bundled");
        }
        return new Layer(deepImmutable(models), deepImmutableProviders(providers));
    }

    /**
     * Read every active document under {@code pathPrefix} into per-scope
     * {@link Layer}s. Used twice per refresh — once with
     * {@link #MODEL_PATH_PREFIX} for manual docs, once with
     * {@link #AUTO_MODEL_PATH_PREFIX} for auto-discovered ones.
     */
    private Map<TenantProject, Layer> readOverrideLayers(String pathPrefix) {
        List<DocumentDocument> docs = documentService.findAllByPathPrefix(pathPrefix);
        // Group by (tenantId, projectId) first, then ingest each file into
        // that scope's accumulating layer. Two passes keep the layer-build
        // logic linear and order-independent.
        Map<TenantProject, ScopeAcc> grouped = new LinkedHashMap<>();
        String layerKind = pathPrefix.equals(AUTO_MODEL_PATH_PREFIX) ? "auto" : "manual";
        for (DocumentDocument doc : docs) {
            String path = doc.getPath();
            if (path == null || !path.startsWith(pathPrefix)) continue;
            String relPath = path.substring(pathPrefix.length());
            String tenant = doc.getTenantId();
            String project = doc.getProjectId();
            if (tenant == null || project == null) continue;
            String content = documentService.readContent(doc);
            if (content == null || content.isBlank()) continue;
            TenantProject key = new TenantProject(tenant, project);
            ScopeAcc acc = grouped.computeIfAbsent(key, k -> new ScopeAcc());
            ingestFile(relPath, content, acc.models, acc.providers,
                    layerKind + "-scope[" + tenant + "/" + project + "]");
        }
        Map<TenantProject, Layer> out = new LinkedHashMap<>();
        for (Map.Entry<TenantProject, ScopeAcc> e : grouped.entrySet()) {
            ScopeAcc acc = e.getValue();
            out.put(e.getKey(), new Layer(
                    deepImmutable(acc.models),
                    deepImmutableProviders(acc.providers)));
        }
        return Map.copyOf(out);
    }

    /**
     * Parse {@code content} and feed it into the per-provider model /
     * provider maps. {@code relPath} is the path under
     * {@link #MODEL_PATH_PREFIX} (or the classpath equivalent) — its
     * first segment names the provider directory, the rest forms the
     * model's wire name (unless overridden by an explicit
     * {@code wireName} field in the YAML).
     */
    private static void ingestFile(
            String relPath, String content,
            Map<String, Map<String, Map<String, Object>>> models,
            Map<String, Map<String, Object>> providers,
            String layerName) {
        int firstSlash = relPath.indexOf('/');
        if (firstSlash < 0) {
            log.warn("ModelCatalog[{}]: top-level YAML file {} ignored — must sit under a provider directory",
                    layerName, relPath);
            return;
        }
        String provider = relPath.substring(0, firstSlash);
        String tail = relPath.substring(firstSlash + 1);
        if (!PROVIDER_NAME_RE.matcher(provider).matches()) {
            log.warn("ModelCatalog[{}]: invalid provider directory name '{}' (must match {}); skipping {}",
                    layerName, provider, PROVIDER_NAME_RE.pattern(), relPath);
            return;
        }
        if (!tail.endsWith(".yaml")) {
            log.warn("ModelCatalog[{}]: ignoring non-yaml file {}", layerName, relPath);
            return;
        }
        String body = tail.substring(0, tail.length() - ".yaml".length());

        Map<String, Object> spec = parseYaml(content, "model file '" + relPath + "'");
        if (spec == null) return;

        // _provider.yaml or any file whose basename is _provider — store
        // as provider metadata, not as a model entry.
        int lastSlash = body.lastIndexOf('/');
        String basename = lastSlash < 0 ? body : body.substring(lastSlash + 1);
        if ("_provider".equals(basename)) {
            providers.merge(provider, spec, (existing, incoming) -> {
                Map<String, Object> merged = new LinkedHashMap<>(existing);
                merged.putAll(incoming);
                return merged;
            });
            return;
        }

        // Validate each filename slug segment. Sub-directories under the
        // provider dir form the wire name's '/'-separated parts (HF-style).
        for (String segment : body.split("/")) {
            if (!FILE_SLUG_RE.matcher(segment).matches()) {
                log.warn("ModelCatalog[{}]: invalid filename slug segment '{}' in {}; skipping",
                        layerName, segment, relPath);
                return;
            }
        }

        // Wire name: explicit `wireName:` field wins; else derive from
        // the relative path (preserves '/' for HF-style names).
        String wireName = readString(spec.get("wireName"));
        if (wireName == null || wireName.isBlank()) {
            wireName = body;
        }
        // Stash the original-case name on the spec so listAll can
        // reproduce it. The merge-key is lowercased downstream.
        spec.putIfAbsent("modelName", wireName);

        Map<String, Map<String, Object>> bucket =
                models.computeIfAbsent(provider, p -> new LinkedHashMap<>());
        String mergeKey = wireName.toLowerCase(Locale.ROOT);
        Map<String, Object> existing = bucket.get(mergeKey);
        if (existing == null) {
            bucket.put(mergeKey, new LinkedHashMap<>(spec));
        } else {
            // Same key within one layer — second file wins per field.
            // In practice this only happens when a YAML uses both a slug
            // file and an explicit wireName that collides; we accept the
            // later read.
            existing.putAll(spec);
        }
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> parseYaml(String content, String origin) {
        try {
            Object parsed = new Yaml().load(content);
            if (parsed == null) return null;
            if (!(parsed instanceof Map<?, ?> m)) {
                log.warn("ModelCatalog: {} top level is not a map — ignoring", origin);
                return null;
            }
            return new LinkedHashMap<>((Map<String, Object>) m);
        } catch (RuntimeException e) {
            log.warn("ModelCatalog: failed to parse {}: {}", origin, e.toString());
            return null;
        }
    }

    private static @Nullable String readResourceContent(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("ModelCatalog: failed to read classpath resource '{}': {}",
                    resource, e.toString());
            return null;
        }
    }

    // ──────────────────── Snapshot type ────────────────────

    /**
     * Immutable snapshot of the bundled layer + per-scope manual and
     * auto layers, plus per-scope merged-view memoization. Lookups go
     * through {@link #viewFor(String, String)}.
     *
     * <p>The two override maps ({@link #perScopeManual} and
     * {@link #perScopeAuto}) are physically separated by document
     * path: {@code _vance/model/**} flows into manual,
     * {@code _vance/model-auto/**} flows into auto. At lookup time,
     * within one scope, the manual layer is applied <i>after</i> the
     * auto layer — manual fields therefore beat auto fields.
     */
    static final class Snapshot {
        final Layer bundled;
        final Map<TenantProject, Layer> perScopeManual;
        final Map<TenantProject, Layer> perScopeAuto;

        /**
         * Memoized merged views per lookup-scope. Built lazily on first
         * access for each {@code (tenant, project)} pair; reset only
         * when a new {@code Snapshot} is published.
         */
        private final Map<TenantProject, Map<String, Map<String, Object>>> mergedCache =
                new ConcurrentHashMap<>();
        private final Map<TenantProject, Map<String, Map<String, Object>>> providerCache =
                new ConcurrentHashMap<>();

        Snapshot(Layer bundled,
                 Map<TenantProject, Layer> perScopeManual,
                 Map<TenantProject, Layer> perScopeAuto) {
            this.bundled = bundled;
            this.perScopeManual = perScopeManual;
            this.perScopeAuto = perScopeAuto;
        }

        static Snapshot empty() {
            return new Snapshot(Layer.empty(), Map.of(), Map.of());
        }

        /**
         * Count of {@code (tenantId, projectId)} pairs that have any
         * override docs (manual + auto union) — for the
         * {@link RefreshResult} counter.
         */
        int uniqueScopeCount() {
            if (perScopeManual.isEmpty()) return perScopeAuto.size();
            if (perScopeAuto.isEmpty()) return perScopeManual.size();
            java.util.Set<TenantProject> union =
                    new java.util.HashSet<>(perScopeManual.keySet());
            union.addAll(perScopeAuto.keySet());
            return union.size();
        }

        Map<String, Map<String, Object>> viewFor(
                @Nullable String tenantId, @Nullable String projectId) {
            TenantProject key = normalizeScope(tenantId, projectId);
            return mergedCache.computeIfAbsent(key, this::buildModelView);
        }

        Map<String, Map<String, Object>> providerViewFor(
                @Nullable String tenantId, @Nullable String projectId) {
            TenantProject key = normalizeScope(tenantId, projectId);
            return providerCache.computeIfAbsent(key, this::buildProviderView);
        }

        private Map<String, Map<String, Object>> buildModelView(TenantProject scope) {
            // Outer → inner application. Each layer overrides fields on
            // any (provider, model) key it carries; within one scope,
            // auto runs first then manual so manual fields win.
            Map<String, Map<String, Object>> acc = new LinkedHashMap<>();
            applyModelLayer(acc, bundled);
            applyScopeModel(acc, new TenantProject(
                    SYSTEM_TENANT, HomeBootstrapService.TENANT_PROJECT_NAME));
            if (scope.tenantId != null) {
                applyScopeModel(acc, new TenantProject(
                        scope.tenantId, HomeBootstrapService.TENANT_PROJECT_NAME));
                if (scope.projectId != null
                        && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(scope.projectId)) {
                    applyScopeModel(acc, new TenantProject(
                            scope.tenantId, scope.projectId));
                }
            }
            return Collections.unmodifiableMap(acc);
        }

        private Map<String, Map<String, Object>> buildProviderView(TenantProject scope) {
            Map<String, Map<String, Object>> acc = new LinkedHashMap<>();
            applyProviderLayer(acc, bundled);
            applyScopeProvider(acc, new TenantProject(
                    SYSTEM_TENANT, HomeBootstrapService.TENANT_PROJECT_NAME));
            if (scope.tenantId != null) {
                applyScopeProvider(acc, new TenantProject(
                        scope.tenantId, HomeBootstrapService.TENANT_PROJECT_NAME));
                if (scope.projectId != null
                        && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(scope.projectId)) {
                    applyScopeProvider(acc, new TenantProject(
                            scope.tenantId, scope.projectId));
                }
            }
            return Collections.unmodifiableMap(acc);
        }

        /** Apply auto then manual for one scope — manual wins per field. */
        private void applyScopeModel(Map<String, Map<String, Object>> acc, TenantProject key) {
            Layer auto = perScopeAuto.get(key);
            if (auto != null) applyModelLayer(acc, auto);
            Layer manual = perScopeManual.get(key);
            if (manual != null) applyModelLayer(acc, manual);
        }

        private void applyScopeProvider(Map<String, Map<String, Object>> acc, TenantProject key) {
            Layer auto = perScopeAuto.get(key);
            if (auto != null) applyProviderLayer(acc, auto);
            Layer manual = perScopeManual.get(key);
            if (manual != null) applyProviderLayer(acc, manual);
        }

        private static void applyModelLayer(
                Map<String, Map<String, Object>> acc, Layer layer) {
            for (Map.Entry<String, Map<String, Map<String, Object>>> provEntry
                    : layer.models.entrySet()) {
                String provider = provEntry.getKey();
                for (Map.Entry<String, Map<String, Object>> modelEntry
                        : provEntry.getValue().entrySet()) {
                    String compositeKey = provider + "/" + modelEntry.getKey();
                    Map<String, Object> base = acc.computeIfAbsent(
                            compositeKey, k -> new LinkedHashMap<>());
                    base.putAll(modelEntry.getValue());
                }
            }
        }

        private static void applyProviderLayer(
                Map<String, Map<String, Object>> acc, Layer layer) {
            for (Map.Entry<String, Map<String, Object>> e : layer.providers.entrySet()) {
                Map<String, Object> base = acc.computeIfAbsent(
                        e.getKey(), k -> new LinkedHashMap<>());
                base.putAll(e.getValue());
            }
        }

        private static TenantProject normalizeScope(
                @Nullable String tenant, @Nullable String project) {
            String t = (tenant == null || tenant.isBlank()) ? null : tenant;
            String p = (project == null || project.isBlank()) ? null : project;
            return new TenantProject(t, p);
        }
    }

    /** One layer of the catalog — bundled, system, or a single (tenant, project). */
    static final class Layer {
        final Map<String, Map<String, Map<String, Object>>> models;   // provider -> modelKey -> spec
        final Map<String, Map<String, Object>> providers;              // provider -> _provider.yaml content

        Layer(Map<String, Map<String, Map<String, Object>>> models,
              Map<String, Map<String, Object>> providers) {
            this.models = models;
            this.providers = providers;
        }

        static Layer empty() {
            return new Layer(Map.of(), Map.of());
        }
    }

    /** Mutable accumulator for one scope during refresh. */
    private static final class ScopeAcc {
        final Map<String, Map<String, Map<String, Object>>> models = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> providers = new LinkedHashMap<>();
    }

    /** Compound key for {@link Snapshot#perScope} and merged-cache. */
    record TenantProject(@Nullable String tenantId, @Nullable String projectId) {
        @Override public int hashCode() {
            return Objects.hash(tenantId, projectId);
        }
    }

    /** REST response payload for {@code POST /admin/ai-models/refresh}. */
    public record RefreshResult(
            Instant refreshedAt,
            int bundledModelsLoaded,
            int bundledProvidersLoaded,
            int overrideScopes,
            long durationMs) {}

    // ──────────────────── ModelInfo construction ────────────────────

    private static boolean isChatKind(Map<String, Object> spec) {
        Object kind = spec.get("kind");
        if (kind == null) return true;
        return "chat".equalsIgnoreCase(kind.toString().trim());
    }

    private static boolean isImageKind(Map<String, Object> spec) {
        Object kind = spec.get("kind");
        if (kind == null) return false;
        return "image".equalsIgnoreCase(kind.toString().trim());
    }

    private static ImageModelInfo buildImageInfo(
            String provider, String modelName, Map<String, Object> spec) {
        Set<String> aspects = readStringList(spec.get("supportedAspectRatios"));
        int maxPromptChars = readInt(spec.get("maxPromptChars"),
                ImageModelInfo.DEFAULT_MAX_PROMPT_CHARS);
        Map<String, Double> costs = readCostMap(spec.get("costPerImage"),
                provider, modelName);
        int timeout = readInt(spec.get("timeoutSeconds"),
                ImageModelInfo.DEFAULT_TIMEOUT_SECONDS);
        return new ImageModelInfo(provider, modelName, aspects, maxPromptChars,
                costs, timeout);
    }

    private static ModelInfo buildInfo(String provider, String modelName, Map<String, Object> spec) {
        int ctx = readInt(spec.get("contextWindowTokens"),
                FALLBACK_TEMPLATE.contextWindowTokens());
        int out = readInt(spec.get("defaultMaxOutputTokens"),
                FALLBACK_TEMPLATE.defaultMaxOutputTokens());
        ModelSize size = readSize(spec.get("size"), provider, modelName);
        Set<ModelCapability> caps = readCapabilities(spec.get("capabilities"), provider, modelName);
        int timeout = readInt(spec.get("timeoutSeconds"),
                FALLBACK_TEMPLATE.timeoutSeconds());
        int corrections = readInt(spec.get("actionLoopCorrections"),
                FALLBACK_TEMPLATE.actionLoopCorrections());
        boolean stripThinkTags = readBoolean(spec.get("stripThinkTags"),
                FALLBACK_TEMPLATE.stripThinkTags());
        ModelInfo.Pricing pricing = readPricing(spec.get("pricing"), provider, modelName);
        return new ModelInfo(provider, modelName, ctx, out, size, caps,
                timeout, corrections, stripThinkTags, pricing);
    }

    private static ModelInfo fallback(@Nullable String provider, @Nullable String modelName) {
        log.warn("ModelCatalog: no entry for '{}/{}' — falling back to {}-token context, "
                        + "no capabilities, {}s timeout",
                provider, modelName, FALLBACK_TEMPLATE.contextWindowTokens(),
                FALLBACK_TEMPLATE.timeoutSeconds());
        return new ModelInfo(
                provider == null ? "?" : provider,
                modelName == null ? "?" : modelName,
                FALLBACK_TEMPLATE.contextWindowTokens(),
                FALLBACK_TEMPLATE.defaultMaxOutputTokens(),
                FALLBACK_TEMPLATE.size(),
                FALLBACK_TEMPLATE.capabilities(),
                FALLBACK_TEMPLATE.timeoutSeconds(),
                FALLBACK_TEMPLATE.actionLoopCorrections(),
                FALLBACK_TEMPLATE.stripThinkTags(),
                /*pricing*/ null);
    }

    @SuppressWarnings("unchecked")
    private static ModelInfo.@Nullable Pricing readPricing(
            @Nullable Object raw, String provider, String modelName) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> m)) {
            log.warn("ModelCatalog: '{}/{}' has non-map pricing '{}' — ignored",
                    provider, modelName, raw);
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) m;
        Double input = readDouble(map.get("inputPerMTok"));
        Double output = readDouble(map.get("outputPerMTok"));
        if (input == null || output == null) {
            log.warn("ModelCatalog: '{}/{}' pricing missing inputPerMTok/outputPerMTok — ignored",
                    provider, modelName);
            return null;
        }
        Double cacheRead = readDouble(map.get("cacheReadPerMTok"));
        Double cacheWrite = readDouble(map.get("cacheWritePerMTok"));
        Object currencyRaw = map.get("currency");
        String currency = currencyRaw == null ? "USD" : currencyRaw.toString().trim();
        if (currency.isEmpty()) currency = "USD";
        return new ModelInfo.Pricing(currency, input, output, cacheRead, cacheWrite);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Double> readCostMap(
            @Nullable Object raw, String provider, String modelName) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> m)) {
            log.warn("ModelCatalog: '{}/{}' has non-map costPerImage '{}' — ignored",
                    provider, modelName, raw);
            return Map.of();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : m.entrySet()) {
            String tier = entry.getKey() == null ? null : entry.getKey().toString().trim();
            if (tier == null || tier.isEmpty()) continue;
            Object value = entry.getValue();
            Double cost = null;
            if (value instanceof Number n) {
                cost = n.doubleValue();
            } else if (value instanceof String s) {
                try {
                    cost = Double.parseDouble(s.trim());
                } catch (NumberFormatException ignored) {
                    // fall through to warn below
                }
            }
            if (cost == null) {
                log.warn("ModelCatalog: '{}/{}' costPerImage.{} is not a number '{}' — skipped",
                        provider, modelName, tier, value);
                continue;
            }
            out.put(tier, cost);
        }
        return out;
    }

    // ──────────────────── Primitive parsers ────────────────────

    private static @Nullable Double readDouble(@Nullable Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.doubleValue();
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean readBoolean(@Nullable Object raw, boolean fallback) {
        if (raw instanceof Boolean b) return b;
        if (raw instanceof String s) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.equals("true") || t.equals("yes") || t.equals("1")) return true;
            if (t.equals("false") || t.equals("no") || t.equals("0")) return false;
        }
        return fallback;
    }

    private static int readInt(@Nullable Object raw, int fallback) {
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static @Nullable String readString(@Nullable Object raw) {
        if (raw == null) return null;
        String s = raw.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Set<String> readStringList(@Nullable Object raw) {
        if (raw == null) return Set.of();
        if (!(raw instanceof List<?> list)) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (Object e : list) {
            if (e == null) continue;
            String s = e.toString().trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static Set<ModelCapability> readCapabilities(
            @Nullable Object raw, String provider, String modelName) {
        if (raw == null) {
            return Set.of();
        }
        if (!(raw instanceof List<?> list)) {
            log.warn("ModelCatalog: '{}/{}' has non-list capabilities '{}' — ignoring",
                    provider, modelName, raw);
            return Set.of();
        }
        EnumSet<ModelCapability> caps = EnumSet.noneOf(ModelCapability.class);
        for (Object element : list) {
            if (element == null) continue;
            ModelCapability.fromString(element.toString()).ifPresentOrElse(
                    caps::add,
                    () -> log.warn("ModelCatalog: '{}/{}' has unknown capability '{}' — skipped",
                            provider, modelName, element));
        }
        return caps;
    }

    private static ModelSize readSize(@Nullable Object raw, String provider, String modelName) {
        if (raw == null) return ModelSize.LARGE;
        if (!(raw instanceof String s)) return ModelSize.LARGE;
        try {
            return ModelSize.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("ModelCatalog: '{}/{}' has unknown size '{}' — defaulting to LARGE",
                    provider, modelName, s);
            return ModelSize.LARGE;
        }
    }

    // ──────────────────── Helpers ────────────────────

    /** Composite key {@code <provider>/<lowercased-modelName>} used as snapshot map key. */
    private static String key(String provider, String modelName) {
        return provider.toLowerCase(Locale.ROOT) + "/" + modelName.toLowerCase(Locale.ROOT);
    }

    /** Reverse of {@link #key} — returns {@code [provider, lower-model]} or null. */
    private static String @Nullable [] splitKey(String compositeKey) {
        int slash = compositeKey.indexOf('/');
        if (slash <= 0) return null;
        return new String[] {
                compositeKey.substring(0, slash),
                compositeKey.substring(slash + 1)};
    }

    /**
     * Returns the case-preserved model name from the spec when
     * available, falling back to the lowercase merge-key form. Used by
     * {@link #listAll} so dropdowns show {@code google/gemma-4-31B-it}
     * verbatim rather than mangled.
     */
    private static String originalCaseName(Map<String, Object> spec, String fallbackLower) {
        String wire = readString(spec.get("wireName"));
        if (wire != null) return wire;
        String mn = readString(spec.get("modelName"));
        if (mn != null) return mn;
        return fallbackLower;
    }

    private static int countModels(Layer layer) {
        int n = 0;
        for (Map<String, Map<String, Object>> m : layer.models.values()) n += m.size();
        return n;
    }

    private static int countProviders(Layer layer) {
        return layer.providers.size();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Map<String, Object>>> deepImmutable(
            Map<String, Map<String, Map<String, Object>>> mutable) {
        Map<String, Map<String, Map<String, Object>>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Map<String, Object>>> e : mutable.entrySet()) {
            Map<String, Map<String, Object>> inner = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> ie : e.getValue().entrySet()) {
                inner.put(ie.getKey(), Map.copyOf(ie.getValue()));
            }
            out.put(e.getKey(), Collections.unmodifiableMap(inner));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, Map<String, Object>> deepImmutableProviders(
            Map<String, Map<String, Object>> mutable) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : mutable.entrySet()) {
            out.put(e.getKey(), Map.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }
}
