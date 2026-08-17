package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Identity of an installed kit — spec:
 * {@code planning/kit-installed-multi.md} §D3.
 */
class KitRecordIdTest {

    private static final String REPO = "https://github.com/mhus/vance-kits.git";

    @Test
    void of_sameCoordinates_isStable() {
        // Stability is what makes re-installing the same source an update
        // rather than a second, competing record.
        assertThat(KitRecordId.of("kernel-security", REPO, "kernel-security"))
                .isEqualTo(KitRecordId.of("kernel-security", REPO, "kernel-security"));
    }

    @Test
    void of_sameNameDifferentRepo_differs() {
        // The shop case: two vendors both ship a kit called "security".
        String a = KitRecordId.of("security", "https://github.com/alice/kits.git", null);
        String b = KitRecordId.of("security", "https://github.com/bob/kits.git", null);
        assertThat(a).isNotEqualTo(b);
        assertThat(a).startsWith("security-");
        assertThat(b).startsWith("security-");
    }

    @Test
    void of_sameRepoDifferentPath_differs() {
        assertThat(KitRecordId.of("k", REPO, "kernel-security"))
                .isNotEqualTo(KitRecordId.of("k", REPO, "c-development"));
    }

    @Test
    void of_pathWithSurroundingSlashes_matchesBarePath() {
        // A user typing "/kernel-security/" means the same kit as
        // "kernel-security" — a second record would be a nasty surprise.
        assertThat(KitRecordId.of("k", REPO, "/kernel-security/"))
                .isEqualTo(KitRecordId.of("k", REPO, "kernel-security"));
    }

    @Test
    void of_urlWithTrailingSlash_matchesBareUrl() {
        assertThat(KitRecordId.of("k", REPO + "/", null))
                .isEqualTo(KitRecordId.of("k", REPO, null));
    }

    @Test
    void of_nullPath_isDistinctFromNamedPath() {
        assertThat(KitRecordId.of("k", REPO, null))
                .isNotEqualTo(KitRecordId.of("k", REPO, "sub"));
    }

    @Test
    void slug_punctuationAndCase_reduceToDashedLowercase() {
        assertThat(KitRecordId.slug("Kernel Security (v2)")).isEqualTo("kernel-security-v2");
    }

    @Test
    void slug_nameWithoutLatinCharacters_fallsBackToKit() {
        // The hash still separates it from everything else, so an
        // unreadable-but-unique name is better than a failed install.
        assertThat(KitRecordId.slug("日本語")).isEqualTo("kit");
    }

    @Test
    void of_producesFilenameSafeId() {
        String id = KitRecordId.of("Kernel Security/v2", REPO, "kernel-security");
        assertThat(id).matches("[a-z0-9-]+");
    }
}
