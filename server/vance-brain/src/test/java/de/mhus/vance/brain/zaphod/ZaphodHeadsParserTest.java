package de.mhus.vance.brain.zaphod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.zaphod.ZaphodPattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for the Zaphod council recipe parser. Mirrors the
 * validations the {@link ZaphodHeadsParser} performs against the
 * recipe YAML so any drift between the parser and the engine's own
 * start-up check (see {@code ZaphodEngine.buildInitialState}) is
 * caught here first.
 *
 * <p>Each negative case asserts on the message substring that names
 * the offending field path — the same message becomes the
 * Slart-VALIDATING recovery hint, so a fuzzy assertion would let
 * silent error-message regressions slip through and weaken the
 * downstream re-prompt loop.
 */
class ZaphodHeadsParserTest {

    private static final String PATH = "test/zaphod";

    // ──────────────────── Positive paths ────────────────────

    @Test
    void parseRecipe_fullRecipe_returnsParsedSpec() {
        String yaml = """
                name: refactor-impact-council
                description: |
                  Three perspectives on a refactor.
                engine: zaphod
                params:
                  pattern: COUNCIL
                  heads:
                    - name: security-reviewer
                      recipe: ford
                      persona: |
                        Focus on attack surface.
                    - name: performance-reviewer
                      recipe: ford
                      persona: |
                        Focus on resource cost.
                  synthesisPrompt: |
                    Combine the two reviews.
                """;

        ZaphodHeadsParser.Spec spec = ZaphodHeadsParser.parseRecipe(yaml, PATH);

        assertThat(spec.name()).isEqualTo("refactor-impact-council");
        assertThat(spec.description()).contains("Three perspectives");
        assertThat(spec.pattern()).isEqualTo(ZaphodPattern.COUNCIL);
        assertThat(spec.synthesisPrompt()).contains("Combine the two reviews");
        assertThat(spec.heads()).hasSize(2);
        assertThat(spec.heads().get(0).name()).isEqualTo("security-reviewer");
        assertThat(spec.heads().get(0).recipe()).isEqualTo("ford");
        assertThat(spec.heads().get(0).persona()).contains("attack surface");
        assertThat(spec.heads().get(1).name()).isEqualTo("performance-reviewer");
    }

    @Test
    void parseRecipe_synthesisPromptMissing_returnsSpecWithNullPrompt() {
        String yaml = """
                name: minimal-council
                engine: zaphod
                params:
                  pattern: COUNCIL
                  heads:
                    - name: a
                      recipe: ford
                    - name: b
                      recipe: ford
                """;

        ZaphodHeadsParser.Spec spec = ZaphodHeadsParser.parseRecipe(yaml, PATH);

        assertThat(spec.synthesisPrompt()).isNull();
        assertThat(spec.heads()).hasSize(2);
    }

    @Test
    void parseRecipe_personaMissingOnHead_returnsHeadWithNullPersona() {
        String yaml = """
                name: c
                engine: zaphod
                params:
                  pattern: COUNCIL
                  heads:
                    - name: bare-head
                      recipe: ford
                    - name: with-persona
                      recipe: ford
                      persona: explicit
                """;

        ZaphodHeadsParser.Spec spec = ZaphodHeadsParser.parseRecipe(yaml, PATH);

        assertThat(spec.heads().get(0).persona()).isNull();
        assertThat(spec.heads().get(1).persona()).isEqualTo("explicit");
    }

    @Test
    void parseRecipe_engineNameCaseInsensitive_accepted() {
        // The recipe schema is engine-name=zaphod; we accept any
        // case because RecipeResolver normalises engine names
        // elsewhere — refusing 'ZAPHOD' here would be inconsistent.
        String yaml = """
                name: x
                engine: ZAPHOD
                params:
                  pattern: COUNCIL
                  heads:
                    - { name: a, recipe: ford }
                    - { name: b, recipe: ford }
                """;

        assertThat(ZaphodHeadsParser.parseRecipe(yaml, PATH).pattern())
                .isEqualTo(ZaphodPattern.COUNCIL);
    }

    @Test
    void parseRecipe_maxHeadsExact_accepted() {
        // The hard cap matches ZaphodEngine.MAX_HEADS — verify we
        // accept exactly that many, only failing at MAX_HEADS+1.
        StringBuilder heads = new StringBuilder();
        IntStream.range(0, ZaphodHeadsParser.MAX_HEADS).forEach(i ->
                heads.append("    - { name: h").append(i)
                        .append(", recipe: ford }\n"));
        String yaml = "name: c\nengine: zaphod\nparams:\n  pattern: COUNCIL\n  heads:\n"
                + heads;

        ZaphodHeadsParser.Spec spec = ZaphodHeadsParser.parseRecipe(yaml, PATH);

        assertThat(spec.heads()).hasSize(ZaphodHeadsParser.MAX_HEADS);
    }

    // ──────────────────── Negative paths — shape ────────────────────

    @Test
    void parseRecipe_topLevelNotMap_throws() {
        assertThatThrownBy(() ->
                ZaphodHeadsParser.parseRecipe("- not a map", PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PATH)
                .hasMessageContaining("top-level YAML must be a map");
    }

    @Test
    void parseRecipe_missingName_throws() {
        String yaml = """
                engine: zaphod
                params:
                  pattern: COUNCIL
                  heads:
                    - { name: a, recipe: ford }
                """;
        assertThatThrownBy(() -> ZaphodHeadsParser.parseRecipe(yaml, PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing required field 'name'");
    }

    @Test
    void parseRecipe_missingEngine_throws() {
        String yaml = """
                name: x
                params:
                  pattern: COUNCIL
                  heads:
                    - { name: a, recipe: ford }
                """;
        assertThatThrownBy(() -> ZaphodHeadsParser.parseRecipe(yaml, PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing 'engine' field");
    }

    @Test
    void parseRecipe_wrongEngine_throws() {
        String yaml = """
                name: x
                engine: marvin
                params:
                  pattern: COUNCIL
                  heads:
                    - { name: a, recipe: ford }
                """;
        assertThatThrownBy(() -> ZaphodHeadsParser.parseRecipe(yaml, PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("engine='marvin'")
                .hasMessageContaining("expected 'zaphod'");
    }

    @Test
    void parseRecipe_missingParams_throws() {
        String yaml = """
                name: x
                engine: zaphod
                """;
        assertThatThrownBy(() -> ZaphodHeadsParser.parseRecipe(yaml, PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must declare a 'params:' map");
    }

    // ──────────────────── Negative paths — pattern ────────────────────

    @Test
    void parseRecipe_patternMissing_throws() {
        String yaml = """
                name: x
                engine: zaphod
                params:
                  heads:
                    - { name: a, recipe: ford }
                """;
        assertThatThrownBy(() -> ZaphodHeadsParser.parseRecipe(yaml, PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("params.pattern missing");
    }

    @Test
    void parseRecipe_patternUnknown_throws() {
        String yaml = """
                name: x
                engine: zaphod
                params:
                  pattern: GIBBERISH
                  heads:
                    - { name: a, recipe: ford }
                """;
        assertThatThrownBy(() -> ZaphodHeadsParser.parseRecipe(yaml, PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("params.pattern='GIBBERISH'")
                .hasMessageContaining("not a known ZaphodPattern");
    }

    // ──────────────────── Negative paths — heads list ────────────────────

    @Test
    void parseRecipe_headsMissing_throws() {
        String yaml = """
                name: x
                engine: zaphod
                params:
                  pattern: COUNCIL
                """;
        assertThatThrownBy(() -> ZaphodHeadsParser.parseRecipe(yaml, PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("params.heads must be a non-empty list");
    }

    @Test
    void parseRecipe_headsEmpty_throws() {
        String yaml = """
                name: x
                engine: zaphod
                params:
                  pattern: COUNCIL
                  heads: []
                """;
        assertThatThrownBy(() -> ZaphodHeadsParser.parseRecipe(yaml, PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("params.heads must be a non-empty list");
    }

    @Test
    void parseRecipe_headsExceedCap_throws() {
        StringBuilder heads = new StringBuilder();
        IntStream.range(0, ZaphodHeadsParser.MAX_HEADS + 1).forEach(i ->
                heads.append("    - { name: h").append(i)
                        .append(", recipe: ford }\n"));
        String yaml = "name: c\nengine: zaphod\nparams:\n  pattern: COUNCIL\n  heads:\n"
                + heads;

        assertThatThrownBy(() -> ZaphodHeadsParser.parseRecipe(yaml, PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entries; the soft cap is "
                        + ZaphodHeadsParser.MAX_HEADS);
    }

    @Test
    void parseRecipe_headNotMap_throws() {
        String yaml = """
                name: x
                engine: zaphod
                params:
                  pattern: COUNCIL
                  heads:
                    - just-a-string
                """;
        assertThatThrownBy(() -> ZaphodHeadsParser.parseRecipe(yaml, PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("params.heads[0] is not a map");
    }

    @Test
    void parseRecipe_headMissingName_throws() {
        String yaml = """
                name: x
                engine: zaphod
                params:
                  pattern: COUNCIL
                  heads:
                    - { recipe: ford }
                """;
        assertThatThrownBy(() -> ZaphodHeadsParser.parseRecipe(yaml, PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("params.heads[0].name")
                .hasMessageContaining("missing required field 'name'");
    }

    @Test
    void parseRecipe_headMissingRecipe_throws() {
        String yaml = """
                name: x
                engine: zaphod
                params:
                  pattern: COUNCIL
                  heads:
                    - { name: a }
                """;
        assertThatThrownBy(() -> ZaphodHeadsParser.parseRecipe(yaml, PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("params.heads[0].recipe")
                .hasMessageContaining("missing required field 'recipe'");
    }

    @Test
    void parseRecipe_headBlankName_throws() {
        String yaml = """
                name: x
                engine: zaphod
                params:
                  pattern: COUNCIL
                  heads:
                    - { name: '', recipe: ford }
                """;
        assertThatThrownBy(() -> ZaphodHeadsParser.parseRecipe(yaml, PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("params.heads[0].name");
    }

    @Test
    void parseRecipe_duplicateHeadNames_throws() {
        String yaml = """
                name: x
                engine: zaphod
                params:
                  pattern: COUNCIL
                  heads:
                    - { name: same, recipe: ford }
                    - { name: same, recipe: ford }
                """;
        assertThatThrownBy(() -> ZaphodHeadsParser.parseRecipe(yaml, PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate name 'same'");
    }

    @Test
    void maxHeads_staysInSyncWithEngine() {
        // If somebody bumps ZaphodEngine.MAX_HEADS but forgets the
        // parser, the cap-violation tests above would mask the
        // drift. This pin makes the divergence visible.
        assertThat(ZaphodHeadsParser.MAX_HEADS)
                .isEqualTo(ZaphodEngine.MAX_HEADS);
    }
}
