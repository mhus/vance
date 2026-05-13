package de.mhus.vance.brain.jeltz;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pure-helper tests for {@link JeltzEngine#extractJson} and {@link JeltzEngine#clampAttempts}. */
class JeltzEngineHelpersTest {

    // ─── extractJson ────────────────────────────────────────────────────

    @Test
    void extractJson_nullInput_returnsEmpty() {
        assertThat(JeltzEngine.extractJson(null)).isEmpty();
    }

    @Test
    void extractJson_blankInput_returnsEmpty() {
        assertThat(JeltzEngine.extractJson("   ")).isEmpty();
    }

    @Test
    void extractJson_pureJson_returnsTrimmed() {
        String json = "{\"a\":1}";
        assertThat(JeltzEngine.extractJson("  " + json + "  ")).isEqualTo(json);
    }

    @Test
    void extractJson_jsonFencedBlock_stripsFences() {
        String raw = "```json\n{\"a\":1}\n```";
        assertThat(JeltzEngine.extractJson(raw)).isEqualTo("{\"a\":1}");
    }

    @Test
    void extractJson_bareFenced_stripsFences() {
        String raw = "```\n{\"k\":\"v\"}\n```";
        assertThat(JeltzEngine.extractJson(raw)).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    void extractJson_jsonWithSurroundingProse_extractsOutermostObject() {
        String raw = "Here is the answer:\n{\"a\":1,\"b\":{\"c\":2}}\nHope that helps.";
        assertThat(JeltzEngine.extractJson(raw))
                .isEqualTo("{\"a\":1,\"b\":{\"c\":2}}");
    }

    @Test
    void extractJson_noBraces_returnsTrimmedFallback() {
        // No braces means there's no JSON to find; we return the trimmed
        // text so the parse-attempt downstream produces a clean error.
        assertThat(JeltzEngine.extractJson("  not json at all  "))
                .isEqualTo("not json at all");
    }

    // ─── clampAttempts ──────────────────────────────────────────────────

    @Test
    void clampAttempts_zero_returnsOne() {
        assertThat(JeltzEngine.clampAttempts(0)).isEqualTo(1);
    }

    @Test
    void clampAttempts_negative_returnsOne() {
        assertThat(JeltzEngine.clampAttempts(-5)).isEqualTo(1);
    }

    @Test
    void clampAttempts_inRange_returnsAsIs() {
        assertThat(JeltzEngine.clampAttempts(3)).isEqualTo(3);
        assertThat(JeltzEngine.clampAttempts(7)).isEqualTo(7);
    }

    @Test
    void clampAttempts_overHardCap_clampsToCap() {
        assertThat(JeltzEngine.clampAttempts(9999)).isEqualTo(10);
    }
}
