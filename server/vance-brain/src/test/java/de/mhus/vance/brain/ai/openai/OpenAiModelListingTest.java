package de.mhus.vance.brain.ai.openai;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.ai.DiscoveredModelInfo;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The parser normalises the field-name dialects OpenAI-compatible
 * gateways use for the context window, output limit and pricing, and
 * tolerates the bare-minimum OpenAI-proper entry (id + owned_by only).
 * Each dialect is one entry, so each test is one {@code parse} call.
 */
class OpenAiModelListingTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode entry(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void parsesOpenAiProperEntry_idAndOwnedByOnly() throws Exception {
        DiscoveredModelInfo info =
                OpenAiModelListing.parse(entry("{\"id\":\"gpt-4o\",\"object\":\"model\",\"owned_by\":\"openai\"}"));
        assertThat(info.wireName()).isEqualTo("gpt-4o");
        assertThat(info.ownedBy()).isEqualTo("openai");
        assertThat(info.contextWindowTokens()).isNull();
        assertThat(info.maxOutputTokens()).isNull();
        assertThat(info.pricing()).isNull();
    }

    @Test
    void normalisesContextWindowDialects() throws Exception {
        assertThat(OpenAiModelListing.parse(entry("{\"id\":\"m1\",\"context_window\":131072}"))
                        .contextWindowTokens())
                .isEqualTo(131072);
        assertThat(OpenAiModelListing.parse(entry("{\"id\":\"m2\",\"context_length\":8192}"))
                        .contextWindowTokens())
                .isEqualTo(8192);
        assertThat(OpenAiModelListing.parse(entry("{\"id\":\"m3\",\"context_size\":1048576}"))
                        .contextWindowTokens())
                .isEqualTo(1048576);
        assertThat(OpenAiModelListing.parse(entry("{\"id\":\"m4\",\"max_context_length\":32768}"))
                        .contextWindowTokens())
                .isEqualTo(32768);
        assertThat(OpenAiModelListing.parse(entry("{\"id\":\"m5\",\"max_input\":64000}"))
                        .contextWindowTokens())
                .isEqualTo(64000);
        assertThat(OpenAiModelListing.parse(entry("{\"id\":\"m6\",\"max_input_tokens\":128000}"))
                        .contextWindowTokens())
                .isEqualTo(128000);
        assertThat(OpenAiModelListing.parse(entry("{\"id\":\"m7\",\"max_tokens\":200000}"))
                        .contextWindowTokens())
                .isEqualTo(200000);
    }

    @Test
    void normalisesOutputLimitDialects() throws Exception {
        assertThat(OpenAiModelListing.parse(entry("{\"id\":\"m1\",\"max_output\":4096}"))
                        .maxOutputTokens())
                .isEqualTo(4096);
        assertThat(OpenAiModelListing.parse(entry("{\"id\":\"m2\",\"max_output_tokens\":8192}"))
                        .maxOutputTokens())
                .isEqualTo(8192);
        assertThat(OpenAiModelListing.parse(entry("{\"id\":\"m3\",\"max_completion_tokens\":16384}"))
                        .maxOutputTokens())
                .isEqualTo(16384);
    }

    @Test
    void picksMostSpecificContextKey_firstPresentWins() throws Exception {
        // A gateway that returns both a generic max_tokens and a specific
        // context_window — the specific one wins (first in the key order).
        DiscoveredModelInfo info =
                OpenAiModelListing.parse(entry("{\"id\":\"m\",\"context_window\":131072,\"max_tokens\":999999}"));
        assertThat(info.contextWindowTokens()).isEqualTo(131072);
    }

    @Test
    void codingProxyStyleEntry_carriesAllObservations() throws Exception {
        // The shape the user observed at the coding-proxy endpoint:
        // max_input / max_output alongside the id.
        DiscoveredModelInfo info = OpenAiModelListing.parse(entry("{\"id\":\"sipgate-coding-ultra\","
                + "\"max_input\":131072,\"max_output\":32768,"
                + "\"owned_by\":\"sipgate\"}"));
        assertThat(info.wireName()).isEqualTo("sipgate-coding-ultra");
        assertThat(info.contextWindowTokens()).isEqualTo(131072);
        assertThat(info.maxOutputTokens()).isEqualTo(32768);
        assertThat(info.ownedBy()).isEqualTo("sipgate");
    }

    @Test
    void cortecsStyleEntry_carriesPricingAndLimits() throws Exception {
        // The shape api.cortecs.ai/v1/models returns: pricing in EUR per
        // MTok (verified against the published "€/M" price list),
        // context_size, max_output_tokens, owned_by.
        DiscoveredModelInfo info =
                OpenAiModelListing.parse(entry("{\"id\":\"gemini-3.8-flash\",\"owned_by\":\"Google\","
                        + "\"context_size\":1048576,\"max_output_tokens\":65535,"
                        + "\"pricing\":{\"currency\":\"EUR\",\"input_token\":0.741,"
                        + "\"output_token\":3.703,\"cache_read_cost\":0.074,"
                        + "\"cache_write_cost\":0.075}}"));
        assertThat(info.contextWindowTokens()).isEqualTo(1_048_576);
        assertThat(info.maxOutputTokens()).isEqualTo(65_535);
        assertThat(info.ownedBy()).isEqualTo("Google");
        assertThat(info.pricing().currency()).isEqualTo("EUR");
        assertThat(info.pricing().inputPerMTok()).isEqualTo(0.741);
        assertThat(info.pricing().outputPerMTok()).isEqualTo(3.703);
        assertThat(info.pricing().cacheReadPerMTok()).isEqualTo(0.074);
        assertThat(info.pricing().cacheWritePerMTok()).isEqualTo(0.075);
    }

    @Test
    void openRouterStyleEntry_convertsPerTokenToPerMTok() throws Exception {
        // OpenRouter reports USD per token and no currency field; Vance
        // stores per MTok. 0.5 $/tok -> 500000 $/MTok.
        DiscoveredModelInfo info = OpenAiModelListing.parse(entry("{\"id\":\"openai/gpt-4\",\"context_length\":8192,"
                + "\"pricing\":{\"prompt\":\"0.5\",\"completion\":\"1.5\","
                + "\"cache_read\":\"0.25\"}}"));
        assertThat(info.contextWindowTokens()).isEqualTo(8_192);
        assertThat(info.pricing().currency()).isEqualTo("USD");
        assertThat(info.pricing().inputPerMTok()).isEqualTo(500_000.0);
        assertThat(info.pricing().outputPerMTok()).isEqualTo(1_500_000.0);
        assertThat(info.pricing().cacheReadPerMTok()).isEqualTo(250_000.0);
        assertThat(info.pricing().cacheWritePerMTok()).isNull();
    }

    @Test
    void zeroPriceIsValid_freeModelsKeepPricing() throws Exception {
        // OpenRouter lists free models with price 0 — that is an
        // observation, not a broken field.
        DiscoveredModelInfo info = OpenAiModelListing.parse(
                entry("{\"id\":\"free-model\",\"pricing\":{\"prompt\":\"0\",\"completion\":\"0\"}}"));
        assertThat(info.pricing()).isNotNull();
        assertThat(info.pricing().inputPerMTok()).isEqualTo(0.0);
        assertThat(info.pricing().outputPerMTok()).isEqualTo(0.0);
    }

    @Test
    void halfPricingBlockIsDropped() throws Exception {
        // Input price without output price (or vice versa) is not a
        // usable observation — no partial pricing block.
        DiscoveredModelInfo info = OpenAiModelListing.parse(entry("{\"id\":\"m\",\"pricing\":{\"input_token\":1.0}}"));
        assertThat(info.pricing()).isNull();
    }

    @Test
    void neverAssertsKind_unsupportedPricingFieldsIgnored() throws Exception {
        // kind stays structurally absent (classification, manual layer).
        // A pricing block whose fields match no known dialect is ignored
        // rather than half-interpreted.
        DiscoveredModelInfo info =
                OpenAiModelListing.parse(entry("{\"id\":\"m\",\"kind\":\"chat\",\"pricing\":{\"input\":1.0}}"));
        assertThat(info.wireName()).isEqualTo("m");
        assertThat(info.pricing()).isNull();
    }

    @Test
    void blankIdReturnsNull() throws Exception {
        assertThat(OpenAiModelListing.parse(entry("{\"id\":\"\"}"))).isNull();
        assertThat(OpenAiModelListing.parse(entry("{\"object\":\"model\"}"))).isNull();
    }

    @Test
    void blankOwnedByBecomesNull() throws Exception {
        DiscoveredModelInfo info = OpenAiModelListing.parse(entry("{\"id\":\"m\",\"owned_by\":\"\"}"));
        assertThat(info.ownedBy()).isNull();
    }
}
