package de.mhus.vance.shared.llmusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.settings.RetentionSettingCache;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;

/**
 * {@link LlmUsageService#costTotals}: one aggregation for several day ranges —
 * the match covers their union, each range is a conditional sum — and the rows
 * are split back per range. No Mongo here (vance-shared has none in its unit
 * tests): the pipeline is checked as rendered, the mapping against canned rows.
 */
class LlmUsageServiceCostTotalsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);
    private static final LlmUsageService.DayRange TODAY_ONLY = new LlmUsageService.DayRange(TODAY, TODAY);
    private static final LlmUsageService.DayRange MONTH_CLOSED =
            new LlmUsageService.DayRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15));

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final LlmUsageService service = new LlmUsageService(mongo, mock(RetentionSettingCache.class), 0, 60, 14);

    @Test
    void severalRanges_areOneAggregation_overTheirUnion() {
        rows(List.of());

        service.costTotals("acme", "research", List.of(TODAY_ONLY, MONTH_CLOSED));

        List<Document> stages = capturedPipeline();
        assertThat(stages).hasSize(2);
        Document match = stages.get(0).get("$match", Document.class);
        assertThat(match).containsEntry("tenantId", "acme").containsEntry("projectId", "research");
        assertThat(match.get("day", Document.class))
                .containsEntry("$gte", "2026-10-01")
                .containsEntry("$lte", "2026-10-16");
        // Chat and embeddings only — image/audio/decision have their own quotas.
        assertThat(match.get("kind", Document.class).getList("$in", String.class))
                .containsExactlyInAnyOrder("CHAT", "EMBEDDING");

        Document group = stages.get(1).get("$group", Document.class);
        assertThat(group.get("_id")).isEqualTo("$currency");
        assertThat(group).containsKeys("cost0", "unpriced0", "cost1", "unpriced1");
        String json = group.toJson();
        assertThat(json).contains("$cond", "$costTotalMicros", "$unpricedCalls", "2026-10-15", "2026-10-16");
    }

    @Test
    void wholeTenant_hasNoProjectFilter() {
        rows(List.of());

        service.costTotals("acme", null, List.of(TODAY_ONLY));

        assertThat(capturedPipeline().get(0).get("$match", Document.class)).doesNotContainKey("projectId");
    }

    @Test
    void rows_areSplitBackPerRange_leavingEmptyCurrenciesOut() {
        rows(List.of(
                new Document("_id", "USD")
                        .append("cost0", 500_000L)
                        .append("unpriced0", 0L)
                        .append("cost1", 3_000_000L)
                        .append("unpriced1", 0L),
                new Document("_id", null)
                        .append("cost0", 0)
                        .append("unpriced0", 0)
                        .append("cost1", 0)
                        .append("unpriced1", 2)));

        Map<LlmUsageService.DayRange, List<LlmUsageService.CostTotal>> totals =
                service.costTotals("acme", "research", List.of(TODAY_ONLY, MONTH_CLOSED));

        assertThat(totals.get(TODAY_ONLY)).containsExactly(new LlmUsageService.CostTotal("USD", 500_000, 0));
        assertThat(totals.get(MONTH_CLOSED))
                .containsExactly(
                        new LlmUsageService.CostTotal("USD", 3_000_000, 0),
                        new LlmUsageService.CostTotal(LlmUsageDailyDocument.NONE, 0, 2));
    }

    @Test
    void equalRanges_collapse() {
        rows(List.of());

        Map<LlmUsageService.DayRange, List<LlmUsageService.CostTotal>> totals =
                service.costTotals("acme", "research", List.of(TODAY_ONLY, TODAY_ONLY));

        assertThat(totals).containsOnlyKeys(TODAY_ONLY);
        assertThat(capturedPipeline().get(1).get("$group", Document.class)).doesNotContainKey("cost1");
    }

    @Test
    void noRanges_readNothing() {
        assertThat(service.costTotals("acme", "research", List.of())).isEmpty();
        verifyNoInteractions(mongo);
    }

    @Test
    void singleRange_isTheSameQuery() {
        rows(List.of(new Document("_id", "EUR").append("cost0", 42L).append("unpriced0", 1L)));

        assertThat(service.costTotals("acme", null, TODAY, TODAY))
                .containsExactly(new LlmUsageService.CostTotal("EUR", 42, 1));
    }

    @Test
    void aRangeMayNotEndBeforeItStarts() {
        assertThatThrownBy(() -> new LlmUsageService.DayRange(TODAY, TODAY.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ──────────────────── helpers ────────────────────

    private void rows(List<Document> rows) {
        @SuppressWarnings("unchecked")
        AggregationResults<Document> results = mock(AggregationResults.class);
        when(results.getMappedResults()).thenReturn(rows);
        when(mongo.aggregate(any(Aggregation.class), eq(LlmUsageDailyDocument.class), eq(Document.class)))
                .thenReturn(results);
    }

    private List<Document> capturedPipeline() {
        ArgumentCaptor<Aggregation> cap = ArgumentCaptor.forClass(Aggregation.class);
        verify(mongo).aggregate(cap.capture(), eq(LlmUsageDailyDocument.class), eq(Document.class));
        return cap.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT);
    }
}
