package de.mhus.vance.shared.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * The rename half, which used to be impossible for Mongo to execute.
 */
class BrainPodProjectDataHandlerTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final BrainPodProjectDataHandler handler =
            new BrainPodProjectDataHandler(mongoTemplate);

    @Test
    void rename_usesTwoUpdates_becauseMongoRejectsOneTouchingOnePathTwice() {
        // The bug: pull and addToSet on 'activeProjects' in a single Update is
        // rejected with "Updating the path 'activeProjects' would create a
        // conflict", so every rename threw. The comment in the handler already
        // said "two writes" — the code did one. Measured against a live pair of
        // brains via `project rename`.
        when(mongoTemplate.updateMulti(any(), any(Update.class), eq(BrainPodDocument.class)))
                .thenReturn(UpdateResult.acknowledged(2, 2L, null));

        long changed = handler.rename("acme", "old-name", "new-name");

        assertThat(changed).isEqualTo(2);
        ArgumentCaptor<Update> updates = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, times(2))
                .updateMulti(any(Query.class), updates.capture(), eq(BrainPodDocument.class));

        List<Update> sent = updates.getAllValues();
        assertThat(sent).hasSize(2);
        for (Update update : sent) {
            String document = update.getUpdateObject().toJson();
            assertThat(document.contains("$pull") && document.contains("$addToSet"))
                    .as("no single update may carry both operators: %s", document)
                    .isFalse();
        }
    }

    @Test
    void rename_addsTheNewNameBeforeRemovingTheOld() {
        // The window between the two writes has the project under both names —
        // a cosmetic duplicate in a display list. The other order has a window
        // with neither, where a heartbeat would publish a pod that looks idle.
        when(mongoTemplate.updateMulti(any(), any(Update.class), eq(BrainPodDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        handler.rename("acme", "old-name", "new-name");

        ArgumentCaptor<Update> updates = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, times(2))
                .updateMulti(any(Query.class), updates.capture(), eq(BrainPodDocument.class));

        assertThat(updates.getAllValues().get(0).getUpdateObject().toJson())
                .contains("$addToSet");
        assertThat(updates.getAllValues().get(1).getUpdateObject().toJson())
                .contains("$pull");
    }
}
