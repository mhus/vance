package de.mhus.vance.api.vogon;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One reaction emitted by a Scorer-case (§2.5), Decider-case (§2.6),
 * Fork branch (§2.7) or Escalation rule (§2.8). Sealed hierarchy so
 * the engine can pattern-match on the concrete action and so YAML
 * shape stays close to the spec ({@code - setFlag: foo}).
 *
 * <p>Terminal actions ({@link Pause}, {@link EscalateTo},
 * {@link JumpToPhase}, {@link ExitLoop}, {@link ExitStrategy}) abort
 * the surrounding {@code do:} list — they hand control off to a
 * different engine path. Strategy-load validation warns about
 * actions placed after a terminal one.
 */
public sealed interface BranchAction
        permits BranchAction.SetFlag,
                BranchAction.SetFlags,
                BranchAction.NotifyParent,
                BranchAction.EscalateTo,
                BranchAction.JumpToPhase,
                BranchAction.Pause,
                BranchAction.ExitLoop,
                BranchAction.ExitStrategy,
                BranchAction.DocCreateText,
                BranchAction.DocCreateKind,
                BranchAction.ListAppend,
                BranchAction.DocConcat,
                BranchAction.InboxPost {

    /** {@code true} for actions that hand control off and abort the
     *  rest of the {@code do:} list. */
    boolean terminal();

    /** Outcome enum shared by {@link ExitLoop} and {@link ExitStrategy}. */
    enum ExitOutcome { OK, FAIL }

    /**
     * Set a strategy-state flag. {@code value} defaults to {@code true}
     * for the bare {@code setFlag: name} form. The map form
     * {@code setFlag: { name: <value> }} carries any scalar (string /
     * boolean / number) the user wants to plumb through to a downstream
     * gate or substitution.
     */
    record SetFlag(String name, Object value) implements BranchAction {
        public SetFlag(String name) { this(name, Boolean.TRUE); }
        @Override public boolean terminal() { return false; }
    }

    /** Set multiple boolean flags to {@code true} in one step. */
    record SetFlags(List<String> names) implements BranchAction {
        @Override public boolean terminal() { return false; }
    }

    /**
     * Emit a {@code ProcessEvent} of the given type to the parent
     * process. Strategy keeps running. {@code summary} may contain
     * {@code ${…}}-substitutions — substitution is the engine's job.
     */
    record NotifyParent(String type, @Nullable String summary) implements BranchAction {
        @Override public boolean terminal() { return false; }
    }

    /**
     * Spawn a sub-strategy as a sibling process. The current strategy
     * stays {@code RUNNING} until the sub-strategy reports back via a
     * {@code ProcessEvent}.
     */
    record EscalateTo(String strategy, Map<String, Object> params) implements BranchAction {
        public EscalateTo {
            params = params == null ? new LinkedHashMap<>() : params;
        }
        public EscalateTo(String strategy) { this(strategy, new LinkedHashMap<>()); }
        @Override public boolean terminal() { return true; }
    }

    /**
     * Set {@code currentPhasePath} to the given phase on the same
     * path level. Jumping out of the current loop is allowed (the
     * loop is left). Forward-only is not enforced — backward jumps
     * effectively re-run earlier phases.
     */
    record JumpToPhase(String phaseName) implements BranchAction {
        @Override public boolean terminal() { return true; }
    }

    /**
     * Pause the strategy on a {@code BLOCKED} status. The session-owner
     * has to issue an explicit resume (e.g. {@code process_resume}) to
     * continue.
     */
    record Pause(@Nullable String reason) implements BranchAction {
        @Override public boolean terminal() { return true; }
    }

    /**
     * Exit the surrounding loop early. {@link ExitOutcome#OK} continues
     * with the phase after the loop; {@link ExitOutcome#FAIL} sets
     * {@code <loopName>_failed} and triggers any matching escalation.
     */
    record ExitLoop(ExitOutcome outcome) implements BranchAction {
        @Override public boolean terminal() { return true; }
    }

    /**
     * Exit the entire strategy. {@link ExitOutcome#OK} → process DONE;
     * {@link ExitOutcome#FAIL} → process FAILED.
     */
    record ExitStrategy(ExitOutcome outcome) implements BranchAction {
        @Override public boolean terminal() { return true; }
    }

    // ──────────────────── Document / inbox actions ────────────────────
    //
    // These are the executive actions that previously had to be tool-
    // called by an LLM worker. Phase J moves them to the engine: a
    // recipe-author declares them as `postActions` on a phase, and the
    // worker only delivers the structured input via {@code outputSchema}.
    // Path / content fields are templated — engine substitutes
    // {@code ${output.X}} / {@code ${params.X}} before executing.

    /** Create a text document at {@code path} with {@code content}. */
    record DocCreateText(
            String path,
            String content,
            @Nullable String title,
            @Nullable List<String> tags) implements BranchAction {
        @Override public boolean terminal() { return false; }
    }

    /**
     * Create a typed document ({@code kind: list|tree|records|graph|sheet}).
     * Items can be specified two ways (mutually exclusive): inline via
     * {@link #items} (statically declared in the YAML) or
     * {@link #itemsFromOutput} (dot-path into the worker's structured
     * output, resolved at execute-time). The latter is the common case
     * for executive workers — the LLM produces the list, the engine
     * populates the document.
     *
     * <p>For {@code kind: list} every item's {@code text} (or {@code title})
     * field becomes one list line. Other kinds get an empty default
     * body in v1.
     */
    record DocCreateKind(
            String path,
            String kind,
            @Nullable String title,
            @Nullable List<String> tags,
            @Nullable List<Map<String, Object>> items,
            @Nullable String itemsFromOutput) implements BranchAction {
        @Override public boolean terminal() { return false; }
    }

    /** Append one entry to a {@code kind: list} document. */
    record ListAppend(String path, String text) implements BranchAction {
        @Override public boolean terminal() { return false; }
    }

    /**
     * Concatenate N source documents verbatim into one target document
     * (matches the {@code doc_concat} tool). Optional {@code separator}
     * (default {@code "\n\n"}), {@code header}, {@code footer}.
     */
    record DocConcat(
            List<String> sources,
            String target,
            @Nullable String separator,
            @Nullable String header,
            @Nullable String footer,
            @Nullable String title) implements BranchAction {
        @Override public boolean terminal() { return false; }
    }

    /**
     * Post an inbox notification for the session-owner. {@code type}
     * is the {@link de.mhus.vance.api.inbox.InboxItemType} name
     * ({@code NOTICE} / {@code APPROVAL} / {@code DECISION} /
     * {@code FEEDBACK}). For pipeline pings {@code NOTICE} is the
     * standard.
     */
    record InboxPost(
            String type,
            String title,
            @Nullable String body,
            @Nullable String criticality) implements BranchAction {
        @Override public boolean terminal() { return false; }
    }
}
