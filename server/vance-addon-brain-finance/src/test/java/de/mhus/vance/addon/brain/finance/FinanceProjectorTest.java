package de.mhus.vance.addon.brain.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import de.mhus.vance.addon.brain.finance.model.FinanceInterest;
import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceProjection;
import de.mhus.vance.addon.brain.finance.model.FinanceValue;
import de.mhus.vance.addon.brain.finance.model.InterestBasis;
import de.mhus.vance.addon.brain.finance.model.Period;
import de.mhus.vance.addon.brain.finance.model.PeriodUnit;
import de.mhus.vance.addon.brain.finance.model.ProjectionRow;
import de.mhus.vance.addon.brain.finance.model.ValueMode;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinanceProjectorTest {

    private static final org.assertj.core.data.Offset<Double> EPS = within(1e-6);

    private static FinanceValue recurring(double value, int count, PeriodUnit unit,
                                          String from, String to) {
        return new FinanceValue(value, ValueMode.RECURRING, new Period(count, unit),
                from, to, null, null);
    }

    private static FinanceNode leaf(String name, int sign, FinanceValue... values) {
        return new FinanceNode(name, null, null, null, sign, null, null,
                List.of(values), List.of());
    }

    private static ProjectionRow row(FinanceProjection p, String name) {
        return p.rows().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void periods_monthlyBucketsAcrossQuarter() {
        FinanceProjection p = FinanceProjector.project(
                leaf("x", 1), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                PeriodUnit.MONTH);
        assertThat(p.periods()).extracting("label")
                .containsExactly("2026-01", "2026-02", "2026-03");
    }

    @Test
    void recurring_proRataByActualDaysPerMonth() {
        // 3650/year = 10/day; Jan has 31 days → 310, Feb 28 → 280.
        FinanceValue v = recurring(3650, 1, PeriodUnit.YEAR, null, null);
        FinanceProjection p = FinanceProjector.project(
                leaf("x", 1, v), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1),
                PeriodUnit.MONTH);
        List<Double> amounts = row(p, "x").amounts();
        assertThat(amounts.get(0)).isCloseTo(310.0, EPS);
        assertThat(amounts.get(1)).isCloseTo(280.0, EPS);
    }

    @Test
    void oneTime_landsInBucketContainingItsDate() {
        FinanceValue lump = new FinanceValue(5000, ValueMode.ONE_TIME, null,
                "2026-03-15", null, null, null);
        FinanceProjection p = FinanceProjector.project(
                leaf("x", 1, lump), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 1),
                PeriodUnit.MONTH);
        List<Double> amounts = row(p, "x").amounts();
        assertThat(amounts).containsExactly(0.0, 0.0, 5000.0, 0.0);
        assertThat(row(p, "x").total()).isCloseTo(5000.0, EPS);
    }

    @Test
    void sign_expenseSubtreeIsNegativeInProjection() {
        FinanceNode ausgaben = leaf("ausgaben", -1,
                recurring(3650, 1, PeriodUnit.YEAR, null, null));
        FinanceNode root = new FinanceNode("projekt", null, null, null, 1, null, null,
                List.of(), List.of(ausgaben));
        FinanceProjection p = FinanceProjector.project(
                root, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), PeriodUnit.MONTH);
        assertThat(row(p, "ausgaben").amounts().get(0)).isCloseTo(-310.0, EPS);
        assertThat(row(p, "projekt").amounts().get(0)).isCloseTo(-310.0, EPS);
    }

    @Test
    void validity_windowLimitsContributionToActiveMonths() {
        // Active only in February → Jan 0, Feb full (28 days × 10/day = 280).
        FinanceValue v = recurring(3650, 1, PeriodUnit.YEAR, "2026-02-01", "2026-02-28");
        FinanceProjection p = FinanceProjector.project(
                leaf("x", 1, v), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1),
                PeriodUnit.MONTH);
        List<Double> amounts = row(p, "x").amounts();
        assertThat(amounts.get(0)).isCloseTo(0.0, EPS);
        assertThat(amounts.get(1)).isCloseTo(280.0, EPS);
    }

    @Test
    void interest_linearFlowAddedProRata() {
        // base 3650/yr + 10%/yr linear → Jan: base 310 + interest 36.5×(31/365)=31.
        FinanceValue v = new FinanceValue(3650, ValueMode.RECURRING,
                new Period(1, PeriodUnit.YEAR), null, null, null,
                new FinanceInterest(10.0, new Period(1, PeriodUnit.YEAR),
                        InterestBasis.VOM_HUNDERT, false));
        FinanceProjection p = FinanceProjector.project(
                leaf("x", 1, v), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1),
                PeriodUnit.MONTH);
        double expected = 3650.0 * 31 / 365 + (3650.0 * 0.10) * 31 / 365; // 310 + 31
        assertThat(row(p, "x").amounts().get(0)).isCloseTo(expected, EPS);
    }

    @Test
    void interest_compoundGrowsPrincipalYearOverYear() {
        // value 1000 (base flow 1000/yr) + 100%/yr COMPOUND from 2026-01-01.
        // Year1: base 1000 + interest 1000×(2^1−2^0)=1000 → 2000.
        // Year2: base 1000 + interest 1000×(2^2−2^1)=2000 → 3000.
        FinanceValue v = new FinanceValue(1000, ValueMode.RECURRING,
                new Period(1, PeriodUnit.YEAR), "2026-01-01", null, null,
                new FinanceInterest(100.0, new Period(1, PeriodUnit.YEAR),
                        InterestBasis.VOM_HUNDERT, true));
        FinanceProjection p = FinanceProjector.project(
                leaf("x", 1, v), LocalDate.of(2026, 1, 1), LocalDate.of(2028, 1, 1),
                PeriodUnit.YEAR);
        List<Double> amounts = row(p, "x").amounts();
        assertThat(amounts.get(0)).isCloseTo(2000.0, EPS);
        assertThat(amounts.get(1)).isCloseTo(3000.0, EPS);
    }

    @Test
    void invalidRange_fromNotBeforeTo_throws() {
        assertThatThrownBy(() -> FinanceProjector.project(
                leaf("x", 1), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1),
                PeriodUnit.MONTH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before");
    }
}
