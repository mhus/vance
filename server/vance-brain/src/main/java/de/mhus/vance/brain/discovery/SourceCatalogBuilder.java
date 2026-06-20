package de.mhus.vance.brain.discovery;

import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillScopeContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.toolpack.Tool;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Renders the source catalog for a tenant/project scope as a single
 * Markdown block. Three sections — Manuals, Skills, Tools — each
 * sorted alphabetically so the same set of sources always yields the
 * same hash. The DiscoveryService hands this block to the
 * {@code how-do-i} recipe via Pebble variable {@code {{ sources }}}.
 *
 * <p>Manuals are rendered as <em>summary cards</em> rather than full
 * bodies — the LLM picks a name from the catalog and the caller
 * loads the body via {@code manual_read('<name>')}. Card layout:
 * H1 title (from the body), plus {@code triggers} and {@code summary}
 * if present in the YAML front-matter:
 *
 * <pre>
 * ---
 * triggers: image, picture, screenshot, Bild
 * summary: How to show a picture in the chat.
 * ---
 * # Embedding — Images
 *
 * Full body lives below; only title + triggers + summary make it
 * into the catalog card.
 * </pre>
 *
 * <p>When {@code summary} is absent, the first prose paragraph after
 * the H1 serves as a fallback description (capped at ~400 chars).
 * Cuts catalog size from ~150 KB to ~5 KB without losing routing
 * signal.
 *
 * <p>Pure render — no Mongo writes, no caching. The
 * {@link SourceCatalogService} owns the cache layer on top.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SourceCatalogBuilder {

    private static final String MANUALS_PREFIX = "_vance/manuals/";
    private static final String MD_SUFFIX = ".md";

    private static final java.util.regex.Pattern H1_LINE =
            java.util.regex.Pattern.compile("(?m)^#\\s+(?<title>.+?)\\s*$");
    private static final String FRONTMATTER_FENCE = "---";

    private final DocumentService documentService;
    private final SkillResolver skillResolver;
    private final List<Tool> tools;

    /**
     * Build a snapshot for {@code tenantId} / {@code projectId}. Both
     * are passed verbatim to {@link DocumentService#listByPrefixCascade}
     * and {@link SkillResolver#listAvailable} — the cascade behaviour
     * is exactly what those services already enforce.
     */
    public CatalogSnapshot build(String tenantId, @Nullable String projectId) {
        StringBuilder md = new StringBuilder(8192);
        Map<String, CatalogSnapshot.EntrySpec> entries = new LinkedHashMap<>();
        appendManuals(md, entries, tenantId, projectId);
        appendSkills(md, entries, tenantId, projectId);
        appendTools(md, entries);
        String text = md.toString();
        return new CatalogSnapshot(text, sha256(text), entries);
    }

    // ──────────────────── Manuals ────────────────────

    private void appendManuals(
            StringBuilder md,
            Map<String, CatalogSnapshot.EntrySpec> metaOut,
            String tenantId,
            @Nullable String projectId) {
        Map<String, LookupResult> hits = documentService.listByPrefixCascade(
                tenantId, projectId == null ? "" : projectId, MANUALS_PREFIX);
        if (hits.isEmpty()) return;
        // Sort by path so successive builds with identical inputs produce
        // the same hash — Markdown order is stable, cache stays warm.
        List<Map.Entry<String, LookupResult>> entries = new ArrayList<>(hits.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));

        md.append("## Manuals\n\n");
        for (Map.Entry<String, LookupResult> e : entries) {
            String path = e.getKey();
            if (!path.endsWith(MD_SUFFIX)) continue;
            String name = manualName(path);
            if (name.isBlank()) continue;
            String content = e.getValue().content();
            if (content == null || content.isBlank()) continue;
            FrontMatter fm = parseFrontMatter(content);
            md.append("### ").append(name).append("\n\n")
                    .append(renderManualCard(fm)).append("\n");
            metaOut.put(name, new CatalogSnapshot.EntrySpec(
                    "manual", parseRequiresTools(fm.values.get("requires-tools"))));
        }
    }

    /**
     * Parse the {@code requires-tools} header value — a comma-separated
     * list of tool names. Empty / null / missing → empty set (no
     * requirement). Used by {@link CatalogFilter} to drop manuals whose
     * required tools aren't in the calling engine's allow-set.
     */
    static Set<String> parseRequiresTools(@Nullable Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * Build the per-manual summary card the catalog ships instead of
     * the full body. Three pieces:
     *
     * <ul>
     *   <li>{@code **Title:**} — the H1 (first {@code # …} line in
     *       the body, after any front-matter). Falls back to the
     *       manual name when no H1 is present.</li>
     *   <li>{@code **Triggers:**} — comma-separated trigger string
     *       from the YAML front-matter key {@code triggers}.
     *       Optional; omitted when absent.</li>
     *   <li>Description — front-matter {@code summary} when present;
     *       otherwise the first non-empty prose paragraph after the
     *       H1 (capped at ~400 chars).</li>
     * </ul>
     */
    static String renderManualCard(String content) {
        return renderManualCard(parseFrontMatter(content));
    }

    static String renderManualCard(FrontMatter fm) {
        var h1Matcher = H1_LINE.matcher(fm.body);
        String title = h1Matcher.find() ? h1Matcher.group("title").trim() : "";

        String triggers = stringOf(fm.values.get("triggers"));
        String summary = stringOf(fm.values.get("summary"));

        String description = !summary.isEmpty()
                ? summary
                : extractFirstParagraph(fm.body);

        StringBuilder out = new StringBuilder();
        if (!title.isEmpty()) {
            out.append("**Title:** ").append(title).append("\n\n");
        }
        if (!triggers.isEmpty()) {
            out.append("**Triggers:** ").append(triggers).append("\n\n");
        }
        if (!description.isEmpty()) {
            out.append(description).append("\n\n");
        }
        return out.toString();
    }

    /**
     * Result of parsing the optional YAML front-matter at the top of
     * a manual. {@code values} contains the scalar keys we care about
     * (currently {@code triggers}, {@code summary}); {@code body} is
     * the markdown body with the front-matter stripped off.
     */
    private record FrontMatter(Map<String, Object> values, String body) {}

    /**
     * Detect and parse a leading {@code ---}-fenced YAML block. Lenient:
     * a missing fence, unterminated fence, or invalid YAML all yield an
     * empty values map plus the body unchanged — front-matter is
     * never load-bearing for the catalog, just enrichment.
     */
    private static FrontMatter parseFrontMatter(String content) {
        String trimmed = content.trim();
        String[] lines = trimmed.split("\\R", -1);
        if (lines.length < 2 || !FRONTMATTER_FENCE.equals(lines[0].trim())) {
            return new FrontMatter(Map.of(), trimmed);
        }
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if (FRONTMATTER_FENCE.equals(lines[i].trim())) { end = i; break; }
        }
        if (end < 0) {
            return new FrontMatter(Map.of(), trimmed);
        }
        StringBuilder fm = new StringBuilder();
        for (int i = 1; i < end; i++) fm.append(lines[i]).append('\n');
        Map<String, Object> parsed;
        try {
            LoaderOptions opts = new LoaderOptions();
            opts.setAllowDuplicateKeys(false);
            Yaml yaml = new Yaml(new SafeConstructor(opts));
            Object root = yaml.load(fm.toString());
            if (root instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() instanceof String k) out.put(k, e.getValue());
                }
                parsed = out;
            } else {
                parsed = Map.of();
            }
        } catch (RuntimeException ignored) {
            parsed = Map.of();
        }
        StringBuilder body = new StringBuilder();
        for (int i = end + 1; i < lines.length; i++) {
            body.append(lines[i]);
            if (i < lines.length - 1) body.append('\n');
        }
        return new FrontMatter(parsed, body.toString().trim());
    }

    private static String stringOf(@Nullable Object v) {
        return v instanceof String s ? s.trim() : "";
    }

    /**
     * Extract the first prose paragraph after the H1. Stops at a blank
     * line, a heading, or a list marker — pure body text only. Capped
     * at ~400 chars to keep catalog cards compact. Used as a fallback
     * when the manual's front-matter doesn't supply an explicit
     * {@code summary}.
     */
    private static String extractFirstParagraph(String content) {
        String[] lines = content.split("\\R", -1);
        boolean pastH1 = false;
        StringBuilder buf = new StringBuilder();
        for (String raw : lines) {
            String line = raw.stripTrailing();
            if (!pastH1) {
                if (line.startsWith("# ")) {
                    pastH1 = true;
                }
                continue;
            }
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                if (buf.length() == 0) continue;
                break;
            }
            // Stop when the paragraph ends in a heading / list / fence /
            // table marker — those aren't part of the opening prose.
            if (stripped.startsWith("#")
                    || stripped.startsWith("- ")
                    || stripped.startsWith("* ")
                    || stripped.startsWith("| ")
                    || stripped.startsWith("```")
                    || stripped.startsWith(">")) {
                if (buf.length() == 0) continue;
                break;
            }
            if (buf.length() > 0) buf.append(' ');
            buf.append(stripped);
            if (buf.length() > 400) break;
        }
        String out = buf.toString();
        if (out.length() > 400) {
            out = out.substring(0, 397).trim() + "…";
        }
        return out;
    }

    private static String manualName(String path) {
        String stem = path.endsWith(MD_SUFFIX)
                ? path.substring(0, path.length() - MD_SUFFIX.length())
                : path;
        // Drop the manuals/ prefix; everything below it is the
        // logical manual name (sub-directories included for grouped
        // families like manuals/eddie/foo).
        if (stem.startsWith(MANUALS_PREFIX)) {
            stem = stem.substring(MANUALS_PREFIX.length());
        }
        return stem;
    }

    // ──────────────────── Skills ────────────────────

    private void appendSkills(
            StringBuilder md,
            Map<String, CatalogSnapshot.EntrySpec> metaOut,
            String tenantId,
            @Nullable String projectId) {
        SkillScopeContext scope = SkillScopeContext.of(tenantId, null, projectId);
        List<ResolvedSkill> available;
        try {
            available = skillResolver.listAvailable(scope);
        } catch (RuntimeException e) {
            log.warn("SourceCatalogBuilder: skill listing failed for tenant '{}': {}",
                    tenantId, e.toString());
            return;
        }
        if (available.isEmpty()) return;

        List<ResolvedSkill> enabled = new ArrayList<>();
        for (ResolvedSkill s : available) {
            if (s.enabled()) enabled.add(s);
        }
        if (enabled.isEmpty()) return;
        enabled.sort(Comparator.comparing(ResolvedSkill::name));

        md.append("## Skills\n\n");
        for (ResolvedSkill s : enabled) {
            md.append("### ").append(s.name()).append("\n\n");
            md.append("**Title:** ").append(safe(s.title())).append("\n\n");
            String description = safe(s.description());
            if (!description.isBlank()) {
                md.append(description).append("\n\n");
            }
            String triggers = formatKeywords(s);
            if (!triggers.isBlank()) {
                md.append("**Triggers:** ").append(triggers).append("\n\n");
            }
            // Skills have their own activation mechanism — no
            // engine-allow-set requirement is enforced here.
            metaOut.put(s.name(), new CatalogSnapshot.EntrySpec("skill", Set.of()));
        }
    }

    private static String formatKeywords(ResolvedSkill skill) {
        if (skill.triggers() == null) return "";
        List<String> all = new ArrayList<>();
        for (ResolvedSkill.Trigger t : skill.triggers()) {
            if (t.keywords() == null) continue;
            for (String kw : t.keywords()) {
                if (kw != null && !kw.isBlank()) all.add(kw.trim());
            }
        }
        return String.join(", ", all);
    }

    // ──────────────────── Tools ────────────────────

    private void appendTools(
            StringBuilder md, Map<String, CatalogSnapshot.EntrySpec> metaOut) {
        if (tools == null || tools.isEmpty()) return;
        List<Tool> primary = new ArrayList<>();
        for (Tool t : tools) {
            if (t == null) continue;
            if (!t.primary()) continue;
            primary.add(t);
        }
        if (primary.isEmpty()) return;
        primary.sort(Comparator.comparing(Tool::name));

        md.append("## Tools\n\n");
        for (Tool t : primary) {
            md.append("### ").append(t.name()).append("\n\n");
            String description = safe(t.description());
            if (!description.isBlank()) {
                md.append(description.trim()).append("\n\n");
            }
            // A tool entry trivially "requires" itself — CatalogFilter
            // drops it when the engine's allow-set doesn't carry the name.
            metaOut.put(t.name(),
                    new CatalogSnapshot.EntrySpec("tool", Set.of(t.name())));
        }
    }

    // ──────────────────── Helpers ────────────────────

    private static String safe(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // Every JRE ships SHA-256 — this branch is for completeness.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
