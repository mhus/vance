package de.mhus.vance.brain.delegate;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads {@code vance-defaults/catalog/engines.yaml} once at boot and
 * exposes the parsed engine descriptions to the recipe selector.
 * The catalog is the curated "what does each engine do" reference
 * that the {@code process_create_delegate} prompt embeds when asking
 * the LLM to pick a recipe.
 *
 * <p>Catalog entries are positional in YAML; this loader keeps
 * insertion order so the prompt presentation is stable.
 *
 * <p>Failures during boot are non-fatal: if the YAML is malformed
 * we log and ship an empty catalog. The selector will degrade to
 * recipe-only matching without engine context.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EngineCatalog {

    static final String CATALOG_RESOURCE = "vance-defaults/catalog/engines.yaml";

    @Getter
    private List<EngineEntry> entries = List.of();

    @PostConstruct
    void load() {
        reload();
    }

    /**
     * Re-parses the bundled catalog resource. Idempotent and
     * thread-safe: the {@link #entries} reference is replaced
     * atomically with a freshly built list so concurrent
     * {@link #renderForPrompt} calls either see the old list or
     * the new one — never a half-written one.
     *
     * <p>Triggered automatically at boot (via {@link PostConstruct})
     * and on demand via the admin REST endpoint
     * {@code POST /brain/{tenant}/admin/catalog/engines/reload}.
     * The reload re-reads the same classpath resource — useful
     * during development or after a re-deploy that refreshed the
     * yaml without restarting the JVM.
     *
     * @return the number of engine entries the parsed catalog now
     *         carries (zero on parse failure — the previous list
     *         is then retained).
     */
    public synchronized int reload() {
        ClassPathResource res = new ClassPathResource(CATALOG_RESOURCE);
        if (!res.exists()) {
            log.warn("EngineCatalog: bundled resource '{}' not found",
                    CATALOG_RESOURCE);
            return entries.size();
        }
        try (InputStream in = res.getInputStream()) {
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<EngineEntry> parsed = parse(raw);
            entries = parsed;
            log.info("EngineCatalog: loaded {} engine descriptions",
                    parsed.size());
            return parsed.size();
        } catch (IOException | RuntimeException e) {
            log.warn("EngineCatalog: failed to parse '{}' — keeping previous {} entries: {}",
                    CATALOG_RESOURCE, entries.size(), e.toString());
            return entries.size();
        }
    }

    /**
     * Renders the catalog as a markdown bullet list — used directly in
     * the selector LLM prompt. Empty catalog yields an empty string;
     * the selector then matches on recipe descriptions alone.
     */
    public String renderForPrompt() {
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (EngineEntry e : entries) {
            sb.append("- **").append(e.name()).append("** — ")
                    .append(e.purpose()).append("\n");
            if (!e.whenToUse().isBlank()) {
                sb.append("    when_to_use: ").append(e.whenToUse()).append("\n");
            }
            if (!e.notFor().isBlank()) {
                sb.append("    not_for: ").append(e.notFor()).append("\n");
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<EngineEntry> parse(String raw) {
        Object loaded = new Yaml().load(raw);
        if (!(loaded instanceof Map<?, ?> top)) return List.of();
        Object enginesRaw = top.get("engines");
        if (!(enginesRaw instanceof Map<?, ?> enginesMap)) return List.of();
        List<EngineEntry> out = new ArrayList<>();
        // LinkedHashMap from snakeyaml preserves YAML order — important
        // so the prompt presentation matches the file.
        for (Map.Entry<?, ?> entry : new LinkedHashMap<Object, Object>(
                (Map<Object, Object>) enginesMap).entrySet()) {
            if (!(entry.getKey() instanceof String name)
                    || !(entry.getValue() instanceof Map<?, ?> body)) continue;
            out.add(new EngineEntry(
                    name,
                    stringOrEmpty(body.get("purpose")),
                    stringOrEmpty(body.get("when_to_use")),
                    stringOrEmpty(body.get("not_for"))));
        }
        return out;
    }

    private static String stringOrEmpty(Object v) {
        if (v == null) return "";
        return v.toString().trim().replaceAll("\\s+", " ");
    }

    /**
     * One catalog entry — what an engine is for, when to pick it,
     * and what use-cases to steer away from. All fields are
     * normalised to single-line strings so the renderer can fold
     * them into the prompt without surprise line breaks.
     */
    public record EngineEntry(
            String name,
            String purpose,
            String whenToUse,
            String notFor) {}
}
