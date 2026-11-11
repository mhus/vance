package de.mhus.vance.brain.ai.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.ai.ModelClassification;
import de.mhus.vance.brain.ai.ModelInfo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** The machine-owned manual document that discovery writes, read back the way the catalog reads it. */
class MachineOwnedDocTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(String yaml) {
        return (Map<String, Object>) new Yaml().load(yaml);
    }

    @Test
    void pricesOnlyKeepTheFormatItAlwaysHad() {
        String yaml = ModelDiscoveryService.renderMachineOwnedDoc(
                "glm", "glm", new ModelInfo.Pricing("EUR", 0.5, 1.5, 0.05, null), null);

        Map<String, Object> doc = read(yaml);
        assertThat(doc).containsEntry("auto", true).doesNotContainKeys("kind", "size", "capabilities", "costCurrency");
        assertThat((Map<String, Object>) doc.get("pricing"))
                .containsEntry("currency", "EUR")
                .containsEntry("inputPerMTok", 0.5)
                .containsEntry("outputPerMTok", 1.5)
                .containsEntry("cacheReadPerMTok", 0.05)
                .doesNotContainKey("cacheWritePerMTok");
        assertThat(ModelDiscoveryService.hasAutoMarker(yaml)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEndpointThatOwnsItsModelListAlsoStatesTheClass() {
        ModelClassification c = new ModelClassification("chat", "LARGE", List.of("tools", "thinking"), Map.of(), "EUR");

        Map<String, Object> doc = read(ModelDiscoveryService.renderMachineOwnedDoc(
                "eu-glm-5.3", "eu-glm-5.3", new ModelInfo.Pricing("EUR", 0.5, 1.5, null, null), c));

        assertThat(doc)
                .containsEntry("auto", true)
                .containsEntry("kind", "chat")
                .containsEntry("size", "LARGE")
                .containsEntry("costCurrency", "EUR");
        assertThat((List<Object>) doc.get("capabilities")).containsExactly("tools", "thinking");
        assertThat(doc).containsKey("pricing");
    }

    @Test
    void anImageAliasCarriesItsPerImageCostAsATierMapAndItsCurrency() {
        ModelClassification c = new ModelClassification(
                "image", null, List.of(), Map.of("costPerImage", Map.of("standard", 0.03)), "EUR");

        Map<String, Object> doc = read(ModelDiscoveryService.renderMachineOwnedDoc("pic", "pic", null, c));

        assertThat(doc)
                .containsEntry("kind", "image")
                .containsEntry("costCurrency", "EUR")
                .doesNotContainKey("pricing");
        assertThat((Map<String, Object>) doc.get("costPerImage")).containsEntry("standard", 0.03);
    }

    @Test
    void aScalarCostFieldIsWrittenAsANumber() {
        ModelClassification c = new ModelClassification("tts", null, List.of(), Map.of("costPerChar", 0.000015), "EUR");

        Map<String, Object> doc = read(ModelDiscoveryService.renderMachineOwnedDoc("voice", "voice", null, c));

        assertThat(doc.get("costPerChar")).isEqualTo(0.000015);
    }

    @Test
    void aWireNameThatDiffersFromTheSlugIsKept() {
        Map<String, Object> doc = read(ModelDiscoveryService.renderMachineOwnedDoc(
                "mistral-large", "mistral:large", new ModelInfo.Pricing("EUR", 1, 2, null, null), null));

        assertThat(doc).containsEntry("wireName", "mistral:large");
    }
}
