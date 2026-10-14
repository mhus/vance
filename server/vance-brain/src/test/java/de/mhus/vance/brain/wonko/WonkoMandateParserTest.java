package de.mhus.vance.brain.wonko;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WonkoMandateParserTest {

    @Test
    void parse_shortAndLongCategoryForms() {
        WonkoMandate m = WonkoMandateParser.parse(Map.of(
                "default", "ask",
                "categories",
                        Map.of(
                                "Naming",
                                "decide",
                                "data-model",
                                Map.of("level", "confirm", "locked", true, "ceiling", "decide")),
                "notes", "prototype"));

        assertThat(m.getDefaultLevel()).isEqualTo(MandateLevel.ASK);
        assertThat(m.levelFor("naming")).isEqualTo(MandateLevel.DECIDE);
        WonkoCategory dataModel = m.getCategories().get("data-model");
        assertThat(dataModel.isLocked()).isTrue();
        assertThat(dataModel.getCeiling()).isEqualTo(MandateLevel.DECIDE);
        assertThat(m.getNotes()).isEqualTo("prototype");
    }

    @Test
    void parse_freeTextBecomesNotes_unknownFallsBackToDefault() {
        assertThat(WonkoMandateParser.parse("decide naming yourself").getNotes())
                .isEqualTo("decide naming yourself");
        assertThat(WonkoMandateParser.parse(null).getDefaultLevel()).isEqualTo(MandateLevel.CONFIRM);
    }

    @Test
    void overlay_callerWins_wonkoCategoriesStay() {
        WonkoMandate remembered = new WonkoMandate();
        remembered
                .getCategories()
                .put(
                        "money",
                        WonkoCategory.builder()
                                .level(MandateLevel.ASK)
                                .createdByWonko(true)
                                .build());
        remembered
                .getCategories()
                .put("naming", WonkoCategory.builder().level(MandateLevel.ASK).build());
        WonkoMandate caller = WonkoMandateParser.parse(Map.of("categories", Map.of("naming", "decide")));

        WonkoMandateParser.overlay(remembered, caller, false);

        assertThat(remembered.levelFor("naming")).isEqualTo(MandateLevel.DECIDE);
        assertThat(remembered.getCategories()).containsKey("money");
    }
}
