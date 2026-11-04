package de.mhus.vance.shared.braindb;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.MongoCollection;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

/** The migration only reports; it never touches the rows, which are the only copy of the content. */
class Migrator_2026_11_02_001_WorkspaceSnapshotsRetiredTest {

    @Test
    @SuppressWarnings("unchecked")
    void leavesTheRowsInPlace() {
        MongoTemplate template = mock(MongoTemplate.class);
        MongoCollection<Document> snapshots = mock(MongoCollection.class);
        when(template.collectionExists("workspace_snapshots")).thenReturn(true);
        when(template.getCollection("workspace_snapshots")).thenReturn(snapshots);
        when(snapshots.countDocuments()).thenReturn(3L);
        SchemaMigrationContext context = mock(SchemaMigrationContext.class);
        when(context.mongoTemplate()).thenReturn(template);

        new Migrator_2026_11_02_001_WorkspaceSnapshotsRetired().up(context);

        verify(snapshots).countDocuments();
        verify(template, never()).dropCollection(anyString());
        verify(snapshots, never()).drop();
    }

    @Test
    void aMissingCollectionIsFine() {
        MongoTemplate template = mock(MongoTemplate.class);
        when(template.collectionExists("workspace_snapshots")).thenReturn(false);
        SchemaMigrationContext context = mock(SchemaMigrationContext.class);
        when(context.mongoTemplate()).thenReturn(template);

        new Migrator_2026_11_02_001_WorkspaceSnapshotsRetired().up(context);

        verify(template, never()).getCollection(anyString());
    }
}
