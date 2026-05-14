package de.mhus.vance.shared.hactar;

import org.jspecify.annotations.Nullable;

/**
 * Parsed {@code bounds:} block — global guardrails per workflow run
 * (plan §3.1 / §11). All fields optional; null means "no bound".
 *
 * @param maxTotalCostUsd Cumulative LLM cost across all
 *        {@code agent_task}s. Hard-cap requires
 *        llm-resource-management integration (plan §14).
 * @param maxWallclockSeconds Wall-clock cap from {@code StartRecord}
 *        timestamp to terminal status.
 * @param maxTaskSpawns Absolute cap on tasks enqueued for this run —
 *        protects against state-graph cycles.
 */
public record HactarBoundsSpec(
        @Nullable Double maxTotalCostUsd,
        @Nullable Long maxWallclockSeconds,
        @Nullable Integer maxTaskSpawns) {

    public static HactarBoundsSpec empty() {
        return new HactarBoundsSpec(null, null, null);
    }
}
