package de.mhus.vance.addon.brain.designer;

import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillScopeContext;
import de.mhus.vance.brain.tools.report.CssSanitizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The design-skill half of the designer app: which skills count as design
 * skills, how their stylesheet is found, and what a style preview shows.
 *
 * <p><b>The convention (manual: "Design skills").</b> A skill tagged
 * {@link #DESIGN_SKILL_TAG} is a design blueprint. To be previewable it
 * carries its house stylesheet as a {@code style.css} sibling of its
 * {@code SKILL.md} — the same file name a design folder uses, read from
 * the cascade tier that carries the skill (never re-cascaded). The preview
 * renders that real file around a fixed demo body; there is no separate
 * preview stylesheet to keep in sync.
 */
@Service
@RequiredArgsConstructor
public class DesignerSkillService {

    /** The tag that marks a skill as a design blueprint. */
    public static final String DESIGN_SKILL_TAG = "design";

    /** The fixed sibling path a design skill's stylesheet lives at. */
    public static final String STYLE_FILE = "style.css";

    private final SkillResolver skillResolver;

    /**
     * Lists the design skills visible in the app's scope, sorted by title.
     * {@code style} reports the style.css convention per skill; the caller
     * owns deciding whether a chat context exists for {@code active}.
     *
     * <p>{@code activeByRecipe} maps each active skill name to whether the
     * recipe (not the user) bound it — the SkillPanel parity that lets the
     * UI disable its clear button. Null means no chat context was given.
     */
    public List<DesignerSkill> list(
            String tenantId, String username, String projectId, @Nullable Map<String, Boolean> activeByRecipe) {
        SkillScopeContext ctx = SkillScopeContext.of(tenantId, username, projectId);
        return skillResolver.listAvailable(ctx).stream()
                .filter(skill -> skill.tags().contains(DESIGN_SKILL_TAG))
                .map(skill -> toDesignerSkill(skill, activeByRecipe, ctx))
                .sorted(Comparator.comparing(s -> s.getTitle() == null ? s.getName() : s.getTitle()))
                .toList();
    }

    /**
     * Reads one design skill's stylesheet — the tier-pinned sibling read,
     * same rule the skill's own reference docs follow. Empty when the skill
     * is unknown, untagged, or carries no {@code style.css}.
     */
    public Optional<String> readStyle(String tenantId, String username, String projectId, String skillName) {
        SkillScopeContext ctx = SkillScopeContext.of(tenantId, username, projectId);
        return skillResolver.readSkillFile(ctx, skillName, STYLE_FILE);
    }

    private DesignerSkill toDesignerSkill(
            ResolvedSkill skill, @Nullable Map<String, Boolean> activeByRecipe, SkillScopeContext ctx) {
        boolean hasStyle =
                skillResolver.readSkillFile(ctx, skill.name(), STYLE_FILE).isPresent();
        boolean active = activeByRecipe != null && activeByRecipe.containsKey(skill.name());
        return DesignerSkill.builder()
                .name(skill.name())
                .title(skill.title() == null || skill.title().isBlank() ? skill.name() : skill.title())
                .description(skill.description())
                .version(skill.version())
                .source(skill.source().name().toLowerCase(Locale.ROOT))
                .style(hasStyle)
                // null = no chat context was given: the field stays absent
                // rather than claiming "inactive" for something that has
                // no activation state without a process.
                .active(active ? Boolean.TRUE : activeByRecipe == null ? null : Boolean.FALSE)
                .fromRecipe(active ? activeByRecipe.get(skill.name()) : null)
                .build();
    }

    /**
     * Wraps a skill's stylesheet around the fixed demo body — the
     * catalogue's comparable rendering. Same content for every skill, so
     * what differs in the small preview is the style, never the markup.
     *
     * <p>Server-rendered on purpose: the demo body has one authority, and
     * the preview iframe gets a self-contained document — no sub-resources,
     * hence no auth dance inside the sandbox.
     *
     * <p><b>The stylesheet is untrusted input.</b> A style.css is a
     * project/user-authored document, and this method's output is served
     * as {@code text/html} — so the CSS goes through the same pipeline the
     * chat themes use ({@link CssSanitizer}: no external {@code @import},
     * no {@code url()}/schemes, no expression vectors; self-containment is
     * the preview's contract anyway), and every {@code </style} occurrence
     * is backslash-escaped afterwards: the HTML tokenizer would otherwise
     * hand everything after an injected close tag to the parser as live
     * markup. The escaped form is inert CSS garbage, so a clean stylesheet
     * renders unchanged and the promise above holds — what differs between
     * two previews is the style, never the markup.
     */
    public static String renderPreviewHtml(String css) {
        String safe = escapeStyleClose(CssSanitizer.sanitize(css));
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                %s
                </style>
                </head>
                <body>
                <h1>Title</h1>
                <p>Text</p>
                <p><a href="#">Link</a></p>
                <p><button type="button">Button</button></p>
                </body>
                </html>
                """.formatted(safe);
    }

    /**
     * Neutralises every {@code </style} in inlined CSS for the HTML
     * tokenizer: {@code <\/style} is the standard escape (a backslash
     * before a non-hex character is a valid CSS escape outside strings,
     * and the tokenizer no longer sees a close tag). The document's own
     * closing tag is written by the template, outside this input.
     */
    static String escapeStyleClose(String css) {
        return css.replaceAll("(?i)</style", "<\\\\/style");
    }
}
