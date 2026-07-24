package de.mhus.vance.addon.brain.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.addon.brain.finance.model.FinanceComputed;
import de.mhus.vance.addon.brain.finance.model.FinanceInterest;
import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import de.mhus.vance.addon.brain.finance.model.FinanceValue;
import de.mhus.vance.addon.brain.finance.model.InterestBasis;
import de.mhus.vance.addon.brain.finance.model.NodeSnapshot;
import de.mhus.vance.addon.brain.finance.model.Period;
import de.mhus.vance.addon.brain.finance.model.PeriodUnit;
import de.mhus.vance.addon.brain.finance.model.ValueMode;
import de.mhus.vance.shared.document.kind.KindCodecException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FinanceTreeCodecTest {

    private static final String YAML = "application/yaml";
    private static final String JSON = "application/json";

    /**
     * Budget tree: net project, positive income sub-tree, negative-sign
     * expense sub-tree holding a recurring rent and a one-time purchase.
     */
    private static FinanceTreeDocument sample() {
        FinanceValue clientA = new FinanceValue(
                50, ValueMode.RECURRING, new Period(3, PeriodUnit.MONTH),
                "2026-01-01", null, null,
                new FinanceInterest(5.0, new Period(1, PeriodUnit.YEAR),
                        InterestBasis.VOM_HUNDERT, false));
        FinanceNode einnahmen = new FinanceNode("einnahmen", "Einnahmen", "💰", "#4f8", 1,
                null, null, List.of(clientA), List.of());

        FinanceValue miete = new FinanceValue(
                800, ValueMode.RECURRING, new Period(1, PeriodUnit.MONTH),
                null, null, null, null);
        FinanceValue anschaffung = new FinanceValue(
                5000, ValueMode.ONE_TIME, null, "2026-03-01", null, null, null);
        FinanceNode ausgaben = new FinanceNode("ausgaben", "Ausgaben", "🧾", null, -1,
                "laufende Kosten", "ausgaben-notes",
                List.of(miete, anschaffung), List.of());

        FinanceNode root = new FinanceNode("projekt", "Projekt", "📊", null, 1,
                null, null, List.of(), List.of(einnahmen, ausgaben));
        return new FinanceTreeDocument(1, "Q1 Finanzplanung", "desc", root);
    }

    @Test
    void yamlRoundTrip_preservesTreeSignsValuesAndInterest() {
        FinanceTreeDocument original = sample();
        String yaml = FinanceTreeCodec.serialize(original, YAML);
        FinanceTreeDocument back = FinanceTreeCodec.parse(yaml, YAML);

        assertThat(back.version()).isEqualTo(1);
        assertThat(back.title()).isEqualTo("Q1 Finanzplanung");
        assertThat(back.root()).isNotNull();
        assertThat(back.root().name()).isEqualTo("projekt");
        assertThat(back.root().children()).extracting(FinanceNode::name)
                .containsExactly("einnahmen", "ausgaben");

        FinanceNode ausgaben = back.root().children().get(1);
        assertThat(ausgaben.sign()).isEqualTo(-1);
        assertThat(ausgaben.notesRef()).isEqualTo("ausgaben-notes");
        assertThat(ausgaben.values()).hasSize(2);

        FinanceValue anschaffung = ausgaben.values().get(1);
        assertThat(anschaffung.mode()).isEqualTo(ValueMode.ONE_TIME);
        assertThat(anschaffung.validFrom()).isEqualTo("2026-03-01");
        assertThat(anschaffung.period()).isNull();

        FinanceValue clientA = back.root().children().get(0).values().get(0);
        assertThat(clientA.period()).isEqualTo(new Period(3, PeriodUnit.MONTH));
        assertThat(clientA.interest()).isNotNull();
        assertThat(clientA.interest().rate()).isEqualTo(5.0);
        assertThat(clientA.interest().period().unit()).isEqualTo(PeriodUnit.YEAR);
    }

    @Test
    void jsonRoundTrip_matchesYamlModel() {
        FinanceTreeDocument original = sample();
        FinanceTreeDocument back =
                FinanceTreeCodec.parse(FinanceTreeCodec.serialize(original, JSON), JSON);

        assertThat(back.root()).isNotNull();
        assertThat(back.root().children().get(0).values().get(0).value()).isEqualTo(50.0);
        assertThat(back.root().children().get(1).sign()).isEqualTo(-1);
    }

    @Test
    void serialize_omitsDefaults() {
        FinanceValue plain = new FinanceValue(
                800, ValueMode.RECURRING, new Period(1, PeriodUnit.MONTH),
                null, null, null,
                new FinanceInterest(3.0, new Period(1, PeriodUnit.YEAR),
                        InterestBasis.VOM_HUNDERT, false));
        Map<String, Object> m = FinanceTreeCodec.valueToMap(plain);

        // RECURRING mode + null validity/sign are omitted; default interest
        // basis (VOM_HUNDERT) + compound=false are omitted.
        assertThat(m).doesNotContainKeys("mode", "validFrom", "validTo", "sign");
        @SuppressWarnings("unchecked")
        Map<String, Object> interest = (Map<String, Object>) m.get("interest");
        assertThat(interest).doesNotContainKeys("basis", "compound");
    }

    @Test
    void serialize_omitsPositiveSignAndEmptyLists() {
        FinanceNode leaf = new FinanceNode("x", null, null, null, 1,
                null, null, List.of(), List.of());
        Map<String, Object> m = FinanceTreeCodec.nodeToMap(leaf);
        assertThat(m).doesNotContainKeys("sign", "values", "children", "title", "icon");
        assertThat(m).containsEntry("name", "x");
    }

    @Test
    void parse_recurringWithoutPeriod_throws() {
        String yaml = """
                $meta:
                  kind: finance-tree
                version: 1
                root:
                  name: r
                  values:
                    - value: 10
                """;
        assertThatThrownBy(() -> FinanceTreeCodec.parse(yaml, YAML))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("requires `period`");
    }

    @Test
    void parse_oneTimeWithoutDate_throws() {
        String yaml = """
                $meta:
                  kind: finance-tree
                version: 1
                root:
                  name: r
                  values:
                    - value: 5000
                      mode: one_time
                """;
        assertThatThrownBy(() -> FinanceTreeCodec.parse(yaml, YAML))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("requires `validFrom`");
    }

    @Test
    void serializeWithComputed_emitsOverlayAndParseIgnoresIt() {
        FinanceTreeDocument doc = sample();
        FinanceComputed computed = new FinanceComputed("2026-07-24T10:00:00Z",
                List.of(new NodeSnapshot("projekt", 1050.0, 87.5, 20.19, 2.87,
                        1000.0, 50.0, 0.0)));

        String yaml = FinanceTreeCodec.serialize(doc, computed, YAML);
        assertThat(yaml).contains("$computed").contains("computedAt").contains("perYear");

        // $computed is derived — parse reads input keys only and drops it,
        // so the overlay never corrupts the typed round-trip.
        FinanceTreeDocument back = FinanceTreeCodec.parse(yaml, YAML);
        assertThat(back.title()).isEqualTo("Q1 Finanzplanung");
        assertThat(back.root()).isNotNull();
        assertThat(back.root().children()).hasSize(2);
    }

    @Test
    void parse_missingVersion_defaultsToOne() {
        String yaml = """
                $meta:
                  kind: finance-tree
                root:
                  name: r
                """;
        FinanceTreeDocument doc = FinanceTreeCodec.parse(yaml, YAML);
        assertThat(doc.version()).isEqualTo(1);
        assertThat(doc.root()).isNotNull();
    }
}
