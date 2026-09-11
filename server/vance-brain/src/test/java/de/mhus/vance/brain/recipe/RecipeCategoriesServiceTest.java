package de.mhus.vance.brain.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.recipe.RecipeListedDto;
import de.mhus.vance.api.recipe.RecipeListedResponse;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ordering and fail-open semantics of {@link RecipeCategoriesService}.
 *
 * <p>The category document is a sort <em>help</em>: absent, incomplete, or
 * malformed are all valid states a tenant can be in, and the picker must
 * produce a usable list in each — worst case alphabetical groups with no
 * labels. These tests pin exactly that ladder, plus the
 * documented-then-undocumented-then-none group order the client relies on
 * for grouped rendering.
 */
class RecipeCategoriesServiceTest {

    private DocumentService documentService;
    private RecipeCategoriesService service;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        service = new RecipeCategoriesService(documentService);
    }

    @Test
    void arrange_noDocument_titleOrderAndNoCategories() {
        stubNoCategoryDocument();

        RecipeListedResponse response = service.arrange(
                "acme",
                "p-1",
                List.of(
                        recipe("web-research", "Web Research", null),
                        recipe("analyze", "Analyze", null),
                        recipe("benjy", "Benjy", null)));

        assertThat(response.getCategories()).isEmpty();
        assertThat(response.getRecipes())
                .extracting(RecipeListedDto::getName)
                .containsExactly("analyze", "benjy", "web-research");
    }

    @Test
    void arrange_documentedCategories_firstInDocumentOrder() {
        stubCategoryDocument("""
                categories:
                  - id: research
                  - id: coding
                """);

        RecipeListedResponse response = service.arrange(
                "acme",
                "p-1",
                List.of(
                        recipe("coding", "Coding", "coding"),
                        recipe("analyze", "Analyze", "research"),
                        recipe("benjy", "Benjy", "workers")));

        // research (rank 0) → coding (rank 1) → workers is not in the document,
        // so it lands after the documented ones, alphabetical among its own kind.
        assertThat(response.getRecipes())
                .extracting(RecipeListedDto::getName)
                .containsExactly("analyze", "coding", "benjy");
        assertThat(response.getCategories()).extracting(c -> c.getId()).containsExactly("research", "coding");
    }

    @Test
    void arrange_withinGroup_titleOrderApplies() {
        stubCategoryDocument("""
                categories:
                  - id: coding
                """);

        RecipeListedResponse response = service.arrange(
                "acme",
                "p-1",
                List.of(
                        recipe("code-read", "Code Read", "coding"),
                        recipe("app-builder", "App Builder", "coding"),
                        recipe("benjy-coding", "Benjy Coding", "coding")));

        assertThat(response.getRecipes())
                .extracting(RecipeListedDto::getName)
                .containsExactly("app-builder", "benjy-coding", "code-read");
    }

    @Test
    void arrange_missingTitle_fallsBackToRecipeName() {
        stubCategoryDocument("""
                categories:
                  - id: coding
                """);

        RecipeListedResponse response =
                service.arrange("acme", "p-1", List.of(recipe("zed", null, "coding"), recipe("alpha", null, "coding")));

        assertThat(response.getRecipes()).extracting(RecipeListedDto::getName).containsExactly("alpha", "zed");
    }

    @Test
    void arrange_recipesWithoutCategory_goLast() {
        stubCategoryDocument("""
                categories:
                  - id: chat
                  - id: coding
                """);

        RecipeListedResponse response = service.arrange(
                "acme",
                "p-1",
                List.of(
                        recipe("no-cat", "No Category", null),
                        recipe("arthur", "Arthur", "chat"),
                        recipe("coding", "Coding", "coding")));

        assertThat(response.getRecipes())
                .extracting(RecipeListedDto::getName)
                .containsExactly("arthur", "coding", "no-cat");
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

        RecipeListedResponse response = service.arrange("acme", "p-1", List.of(recipe("coding", "Coding", "coding")));

        assertThat(response.getCategories()).hasSize(1);
        assertThat(response.getCategories().get(0).getId()).isEqualTo("coding");
        assertThat(response.getCategories().get(0).getTitle())
                .containsEntry("en", "Coding")
                .containsEntry("de", "Programmierung");
    }

    @Test
    void arrange_malformedDocument_fallsBackToAlphabeticalGroups() {
        // 'categories' as a map instead of a list — the whole document is
        // ignored (a half-parsed order would lie about the ordering).
        // Recipes still group, alphabetically, like with no document at all.
        stubCategoryDocument("categories: {coding: yes}");

        RecipeListedResponse response = service.arrange(
                "acme", "p-1", List.of(recipe("coding", "Coding", "coding"), recipe("analyze", "Analyze", "research")));

        assertThat(response.getCategories()).isEmpty();
        assertThat(response.getRecipes()).extracting(RecipeListedDto::getName).containsExactly("coding", "analyze");
    }

    @Test
    void arrange_duplicateCategoryId_failOpen() {
        stubCategoryDocument("""
                categories:
                  - id: coding
                  - id: coding
                """);

        RecipeListedResponse response = service.arrange("acme", "p-1", List.of(recipe("coding", "Coding", "coding")));

        // An order in which a key appears twice does not define an order —
        // better plain title order than a silently arbitrary one.
        assertThat(response.getCategories()).isEmpty();
        assertThat(response.getRecipes()).extracting(RecipeListedDto::getName).containsExactly("coding");
    }

    // ──────────────────── fixtures ────────────────────

    private static RecipeListedDto recipe(String name, String title, String category) {
        return RecipeListedDto.builder()
                .name(name)
                .title(title)
                .category(category)
                .build();
    }

    private void stubNoCategoryDocument() {
        when(documentService.lookupCascade(any(), any(), any())).thenReturn(Optional.empty());
    }

    private void stubCategoryDocument(String yaml) {
        LookupResult hit =
                new LookupResult(RecipeCategoriesService.CATEGORY_CONFIG_PATH, yaml, LookupResult.Source.VANCE, null);
        when(documentService.lookupCascade(any(), any(), eq(RecipeCategoriesService.CATEGORY_CONFIG_PATH)))
                .thenReturn(Optional.of(hit));
    }
}
