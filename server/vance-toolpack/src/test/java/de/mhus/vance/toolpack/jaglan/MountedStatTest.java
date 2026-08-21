package de.mhus.vance.toolpack.jaglan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.documents.MountAccess;
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

    @Test
    void source_labelFallsBackToTheMountName() {
        de.mhus.vance.api.mount.MountedSource unnamed = new de.mhus.vance.api.mount.MountedSource(
                "library", null, "ode", MountAccess.RO, null, null, null);
        de.mhus.vance.api.mount.MountedSource named = new de.mhus.vance.api.mount.MountedSource(
                "library", "Book Library", "ode", MountAccess.RO, 42L, null, null);

        assertThat(unnamed.label()).isEqualTo("library");
        assertThat(named.label()).isEqualTo("Book Library");
        assertThat(named.itemCount()).isEqualTo(42L);
    }

    @Test
    void source_negativeItemCountBecomesUnknownNotZero() {
        de.mhus.vance.api.mount.MountedSource source = new de.mhus.vance.api.mount.MountedSource(
                "library", null, "ode", MountAccess.RO, -3L, null, null);

        assertThat(source.itemCount()).isNull();
    }

    @Test
    void source_absentOrZeroTtlFallsBackToTheDefault() {
        // A zero TTL here would produce a shell row that expires the instant
        // it is written; JaglanCapabilities has already clamped a genuine
        // "do not cache" to its floor before it ever reaches this record.
        assertThat(new de.mhus.vance.api.mount.MountedSource(
                "library", null, "ode", MountAccess.RO, null, null, null).metadataTtl())
                .isEqualTo(de.mhus.vance.api.mount.MountedSource.DEFAULT_TTL);
        assertThat(new de.mhus.vance.api.mount.MountedSource(
                "library", null, "ode", MountAccess.RO, null, null, java.time.Duration.ZERO)
                .metadataTtl())
                .isEqualTo(de.mhus.vance.api.mount.MountedSource.DEFAULT_TTL);
    }

    @Test
    void source_statedTtlIsKept() {
        assertThat(new de.mhus.vance.api.mount.MountedSource(
                "library", null, "ode", MountAccess.RO, null, null,
                java.time.Duration.ofMinutes(7)).metadataTtl())
                .isEqualTo(java.time.Duration.ofMinutes(7));
    }
}
