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
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Renders the source catalog for a tenant/project scope as a single
 * Markdown block. Three sections — Manuals, Skills, Tools — each
 * sorted alphabetically so the same set of sources always yields the
 * same hash. The DiscoveryService hands this block to the
 * {@code how-do-i} recipe via Pebble variable {@code {{ sources }}}.
 *
 * <p>Pure render — no Mongo writes, no caching. The
 * {@link SourceCatalogService} owns the cache layer on top.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SourceCatalogBuilder {

    private static final String MANUALS_PREFIX = "manuals/";
    private static final String MD_SUFFIX = ".md";

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
        appendManuals(md, tenantId, projectId);
        appendSkills(md, tenantId, projectId);
        appendTools(md);
        String text = md.toString();
        return new CatalogSnapshot(text, sha256(text));
    }

    // ──────────────────── Manuals ────────────────────

    private void appendManuals(StringBuilder md, String tenantId, @Nullable String projectId) {
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
            md.append("### ").append(name).append("\n\n")
                    .append(content.trim()).append("\n\n");
        }
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

    private void appendSkills(StringBuilder md, String tenantId, @Nullable String projectId) {
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

    private void appendTools(StringBuilder md) {
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
