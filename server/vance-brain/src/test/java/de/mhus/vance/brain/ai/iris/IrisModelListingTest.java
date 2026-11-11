package de.mhus.vance.brain.ai.iris;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.ai.DiscoveredModelInfo;
import de.mhus.vance.brain.ai.ModelClassification;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The entries below are what Iris' {@code GET /v1/models} answers (prices in EUR). */
class IrisModelListingTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static DiscoveredModelInfo parse(String json) {
        return IrisModelListing.parse(MAPPER.readTree(json));
    }

    @Test
    void aChatAliasCarriesItsClassAndItsTokenPrices() {
        DiscoveredModelInfo m = parse(
                "{\"id\":\"eu-glm-5.3\",\"object\":\"model\",\"owned_by\":\"iris\",\"kind\":\"chat\","
                        + "\"context_window\":200000,\"max_output_tokens\":8192,\"size\":\"LARGE\","
                        + "\"capabilities\":[\"tools\",\"thinking\"],"
                        + "\"pricing\":{\"currency\":\"EUR\",\"input_token\":0.5,\"output_token\":1.5,\"cache_read_cost\":0.05}}");

        assertThat(m.wireName()).isEqualTo("eu-glm-5.3");
        assertThat(m.contextWindowTokens()).isEqualTo(200000);
        assertThat(m.maxOutputTokens()).isEqualTo(8192);
        assertThat(m.pricing().currency()).isEqualTo("EUR");
        assertThat(m.pricing().inputPerMTok()).isEqualTo(0.5);
        assertThat(m.pricing().outputPerMTok()).isEqualTo(1.5);
        assertThat(m.pricing().cacheReadPerMTok()).isEqualTo(0.05);
        ModelClassification c = m.classification();
        assertThat(c.kind()).isEqualTo("chat");
        assertThat(c.size()).isEqualTo("LARGE");
        assertThat(c.capabilities()).containsExactly("tools", "thinking");
    }

    @Test
    void unitPricesAreMappedToTheCatalogsOwnFieldNames() {
        assertThat(parse(
                                "{\"id\":\"pic\",\"kind\":\"image\",\"unit_pricing\":{\"currency\":\"EUR\",\"per_image\":0.03}}")
                        .classification()
                        .costFields())
                .containsEntry("costPerImage", Map.of("standard", 0.03));
        // The catalog counts speech per character, Iris per thousand.
        Object perChar = parse(
                        "{\"id\":\"voice\",\"kind\":\"tts\",\"unit_pricing\":{\"currency\":\"EUR\",\"per_1k_chars\":0.015}}")
                .classification()
                .costFields()
                .get("costPerChar");
        assertThat((Double) perChar).isCloseTo(0.000015, org.assertj.core.api.Assertions.within(1e-12));
        assertThat(parse(
                                "{\"id\":\"s\",\"kind\":\"stt\",\"unit_pricing\":{\"currency\":\"EUR\",\"per_second\":0.0001}}")
                        .classification()
                        .costFields())
                .containsEntry("costPerSecond", 0.0001);
        assertThat(parse("{\"id\":\"m\",\"kind\":\"music\",\"unit_pricing\":{\"currency\":\"EUR\",\"per_track\":0.05}}")
                        .classification()
                        .costFields())
                .containsEntry("costPerTrack", 0.05);
    }

    @Test
    void theCurrencyOfTheCostsComesFromTheListing() {
        ModelClassification c = parse(
                        "{\"id\":\"pic\",\"kind\":\"image\",\"unit_pricing\":{\"currency\":\"EUR\",\"per_image\":0.03}}")
                .classification();
        assertThat(c.costCurrency()).isEqualTo("EUR");
    }

    @Test
    void kindsTheCatalogCannotPriceStayUnpricedInsteadOfBeingGuessed() {
        // Audio analysis is billed by tokens, a decision by question: nothing to state.
        assertThat(parse(
                                "{\"id\":\"listen\",\"kind\":\"au\",\"pricing\":{\"currency\":\"EUR\",\"input_token\":0.5,\"output_token\":1.5,\"audio_input_token\":100}}")
                        .classification()
                        .costFields())
                .isEmpty();
        assertThat(parse(
                                "{\"id\":\"d\",\"kind\":\"decision\",\"unit_pricing\":{\"currency\":\"EUR\",\"per_question\":0.00005}}")
                        .classification()
                        .costFields())
                .isEmpty();
    }

    @Test
    void anEntryWithoutAKindStatesOnlyObservations() {
        DiscoveredModelInfo m = parse("{\"id\":\"plain\",\"context_window\":4096}");
        assertThat(m.classification()).isNull();
        assertThat(m.contextWindowTokens()).isEqualTo(4096);
    }

    @Test
    void anEntryWithoutAnIdIsDropped() {
        assertThat(IrisModelListing.parse(MAPPER.readTree("{\"kind\":\"chat\"}")))
                .isNull();
    }

    @Test
    void anUnpricedUnitAliasWritesNoCostField() {
        assertThat(parse("{\"id\":\"pic\",\"kind\":\"image\",\"unit_pricing\":{\"currency\":\"EUR\"}}")
                        .classification()
                        .costFields())
                .isEmpty();
    }
}
