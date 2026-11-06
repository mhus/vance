package de.mhus.vance.brain.ursaeventtrigger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class UrsaEventDeliveryCacheTest {

    private final UrsaEventDeliveryCache cache = new UrsaEventDeliveryCache(Duration.ofMinutes(10));

    @Test
    void theFirstClaimWinsAndALaterOneSeesItInFlightUntilItIsAnswered() {
        UrsaEventDeliveryCache.Claim first = cache.claim("d-1");
        UrsaEventDeliveryCache.Claim second = cache.claim("d-1");

        assertThat(first.first()).isTrue();
        assertThat(second.first()).isFalse();
        assertThat(second.inFlight()).isTrue();

        var answer = new UrsaEventService.UrsaEventTriggerResult("w", "run-1", null, "evt_1", java.time.Instant.EPOCH);
        cache.complete(first, answer);

        UrsaEventDeliveryCache.Claim third = cache.claim("d-1");
        assertThat(third.inFlight()).isFalse();
        assertThat(third.delivery().result()).isSameAs(answer);
    }

    @Test
    void aReleasedIdIsFreshAgain() {
        cache.claim("d-1");

        cache.release("d-1");

        assertThat(cache.claim("d-1").first()).isTrue();
    }

    @Test
    void aFloodOfNewIdsCannotGrowTheCachePastItsBound() {
        for (int i = 0; i < UrsaEventDeliveryCache.MAX_ENTRIES * 3; i++) {
            cache.claim("flood-" + i);
        }

        assertThat(cache.size()).isLessThanOrEqualTo(UrsaEventDeliveryCache.MAX_ENTRIES);
    }
}
