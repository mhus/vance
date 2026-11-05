package de.mhus.vance.shared.magrathea.journal;

import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Workflow run started. Contains the frozen YAML snapshot — laufende
 * Runs lesen ausschließlich daraus, Source-Document-Edits beeinflussen
 * den Run nicht (plan §7).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartRecord implements JournalRecord {

    /** Workflow definition name. */
    private String workflowName;

    /** Workflow definition version at start time. */
    private @Nullable String workflowVersion;

    /** Frozen verbatim YAML — only authoritative reference for the run. */
    private String definitionYaml;

    /** Caller-supplied params, after defaulting. */
    private @Nullable Map<String, Object> params;

    /** Audit hint — user id, scheduler key, hook origin. */
    private @Nullable String startedBy;

    /**
     * Document path the definition came from, when the run was started
     * from a document rather than by name. Null for name-resolved starts,
     * where the path is implied by the cascade.
     *
     * <p>{@link #workflowName} alone stops identifying the source once a
     * run can begin anywhere: two {@code helloworld.yaml} in different
     * folders share a name. The path is what makes the audit trail answer
     * "which file was this".
     */
    private @Nullable String sourcePath;

    /** Parent workflow run id when this run was spawned via {@code workflow_task} (plan §4.7). */
    private @Nullable String parentMagratheaProcessId;

    /** State in the parent that triggered this sub-run. */
    private @Nullable String parentState;

    /**
     * Session this run belongs to, when it was started bound to one — the
     * Vogon case, where the session has a human owner. Null for headless
     * runs; those get a system session lazily, per agent-task, from
     * {@code MagratheaSessionResolver}.
     */
    private @Nullable String sessionId;

    /**
     * ThinkProcess that owns this run: it waits for the result and
     * represents the run outwards (raising a blocked gate where the
     * conversation is). Null for a run that belongs to a project rather
     * than to anybody.
     *
     * <p>Distinct from {@link #parentMagratheaProcessId}, which is another
     * <em>run</em> waiting via {@code workflow_task}. A run can have both:
     * a sub-workflow started from inside a Vogon plan.
     */
    private @Nullable String ownerProcessId;

    /**
     * Capabilities the run was started with, as {@link
     * de.mhus.vance.api.magrathea.RunCapability} names.
     *
     * <p>Frozen here rather than recomputed because the answer must not
     * change under a running plan: a session that is later deleted, or an
     * owner process that closes, would otherwise silently turn a legal run
     * into an illegal one halfway through.
     */
    private @Nullable Set<String> capabilities;

    /**
     * Parameters that were read out of what somebody said, rather than
     * passed in by the caller.
     *
     * <p>The values themselves are already in {@link #params}. What this
     * adds is that some of them are an <em>interpretation</em> — which is
     * the first thing worth knowing when a run did something nobody
     * expected, and the one thing the parameter list cannot show.
     */
    private @Nullable Set<String> derivedParamKeys;

    /**
     * The run was started in debug mode: it halts before each state and
     * waits to be stepped. Absent in records written before the mode existed,
     * which reads as {@code false}. Whether it is stepping <em>right now</em>
     * is the latest {@link DebugRecord}, not this flag — resuming ends the
     * stepping, the run stays a debug run.
     */
    private boolean debug;

    /**
     * States the run halts before, set at its start. Absent or empty for a run
     * without breakpoints. The current set is the latest
     * {@link BreakpointsRecord}, if there is one; this is where it starts.
     */
    private @Nullable Set<String> breakpoints;

    /**
     * Breakpoints for the workflows this run starts as sub-runs, by workflow
     * name. Handed on to every sub-run, which takes its own entry as its
     * breakpoints — a breakpoint belongs to the workflow, not to one run of it,
     * so each element of a for-each halts at the same state. The current map is
     * the latest {@link BreakpointsRecord}, if there is one.
     */
    private @Nullable Map<String, Set<String>> subBreakpoints;

    /**
     * The run this one repeats, when it was started from an ended run
     * ({@code rerun} / {@code retry from}). Not a parent: nobody waits for this
     * run, and a stop does not cascade to the one it repeats.
     */
    private @Nullable String restartOf;

    /** The state a restart began in; absent for a rerun from the start state. */
    private @Nullable String restartFrom;
}
