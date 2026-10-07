package de.mhus.vance.brain.recipe;

import java.util.Map;

/**
 * One Shooty guard — a JS guard script ({@link ScriptGuard}) or a Java
 * {@code GuardHandler} bean ({@link HandlerGuard}), plus the hook points
 * it fires at and its loop cap. Config-level: the recipe
 * {@code guard:} block or a per-process runtime override (scripts
 * only). See {@code specification/public/shooty.md}.
 *
 * <p>Both shapes share the evaluation machinery of the
 * {@code ShootyGuardService} — points, fail strategies, scratch
 * stores, round caps, re-entrancy — so a handler is a typed, testable
 * twin of a guard script, not a second guard system. Scripts stay the
 * surface for user-authored, project-specific guards; handlers are
 * the shipped defaults ("batteries included", no document cascade
 * needed).
 *
 * <p>Ordering: the recipe list position is the evaluation order (at
 * the yield point the first {@code continueWith} wins, at the command
 * point the first denial wins) — there is deliberately no separate
 * {@code order} field to drift against.
 */
public sealed interface GuardConfig permits ScriptGuard, HandlerGuard {

    /**
     * Guard inputs — exposed as {@code vance.params.*} to a script and
     * via {@code GuardContext.params()} to a handler. This is how a
     * reusable guard (script or handler) is configured per recipe.
     */
    Map<String, Object> params();

    /**
     * Hard cap on guard injections for the process (0 = disabled).
     * Only evaluated at the STOP/TERMINATE points — START runs once
     * per user turn, COMMAND once per command, both bounded by the
     * script timeout / handler run.
     */
    int maxRounds();

    /** Whether this guard fires at the START point (once per genuine user turn). */
    boolean firesOnStart();

    /** Whether this guard gates engine-command dispatch (COMMAND point). */
    boolean firesOnCommand();

    /**
     * Whether this guard gates exec-run tool calls before they execute
     * (TOOL point, fail-closed like COMMAND).
     */
    boolean firesOnTool();

    /** Whether this guard fires on a natural stop (engine produced its output). */
    boolean firesOnNaturalStop();

    /** Whether this guard fires on an explicit terminate. */
    boolean firesOnTerminate();
}
