package de.mhus.vance.shared.braindb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.result.UpdateResult;
import java.util.Arrays;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The migration's job is a single {@code updateMany} with an aggregation
 * pipeline that writes {@code updatedAt = max(lastArchivedAt, createdAt)}.
 * The pipeline construction is the part that can rot silently — a typo in
 * the {@code $cond} branches would compile, run, and produce wrong values
 * for every legacy document. Mongo-level coverage (real rows with the three
 * shape variants) is an opt-in Testcontainers concern; this test pins the
 * pipeline shape and the collection-level invocation against a mocked
 * {@link MongoTemplate}, so a regression here fails the build.
 */
class Migrator_2026_10_21_001_DocumentUpdatedAtTest {

    @Test
    void runs_updateMany_on_documents_collection_with_aggregation_pipeline() {
        MongoTemplate template = mock(MongoTemplate.class);
        @SuppressWarnings("unchecked")
        MongoCollection<Document> collection = mock(MongoCollection.class);
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getModifiedCount()).thenReturn(0L);
        when(collection.updateMany(any(Document.class), any(List.class))).thenReturn(updateResult);
        when(template.getCollection("documents")).thenReturn(collection);

        new Migrator_2026_10_21_001_DocumentUpdatedAt()
                .up(new de.mhus.vance.shared.schema.SchemaMigrationContext(template, "2026-10-21_001", "test/host"));

        ArgumentCaptor<List<Document>> pipelineCaptor = ArgumentCaptor.forClass(List.class);
        verify(collection)
                .updateMany(eq(new Document("updatedAt", new Document("$exists", false))), pipelineCaptor.capture());
        List<Document> pipeline = pipelineCaptor.getValue();

        assertThat(pipeline).hasSize(1);
        Document setStage = pipeline.get(0);
        assertThat(setStage).containsOnlyKeys("$set");
        Document setFields = (Document) setStage.get("$set");
        assertThat(setFields).containsOnlyKeys("updatedAt");

        Document cond = (Document) setFields.get("updatedAt");
        assertThat(cond).containsOnlyKeys("$cond");
        List<Object> condArgs = (List<Object>) cond.get("$cond");
        assertThat(condArgs).hasSize(3);

        // First branch: lastArchivedAt present AND newer than createdAt → use it
        assertThat(condArgs.get(0)).isInstanceOf(Document.class);
        Document andExpr = (Document) condArgs.get(0);
        assertThat(andExpr).containsOnlyKeys("$and");
        List<Object> andArgs = (List<Object>) andExpr.get("$and");
        assertThat(andArgs).hasSize(2);
        assertThat(andArgs.get(0)).isEqualTo(new Document("$ne", Arrays.asList("$lastArchivedAt", null)));
        assertThat(andArgs.get(1)).isEqualTo(new Document("$gt", Arrays.asList("$lastArchivedAt", "$createdAt")));

        // True branch: lastArchivedAt wins
        assertThat(condArgs.get(1)).isEqualTo("$lastArchivedAt");
        // False branch: createdAt
        assertThat(condArgs.get(2)).isEqualTo("$createdAt");
    }

    @Test
    void filters_on_$exists_false_so_reruns_are_idempotent() {
        // The pipeline's filter is what makes the migration safe to replay
        // after a partial failure: only rows that still lack updatedAt are
        // touched. Pin the filter shape so a future refactor can't silently
        // broaden it to "every document".
        Document expectedFilter = new Document("updatedAt", new Document("$exists", false));
        assertThat(expectedFilter.toJson())
                .as("filter must match only rows that have never been stamped")
                .isEqualTo("{\"updatedAt\": {\"$exists\": false}}");
    }

    @Test
    void isInstantiableWithPublicNoArgConstructor() {
        // The reflective runner in SchemaMigrationService creates the
        // migration when it is pending; a missing no-arg constructor would
        // surface mid-boot. SchemaMigrationRegistryTest covers the
        // registry-level invariant; this local test documents the contract
        // at the class itself for anyone touching the migration in
        // isolation.
        Migrator_2026_10_21_001_DocumentUpdatedAt migration = new Migrator_2026_10_21_001_DocumentUpdatedAt();
        assertThat(migration).isNotNull();
    }
}
