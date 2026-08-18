package de.mhus.vance.shared.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.database.DatabaseIdentityGuard.DatabaseIdentityException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The one question asked before anything is written: is this our database?
 *
 * <p>It exists because the brain and the kit store now share blob storage
 * and the migration machinery, so a wrong connection string no longer
 * fails on its own — both would find collections they recognise.
 */
class DatabaseIdentityGuardTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);

    @Test
    void anUnclaimedDatabase_isClaimed() {
        when(mongoTemplate.findById(any(), eq(DatabaseIdentityDocument.class))).thenReturn(null);

        guard("brain").verify();

        ArgumentCaptor<DatabaseIdentityDocument> written =
                ArgumentCaptor.forClass(DatabaseIdentityDocument.class);
        verify(mongoTemplate).insert(written.capture());
        assertThat(written.getValue().getOwner()).isEqualTo("brain");
        assertThat(written.getValue().getId()).isEqualTo(DatabaseIdentityDocument.SINGLETON_ID);
    }

    @Test
    void ourOwnDatabase_passesAndWritesNothing() {
        givenClaimedBy("brain");

        assertThatCode(() -> guard("brain").verify()).doesNotThrowAnyException();
        verify(mongoTemplate, never()).insert(any(DatabaseIdentityDocument.class));
    }

    @Test
    void somebodyElsesDatabase_refusesToStartAndNamesBoth() {
        // The store pointed at a brain database. Continuing would mean the
        // store's migrator stamping its ids onto the brain's timeline.
        givenClaimedBy("brain");

        assertThatThrownBy(() -> guard("store").verify())
                .isInstanceOf(DatabaseIdentityException.class)
                .hasMessageContaining("brain")
                .hasMessageContaining("store");
        verify(mongoTemplate, never()).insert(any(DatabaseIdentityDocument.class));
    }

    @Test
    void twoProcessesClaimingAtOnce_loserRereadsInsteadOfOverwriting() {
        // The fixed _id turns the race into a duplicate key; whoever loses
        // compares like everyone else. A save() would have overwritten the
        // winner instead of colliding — that is the check being skipped.
        when(mongoTemplate.findById(any(), eq(DatabaseIdentityDocument.class)))
                .thenReturn(null)
                .thenReturn(claimed("brain"));
        when(mongoTemplate.insert(any(DatabaseIdentityDocument.class)))
                .thenThrow(new DuplicateKeyException("already claimed"));

        assertThatCode(() -> guard("brain").verify()).doesNotThrowAnyException();
    }

    @Test
    void theLoserOfARace_againstAForeignClaim_stillRefuses() {
        when(mongoTemplate.findById(any(), eq(DatabaseIdentityDocument.class)))
                .thenReturn(null)
                .thenReturn(claimed("store"));
        when(mongoTemplate.insert(any(DatabaseIdentityDocument.class)))
                .thenThrow(new DuplicateKeyException("already claimed"));

        assertThatThrownBy(() -> guard("brain").verify())
                .isInstanceOf(DatabaseIdentityException.class);
    }

    @Test
    void anUnclaimedDatabaseHoldingSomebodyElsesCollections_isNotClaimed() {
        // The case that made this check necessary: every database older
        // than this guard is unclaimed, so without it the first process to
        // boot records whatever it was pointed at as the truth. The store
        // did exactly that to a brain database.
        when(mongoTemplate.findById(any(), eq(DatabaseIdentityDocument.class))).thenReturn(null);
        when(mongoTemplate.getCollectionNames()).thenReturn(Set.of("documents", "settings"));

        assertThatThrownBy(() -> guardAvoiding("store", Set.of("documents", "settings")).verify())
                .isInstanceOf(DatabaseIdentityException.class)
                .hasMessageContaining("documents");
        verify(mongoTemplate, never()).insert(any(DatabaseIdentityDocument.class));
    }

    @Test
    void anUnclaimedDatabaseWithNothingForeignInIt_isClaimed() {
        when(mongoTemplate.findById(any(), eq(DatabaseIdentityDocument.class))).thenReturn(null);
        when(mongoTemplate.getCollectionNames()).thenReturn(Set.of("store_kits"));

        guardAvoiding("store", Set.of("documents", "settings")).verify();

        verify(mongoTemplate).insert(any(DatabaseIdentityDocument.class));
    }

    @Test
    void noOwnerDeclared_refusesToBuild() {
        // Nobody claimed the database, so every process could write to any
        // of them — the state this class exists to end.
        assertThatThrownBy(() -> new DatabaseIdentityGuard(mongoTemplate, List.of()))
                .isInstanceOf(DatabaseIdentityException.class)
                .hasMessageContaining("No DatabaseOwner");
    }

    @Test
    void twoOwnersDeclared_refusesToBuild() {
        // The mixing itself, one step earlier: an application scanning a
        // package that belongs to another.
        assertThatThrownBy(() -> new DatabaseIdentityGuard(
                mongoTemplate, List.of(() -> "brain", () -> "store")))
                .isInstanceOf(DatabaseIdentityException.class)
                .hasMessageContaining("More than one DatabaseOwner");
    }

    private DatabaseIdentityGuard guardAvoiding(String owner, Set<String> foreign) {
        return new DatabaseIdentityGuard(mongoTemplate, List.of(new DatabaseOwner() {
            @Override
            public String owner() {
                return owner;
            }

            @Override
            public Set<String> foreignCollections() {
                return foreign;
            }
        }));
    }

    private DatabaseIdentityGuard guard(String owner) {
        return new DatabaseIdentityGuard(mongoTemplate, List.of(() -> owner));
    }

    private void givenClaimedBy(String owner) {
        when(mongoTemplate.findById(any(), eq(DatabaseIdentityDocument.class)))
                .thenReturn(claimed(owner));
    }

    private static DatabaseIdentityDocument claimed(String owner) {
        return DatabaseIdentityDocument.builder()
                .id(DatabaseIdentityDocument.SINGLETON_ID)
                .owner(owner)
                .claimedAt(Instant.EPOCH)
                .claimedBy("host-1")
                .build();
    }
}
