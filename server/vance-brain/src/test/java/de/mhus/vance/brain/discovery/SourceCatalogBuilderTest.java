package de.mhus.vance.brain.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.api.skills.SkillTriggerType;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the rendering logic in
 * {@link SourceCatalogBuilder}. Mocks DocumentService + SkillResolver
 * and provides stub {@link Tool} beans inline.
 */
class SourceCatalogBuilderTest {

    private static final String TENANT = "acme";

    @Test
    void renders_manuals_section_with_each_manual_as_summary_card() {
        DocumentService documentService = mock(DocumentService.class);
        SkillResolver skillResolver = mock(SkillResolver.class);
        when(skillResolver.listAvailable(any())).thenReturn(List.of());

        Map<String, LookupResult> manuals = new LinkedHashMap<>();
        manuals.put("manuals/getting-started.md",
                new LookupResult("manuals/getting-started.md",
                        "# Getting started\n\nWelcome to Vance.\n",
                        LookupResult.Source.RESOURCE, null));
        manuals.put("manuals/embed-images.md",
                new LookupResult("manuals/embed-images.md",
                        "---\n"
                        + "triggers: image, picture, screenshot, Bild\n"
                        + "summary: How to show a picture in the chat.\n"
                        + "---\n"
                        + "# Embedding — Images\n\n"
                        + "Long body lives below.\n",
                        LookupResult.Source.RESOURCE, null));
        when(documentService.listByPrefixCascade(any(), any(), any()))
                .thenReturn(manuals);

        SourceCatalogBuilder builder = new SourceCatalogBuilder(
                documentService, skillResolver, List.of());
        CatalogSnapshot snapshot = builder.build(TENANT, null);

        assertThat(snapshot.markdown())
                .contains("## Manuals")
                .contains("### embed-images")
                .contains("**Title:** Embedding — Images")
                .contains("**Triggers:** image, picture, screenshot, Bild")
                // summary from front-matter beats first paragraph
                .contains("How to show a picture in the chat.")
                .doesNotContain("Long body lives below")
                .contains("### getting-started")
                .contains("**Title:** Getting started")
                .contains("Welcome to Vance.");
    }

    @Test
    void manual_card_omits_triggers_line_when_absent() {
        DocumentService documentService = mock(DocumentService.class);
        SkillResolver skillResolver = mock(SkillResolver.class);
        when(skillResolver.listAvailable(any())).thenReturn(List.of());

        when(documentService.listByPrefixCascade(any(), any(), any()))
                .thenReturn(Map.of("manuals/plain.md",
                        new LookupResult("manuals/plain.md",
                                "# Plain manual\n\nBody description.\n",
                                LookupResult.Source.RESOURCE, null)));

        SourceCatalogBuilder builder = new SourceCatalogBuilder(
                documentService, skillResolver, List.of());
        String md = builder.build(TENANT, null).markdown();

        assertThat(md)
                .contains("**Title:** Plain manual")
                .contains("Body description.")
                .doesNotContain("**Triggers:**");
    }

    @Test
    void manual_card_falls_back_to_first_paragraph_when_summary_absent() {
        DocumentService documentService = mock(DocumentService.class);
        SkillResolver skillResolver = mock(SkillResolver.class);
        when(skillResolver.listAvailable(any())).thenReturn(List.of());

        // Front-matter has triggers but no summary — the catalog card
        // should fall back to the first body paragraph.
        when(documentService.listByPrefixCascade(any(), any(), any()))
                .thenReturn(Map.of("manuals/with-triggers.md",
                        new LookupResult("manuals/with-triggers.md",
                                "---\ntriggers: foo, bar\n---\n"
                                + "# Title here\n\nFirst paragraph wins.\n\n"
                                + "Second paragraph ignored.\n",
                                LookupResult.Source.RESOURCE, null)));

        SourceCatalogBuilder builder = new SourceCatalogBuilder(
                documentService, skillResolver, List.of());
        String md = builder.build(TENANT, null).markdown();

        assertThat(md)
                .contains("**Triggers:** foo, bar")
                .contains("First paragraph wins.")
                .doesNotContain("Second paragraph");
    }

    @Test
    void manual_card_drops_full_body_paragraphs_beyond_the_first() {
        DocumentService documentService = mock(DocumentService.class);
        SkillResolver skillResolver = mock(SkillResolver.class);
        when(skillResolver.listAvailable(any())).thenReturn(List.of());

        String longBody = "# Big manual\n\nOpening paragraph.\n\n"
                + "## Section\n\nLots of detail here that should not "
                + "appear in the catalog summary card.\n";
        when(documentService.listByPrefixCascade(any(), any(), any()))
                .thenReturn(Map.of("manuals/big.md",
                        new LookupResult("manuals/big.md", longBody,
                                LookupResult.Source.RESOURCE, null)));

        SourceCatalogBuilder builder = new SourceCatalogBuilder(
                documentService, skillResolver, List.of());
        String md = builder.build(TENANT, null).markdown();

        assertThat(md)
                .contains("Opening paragraph.")
                .doesNotContain("Lots of detail")
                .doesNotContain("## Section");
    }

    @Test
    void sorts_manuals_alphabetically_for_stable_hash() {
        DocumentService documentService = mock(DocumentService.class);
        SkillResolver skillResolver = mock(SkillResolver.class);
        when(skillResolver.listAvailable(any())).thenReturn(List.of());

        Map<String, LookupResult> shuffled = new LinkedHashMap<>();
        shuffled.put("manuals/zeta.md",
                new LookupResult("manuals/zeta.md", "z", LookupResult.Source.RESOURCE, null));
        shuffled.put("manuals/alpha.md",
                new LookupResult("manuals/alpha.md", "a", LookupResult.Source.RESOURCE, null));
        when(documentService.listByPrefixCascade(any(), any(), any())).thenReturn(shuffled);

        SourceCatalogBuilder builder = new SourceCatalogBuilder(
                documentService, skillResolver, List.of());
        String md = builder.build(TENANT, null).markdown();

        int alphaIdx = md.indexOf("### alpha");
        int zetaIdx = md.indexOf("### zeta");
        assertThat(alphaIdx).isGreaterThan(-1);
        assertThat(zetaIdx).isGreaterThan(alphaIdx);
    }

    @Test
    void renders_skills_section_with_triggers_joined() {
        DocumentService documentService = mock(DocumentService.class);
        when(documentService.listByPrefixCascade(any(), any(), any())).thenReturn(Map.of());

        SkillResolver skillResolver = mock(SkillResolver.class);
        ResolvedSkill skill = new ResolvedSkill(
                "synthesis",
                "Synthesis",
                "Aggregate multiple sources into a coherent picture.",
                "1.0.0",
                List.of(new ResolvedSkill.Trigger(
                        SkillTriggerType.KEYWORDS, null,
                        List.of("synthesis", "synthese", "aggregate"))),
                "prompt extension",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true,
                SkillScope.RESOURCE);
        when(skillResolver.listAvailable(any())).thenReturn(List.of(skill));

        SourceCatalogBuilder builder = new SourceCatalogBuilder(
                documentService, skillResolver, List.of());
        String md = builder.build(TENANT, null).markdown();

        assertThat(md)
                .contains("## Skills")
                .contains("### synthesis")
                .contains("**Title:** Synthesis")
                .contains("Aggregate multiple sources")
                .contains("**Triggers:** synthesis, synthese, aggregate");
    }

    @Test
    void filters_disabled_skills() {
        DocumentService documentService = mock(DocumentService.class);
        when(documentService.listByPrefixCascade(any(), any(), any())).thenReturn(Map.of());

        SkillResolver skillResolver = mock(SkillResolver.class);
        ResolvedSkill disabledSkill = new ResolvedSkill(
                "draft",
                "Draft",
                "desc",
                "1",
                List.of(),
                null, List.of(), List.of(), List.of(), List.of(),
                List.of(),
                false,           // enabled=false
                SkillScope.RESOURCE);
        when(skillResolver.listAvailable(any())).thenReturn(List.of(disabledSkill));

        SourceCatalogBuilder builder = new SourceCatalogBuilder(
                documentService, skillResolver, List.of());
        String md = builder.build(TENANT, null).markdown();

        assertThat(md).doesNotContain("## Skills");
        assertThat(md).doesNotContain("draft");
    }

    @Test
    void renders_tools_section_only_for_primary_tools() {
        DocumentService documentService = mock(DocumentService.class);
        when(documentService.listByPrefixCascade(any(), any(), any())).thenReturn(Map.of());
        SkillResolver skillResolver = mock(SkillResolver.class);
        when(skillResolver.listAvailable(any())).thenReturn(List.of());

        Tool primaryTool = stubTool("web_search", "Search the web.", true);
        Tool helperTool = stubTool("manual_read", "Read a manual.", false);

        SourceCatalogBuilder builder = new SourceCatalogBuilder(
                documentService, skillResolver, List.of(primaryTool, helperTool));
        String md = builder.build(TENANT, null).markdown();

        assertThat(md)
                .contains("## Tools")
                .contains("### web_search")
                .contains("Search the web.")
                .doesNotContain("### manual_read");
    }

    @Test
    void identical_inputs_produce_identical_hash() {
        DocumentService documentService = mock(DocumentService.class);
        SkillResolver skillResolver = mock(SkillResolver.class);
        when(skillResolver.listAvailable(any())).thenReturn(List.of());
        when(documentService.listByPrefixCascade(any(), any(), any())).thenReturn(
                Map.of("manuals/x.md",
                        new LookupResult("manuals/x.md", "body",
                                LookupResult.Source.RESOURCE, null)));

        SourceCatalogBuilder builder = new SourceCatalogBuilder(
                documentService, skillResolver, List.of());
        String hash1 = builder.build(TENANT, null).contentHash();
        String hash2 = builder.build(TENANT, null).contentHash();

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64);
    }

    @Test
    void empty_sources_produces_empty_markdown() {
        DocumentService documentService = mock(DocumentService.class);
        when(documentService.listByPrefixCascade(any(), any(), any())).thenReturn(Map.of());
        SkillResolver skillResolver = mock(SkillResolver.class);
        when(skillResolver.listAvailable(any())).thenReturn(List.of());

        SourceCatalogBuilder builder = new SourceCatalogBuilder(
                documentService, skillResolver, List.of());
        CatalogSnapshot snap = builder.build(TENANT, null);

        assertThat(snap.markdown()).isEmpty();
        assertThat(snap.contentHash()).hasSize(64);
    }

    // ──────────────────── Helpers ────────────────────

    private static Tool stubTool(String name, String description, boolean primary) {
        return new Tool() {
            @Override
            public String name() { return name; }
            @Override
            public String description() { return description; }
            @Override
            public boolean primary() { return primary; }
            @Override
            public Map<String, Object> paramsSchema() { return Map.of(); }
            @Override
            public Set<String> labels() { return Set.of(); }
            @Override
            public Map<String, Object> invoke(
                    Map<String, Object> params, ToolInvocationContext ctx) {
                return Map.of();
            }
        };
    }
}
