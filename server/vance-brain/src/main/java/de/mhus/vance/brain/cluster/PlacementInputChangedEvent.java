package de.mhus.vance.brain.cluster;

/**
 * Something that decides placement changed, so a project that could not be
 * placed a moment ago might be placeable now.
 *
 * <p>Placement reads exactly two things, and this event is published wherever
 * either is written:
 *
 * <ul>
 *   <li><b>The pod side</b> — a pod registering (a candidate appeared) and
 *       {@code PATCH .../pods/{podId}/placement} (labels or {@code exclusive}
 *       changed which projects may land there, or the score cap changed how
 *       many fit).
 *   <li><b>The project side</b> — {@code POST .../projects/placement}, which
 *       writes a project's {@code placementSelector}.
 * </ul>
 *
 * <p>Both belong here because they are the two remedies the demand signal
 * invites: an unschedulable project is answered either by providing a pod that
 * fits or by fixing the selector that fits nothing. Accelerating only the
 * expensive one would be an odd asymmetry. The score cap is in for the same
 * reason — {@code NO_CAPACITY} is the other gap, and an operator who raises a
 * cap usually has something waiting.
 *
 * <p><b>Not</b> published on heartbeats. A beat carries a fresh current score
 * every few seconds, and reacting to that would make the accelerator a second
 * scheduler with a much shorter interval. Load is checked at decision time
 * anyway; what this event marks is a deliberate change someone made.
 *
 * <p>Deliberately a fact, not a command. The publisher does not know whether
 * anything is waiting, and the reaction is gated on
 * {@code vance.cluster.master.enabled} — a pod told not to master publishes
 * the event and nobody reacts, which is the intended behaviour.
 *
 * @param reason short human-readable origin, for the log line of the round it
 *               triggers ({@code "pod registered: gpu-01"})
 */
public record PlacementInputChangedEvent(String reason) {}
