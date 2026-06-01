package de.mhus.vance.brain.agrajag;

import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import de.mhus.vance.brain.agrajag.ToolErrorPattern.HealthAction;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads tool-error pattern rules through the document cascade
 * (project → tenant → bundled) and exposes the merged ordered list to
 * the {@code AgrajagChecker}.
 *
 * <p>Per spec {@code specification/agrajag-engine.md} §4.2: rules in a
 * closer scope completely replace bundled entries with the same
 * {@code id} (no field-merge); new ids in the closer scope are
 * prepended in front of the catch-all fallback.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolErrorPatternResolver {

    public static final String BUNDLED_RESOURCE = "vance-defaults/_vance/agrajag/error-patterns.yaml";
    public static final String DOCUMENT_PATH = "_vance/agrajag/error-patterns.yaml";

    private final DocumentService documentService;

    /** Loaded once at boot; refresh on demand is a later concern. */
    private volatile List<ToolErrorPattern> bundled = List.of();

    @PostConstruct
    void loadBundled() {
        bundled = parseClasspath(BUNDLED_RESOURCE);
        log.info("ToolErrorPatternResolver: loaded {} bundled pattern(s)", bundled.size());
    }

    /**
     * Resolved pattern list for a tenant/project scope. The first
     * pattern that matches an error wins.
     */
    public List<ToolErrorPattern> resolve(String tenantId, @Nullable String projectId) {
        List<ToolErrorPattern> tenantLayer = loadDocumentLayer(
                tenantId, HomeBootstrapService.TENANT_PROJECT_NAME);
        List<ToolErrorPattern> projectLayer = projectId == null
                ? List.of()
                : loadDocumentLayer(tenantId, projectId);
        return merge(bundled, tenantLayer, projectLayer);
    }

    /** Reload bundled patterns from the classpath — used by tests. */
    public void reloadBundledForTest() {
        loadBundled();
    }

    // ───────────────────────────────── Document layer

    private List<ToolErrorPattern> loadDocumentLayer(String tenantId, String projectId) {
        try {
            return documentService.findByPath(tenantId, projectId, DOCUMENT_PATH)
                    .map(documentService::readContent)
                    .map(this::parseText)
                    .orElse(List.of());
        } catch (RuntimeException e) {
            log.warn("ToolErrorPatternResolver: failed to read '{}/{}' for tenant '{}': {}",
                    projectId, DOCUMENT_PATH, tenantId, e.toString());
            return List.of();
        }
    }

    // ───────────────────────────────── Merge

    /**
     * Cascade merge. The closer layer's entries replace bundled entries
     * with the same id; new ids in the closer layer prepend before the
     * fallback (id={@code fallback}, present in the bundled defaults).
     */
    static List<ToolErrorPattern> merge(
            List<ToolErrorPattern> bundled,
            List<ToolErrorPattern> tenant,
            List<ToolErrorPattern> project) {

        // Start from a LinkedHashMap with bundled rules in their original order.
        LinkedHashMap<String, ToolErrorPattern> ordered = new LinkedHashMap<>();
        for (ToolErrorPattern p : bundled) ordered.put(p.getId(), p);

        applyLayer(ordered, tenant);
        applyLayer(ordered, project);

        return new ArrayList<>(ordered.values());
    }

    private static void applyLayer(
            LinkedHashMap<String, ToolErrorPattern> ordered,
            List<ToolErrorPattern> layer) {
        for (ToolErrorPattern p : layer) {
            if (ordered.containsKey(p.getId())) {
                // Replace in place — keep position.
                ordered.put(p.getId(), p);
            } else {
                // New rule — insert before the catch-all fallback if present,
                // otherwise append.
                if (ordered.containsKey("fallback")) {
                    LinkedHashMap<String, ToolErrorPattern> rebuilt = new LinkedHashMap<>();
                    for (Map.Entry<String, ToolErrorPattern> e : ordered.entrySet()) {
                        if ("fallback".equals(e.getKey())) {
                            rebuilt.put(p.getId(), p);
                        }
                        rebuilt.put(e.getKey(), e.getValue());
                    }
                    ordered.clear();
                    ordered.putAll(rebuilt);
                } else {
                    ordered.put(p.getId(), p);
                }
            }
        }
    }

    // ───────────────────────────────── Parsing

    private List<ToolErrorPattern> parseClasspath(String resource) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                log.error("ToolErrorPatternResolver: bundled resource '{}' missing", resource);
                return List.of();
            }
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parseText(body);
        } catch (Exception e) {
            log.error("ToolErrorPatternResolver: failed to read bundled '{}': {}",
                    resource, e.toString(), e);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    List<ToolErrorPattern> parseText(String yamlBody) {
        if (yamlBody == null || yamlBody.isBlank()) return List.of();
        Object parsed = new Yaml().load(yamlBody);
        if (!(parsed instanceof Map<?, ?> root)) return List.of();
        Object patternsObj = ((Map<String, Object>) root).get("patterns");
        if (!(patternsObj instanceof List<?> list)) return List.of();

        List<ToolErrorPattern> out = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) continue;
            ToolErrorPattern pattern = parseOne((Map<String, Object>) map);
            if (pattern != null) out.add(pattern);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable ToolErrorPattern parseOne(Map<String, Object> map) {
        String id = stringOrNull(map.get("id"));
        if (id == null || id.isBlank()) return null;

        ToolErrorPattern.ToolErrorPatternBuilder b = ToolErrorPattern.builder().id(id);

        Map<String, Object> match = (Map<String, Object>) map.getOrDefault("match", Map.of());

        Object hs = match.get("httpStatus");
        if (hs instanceof Number n) b.httpStatus(n.intValue());

        Object range = match.get("httpStatusRange");
        if (range instanceof List<?> r && r.size() == 2
                && r.get(0) instanceof Number lo && r.get(1) instanceof Number hi) {
            b.httpStatusRange(new int[]{lo.intValue(), hi.intValue()});
        }

        b.exceptionTypes(stringListOrNull(match.get("exceptionType")));
        b.bodyContains(stringListOrNull(match.get("bodyContains")));
        b.errorCodes(stringListOrNull(match.get("errorCode")));

        b.signature(Objects.requireNonNullElse(stringOrNull(map.get("signature")), id));

        String classification = stringOrNull(map.get("classification"));
        b.classification(classification == null
                ? ToolHealthClassification.UNCLEAR
                : ToolHealthClassification.valueOf(classification));

        String cooldownStr = stringOrNull(map.get("cooldown"));
        if (cooldownStr != null) {
            if (cooldownStr.startsWith("header:")) {
                b.cooldown(ToolErrorPattern.COOLDOWN_FROM_RETRY_AFTER);
            } else {
                b.cooldown(Duration.parse(cooldownStr));
            }
        }

        String action = stringOrNull(map.get("healthAction"));
        if (action != null) {
            switch (action.toLowerCase(Locale.ROOT)) {
                case "markunavailable" -> b.healthAction(HealthAction.MARK_UNAVAILABLE);
                case "markdegraded" -> b.healthAction(HealthAction.MARK_DEGRADED);
                default -> b.healthAction(HealthAction.NONE);
            }
        }

        Object locked = map.get("locked");
        b.locked(Boolean.TRUE.equals(locked));

        b.note(stringOrNull(map.get("note")));

        return b.build();
    }

    private static @Nullable String stringOrNull(@Nullable Object v) {
        if (v == null) return null;
        return v.toString();
    }

    @SuppressWarnings("unchecked")
    private static @Nullable List<String> stringListOrNull(@Nullable Object v) {
        if (!(v instanceof List<?> list) || list.isEmpty()) return null;
        List<String> out = new ArrayList<>(list.size());
        for (Object e : list) {
            if (e == null) continue;
            out.add(e.toString());
        }
        return out.isEmpty() ? null : out;
    }

    /** Number of bundled patterns loaded — useful for tests + health-checks. */
    public int bundledCount() {
        return bundled.size();
    }

    /** Snapshot of the bundled patterns — for tests. */
    public List<ToolErrorPattern> bundledForTest() {
        return List.copyOf(bundled);
    }

    /** Reset utility for tests. */
    public Optional<ToolErrorPattern> findBundled(String id) {
        return bundled.stream().filter(p -> p.getId().equals(id)).findFirst();
    }
}
