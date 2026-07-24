package de.mhus.vance.addon.brain.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.addon.brain.finance.model.FinanceInterest;
import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import de.mhus.vance.addon.brain.finance.model.FinanceValue;
import de.mhus.vance.addon.brain.finance.model.InterestBasis;
import de.mhus.vance.addon.brain.finance.model.Period;
import de.mhus.vance.addon.brain.finance.model.PeriodUnit;
import de.mhus.vance.addon.brain.finance.model.ValueMode;
import de.mhus.vance.shared.document.kind.KindCodecException;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinanceDtoMapperTest {

    private static FinanceTreeDocument sample() {
        FinanceValue rent = new FinanceValue(800, ValueMode.RECURRING,
                new Period(1, PeriodUnit.MONTH), "2026-01-01", null, null,
                new FinanceInterest(5.0, new Period(1, PeriodUnit.YEAR),
                        InterestBasis.VOM_HUNDERT, false));
        FinanceValue lump = new FinanceValue(5000, ValueMode.ONE_TIME, null,
                "2026-03-01", null, null, null);
        FinanceNode ausgaben = new FinanceNode("ausgaben", "Ausgaben", "🧾", null, -1,
                "costs", "notes", List.of(rent, lump), List.of());
        FinanceNode root = new FinanceNode("projekt", "Projekt", null, null, 1, null, null,
                List.of(), List.of(ausgaben));
        return new FinanceTreeDocument(1, "Plan", "desc", root);
    }

    @Test
    void roundTrip_modelToDtoToModel_preservesEverything() {
        FinanceTreeDocument original = sample();
        FinanceTreeDocument back = FinanceDtoMapper.fromDto(FinanceDtoMapper.toDto(original));

        assertThat(back.version()).isEqualTo(1);
        assertThat(back.title()).isEqualTo("Plan");
        FinanceNode ausgaben = back.root().children().get(0);
        assertThat(ausgaben.sign()).isEqualTo(-1);
        assertThat(ausgaben.notesRef()).isEqualTo("notes");
        assertThat(ausgaben.values()).hasSize(2);

        FinanceValue rent = ausgaben.values().get(0);
        assertThat(rent.period()).isEqualTo(new Period(1, PeriodUnit.MONTH));
        assertThat(rent.validFrom()).isEqualTo("2026-01-01");
        assertThat(rent.interest()).isNotNull();
        assertThat(rent.interest().rate()).isEqualTo(5.0);

        FinanceValue lump = ausgaben.values().get(1);
        assertThat(lump.mode()).isEqualTo(ValueMode.ONE_TIME);
        assertThat(lump.validFrom()).isEqualTo("2026-03-01");
    }

    @Test
    void fromDto_reusesCodecValidation_recurringWithoutPeriodThrows() {
        FinanceValueDto bad = new FinanceValueDto(10, "recurring", null, null, null, null, null);
        FinanceNodeDto root = new FinanceNodeDto("r", null, null, null, 1, null, null,
                List.of(bad), List.of());
        FinanceTreeDto dto = new FinanceTreeDto(1, "t", null, root);
        assertThatThrownBy(() -> FinanceDtoMapper.fromDto(dto))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("requires `period`");
    }
}
