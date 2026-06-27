package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.documents.WriterRole;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Static-helper tests for the soft document-lock primitives. The
 * service-side wiring (setLockedFor, requireWriteAllowed inside the
 * write methods) is exercised by the brain end-to-end tests; this
 * class covers the pure-logic pieces — normalisation, $meta-seed
 * parsing, identity→role mapping — without a Spring context.
 */
class DocumentLockTest {

    // ────────────────── normalizeLockedFor ──────────────────

    @Test
    void normalize_nullOrEmpty_returnsEmptySet() {
        assertThat(DocumentService.normalizeLockedFor(null)).isEmpty();
        assertThat(DocumentService.normalizeLockedFor(List.of())).isEmpty();
    }

    @Test
    void normalize_singleRole_preservedExactly() {
        // Free selection — no auto-add, no implicit dependencies.
        assertThat(DocumentService.normalizeLockedFor(EnumSet.of(WriterRole.AI)))
                .containsExactly(WriterRole.AI);
        assertThat(DocumentService.normalizeLockedFor(EnumSet.of(WriterRole.USER)))
                .containsExactly(WriterRole.USER);
        assertThat(DocumentService.normalizeLockedFor(EnumSet.of(WriterRole.KIT)))
                .containsExactly(WriterRole.KIT);
    }

    @Test
    void normalize_multipleRoles_preservedAsIs() {
        Set<WriterRole> out = DocumentService.normalizeLockedFor(
                EnumSet.of(WriterRole.USER, WriterRole.KIT));
        assertThat(out).containsExactlyInAnyOrder(WriterRole.USER, WriterRole.KIT);
    }

    @Test
    void normalize_inputUnaffected() {
        // The defensive copy must not mutate the caller's collection.
        EnumSet<WriterRole> input = EnumSet.of(WriterRole.USER);
        Set<WriterRole> out = DocumentService.normalizeLockedFor(input);
        out.add(WriterRole.KIT);
        assertThat(input).containsExactly(WriterRole.USER);
    }

    // ────────────────── parseLockedForInitial ──────────────────

    @Test
    void parseInitial_emptyOrBlank_returnsEmptySet() {
        assertThat(DocumentService.parseLockedForInitial(null)).isEmpty();
        assertThat(DocumentService.parseLockedForInitial("")).isEmpty();
        assertThat(DocumentService.parseLockedForInitial("   ")).isEmpty();
    }

    @Test
    void parseInitial_singleValue() {
        assertThat(DocumentService.parseLockedForInitial("AI"))
                .containsExactly(WriterRole.AI);
    }

    @Test
    void parseInitial_inlineYamlList() {
        assertThat(DocumentService.parseLockedForInitial("[AI, KIT]"))
                .containsExactlyInAnyOrder(WriterRole.AI, WriterRole.KIT);
    }

    @Test
    void parseInitial_commaSeparated() {
        assertThat(DocumentService.parseLockedForInitial("AI,USER,KIT"))
                .containsExactlyInAnyOrder(WriterRole.AI, WriterRole.USER, WriterRole.KIT);
    }

    @Test
    void parseInitial_caseInsensitive() {
        assertThat(DocumentService.parseLockedForInitial("ai, kit"))
                .containsExactlyInAnyOrder(WriterRole.AI, WriterRole.KIT);
    }

    @Test
    void parseInitial_quotedValues() {
        assertThat(DocumentService.parseLockedForInitial("[\"AI\", 'KIT']"))
                .containsExactlyInAnyOrder(WriterRole.AI, WriterRole.KIT);
    }

    @Test
    void parseInitial_unknownRolesSilentlyDropped() {
        // Author intent is best-effort — a typo should not kill the
        // valid entries next to it. The remaining valid roles still
        // seed the lock; the bad one is logged at debug and skipped.
        assertThat(DocumentService.parseLockedForInitial("[AI, BOGUS, KIT]"))
                .containsExactlyInAnyOrder(WriterRole.AI, WriterRole.KIT);
    }

    // ────────────────── writerRoleOf ──────────────────

    @Test
    void writerRoleOf_kitEditorId_mapsToKit() {
        DocumentService.WriterIdentity id = DocumentService.KIT_IDENTITY;
        assertThat(DocumentService.writerRoleOf(id)).isEqualTo(WriterRole.KIT);
    }

    @Test
    void writerRoleOf_toolEditorId_mapsToAi() {
        DocumentService.WriterIdentity id = DocumentService.TOOL_IDENTITY;
        assertThat(DocumentService.writerRoleOf(id)).isEqualTo(WriterRole.AI);
    }

    @Test
    void writerRoleOf_nullEditorId_mapsToAi() {
        DocumentService.WriterIdentity id =
                DocumentService.WriterIdentity.of(null, null, null);
        assertThat(DocumentService.writerRoleOf(id)).isEqualTo(WriterRole.AI);
    }

    @Test
    void writerRoleOf_clientUuid_mapsToUser() {
        DocumentService.WriterIdentity id =
                DocumentService.WriterIdentity.of("c0ffee-1234", "alice", "Alice");
        assertThat(DocumentService.writerRoleOf(id)).isEqualTo(WriterRole.USER);
    }

    @Test
    void writerRoleOf_nullIdentity_defaultsToAi() {
        // Defensive default — should never happen in production but
        // keeps the lock-check loop safe under refactor.
        assertThat(DocumentService.writerRoleOf(null)).isEqualTo(WriterRole.AI);
    }

    // ────────────────── DocumentLockedException ──────────────────

    @Test
    void documentLockedException_carriesBlockedRoleAndLockedSet() {
        Set<WriterRole> locked = EnumSet.of(WriterRole.AI, WriterRole.KIT);
        DocumentService.DocumentLockedException ex =
                new DocumentService.DocumentLockedException(WriterRole.KIT, locked);

        assertThat(ex.getBlockedRole()).isEqualTo(WriterRole.KIT);
        assertThat(ex.getLockedFor())
                .containsExactlyInAnyOrder(WriterRole.AI, WriterRole.KIT);

        // The exception copies the set defensively — mutating the
        // caller's collection must not change the exception's view.
        assertThat(ex.getMessage()).contains("KIT");

        assertThatThrownBy(() -> ex.getLockedFor().add(WriterRole.USER))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
