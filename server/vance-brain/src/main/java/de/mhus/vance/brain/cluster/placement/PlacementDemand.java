package de.mhus.vance.brain.cluster.placement;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the cluster is missing, as one document.
 *
 * <p><b>This is the contract, not the transport.</b> The same shape is read
 * through {@code GET /internal/cluster/placement/demand} and pushed to the
 * configured webhook — one fact, one form, two directions, and neither is a
 * diagnostic version of the other. Two shapes for the same fact would be two
 * truths ({@code planning/project-placement-labels.md} §6.3).
 *
 * <p>Grouped by <b>(tenant, distinct selector)</b> because that is the unit
 * somebody can act on: "one pod with {@code {gpu=true}} and at least 40 score"
 * is an order, a list of 42 projects is an incident report. A per-selector POST
 * was considered and rejected — it would take the aggregation apart again.
 */
public record PlacementDemand(
        String clusterId,
        Instant sentAt,
        List<Entry> demand) {

    /** One actionable line: this many projects want this kind of pod. */
    public record Entry(
            String tenantId,
            Map<String, String> selector,
            PlacementGap gap,
            int projectCount,
            /** Sum of {@code homeResourceScore} over the waiting projects. */
            int requiredScore,
            /**
             * Oldest {@code pendingSince} in the group — the hysteresis input.
             * Whoever provisions pods must be able to tell a five-second blip
             * from twenty minutes of waiting.
             */
            Instant oldestSince) {}

    public boolean isEmpty() {
        return demand.isEmpty();
    }
}
