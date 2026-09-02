package de.mhus.vance.addon.brain.gtd;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Exhaustive rules for {@link GtdBucketResolver} — pure, no Mongo/clock. */
class GtdBucketResolverTest {

    private final GtdBucketResolver resolver = new GtdBucketResolver();
    private static final LocalDate TODAY = LocalDate.parse("2026-07-24");

    @Test
    void inbox_winsOverEverything() {
        assertThat(resolver.bucketOf(true, false, "someday", null, TODAY)).isEqualTo(GtdBucket.INBOX);
        assertThat(resolver.bucketOf(true, false, "2030-01-01", null, TODAY)).isEqualTo(GtdBucket.INBOX);
    }

    @Test
    void trash_winsOverEverything_evenTheInbox() {
        assertThat(resolver.bucketOf(false, true, "today", "2026-07-20", TODAY))
                .isEqualTo(GtdBucket.TRASH);
        // Only reachable if inbox/ and trash/ were configured to overlap, but
        // the rule has to have an answer: put-away beats unprocessed.
        assertThat(resolver.bucketOf(true, true, "", null, TODAY)).isEqualTo(GtdBucket.TRASH);
    }

    @Test
    void deadlineOnOrBeforeToday_forcesToday() {
        assertThat(resolver.bucketOf(false, false, "someday", "2026-07-24", TODAY)).isEqualTo(GtdBucket.TODAY);
        assertThat(resolver.bucketOf(false, false, "", "2026-07-20", TODAY)).isEqualTo(GtdBucket.TODAY);
    }

    @Test
    void futureDeadline_doesNotForceToday() {
        // future deadline, no when → Anytime (deadline only pulls forward, never pushes)
        assertThat(resolver.bucketOf(false, false, "", "2026-08-01", TODAY)).isEqualTo(GtdBucket.ANYTIME);
    }

    @Test
    void whenSomeday_isSomeday() {
        assertThat(resolver.bucketOf(false, false, "someday", null, TODAY)).isEqualTo(GtdBucket.SOMEDAY);
        assertThat(resolver.bucketOf(false, false, "SOMEDAY", null, TODAY)).isEqualTo(GtdBucket.SOMEDAY);
    }

    @Test
    void whenTodayKeyword_isToday() {
        assertThat(resolver.bucketOf(false, false, "today", null, TODAY)).isEqualTo(GtdBucket.TODAY);
    }

    @Test
    void whenFutureDate_isUpcoming_slidesToTodayOnDay() {
        assertThat(resolver.bucketOf(false, false, "2026-07-25", null, TODAY)).isEqualTo(GtdBucket.UPCOMING);
        assertThat(resolver.bucketOf(false, false, "2026-07-24", null, TODAY)).isEqualTo(GtdBucket.TODAY);
    }

    @Test
    void whenPastDate_isToday_overdueStaysVisible() {
        assertThat(resolver.bucketOf(false, false, "2026-07-20", null, TODAY)).isEqualTo(GtdBucket.TODAY);
    }

    @Test
    void noWhen_isAnytime() {
        assertThat(resolver.bucketOf(false, false, "", null, TODAY)).isEqualTo(GtdBucket.ANYTIME);
        assertThat(resolver.bucketOf(false, false, null, null, TODAY)).isEqualTo(GtdBucket.ANYTIME);
    }

    @Test
    void unparseableWhen_fallsBackToAnytime() {
        assertThat(resolver.bucketOf(false, false, "whenever", null, TODAY)).isEqualTo(GtdBucket.ANYTIME);
    }

    @Test
    void isOverdue_pastWhenOrDeadline() {
        assertThat(resolver.isOverdue("2026-07-20", null, TODAY)).isTrue();
        assertThat(resolver.isOverdue(null, "2026-07-20", TODAY)).isTrue();
        assertThat(resolver.isOverdue("today", null, TODAY)).isFalse();
        assertThat(resolver.isOverdue("2026-07-24", null, TODAY)).isFalse();
        assertThat(resolver.isOverdue("2026-08-01", null, TODAY)).isFalse();
    }
}
