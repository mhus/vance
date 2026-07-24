package de.mhus.vance.addon.brain.issues;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Manifest parse + render round-trip for {@link IssuesConfig}, incl. the number counter. */
class IssuesConfigTest {

    @Test
    void render_thenParse_roundTripsNextNumberAndLabels() {
        IssuesConfig c = new IssuesConfig("Bugs", "desc", "items", "archive", 5,
                java.util.List.of("bug", "feature"));
        IssuesConfig back = IssuesConfig.parse(c.render());
        assertThat(back.title()).isEqualTo("Bugs");
        assertThat(back.nextNumber()).isEqualTo(5);
        assertThat(back.itemsDir()).isEqualTo("items");
        assertThat(back.archiveDir()).isEqualTo("archive");
        assertThat(back.suggestedLabels()).containsExactly("bug", "feature");
    }

    @Test
    void withNextNumber_bumpsOnly_thatField() {
        IssuesConfig c = IssuesConfig.defaults().withNextNumber(9);
        assertThat(c.nextNumber()).isEqualTo(9);
        assertThat(IssuesConfig.parse(c.render()).nextNumber()).isEqualTo(9);
    }

    @Test
    void parse_sparseManifest_usesDefaults() {
        IssuesConfig c = IssuesConfig.parse("$meta:\n  kind: application\n  app: issues\ntitle: X\n");
        assertThat(c.itemsDir()).isEqualTo("items");
        assertThat(c.nextNumber()).isEqualTo(1);
    }
}
