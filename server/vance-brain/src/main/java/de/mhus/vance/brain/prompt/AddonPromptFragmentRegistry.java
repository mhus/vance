package de.mhus.vance.brain.prompt;

import de.mhus.vance.shared.document.DocumentService;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * Boot-time registry of addon-supplied prompt fragments. Each addon JAR
 * may ship one Markdown file per engine under
 * {@code src/main/resources/vance-defaults/_vance/prompts/<engine>/<addon-id>.md}.
 * Files are raw Pebble templates that get injected into the engine's
 * system prompt at render time — see {@code SystemPrompts.compose} and
 * the {@code addonSections} variable exposed by {@link PromptContextBuilder}.
 *
 * <p>The scan runs once at {@code @PostConstruct} via Spring's
 * {@link PathMatchingResourcePatternResolver}. Results are cached in
 * memory; the classpath is fixed after JVM start, so a single scan is
 * enough and the per-turn render path stays allocation-free.
 *
 * <p>Per engine, fragments are sorted alphabetically by {@code addonId}
 * for deterministic render output. Templates are pre-validated with
 * {@link PromptTemplateRenderer#compile} during scan — a broken
 * fragment fails the brain boot (fail-fast) rather than blowing up on
 * first turn for an unrelated tenant.
 *
 * <p>Out of scope for v1: runtime additions, per-tenant overrides via
 * the document cascade, and {@code addonService.listEnabled()} gating —
 * installing or removing an addon means adding/removing the JAR on the
 * classpath and restarting the brain. The matching {@code AddonService}
 * Mongo row toggles UI registration, not prompt-fragment inclusion.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AddonPromptFragmentRegistry {

    /**
     * Classpath pattern: matches any {@code <engine>/<file>.md} directly
     * under {@code vance-defaults/_vance/prompts/}. The legacy engine-
     * default prompts ({@code arthur-prompt.md}, {@code eddie-prompt.md},
     * …) sit one level higher in {@code prompts/} itself and are NOT
     * matched — they remain owned by their engine.
     */
    static final String SCAN_PATTERN =
            "classpath*:" + DocumentService.RESOURCE_PREFIX
                    + "_vance/prompts/*/*.md";

    private final PromptTemplateRenderer templateRenderer;

    private Map<String, List<AddonPromptFragment>> byEngine = Map.of();

    @PostConstruct
    void scan() {
        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver(getClass().getClassLoader());
        Resource[] resources;
        try {
            resources = resolver.getResources(SCAN_PATTERN);
        } catch (IOException e) {
            // A failed classpath scan would leave the brain blind to all
            // addon fragments — log loudly but don't crash, since core
            // engine prompts still work without them.
            log.error("AddonPromptFragmentRegistry: scan failed for '{}': {}",
                    SCAN_PATTERN, e.toString());
            this.byEngine = Map.of();
            return;
        }

        Map<String, Map<String, AddonPromptFragment>> grouped = new LinkedHashMap<>();
        for (Resource resource : resources) {
            if (!resource.isReadable()) continue;
            ParsedKey key = parseKey(resource);
            if (key == null) continue;
            String template = readUtf8(resource, key.sourcePath());
            if (template == null) continue;
            try {
                templateRenderer.compile(template);
            } catch (PromptTemplateException e) {
                throw new IllegalStateException(
                        "Addon prompt fragment failed Pebble compile: "
                                + key.sourcePath() + " — " + e.getMessage(), e);
            }
            AddonPromptFragment fragment = new AddonPromptFragment(
                    key.addonId(), key.engine(), key.sourcePath(), template);
            Map<String, AddonPromptFragment> forEngine = grouped.computeIfAbsent(
                    key.engine(), k -> new LinkedHashMap<>());
            AddonPromptFragment existing = forEngine.put(key.addonId(), fragment);
            if (existing != null) {
                // Two addon JARs ship the same <engine>/<addonId>.md —
                // possible on a misconfigured deploy. Keep the later one
                // (deterministic by classloader order isn't worth promising)
                // and warn so the duplicate gets noticed.
                log.warn("AddonPromptFragmentRegistry: duplicate fragment '{}' "
                                + "for engine '{}' — replacing {} with {}",
                        key.addonId(), key.engine(),
                        existing.sourcePath(), fragment.sourcePath());
            }
        }

        Map<String, List<AddonPromptFragment>> finalMap = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, AddonPromptFragment>> e : grouped.entrySet()) {
            List<AddonPromptFragment> sorted = new ArrayList<>(e.getValue().values());
            sorted.sort(Comparator.comparing(AddonPromptFragment::addonId));
            finalMap.put(e.getKey(), List.copyOf(sorted));
        }
        this.byEngine = Map.copyOf(finalMap);

        if (!finalMap.isEmpty()) {
            int total = finalMap.values().stream().mapToInt(List::size).sum();
            log.info("AddonPromptFragmentRegistry loaded {} fragments across {} engines: {}",
                    total, finalMap.size(), finalMap.keySet());
        }
    }

    /**
     * Fragments registered for {@code engineName}, in stable alphabetical
     * order by {@code addonId}. Empty list when no addon ships a fragment
     * for that engine — callers treat this as the steady-state default
     * (no addon injections).
     */
    public List<AddonPromptFragment> getFragments(@Nullable String engineName) {
        if (engineName == null || engineName.isBlank()) return List.of();
        return byEngine.getOrDefault(engineName, List.of());
    }

    /**
     * Engines that have at least one fragment registered. Intended for
     * diagnostics / admin views, not for hot-path render code.
     */
    public Set<String> enginesWithFragments() {
        return byEngine.keySet();
    }

    /**
     * Renders every fragment registered for {@code engineName} against
     * {@code ctx} and joins the non-blank results with a blank-line
     * separator. Returns an empty string when no fragments are
     * registered or all of them rendered blank (e.g. a Pebble
     * {@code {% if %}} that didn't match the current tier/provider).
     *
     * <p>Designed as the one call sites need before handing the result
     * to {@link de.mhus.vance.brain.thinkengine.SystemPrompts#compose}'s
     * {@code addonSections} parameter.
     */
    public String renderAndJoin(
            @Nullable String engineName,
            Map<String, Object> ctx,
            PromptTemplateRenderer renderer) {
        List<AddonPromptFragment> fragments = getFragments(engineName);
        if (fragments.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (AddonPromptFragment fragment : fragments) {
            String rendered = renderer.render(fragment.template(), ctx);
            if (rendered == null || rendered.isBlank()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(rendered);
        }
        return sb.toString();
    }

    private static @Nullable ParsedKey parseKey(Resource resource) {
        String uri;
        try {
            uri = resource.getURI().toString();
        } catch (IOException e) {
            log.warn("AddonPromptFragmentRegistry: cannot read URI for resource: {}",
                    e.toString());
            return null;
        }
        // Expected URI tail: …/vance-defaults/_vance/prompts/<engine>/<file>.md
        String prefix = DocumentService.RESOURCE_PREFIX + "_vance/prompts/";
        int idx = uri.indexOf(prefix);
        if (idx < 0) return null;
        String tail = uri.substring(idx + prefix.length());
        int slash = tail.indexOf('/');
        if (slash <= 0 || slash >= tail.length() - 1) return null;
        String engine = tail.substring(0, slash);
        String filename = tail.substring(slash + 1);
        if (!filename.endsWith(".md") || filename.length() <= 3) return null;
        if (filename.contains("/")) return null;
        String addonId = filename.substring(0, filename.length() - 3);
        if (engine.isBlank() || addonId.isBlank()) return null;
        return new ParsedKey(engine, addonId, prefix + engine + "/" + filename);
    }

    private static @Nullable String readUtf8(Resource resource, String pathForDiag) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("AddonPromptFragmentRegistry: failed to read '{}': {}",
                    pathForDiag, e.toString());
            return null;
        }
    }

    private record ParsedKey(String engine, String addonId, String sourcePath) {}
}
