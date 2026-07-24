package de.mhus.vance.addon.brain.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.mhus.vance.addon.brain.finance.model.FinanceInterest;
import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceValue;
import de.mhus.vance.addon.brain.finance.model.InterestBasis;
import de.mhus.vance.addon.brain.finance.model.NodeSnapshot;
import de.mhus.vance.addon.brain.finance.model.Period;
import de.mhus.vance.addon.brain.finance.model.PeriodUnit;
import de.mhus.vance.addon.brain.finance.model.ValueMode;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinanceCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 1);
    private static final org.assertj.core.data.Offset<Double> EPS = within(1e-6);

    private static FinanceValue recurring(double value, int count, PeriodUnit unit) {
        return new FinanceValue(value, ValueMode.RECURRING, new Period(count, unit),
                null, null, null, null);
    }

    private static FinanceNode leaf(String name, int sign, FinanceValue... values) {
        return new FinanceNode(name, null, null, null, sign, null, null,
                List.of(values), List.of());
    }

    private static NodeSnapshot byName(List<NodeSnapshot> l, String name) {
        return l.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void normalization_fiftyPerThreeMonths_isTwoHundredPerYear() {
        List<NodeSnapshot> r = FinanceCalculator.compute(
                leaf("x", 1, recurring(50, 3, PeriodUnit.MONTH)), TODAY);
        assertThat(byName(r, "x").perYear()).isCloseTo(200.0, EPS);
    }

    @Test
    void normalization_eightHundredPerMonth_isNinetySixHundredPerYear() {
        List<NodeSnapshot> r = FinanceCalculator.compute(
                leaf("rent", 1, recurring(800, 1, PeriodUnit.MONTH)), TODAY);
        assertThat(byName(r, "rent").perYear()).isCloseTo(9600.0, EPS);
    }

    @Test
    void signRollup_expenseSubtreeSubtractsFromParent() {
        FinanceNode einnahmen = leaf("einnahmen", 1, recurring(1000, 1, PeriodUnit.YEAR));
        FinanceNode ausgaben = leaf("ausgaben", -1, recurring(800, 1, PeriodUnit.MONTH));
        FinanceNode root = new FinanceNode("projekt", null, null, null, 1, null, null,
                List.of(), List.of(einnahmen, ausgaben));

        List<NodeSnapshot> r = FinanceCalculator.compute(root, TODAY);
        assertThat(byName(r, "einnahmen").perYear()).isCloseTo(1000.0, EPS);
        assertThat(byName(r, "ausgaben").perYear()).isCloseTo(-9600.0, EPS);
        assertThat(byName(r, "projekt").perYear()).isCloseTo(1000.0 - 9600.0, EPS);
    }

    @Test
    void interest_linearOnBaseValue_trackedSeparately() {
        FinanceValue v = new FinanceValue(1000, ValueMode.RECURRING, new Period(1, PeriodUnit.YEAR),
                null, null, null,
                new FinanceInterest(5.0, new Period(1, PeriodUnit.YEAR),
                        InterestBasis.VOM_HUNDERT, false));
        NodeSnapshot s = byName(FinanceCalculator.compute(leaf("x", 1, v), TODAY), "x");
        assertThat(s.base()).isCloseTo(1000.0, EPS);
        assertThat(s.interest()).isCloseTo(50.0, EPS);
        assertThat(s.perYear()).isCloseTo(1050.0, EPS);
    }

    @Test
    void oneTime_excludedFromRate_reportedInOneTimeSum() {
        FinanceValue rate = recurring(100, 1, PeriodUnit.YEAR);
        FinanceValue lump = new FinanceValue(5000, ValueMode.ONE_TIME, null,
                "2026-03-01", null, null, null);
        NodeSnapshot s = byName(FinanceCalculator.compute(leaf("x", 1, rate, lump), TODAY), "x");
        assertThat(s.perYear()).isCloseTo(100.0, EPS);
        assertThat(s.oneTimeSum()).isCloseTo(5000.0, EPS);
    }

    @Test
    void validity_futureRecurring_notCountedToday() {
        FinanceValue future = new FinanceValue(100, ValueMode.RECURRING,
                new Period(1, PeriodUnit.YEAR), "2027-01-01", null, null, null);
        assertThat(byName(FinanceCalculator.compute(leaf("x", 1, future), TODAY), "x").perYear())
                .isCloseTo(0.0, EPS);
    }

    @Test
    void validity_activeWindow_counted() {
        FinanceValue active = new FinanceValue(100, ValueMode.RECURRING,
                new Period(1, PeriodUnit.YEAR), "2026-01-01", "2026-12-31", null, null);
        assertThat(byName(FinanceCalculator.compute(leaf("x", 1, active), TODAY), "x").perYear())
                .isCloseTo(100.0, EPS);
    }

    @Test
    void perRecordSign_escapeHatch_flipsSingleRecord() {
        FinanceValue neg = new FinanceValue(100, ValueMode.RECURRING,
                new Period(1, PeriodUnit.YEAR), null, null, -1, null);
        assertThat(byName(FinanceCalculator.compute(leaf("x", 1, neg), TODAY), "x").perYear())
                .isCloseTo(-100.0, EPS);
    }

    @Test
    void displayConversions_useFixedYear() {
        NodeSnapshot s = byName(FinanceCalculator.compute(
                leaf("x", 1, recurring(1200, 1, PeriodUnit.YEAR)), TODAY), "x");
        assertThat(s.perMonth()).isCloseTo(100.0, EPS);          // 1200 / 12
        assertThat(s.perWeek()).isCloseTo(1200.0 / 52.0, EPS);
        assertThat(s.perDay()).isCloseTo(1200.0 / 365.0, EPS);
    }

    @Test
    void order_isPreOrderParentBeforeChildren() {
        FinanceNode root = new FinanceNode("projekt", null, null, null, 1, null, null,
                List.of(), List.of(leaf("a", 1), leaf("b", 1)));
        List<NodeSnapshot> r = FinanceCalculator.compute(root, TODAY);
        assertThat(r).extracting(NodeSnapshot::name).containsExactly("projekt", "a", "b");
    }
}
