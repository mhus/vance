package de.mhus.vance.addon.brain.designer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The design-skill catalogue's contract: only {@code design}-tagged
 * skills, the style.css convention reported per skill, the activation
 * state only claimed when a chat context was given (absent, not false),
 * and the fixed demo body of the style preview.
 */
class DesignerSkillServiceTest {

    private final SkillResolver skillResolver = mock(SkillResolver.class);
    private final DesignerSkillService service = new DesignerSkillService(skillResolver);

    private static ResolvedSkill skill(String name, String title, String tag, SkillScope source) {
        return new ResolvedSkill(
                name,
                title,
                "description of " + name,
                "1.0.0",
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(tag),
                true,
                source);
    }

    @Test
    void list_filtersByDesignTag_sortsByTitle_andJoinsActiveState() {
        when(skillResolver.listAvailable(any()))
                .thenReturn(List.of(
                        skill("zeta", "Zeta Style", "design", SkillScope.PROJECT),
                        skill("plain", "Not a design skill", "css", SkillScope.PROJECT),
                        skill("alpha", "Alpha Style", "design", SkillScope.RESOURCE)));
        when(skillResolver.readSkillFile(
                        any(), argThat((String n) -> "alpha".equals(n)), argThat((String p) -> "style.css".equals(p))))
                .thenReturn(java.util.Optional.of("a {}"));
        when(skillResolver.readSkillFile(
                        any(), argThat((String n) -> "zeta".equals(n)), argThat((String p) -> "style.css".equals(p))))
                .thenReturn(java.util.Optional.empty());

        List<DesignerSkill> out = service.list("acme", "alice", "p1", Map.of("zeta", true));

        assertThat(out).extracting(DesignerSkill::getName).containsExactly("alpha", "zeta");
        assertThat(out.get(0).isStyle()).isTrue();
        assertThat(out.get(0).getSource()).isEqualTo("resource");
        assertThat(out.get(0).getActive()).isFalse();
        assertThat(out.get(0).getFromRecipe()).isNull();
        assertThat(out.get(1).isStyle()).isFalse();
        assertThat(out.get(1).getActive()).isTrue();
        // The SkillPanel parity: a recipe-bound skill cannot be cleared,
        // so the row must say who bound it.
        assertThat(out.get(1).getFromRecipe()).isTrue();
    }

    @Test
    void list_withoutChatContext_leavesActiveAbsentInsteadOfClaimingInactive() {
        when(skillResolver.listAvailable(any()))
                .thenReturn(List.of(skill("alpha", "Alpha Style", "design", SkillScope.RESOURCE)));
        when(skillResolver.readSkillFile(any(), any(), any())).thenReturn(java.util.Optional.of("a {}"));

        List<DesignerSkill> out = service.list("acme", "alice", "p1", null);

        // Absent, not false: without a chat process there is no activation
        // state, and "inactive" would be a claim the server cannot make.
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getActive()).isNull();
    }

    @Test
    void renderPreviewHtml_inlinesTheRealStylesheetAroundTheFixedDemoBody() {
        String html = DesignerSkillService.renderPreviewHtml("body { color: red; }");

        // The stylesheet is the skill's real file, inlined — the preview
        // document is self-contained, no sub-resources for the sandbox to
        // fetch (and nothing to keep in sync with a separate preview.css).
        assertThat(html).contains("body { color: red; }");
        // The same fixed body for every skill: what differs between two
        // previews is the style, never the markup.
        assertThat(html).contains("<h1>Title</h1>");
        assertThat(html).contains("<p>Text</p>");
        assertThat(html).contains("<a href=\"#\">Link</a>");
        assertThat(html).contains("<button type=\"button\">Button</button>");
    }
}
