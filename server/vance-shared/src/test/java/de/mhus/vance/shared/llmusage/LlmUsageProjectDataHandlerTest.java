package de.mhus.vance.shared.llmusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * The daily rollup's {@code _id} is a hash <em>over</em> the project name.
 * Renaming by setting {@code projectId} alone would leave the key naming the
 * old project, and the next write for that day would insert a second row — the
 * day split in two, both halves claiming to be the total.
 */
class LlmUsageProjectDataHandlerTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final LlmUsageProjectDataHandler handler = new LlmUsageProjectDataHandler(mongoTemplate);

    @Test
    void rename_reKeysTheDailyRollup_notJustItsProjectField() {
        LlmUsageDailyDocument row = daily("acme", "2026-08-01", "p1");
        String oldBucketId = row.getBucketId();
        when(mongoTemplate.updateMulti(any(), any(), any(Class.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        when(mongoTemplate.find(any(), any())).thenReturn(List.of(row));

        handler.rename("acme", "p1", "p2");

        ArgumentCaptor<Object> saved = ArgumentCaptor.forClass(Object.class);
        verify(mongoTemplate).save(saved.capture());
        LlmUsageDailyDocument moved = (LlmUsageDailyDocument) saved.getValue();
        assertThat(moved.getProjectId()).isEqualTo("p2");
        assertThat(moved.getBucketId())
                .isNotEqualTo(oldBucketId)
                .isEqualTo(daily("acme", "2026-08-01", "p2").getBucketId());
    }

    @Test
    void delete_movesSpendToTenantLevel_insteadOfRemovingIt() {
        LlmUsageDailyDocument row = daily("acme", "2026-08-01", "p1");
        row.setCostTotalMicros(5_000_000L);
        row.setCalls(3);
        when(mongoTemplate.updateMulti(any(), any(), any(Class.class)))
                .thenReturn(UpdateResult.acknowledged(2, 2L, null));
        when(mongoTemplate.find(any(), any())).thenReturn(List.of(row));

        long moved = handler.delete("acme", "p1");

        assertThat(moved).isEqualTo(3);
        ArgumentCaptor<Query> target = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).upsert(target.capture(), update.capture(), eq(LlmUsageDailyDocument.class));
        assertThat(target.getValue().getQueryObject().get("_id"))
                .isEqualTo(
                        daily("acme", "2026-08-01", LlmUsageDailyDocument.NONE).getBucketId());
        // $inc, so a tenant-level row of the same key is added to, not replaced.
        Document inc = (Document) update.getValue().getUpdateObject().get("$inc");
        assertThat(inc.get("costTotalMicros")).isEqualTo(5_000_000L);
        assertThat(inc.get("calls")).isEqualTo(3L);
        verify(mongoTemplate).remove(any(Query.class), eq(LlmUsageDailyDocument.class));
        verify(mongoTemplate, never()).remove(any(Query.class), eq(LlmUsageDocument.class));
    }

    @Test
    void renameBlocker_refuses_whenTheTargetRollupAlreadyExists() {
        // Leftover accounting from an earlier project of that name. Saving over
        // it would replace one day's totals with another's.
        when(mongoTemplate.find(any(), any())).thenReturn(List.of(daily("acme", "2026-08-01", "p1")));
        when(mongoTemplate.exists(any(), any(Class.class))).thenReturn(true);

        assertThat(handler.renameBlocker("acme", "p1", "p2"))
                .contains("2026-08-01")
                .contains("delete it before renaming");
    }

    @Test
    void renameBlocker_isSilent_whenTheTargetIsFree() {
        when(mongoTemplate.find(any(), any())).thenReturn(List.of(daily("acme", "2026-08-01", "p1")));
        when(mongoTemplate.exists(any(), any(Class.class))).thenReturn(false);

        assertThat(handler.renameBlocker("acme", "p1", "p2")).isNull();
    }

    private static LlmUsageDailyDocument daily(String tenant, String day, String project) {
        LlmUsageDailyDocument row = new LlmUsageDailyDocument();
        row.setTenantId(tenant);
        row.setDay(day);
        row.setProjectId(project);
        row.setKind(UsageKind.CHAT);
        row.setBucketId(LlmUsageDailyDocument.bucketId(
                tenant,
                day,
                project,
                row.getCaller(),
                row.getRecipeName(),
                row.getProviderModel(),
                row.getCurrency(),
                row.getKind()));
        return row;
    }
}
