package de.mhus.vance.toolpack.jaglan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import de.mhus.vance.api.documents.MountAccess;
import de.mhus.vance.api.mount.MountedSource;
import de.mhus.vance.api.mount.MountedStat;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code vance-api} mount records. They live in {@code vance-api}
 * (both sides of the port must see them) but are tested from here, because
 * {@code vance-api} deliberately carries no test dependencies — jackson and
 * jakarta-validation only.
 */
class MountedStatTest {

    private static MountedStat file(@Nullable String path) {
        return new MountedStat(path, false, 12, "application/pdf", "etag-1", 1L, MountAccess.RO);
    }

    @Test
    void path_isStrippedOfSurroundingSlashes() {
        // Normalise rather than reject: a source handing back "/books/" is
        // using a different convention, not misconfigured.
        assertThat(file("/books/dune.pdf").path()).isEqualTo("books/dune.pdf");
        assertThat(file("books/dune.pdf/").path()).isEqualTo("books/dune.pdf");
        assertThat(file("  books/dune.pdf  ").path()).isEqualTo("books/dune.pdf");
    }

    @Test
    void nullPath_isRejected() {
        assertThatThrownBy(() -> file(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path is required");
    }

    @Test
    void emptyPath_isTheMountRootAndAlwaysADirectory() {
        // The rest of the contract already uses "" for the root — list(""),
        // documentPath(mount, "") — so refusing it here would make the one
        // entry that always exists the one that cannot be described.
        assertThat(file("  ").path()).isEmpty();
        assertThat(file("/").path()).isEmpty();
        assertThat(file("/").directory()).isTrue();
        assertThat(file("/").size()).isZero();
        assertThat(file("/").mimeType()).isNull();
    }

    @Test
    void directory_hasNoSizeAndNoMimeType() {
        MountedStat dir = new MountedStat(
                "books", true, 4096, "text/plain", null, null, MountAccess.RW);

        assertThat(dir.size()).isZero();
        assertThat(dir.mimeType()).isNull();
        assertThat(dir.directory()).isTrue();
    }

    @Test
    void negativeSize_becomesZero() {
        MountedStat stat = new MountedStat(
                "x.pdf", false, -5, null, null, null, MountAccess.RO);

        assertThat(stat.size()).isZero();
    }

    @Test
    void nullAccess_becomesUnknownRatherThanAssumingWrite() {
        MountedStat stat = new MountedStat("x.pdf", false, 1, null, null, null, null);

        assertThat(stat.access()).isEqualTo(MountAccess.UNKNOWN);
    }

    @Test
    void directoryFactory_producesAnUnknownAccessFolder() {
        MountedStat dir = MountedStat.directory("books");

        assertThat(dir.directory()).isTrue();
        assertThat(dir.access()).isEqualTo(MountAccess.UNKNOWN);
        assertThat(dir.path()).isEqualTo("books");
    }

    private static MountedSource source(
            @Nullable String displayName, @Nullable Long itemCount,
            @Nullable Duration ttl, boolean canSearch) {
        return new MountedSource(
                "library", displayName, "ode", MountAccess.RO, itemCount, null, ttl, canSearch);
    }

    @Test
    void source_labelFallsBackToTheMountName() {
        assertThat(source(null, null, null, false).label()).isEqualTo("library");
        assertThat(source("Book Library", 42L, null, false).label()).isEqualTo("Book Library");
        assertThat(source("Book Library", 42L, null, false).itemCount()).isEqualTo(42L);
    }

    @Test
    void source_negativeItemCountBecomesUnknownNotZero() {
        assertThat(source(null, -3L, null, false).itemCount()).isNull();
    }

    @Test
    void source_absentOrZeroTtlFallsBackToTheDefault() {
        // A zero TTL here would produce a shell row that expires the instant
        // it is written; JaglanCapabilities has already clamped a genuine
        // "do not cache" to its floor before it ever reaches this record.
        assertThat(source(null, null, null, false).metadataTtl())
                .isEqualTo(MountedSource.DEFAULT_TTL);
        assertThat(source(null, null, Duration.ZERO, false).metadataTtl())
                .isEqualTo(MountedSource.DEFAULT_TTL);
    }

    @Test
    void source_statedTtlIsKept() {
        assertThat(source(null, null, Duration.ofMinutes(7), false).metadataTtl())
                .isEqualTo(Duration.ofMinutes(7));
    }

    @Test
    void source_canSearchIsCarried() {
        // Both callers need it before they act: a folder listing to know
        // whether a search term can be delegated, a client to know whether to
        // offer the search at all.
        assertThat(source(null, null, null, true).canSearch()).isTrue();
        assertThat(source(null, null, null, false).canSearch()).isFalse();
    }
}
