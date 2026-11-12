package de.mhus.vance.shared.irismodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class IrisCatalogTest {

    private static final String BODY = """
            {"object":"list","version":7,"data":[
              {"id":"eu-glm","kind":"chat","size":"large","capabilities":["tools"],"context_window":128000,
               "max_output_tokens":8000,"owned_by":"iris",
               "pricing":{"input_token":0.5,"output_token":1.5,"cache_read_cost":0.05,"currency":"EUR"}},
              {"id":"pic","kind":"image","unit_pricing":{"per_image":0.03,"currency":"EUR"}},
              {"id":"voice","kind":"tts","unit_pricing":{"per_1k_chars":0.2,"currency":"EUR"}},
              {"id":"nokind"},{"id":""}]}
            """;

    @Test
    void readsVersionAndOnlyEntriesThatStateAKind() {
        IrisCatalog.Listing l = IrisCatalog.parseListing(BODY);

        assertThat(l.version()).isEqualTo(7);
        assertThat(l.models()).extracting(IrisModel::id).containsExactly("eu-glm", "pic", "voice");
    }

    @Test
    void mapsTokenAndUnitPricesToCatalogFields() {
        List<IrisModel> m = IrisCatalog.parseListing(BODY).models();

        assertThat(m.get(0).size()).isEqualTo("LARGE");
        assertThat(m.get(0).pricing().cacheReadPerMTok()).isEqualTo(0.05);
        assertThat(m.get(0).currency()).isEqualTo("EUR");
        assertThat(m.get(1).unitCosts()).containsEntry("costPerImage", Map.of("standard", 0.03));
        assertThat((Double) m.get(2).unitCosts().get("costPerChar")).isEqualTo(0.0002);
    }

    @Test
    @SuppressWarnings("unchecked")
    void renderedDocIsTheMachineOwnedFormat() {
        IrisModel chat = IrisCatalog.parseListing(BODY).models().get(0);

        Map<String, Object> doc = (Map<String, Object>) new Yaml().load(MachineOwnedModelDoc.render(chat));

        assertThat(doc)
                .containsEntry("auto", true)
                .containsEntry("kind", "chat")
                .containsEntry("costCurrency", "EUR")
                .containsEntry("contextWindowTokens", 128000);
        assertThat((Map<String, Object>) doc.get("pricing")).containsEntry("cacheReadPerMTok", 0.05);
    }

    @Test
    void aHostileWireNameCannotWriteFurtherKeys() {
        String yaml = MachineOwnedModelDoc.render("x", "a\nkind: chat", null, null);

        Map<String, Object> doc = new Yaml().load(yaml);
        assertThat(doc.containsKey("kind")).isFalse();
    }
}
