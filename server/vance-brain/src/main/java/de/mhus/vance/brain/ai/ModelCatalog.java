package de.mhus.vance.brain.ai;

import de.mhus.vance.brain.ai.image.ImageModelInfo;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Catalog of {@link ModelInfo} entries indexed by {@code (provider,
 * modelName)}. Reads {@code ai-models.yaml} along the standard cascade
 * — project → {@code _vance} → classpath {@code vance-defaults/} —
 * exactly like {@code RecipeLoader} reads its recipes. Merge is
 * <b>deep, per-key</b>: a project- or tenant-level override only needs
 * to carry the fields it changes; everything else inherits from the
 * next outer layer. Lists (e.g. {@code capabilities}) are replaced as
 * a whole, not concatenated — "wer setzt, der hat".
 *
 * <p>Bundled YAML lives under
 * {@code src/main/resources/vance-defaults/ai-models.yaml}. Tenants
 * override by creating a document with path {@code ai-models.yaml} in
 * the per-tenant {@code _vance} system project; projects do the same
 * in their own project. The cascade is identical to recipes/prompts —
 * one mental model for every override surface.
 *
 * <p>Lookups without a tenant/project scope (the {@link #lookup(String,
 * String)} / {@link #lookupOrDefault(String, String)} overloads) return
 * the bundled view only. They exist for boot-time access and for tests
 * that don't care about overrides; production code that has a process
 * in hand should always thread its {@code (tenantId, projectId)} down.
 *
 * <p>The bundled YAML is parsed once at startup (eager init via
 * {@link jakarta.annotation.PostConstruct}) and cached for the lifetime
 * of the JVM — its contents are immutable, and a per-call
 * {@link ClassPathResource} read had an intermittent miss under
 * virtual-thread contention with Spring Boot's nested-JAR loader. The
 * tenant/project layers are still read on every call (they live in
 * Mongo and change over time).
 *
 * <p>Unknown combinations resolve via {@link #lookupOrDefault} to a
 * conservative fallback (8K context, 4K output) and a WARN log line so
 * the gap is visible — better than silently treating an unknown model
 * as if it had infinite room.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelCatalog {

    /** Document path used in every cascade layer. */
    public static final String CATALOG_PATH = "_vance/ai-models.yaml";

    /** Classpath location of the bundled defaults (mirrors {@link DocumentService} convention). */
    private static final String BUNDLED_RESOURCE = "vance-defaults/" + CATALOG_PATH;

    private static final ModelInfo FALLBACK_TEMPLATE = new ModelInfo(
            "?", "?", 8192, 4096, ModelSize.LARGE, Set.of(),
            ModelInfo.DEFAULT_TIMEOUT_SECONDS,
            ModelInfo.DEFAULT_ACTION_LOOP_CORRECTIONS,
            false);

    private final DocumentService documentService;

    /**
     * Cached parse of {@link #BUNDLED_RESOURCE}. Populated once at
     * startup via {@link #init()}; subsequent {@link #readBundled()}
     * calls return the cached map without touching the classpath. A
     * {@code null} value means the resource was missing at startup —
     * {@link #readBundled()} retries once per call so a late-arriving
     * classloader (e.g. dev-mode reload) recovers without a restart.
     */
    private volatile @Nullable Map<String, Object> bundledCache;
    private volatile boolean bundledCacheLoaded;

    @jakarta.annotation.PostConstruct
    void init() {
        // Eager load on the boot thread — the failure mode we're guarding
        // against is virtual-thread / Langchain4j-worker contention on
        // ClassPathResource lookups. The main thread reading the resource
        // once at startup sidesteps that path.
        loadBundledIntoCache();
    }

    // ──────────────────── Scoped lookups (preferred) ────────────────────

    /**
     * Cascade-aware lookup. {@code tenantId} / {@code projectId} may be
     * {@code null} — that signals "no scope", reading only the bundled
     * layer. Returns empty when no layer carries the {@code (provider,
     * modelName)} pair.
     */
    public Optional<ModelInfo> lookup(
            @Nullable String tenantId, @Nullable String projectId,
            String provider, String modelName) {
        if (provider == null || modelName == null) {
            return Optional.empty();
        }
        Map<String, Map<String, Object>> catalog = resolveMerged(tenantId, projectId);
        Map<String, Object> spec = catalog.get(key(provider, modelName));
        if (spec == null || !isChatKind(spec)) {
            return Optional.empty();
        }
        return Optional.of(buildInfo(provider, modelName, spec));
    }

    /**
     * Cascade-aware lookup of an image-generation model entry
     * ({@code kind: image} in {@code ai-models.yaml}). Returns empty
     * when no layer carries the {@code (provider, modelName)} pair
     * <i>as an image model</i> — chat-kind entries are filtered out
     * even if the name matches, so the call surface for images stays
     * disjoint from the chat one.
     */
    public Optional<ImageModelInfo> lookupImage(
            @Nullable String tenantId, @Nullable String projectId,
            String provider, String modelName) {
        if (provider == null || modelName == null) {
            return Optional.empty();
        }
        Map<String, Map<String, Object>> catalog = resolveMerged(tenantId, projectId);
        Map<String, Object> spec = catalog.get(key(provider, modelName));
        if (spec == null || !isImageKind(spec)) {
            return Optional.empty();
        }
        return Optional.of(buildImageInfo(provider, modelName, spec));
    }

    /** Same as {@link #lookup}, with a conservative WARN-on-miss fallback. */
    public ModelInfo lookupOrDefault(
            @Nullable String tenantId, @Nullable String projectId,
            String provider, String modelName) {
        return lookup(tenantId, projectId, provider, modelName)
                .orElseGet(() -> fallback(provider, modelName));
    }

    /**
     * Cascade-aware lookup with a fallback from a named provider instance to
     * its underlying protocol type. Tries {@code (instance, modelName)} first;
     * if that misses and {@code instance != protocolType}, tries
     * {@code (protocolType, modelName)} — so a tenant who declares a custom
     * instance (e.g. {@code deepseek-direct} on the openai wire) doesn't have
     * to copy every bundled model entry into a new YAML section. Falls back to
     * the WARN-on-miss default after both tries.
     */
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

    /** Bundled-only lookup. Equivalent to {@code lookup(null, null, ...)}. */
    public Optional<ModelInfo> lookup(String provider, String modelName) {
        return lookup(null, null, provider, modelName);
    }

    /** Bundled-only fallback lookup. Equivalent to {@code lookupOrDefault(null, null, ...)}. */
    public ModelInfo lookupOrDefault(String provider, String modelName) {
        return lookupOrDefault(null, null, provider, modelName);
    }

    /**
     * Cascade-aware enumeration of every {@code (provider, modelName)}
     * pair visible to the given scope. Used by the Setting-Form
     * subsystem to populate model-picker dropdowns dynamically, and by
     * future admin-views that want to inspect the merged catalogue.
     *
     * <p>Order follows the {@link #resolveMerged} iteration order:
     * bundled entries first in classpath-YAML declaration order, then
     * any tenant-/project-level additions appended in the order they
     * were merged in. Stable across calls.
     */
    public List<ModelInfo> listAll(@Nullable String tenantId, @Nullable String projectId) {
        Map<String, Map<String, Object>> merged = resolveMerged(tenantId, projectId);
        List<ModelInfo> out = new java.util.ArrayList<>(merged.size());
        for (Map.Entry<String, Map<String, Object>> entry : merged.entrySet()) {
            Map<String, Object> spec = entry.getValue();
            if (!isChatKind(spec)) continue;
            String key = entry.getKey();
            // {@link #key} joins (provider, modelName) with '/' — splitting on
            // ':' would mangle ollama tags like "qwen3:8b". Split at the first
            // '/' instead, which is reserved for the cascade-internal key.
            int slash = key.indexOf('/');
            if (slash <= 0) continue;
            String provider = key.substring(0, slash);
            String modelName = key.substring(slash + 1);
            out.add(buildInfo(provider, modelName, spec));
        }
        return out;
    }

    /**
     * Cascade-aware enumeration of every image-generation
     * {@code (provider, modelName)} pair visible to the given scope.
     * Returns only entries carrying {@code kind: image} —
     * chat entries are excluded. Order follows the
     * {@link #resolveMerged} iteration order.
     */
    public List<ImageModelInfo> listAllImages(
            @Nullable String tenantId, @Nullable String projectId) {
        Map<String, Map<String, Object>> merged = resolveMerged(tenantId, projectId);
        List<ImageModelInfo> out = new java.util.ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : merged.entrySet()) {
            Map<String, Object> spec = entry.getValue();
            if (!isImageKind(spec)) continue;
            String key = entry.getKey();
            int slash = key.indexOf('/');
            if (slash <= 0) continue;
            String provider = key.substring(0, slash);
            String modelName = key.substring(slash + 1);
            out.add(buildImageInfo(provider, modelName, spec));
        }
        return out;
    }

    // ──────────────────── Cascade resolution ────────────────────

    /**
     * Read all three layers, parse each YAML to a nested {@code
     * Map<key, Map<field, Object>>}, and deep-merge outermost-first.
     * Each layer fully replaces fields it sets on a key; fields it omits
     * inherit from the previous merge step.
     */
    private Map<String, Map<String, Object>> resolveMerged(
            @Nullable String tenantId, @Nullable String projectId) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        applyLayer(merged, readBundled(), "bundled");
        if (tenantId != null && !tenantId.isBlank()) {
            applyLayer(merged, readDocument(tenantId, HomeBootstrapService.TENANT_PROJECT_NAME),
                    "tenant(_vance)");
            // Project layer is skipped when projectId IS _vance — that's
            // the tenant layer; reading the same document twice would
            // duplicate work without changing the result.
            if (projectId != null && !projectId.isBlank()
                    && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
                applyLayer(merged, readDocument(tenantId, projectId), "project");
            }
        }
        return merged;
    }

    /**
     * Merge {@code overlay} into {@code acc}, replacing fields per
     * {@code (provider, modelName)} key. {@code overlay} entries with a
     * value that is not a map are skipped with a WARN.
     */
    @SuppressWarnings("unchecked")
    private static void applyLayer(
            Map<String, Map<String, Object>> acc,
            @Nullable Map<String, Object> overlay,
            String layerName) {
        if (overlay == null) return;
        for (Map.Entry<String, Object> providerEntry : overlay.entrySet()) {
            String provider = providerEntry.getKey();
            if (!(providerEntry.getValue() instanceof Map<?, ?> models)) {
                log.warn("ModelCatalog[{}]: provider '{}' has no model map — skipped",
                        layerName, provider);
                continue;
            }
            for (Map.Entry<?, ?> modelEntry : models.entrySet()) {
                String modelName = modelEntry.getKey().toString();
                if (!(modelEntry.getValue() instanceof Map<?, ?> spec)) {
                    log.warn("ModelCatalog[{}]: '{}/{}' is not a map — skipped",
                            layerName, provider, modelName);
                    continue;
                }
                String key = key(provider, modelName);
                Map<String, Object> base = acc.computeIfAbsent(key, k -> new LinkedHashMap<>());
                // Field-level override: each field the overlay sets wins;
                // fields it omits stay at the base. Lists are values like
                // any other Object — replace-as-a-whole, matching YAML's
                // "value wins" semantics elsewhere in the codebase.
                for (Map.Entry<?, ?> field : spec.entrySet()) {
                    base.put(field.getKey().toString(), field.getValue());
                }
            }
        }
    }

    private @Nullable Map<String, Object> readDocument(String tenantId, String projectId) {
        Optional<DocumentDocument> hit = documentService.findByPath(
                tenantId, projectId, CATALOG_PATH);
        if (hit.isEmpty()) return null;
        DocumentDocument doc = hit.get();
        String content = documentService.readContent(doc);
        if (content.isBlank()) return null;
        return parseYaml(content,
                "document tenant='" + tenantId + "' project='" + projectId + "'");
    }

    private @Nullable Map<String, Object> readBundled() {
        if (bundledCacheLoaded) {
            return bundledCache;
        }
        // Startup load missed; retry once. Common case is a single hot
        // path that triggers the miss — the next call gets the cache.
        return loadBundledIntoCache();
    }

    private synchronized @Nullable Map<String, Object> loadBundledIntoCache() {
        if (bundledCacheLoaded) {
            return bundledCache;
        }
        ClassPathResource resource = new ClassPathResource(BUNDLED_RESOURCE);
        if (!resource.exists()) {
            log.warn("ModelCatalog: bundled '{}' not found on classpath — catalog will be empty",
                    BUNDLED_RESOURCE);
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> parsed = parseYaml(content, "classpath:" + BUNDLED_RESOURCE);
            bundledCache = parsed;
            bundledCacheLoaded = true;
            return parsed;
        } catch (IOException e) {
            log.warn("ModelCatalog: failed to read bundled '{}': {}",
                    BUNDLED_RESOURCE, e.toString());
            return null;
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
            return (Map<String, Object>) m;
        } catch (RuntimeException e) {
            log.warn("ModelCatalog: failed to parse {}: {}", origin, e.toString());
            return null;
        }
    }

    // ──────────────────── ModelInfo construction ────────────────────

    /**
     * Whether {@code spec} describes a chat model. The {@code kind:}
     * field is optional — entries without it default to {@code chat}
     * so all pre-existing {@code ai-models.yaml} entries (and every
     * tenant override of them) keep working unchanged.
     */
    private static boolean isChatKind(Map<String, Object> spec) {
        Object kind = spec.get("kind");
        if (kind == null) return true;
        return "chat".equalsIgnoreCase(kind.toString().trim());
    }

    /** Whether {@code spec} explicitly declares {@code kind: image}. */
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
        return new ModelInfo(provider, modelName, ctx, out, size, caps,
                timeout, corrections, stripThinkTags);
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
                FALLBACK_TEMPLATE.stripThinkTags());
    }

    private static boolean readBoolean(@Nullable Object raw, boolean fallback) {
        if (raw instanceof Boolean b) return b;
        if (raw instanceof String s) {
            String t = s.trim().toLowerCase(java.util.Locale.ROOT);
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
            return ModelSize.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("ModelCatalog: '{}/{}' has unknown size '{}' — defaulting to LARGE",
                    provider, modelName, s);
            return ModelSize.LARGE;
        }
    }

    private static String key(String provider, String modelName) {
        return provider.toLowerCase(Locale.ROOT) + "/" + modelName.toLowerCase(Locale.ROOT);
    }
}
