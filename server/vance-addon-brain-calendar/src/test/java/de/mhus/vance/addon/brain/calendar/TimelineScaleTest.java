package de.mhus.vance.addon.brain.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

/**
 * Projection of positions onto the number line where smaller means
 * earlier. This is the one calculation the timeline kind exists for:
 * the same code has to order 201.4 Ma and 21:47 on 4 March, and the
 * {@code ago} direction is what keeps the Jurassic from rendering
 * mirror-imaged.
 */
class TimelineScaleTest {

    private static TimelineAxis axis(
            TimelineAxis.TimelineAxisMode mode, TimelineAxis.TimelineDirection direction) {
        return new TimelineAxis(mode, null, direction, null, null, null, new LinkedHashMap<>());
    }

    private static final TimelineAxis FORWARD =
            axis(TimelineAxis.TimelineAxisMode.NUMERIC, TimelineAxis.TimelineDirection.FORWARD);
    private static final TimelineAxis AGO =
            axis(TimelineAxis.TimelineAxisMode.NUMERIC, TimelineAxis.TimelineDirection.AGO);
    private static final TimelineAxis DATETIME =
            axis(TimelineAxis.TimelineAxisMode.DATETIME, TimelineAxis.TimelineDirection.FORWARD);

    @Test
    void numericForward_keepsTheValue() {
        assertThat(TimelineScale.position(FORWARD, "12.5")).isEqualTo(12.5);
    }

    @Test
    void numericAgo_ordersTheLargerNumberEarlier() {
        Double jura = TimelineScale.position(AGO, "201.4");
        Double kreide = TimelineScale.position(AGO, "143.1");

        assertThat(jura).isNotNull();
        assertThat(kreide).isNotNull();
        assertThat(jura).isLessThan(kreide);
    }

    @Test
    void numeric_toleratesWhitespaceAndLeadingPlus() {
        assertThat(TimelineScale.position(FORWARD, "  +7 ")).isEqualTo(7.0);
    }

    @Test
    void numeric_rejectsAValueCarryingItsUnit() {
        // The unit belongs in axis.unit; "201.4 Ma" as a position is
        // the mistake the validator has to be able to name.
        assertThat(TimelineScale.position(FORWARD, "201.4 Ma")).isNull();
    }

    @Test
    void numeric_rejectsInfinity() {
        assertThat(TimelineScale.position(FORWARD, "Infinity")).isNull();
    }

    @Test
    void datetime_readsFullInstants() {
        Double earlier = TimelineScale.position(DATETIME, "2026-03-04T21:40");
        Double later = TimelineScale.position(DATETIME, "2026-03-04T21:47:30");

        assertThat(earlier).isNotNull();
        assertThat(later).isNotNull();
        assertThat(later - earlier).isEqualTo(450.0);
    }

    @Test
    void datetime_honoursAnExplicitOffset() {
        Double utc = TimelineScale.position(DATETIME, "2026-03-04T21:00Z");
        Double berlin = TimelineScale.position(DATETIME, "2026-03-04T22:00+01:00");

        assertThat(utc).isEqualTo(berlin);
    }

    @Test
    void datetime_acceptsCompactOffsetForm() {
        assertThat(TimelineScale.position(DATETIME, "2026-03-04T22:00+0100"))
                .isEqualTo(TimelineScale.position(DATETIME, "2026-03-04T21:00Z"));
    }

    @Test
    void datetime_yearOnlyMeansTheStartOfThatYear() {
        Double year = TimelineScale.position(DATETIME, "1969");
        Double landing = TimelineScale.position(DATETIME, "1969-07-20");

        assertThat(year).isNotNull();
        assertThat(landing).isNotNull();
        assertThat(year).isLessThan(landing);
    }

    @Test
    void datetime_readsNegativeYearsForBce() {
        Double caesar = TimelineScale.position(DATETIME, "-0044-03-15");
        Double later = TimelineScale.position(DATETIME, "0001-01-01");

        assertThat(caesar).isNotNull();
        assertThat(later).isNotNull();
        assertThat(caesar).isLessThan(later);
    }

    @Test
    void datetime_rejectsAnImpossibleDate() {
        assertThat(TimelineScale.position(DATETIME, "2026-02-31")).isNull();
        assertThat(TimelineScale.position(DATETIME, "2026-13-01")).isNull();
    }

    @Test
    void datetime_rejectsABareNumberThatIsNotAYear() {
        assertThat(TimelineScale.position(DATETIME, "201.4")).isNull();
    }

    @Test
    void position_isNullForBlankAndMissingValues() {
        assertThat(TimelineScale.position(FORWARD, null)).isNull();
        assertThat(TimelineScale.position(FORWARD, "   ")).isNull();
    }
}
