package de.mhus.vance.brain.ai.openai;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.ai.DiscoveredModelInfo;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The parser normalises the field-name dialects OpenAI-compatible
 * gateways use for the context window and output limit, and tolerates
 * the bare-minimum OpenAI-proper entry (id + owned_by only). Each
 * dialect is one entry, so each test is one {@code parse} call.
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
    void cortecsStyleEntry_limitsWithoutPricing() throws Exception {
        // The shape api.cortecs.ai/v1/models returns: context_size,
        // max_output_tokens, owned_by — and a pricing block that is
        // deliberately ignored (prices are owned by a different source,
        // the manual layer).
        DiscoveredModelInfo info =
                OpenAiModelListing.parse(entry("{\"id\":\"gemini-3.8-flash\",\"owned_by\":\"Google\","
                        + "\"context_size\":1048576,\"max_output_tokens\":65535,"
                        + "\"pricing\":{\"currency\":\"EUR\",\"input_token\":0.741,"
                        + "\"output_token\":3.703,\"cache_read_cost\":0.074,"
                        + "\"cache_write_cost\":0.075}}"));
        assertThat(info.contextWindowTokens()).isEqualTo(1_048_576);
        assertThat(info.maxOutputTokens()).isEqualTo(65_535);
        assertThat(info.ownedBy()).isEqualTo("Google");
    }

    @Test
    void neverAssertsKindOrPricing() throws Exception {
        // kind is a classification (manual layer) and pricing belongs to
        // a different source (the vendor's price sheet, manual layer) —
        // both stay structurally absent, even when the entry ships them.
        DiscoveredModelInfo info =
                OpenAiModelListing.parse(entry("{\"id\":\"m\",\"kind\":\"chat\",\"pricing\":{\"input\":1.0}}"));
        assertThat(info.wireName()).isEqualTo("m");
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
