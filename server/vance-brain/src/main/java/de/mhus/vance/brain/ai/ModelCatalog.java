package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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
 * <p>Resolution parses YAML on every call — the file is small (a few
 * KB) and the cost is dominated by Mongo I/O on the document side.
 * Match {@code RecipeLoader}'s no-cache choice; profile-driven caching
 * can land later if it ever matters.
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
    public static final String CATALOG_PATH = "ai-models.yaml";

    /** Classpath location of the bundled defaults (mirrors {@link DocumentService} convention). */
    private static final String BUNDLED_RESOURCE = "vance-defaults/" + CATALOG_PATH;

    private static final ModelInfo FALLBACK_TEMPLATE = new ModelInfo(
            "?", "?", 8192, 4096, ModelSize.LARGE, Set.of(),
            ModelInfo.DEFAULT_TIMEOUT_SECONDS);

    private final DocumentService documentService;

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
        if (spec == null) {
            return Optional.empty();
        }
        return Optional.of(buildInfo(provider, modelName, spec));
    }

    /** Same as {@link #lookup}, with a conservative WARN-on-miss fallback. */
    public ModelInfo lookupOrDefault(
            @Nullable String tenantId, @Nullable String projectId,
            String provider, String modelName) {
        return lookup(tenantId, projectId, provider, modelName)
                .orElseGet(() -> fallback(provider, modelName));
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
        ClassPathResource resource = new ClassPathResource(BUNDLED_RESOURCE);
        if (!resource.exists()) {
            log.warn("ModelCatalog: bundled '{}' not found on classpath — catalog will be empty",
                    BUNDLED_RESOURCE);
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parseYaml(content, "classpath:" + BUNDLED_RESOURCE);
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

    private static ModelInfo buildInfo(String provider, String modelName, Map<String, Object> spec) {
        int ctx = readInt(spec.get("contextWindowTokens"),
                FALLBACK_TEMPLATE.contextWindowTokens());
        int out = readInt(spec.get("defaultMaxOutputTokens"),
                FALLBACK_TEMPLATE.defaultMaxOutputTokens());
        ModelSize size = readSize(spec.get("size"), provider, modelName);
        Set<ModelCapability> caps = readCapabilities(spec.get("capabilities"), provider, modelName);
        int timeout = readInt(spec.get("timeoutSeconds"),
                FALLBACK_TEMPLATE.timeoutSeconds());
        return new ModelInfo(provider, modelName, ctx, out, size, caps, timeout);
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
                FALLBACK_TEMPLATE.timeoutSeconds());
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
