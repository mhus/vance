package de.mhus.vance.foot.session;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.recipe.RecipeCategoryDto;
import de.mhus.vance.api.recipe.RecipeListedDto;
import de.mhus.vance.foot.session.RecipePickerView.Kind;
import de.mhus.vance.foot.session.RecipePickerView.Row;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Row model of the {@code /ui-new} recipe picker — grouping, label
 * fallbacks and filtering are pure, so they are pinned without a
 * Lanterna TextGUI. The window itself is thin rendering on top.
 */
class RecipePickerViewRowsTest {

    @Test
    void defaultFirst_cancelLast_headersBetweenCategories() {
        List<Row> rows = RecipePickerView.buildRows(
                List.of(
                        recipe("arthur", "Arthur — Reactive Chat", "chat", null),
                        recipe("eddie", "Eddie — Personal Hub", "chat", null),
                        recipe("coding", "Coding", "coding", null),
                        recipe("legacy", "Legacy", null, null)),
                List.of(category("chat", "Chat"), category("coding", "Coding")),
                "");

        assertThat(rows)
                .extracting(Row::kind)
                .containsExactly(
                        Kind.DEFAULT,
                        Kind.HEADER,
                        Kind.RECIPE,
                        Kind.RECIPE,
                        Kind.HEADER,
                        Kind.RECIPE,
                        Kind.RECIPE,
                        Kind.CANCEL);
        // One header per category change, not per recipe.
        assertThat(rows.get(1).text()).isEqualTo("── Chat ──");
        assertThat(rows.get(4).text()).isEqualTo("── Coding ──");
        // No header for the trailing no-category entries.
        assertThat(rows.get(6).recipeName()).isEqualTo("legacy");
    }

    @Test
    void categoryLabel_fallsBackFromEnToHumanisedId() {
        List<Row> rows = RecipePickerView.buildRows(
                List.of(
                        recipe("benjy-coding", null, "coding", null),
                        recipe("quick-lookup", null, "no-such-doc", null)),
                List.of(category("coding", null)),
                "");

        // Document without an en title → humanised id.
        assertThat(rows.get(1).text()).isEqualTo("── Coding ──");
        // Category not in the document at all → humanised id as well.
        assertThat(rows.get(3).text()).isEqualTo("── No Such Doc ──");
    }

    @Test
    void filter_matchesTitleAndDescriptionCaseInsensitive() {
        RecipeListedDto described =
                recipe("web-research", "Web Research", "research", "Public-web research\nsecond line");

        List<Row> rows = RecipePickerView.buildRows(
                List.of(recipe("arthur", "Arthur", "chat", "chat orchestrator"), described), List.of(), "  PUBLIC ");

        // Only the description match survives; its group header follows
        // the surviving recipe, the empty chat group vanishes.
        assertThat(rows).extracting(Row::kind).containsExactly(Kind.DEFAULT, Kind.HEADER, Kind.RECIPE, Kind.CANCEL);
        assertThat(rows.get(2).recipeName()).isEqualTo("web-research");
    }

    @Test
    void row_showsTitleNameAndFirstDescriptionLine() {
        RecipeListedDto r = recipe("coding", "Coding", "coding", "\n  Coding worker on the Frankie engine.\n  More.\n");

        List<Row> rows = RecipePickerView.buildRows(List.of(r), List.of(), "");

        assertThat(rows.get(2).text()).isEqualTo("Coding  [coding] — Coding worker on the Frankie engine.");
    }

    @Test
    void noRecipes_defaultAndCancelOnly() {
        List<Row> rows = RecipePickerView.buildRows(List.of(), List.of(), "");

        assertThat(rows).extracting(Row::kind).containsExactly(Kind.DEFAULT, Kind.CANCEL);
    }

    // ──────────────────── fixtures ────────────────────

    private static RecipeListedDto recipe(String name, String title, String category, String description) {
        return RecipeListedDto.builder()
                .name(name)
                .title(title)
                .category(category)
                .description(description)
                .build();
    }

    private static RecipeCategoryDto category(String id, String enTitle) {
        return RecipeCategoryDto.builder()
                .id(id)
                .title(enTitle == null ? null : java.util.Map.of("en", enTitle))
                .build();
    }
}
