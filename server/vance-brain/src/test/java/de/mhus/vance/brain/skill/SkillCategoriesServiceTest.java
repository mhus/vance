package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.skills.SkillSummaryDto;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ordering and fail-open semantics of {@link SkillCategoriesService}.
 *
 * <p>The category document is a sort <em>help</em>: absent, incomplete, or
 * malformed are all valid states a tenant can be in, and the skill listing
 * must produce a usable list in each — worst case alphabetical groups with
 * no labels. These tests pin exactly that ladder, plus the
 * documented-then-undocumented-then-none group order the client relies on
 * for grouped rendering.
 */
class SkillCategoriesServiceTest {

    private DocumentService documentService;
    private SkillCategoriesService service;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        service = new SkillCategoriesService(documentService);
    }

    @Test
    void arrange_noDocument_titleOrderAndNoCategories() {
        stubNoCategoryDocument();

        SkillCategoriesService.Arrangement arranged = service.arrange(
                "acme",
                "p-1",
                List.of(
                        skill("web-scrape", "Web Scrape", null),
                        skill("analyze", "Analyze", null),
                        skill("benjy", "Benjy", null)));

        assertThat(arranged.categories()).isEmpty();
        assertThat(arranged.skills())
                .extracting(SkillSummaryDto::getName)
                .containsExactly("analyze", "benjy", "web-scrape");
    }

    @Test
    void arrange_documentedCategories_firstInDocumentOrder() {
        stubCategoryDocument("""
                categories:
                  - id: research
                  - id: coding
                """);

        SkillCategoriesService.Arrangement arranged = service.arrange(
                "acme",
                "p-1",
                List.of(
                        skill("coding", "Coding", "coding"),
                        skill("analyze", "Analyze", "research"),
                        skill("writing", "Writing", "media")));

        // research (rank 0) → coding (rank 1) → media is not in the document,
        // so it lands after the documented ones, alphabetical among its own kind.
        assertThat(arranged.skills())
                .extracting(SkillSummaryDto::getName)
                .containsExactly("analyze", "coding", "writing");
        assertThat(arranged.categories()).extracting(c -> c.getId()).containsExactly("research", "coding");
    }

    @Test
    void arrange_withinGroup_titleOrderApplies() {
        stubCategoryDocument("""
                categories:
                  - id: coding
                """);

        SkillCategoriesService.Arrangement arranged = service.arrange(
                "acme",
                "p-1",
                List.of(
                        skill("code-read", "Code Read", "coding"),
                        skill("app-builder", "App Builder", "coding"),
                        skill("diff-walk", "Diff Walk", "coding")));

        assertThat(arranged.skills())
                .extracting(SkillSummaryDto::getName)
                .containsExactly("app-builder", "code-read", "diff-walk");
    }

    @Test
    void arrange_missingTitle_fallsBackToSkillName() {
        stubCategoryDocument("""
                categories:
                  - id: coding
                """);

        SkillCategoriesService.Arrangement arranged =
                service.arrange("acme", "p-1", List.of(skill("zed", null, "coding"), skill("alpha", null, "coding")));

        assertThat(arranged.skills()).extracting(SkillSummaryDto::getName).containsExactly("alpha", "zed");
    }

    @Test
    void arrange_skillsWithoutCategory_goLast() {
        stubCategoryDocument("""
                categories:
                  - id: coding
                  - id: design
                """);

        SkillCategoriesService.Arrangement arranged = service.arrange(
                "acme",
                "p-1",
                List.of(
                        skill("no-cat", "No Category", null),
                        skill("code-review", "Code Review", "coding"),
                        skill("blueprint", "Blueprint", "design")));

        assertThat(arranged.skills())
                .extracting(SkillSummaryDto::getName)
                .containsExactly("code-review", "blueprint", "no-cat");
    }

    @Test
    void arrange_categoryIdAndTitle_normalisedAndPassedThrough() {
        stubCategoryDocument("""
                categories:
                  - id:   Coding
                    title:
                      en:   Coding
                      de:   Programmierung
                """);

        SkillCategoriesService.Arrangement arranged =
                service.arrange("acme", "p-1", List.of(skill("code-review", "Code Review", "coding")));

        assertThat(arranged.categories()).hasSize(1);
        assertThat(arranged.categories().get(0).getId()).isEqualTo("coding");
        assertThat(arranged.categories().get(0).getTitle())
                .containsEntry("en", "Coding")
                .containsEntry("de", "Programmierung");
    }

    @Test
    void arrange_malformedDocument_fallsBackToAlphabeticalGroups() {
        // 'categories' as a map instead of a list — the whole document is
        // ignored (a half-parsed order would lie about the ordering).
        // Skills still group, alphabetically, like with no document at all.
        stubCategoryDocument("categories: {coding: yes}");

        SkillCategoriesService.Arrangement arranged = service.arrange(
                "acme",
                "p-1",
                List.of(skill("code-review", "Code Review", "coding"), skill("analyze", "Analyze", "research")));

        assertThat(arranged.categories()).isEmpty();
        assertThat(arranged.skills()).extracting(SkillSummaryDto::getName).containsExactly("code-review", "analyze");
    }

    @Test
    void arrange_duplicateCategoryId_failOpen() {
        stubCategoryDocument("""
                categories:
                  - id: coding
                  - id: coding
                """);

        SkillCategoriesService.Arrangement arranged =
                service.arrange("acme", "p-1", List.of(skill("code-review", "Code Review", "coding")));

        // An order in which a key appears twice does not define an order —
        // better plain title order than a silently arbitrary one.
        assertThat(arranged.categories()).isEmpty();
        assertThat(arranged.skills()).extracting(SkillSummaryDto::getName).containsExactly("code-review");
    }

    @Test
    void arrange_blankProjectId_resolvesThroughTenantProject() {
        stubNoCategoryDocument();

        service.arrange("acme", null, List.of());

        // SkillLoader.effectiveProjectId: null → _tenant, same as every
        // skill-level cascade lookup.
        verifyCascadeProject("_tenant");
    }

    // ──────────────────── fixtures ────────────────────

    private static SkillSummaryDto skill(String name, String title, String category) {
        return SkillSummaryDto.builder()
                .name(name)
                .title(title)
                .category(category)
                .build();
    }

    private void verifyCascadeProject(String expectedProjectId) {
        verify(documentService)
                .lookupCascade(eq("acme"), eq(expectedProjectId), eq(SkillCategoriesService.CATEGORY_CONFIG_PATH));
    }

    private void stubNoCategoryDocument() {
        when(documentService.lookupCascade(any(), any(), any())).thenReturn(Optional.empty());
    }

    private void stubCategoryDocument(String yaml) {
        LookupResult hit =
                new LookupResult(SkillCategoriesService.CATEGORY_CONFIG_PATH, yaml, LookupResult.Source.VANCE, null);
        when(documentService.lookupCascade(any(), any(), eq(SkillCategoriesService.CATEGORY_CONFIG_PATH)))
                .thenReturn(Optional.of(hit));
    }
}
