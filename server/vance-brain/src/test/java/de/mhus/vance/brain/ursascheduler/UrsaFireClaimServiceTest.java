package de.mhus.vance.brain.ursascheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The atomic cross-pod fire claim. The unique {@code _id} insert is the
 * arbiter: the first pod to insert a tick-slot wins (fires), a second pod
 * inserting the same slot hits a duplicate key and skips — closing the
 * handover-window double-fire.
 */
class UrsaFireClaimServiceTest {

    private MongoTemplate mongoTemplate;
    private UrsaFireClaimService service;
    private final Instant slot = Instant.parse("2026-07-24T10:00:00Z");

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        service = new UrsaFireClaimService(mongoTemplate);
    }

    @Test
    void claim_firstInsertWins_returnsTrue_withSlotScopedId() {
        when(mongoTemplate.insert(any(FireClaimDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.claim("acme", "p-1", "nightly", slot)).isTrue();

        ArgumentCaptor<FireClaimDocument> cap = ArgumentCaptor.forClass(FireClaimDocument.class);
        verify(mongoTemplate).insert(cap.capture());
        // id must scope the claim to (tenant, project, scheduler, second-slot).
        assertThat(cap.getValue().getId())
                .isEqualTo("acme/p-1/nightly/" + slot.getEpochSecond());
        assertThat(cap.getValue().getClaimedAt()).isNotNull();
    }

    @Test
    void claim_duplicateSlot_returnsFalse() {
        when(mongoTemplate.insert(any(FireClaimDocument.class)))
                .thenThrow(new DuplicateKeyException("slot already claimed"));

        assertThat(service.claim("acme", "p-1", "nightly", slot)).isFalse();
    }

    @Test
    void claim_distinctSecondSlots_useDistinctIds() {
        when(mongoTemplate.insert(any(FireClaimDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.claim("acme", "p-1", "nightly", slot);
        service.claim("acme", "p-1", "nightly", slot.plusSeconds(1));

        ArgumentCaptor<FireClaimDocument> cap = ArgumentCaptor.forClass(FireClaimDocument.class);
        verify(mongoTemplate, org.mockito.Mockito.times(2)).insert(cap.capture());
        assertThat(cap.getAllValues()).extracting(FireClaimDocument::getId)
                .containsExactly(
                        "acme/p-1/nightly/" + slot.getEpochSecond(),
                        "acme/p-1/nightly/" + slot.plusSeconds(1).getEpochSecond());
    }
}
