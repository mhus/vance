package de.mhus.vance.api.thinkprocess;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Operating mode of a chat-orchestrator process (Arthur). Orthogonal to
 * {@link ThinkProcessStatus}: the status describes whether a turn is
 * running ({@code RUNNING}/{@code IDLE}/{@code BLOCKED}/...), the mode
 * describes <em>what kind</em> of work is being done.
 *
 * <p>For non-Arthur engines (Ford, Marvin, Eddie, Vogon, ...) the mode
 * stays at {@link #NORMAL} for the entire process life — Plan-Mode is
 * an Arthur-only mechanism.
 *
 * <p>See {@code readme/arthur-plan-mode.md} §3.1.
 *
 * <ul>
 *   <li>{@link #NORMAL} — default. Arthur can answer directly, delegate,
 *       or transition into {@code EXPLORING} when the trigger logic
 *       in the system prompt fires.</li>
 *   <li>{@link #EXPLORING} — read-only exploration phase. Tool filter
 *       restricts Arthur to read-only tools. Action schema only allows
 *       {@code PROPOSE_PLAN}, {@code ANSWER} (clarifying questions),
 *       and recursive {@code START_PLAN}.</li>
 *   <li>{@link #PLANNING} — plan submitted, waiting for user approval.
 *       Tool filter still read-only. Next user message is interpreted
 *       as approval / edit / reject.</li>
 *   <li>{@link #EXECUTING} — user accepted the plan. Tool filter back
 *       to full Arthur tool-set. Arthur works the TodoList; on
 *       completion mode falls back to {@link #NORMAL}.</li>
 * </ul>
 */
@GenerateTypeScript("thinkprocess")
public enum ProcessMode {
    NORMAL,
    EXPLORING,
    PLANNING,
    EXECUTING
}
