/**
 * Provider dispatch for System-One decision models — the Zem modality.
 * Parallel to {@code ai/audio/} and {@code ai/image/}: one provider
 * interface, one dispatch service ({@link
 * de.mhus.vance.brain.ai.decision.AiDecisionService}, instance before
 * protocol), one catalog kind ({@code kind: decision} →
 * {@link de.mhus.vance.brain.ai.decision.DecisionModelInfo}).
 *
 * <p>All current providers speak the same wire
 * ({@link de.mhus.vance.brain.ai.decision.systemone.SystemOneWireClient});
 * they differ in endpoint, auth and limits. Limits are catalog data, not
 * provider code. Design: {@code planning/zem-engine.md} §5.
 */
@NullMarked
package de.mhus.vance.brain.ai.decision;

import org.jspecify.annotations.NullMarked;
