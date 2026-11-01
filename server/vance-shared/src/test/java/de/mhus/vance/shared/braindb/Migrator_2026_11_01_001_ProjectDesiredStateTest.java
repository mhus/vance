package de.mhus.vance.shared.braindb;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The two transient statuses are folded into the desired state they stood for:
 * a project that was on its way up is RUNNING, one that was on its way down is
 * SUSPENDED. A row still carrying the old value would fail to load.
 */
class Migrator_2026_11_01_001_ProjectDesiredStateTest {

    @Test
    @SuppressWarnings("unchecked")
    void rewritesRecoveringToRunningAndSuspendingToSuspended() {
        MongoTemplate template = mock(MongoTemplate.class);
        MongoCollection<Document> projects = mock(MongoCollection.class);
        UpdateResult result = mock(UpdateResult.class);
        when(result.getModifiedCount()).thenReturn(0L);
        when(projects.updateMany(any(Document.class), any(Document.class))).thenReturn(result);
        when(template.getCollection("projects")).thenReturn(projects);

        new Migrator_2026_11_01_001_ProjectDesiredState()
                .up(new SchemaMigrationContext(template, "2026-11-01_001", "test/host"));

        verify(projects)
                .updateMany(
                        eq(new Document("status", "RECOVERING")),
                        eq(new Document("$set", new Document("status", "RUNNING"))));
        verify(projects)
                .updateMany(
                        eq(new Document("status", "SUSPENDING")),
                        eq(new Document("$set", new Document("status", "SUSPENDED"))));
    }
}
