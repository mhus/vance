package de.mhus.vance.shared.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.mhus.vance.shared.schema.SchemaMigrationSource.Registered;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * How several sources become one version scale — and the three ways that
 * refuses rather than guesses.
 *
 * <p>All three failures share a shape: the alternative is a database left
 * in an old form while the code reads it as the new one. That is why they
 * throw out of the constructor, before anything can query.
 */
class SchemaMigrationSourceMergeTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final SchemaMigrationLockStore lockStore = mock(SchemaMigrationLockStore.class);
    private final SchemaMigrationProperties properties = new SchemaMigrationProperties();

    @Test
    void twoSources_mergeIntoOneAscendingScale() {
        // Ids order globally, not per source: a store migration dated
        // between two brain ones belongs between them.
        SchemaMigrationService service = build(
                source("brain", entry("2026-08-12_001"), entry("2026-09-01_001")),
                source("store", entry("2026-08-20_001")));

        assertThat(service.declaredIds())
                .containsExactly("2026-08-12_001", "2026-08-20_001", "2026-09-01_001");
    }

    @Test
    void noSourceAtAll_refusesToBoot() {
        // "Nothing to do" and "nobody was asked" look identical from here,
        // and only one of them is safe. This is the inverse of the orphan
        // sweep, where no source means no deletion.
        assertThatThrownBy(() -> build())
                .isInstanceOf(SchemaMigrationException.class)
                .hasMessageContaining("No SchemaMigrationSource registered");
    }

    @Test
    void aSourceThatDeclaresNothing_refusesToBoot() {
        assertThatThrownBy(() -> build(source("store")))
                .isInstanceOf(SchemaMigrationException.class)
                .hasMessageContaining("store");
    }

    @Test
    void theSameIdFromTwoSources_namesBothAndRefuses() {
        // Ids are the version scale. Letting the first one win would mean
        // the other migration never runs and never shows up as pending.
        assertThatThrownBy(() -> build(
                source("brain", entry("2026-08-12_001")),
                source("store", entry("2026-08-12_001"))))
                .isInstanceOf(SchemaMigrationException.class)
                .hasMessageContaining("brain")
                .hasMessageContaining("store");
    }

    private SchemaMigrationService build(SchemaMigrationSource... sources) {
        return new SchemaMigrationService(mongoTemplate, lockStore, properties, List.of(sources));
    }

    private static Registered entry(String id) {
        return new Registered(id, NoOp.class);
    }

    private static SchemaMigrationSource source(String name, Registered... entries) {
        return new SchemaMigrationSource() {
            @Override
            public List<Registered> migrations() {
                return List.of(entries);
            }

            @Override
            public String sourceName() {
                return name;
            }
        };
    }

    public static final class NoOp implements SchemaMigration {
        @Override
        public void up(SchemaMigrationContext context) {
            // nothing
        }
    }
}
