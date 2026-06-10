package de.mhus.vance.brain.fenchurch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FenchurchServiceSlugifyTest {

    @Test
    void plain_words_become_kebab() {
        assertThat(FenchurchService.slugify("Watercolor Cat on Moon"))
                .isEqualTo("watercolor-cat-on-moon");
    }

    @Test
    void diacritics_are_stripped() {
        assertThat(FenchurchService.slugify("Café Crème naïve"))
                .isEqualTo("cafe-creme-naive");
    }

    @Test
    void umlauts_collapse_via_nfd_strip() {
        // ä → a (NFD strips combining mark, slug filter drops it)
        assertThat(FenchurchService.slugify("Mädchen träumt"))
                .isEqualTo("madchen-traumt");
    }

    @Test
    void punctuation_and_runs_of_separators_collapse() {
        assertThat(FenchurchService.slugify("Hello,  World!!!  test--case"))
                .isEqualTo("hello-world-test-case");
    }

    @Test
    void leading_and_trailing_hyphens_trimmed() {
        assertThat(FenchurchService.slugify("---cat---")).isEqualTo("cat");
    }

    @Test
    void empty_input_falls_back_to_image() {
        assertThat(FenchurchService.slugify("")).isEqualTo("image");
        assertThat(FenchurchService.slugify("   ")).isEqualTo("image");
        assertThat(FenchurchService.slugify(null)).isEqualTo("image");
    }

    @Test
    void only_punctuation_falls_back_to_image() {
        assertThat(FenchurchService.slugify("!!!@@@???")).isEqualTo("image");
    }

    @Test
    void slug_truncated_to_30_characters() {
        String long_ = "a very long title that exceeds the thirty character limit";
        String slug = FenchurchService.slugify(long_);
        assertThat(slug.length()).isLessThanOrEqualTo(30);
    }

    @Test
    void digits_are_kept() {
        assertThat(FenchurchService.slugify("Test 123 v2"))
                .isEqualTo("test-123-v2");
    }
}
