package de.mhus.vance.brain.vogon;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemStatus;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.vogon.BranchAction;
import de.mhus.vance.api.vogon.PhaseSpec;
import de.mhus.vance.api.vogon.StrategySpec;
import de.mhus.vance.api.vogon.StrategyState;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Executes the {@code do:} list of a Scorer-case (§2.5),
 * Decider-case (§2.6), Fork branch (§2.7) or Escalation rule (§2.8).
 * Pure StrategyState mutator — no Spring, no Mongo, no IO. The
 * surrounding engine consumes the returned {@link Result} and acts
 * on it (advance, pause, escalate, fail).
 *
 * <p>Action semantics follow {@code specification/vogon-engine.md}
 * §2.5 "Branch-Action-Vokabular". Terminal actions (Pause /
 * EscalateTo / JumpToPhase / ExitLoop / ExitStrategy) abort the
 * remaining list — strategy-load validation already rejects later
 * unreachable actions, but the executor enforces it at runtime as
 * a safety net.
 */
final class BranchActionExecutor {

    /** Outcome of a single {@code do:} list execution. */
    record Result(
            ResultKind kind,
            @Nullable String detail,
            BranchAction.@Nullable ExitOutcome exitOutcome,
            Map<String, Object> escalationParams) {

        static Result continueRunning() {
            return new Result(ResultKind.CONTINUE, null, null, Map.of());
        }

        static Result paused(@Nullable String reason) {
            return new Result(ResultKind.PAUSED, reason, null, Map.of());
        }

        static Result jumped(String phaseName) {
            return new Result(ResultKind.JUMPED, phaseName, null, Map.of());
        }

        static Result escalated(String strategy, Map<String, Object> params) {
            return new Result(
                    ResultKind.ESCALATED, strategy, null,
                    params == null ? Map.of() : params);
        }

        static Result exitedLoop(BranchAction.ExitOutcome outcome) {
            return new Result(ResultKind.EXIT_LOOP, null, outcome, Map.of());
        }

        static Result exitedStrategy(BranchAction.ExitOutcome outcome) {
            return new Result(ResultKind.EXIT_STRATEGY, null, outcome, Map.of());
        }
    }

    enum ResultKind {
        /** No terminal action fired — engine continues from where it was. */
        CONTINUE,
        /** {@code pause:} fired — engine sets process BLOCKED, waits for resume. */
        PAUSED,
        /** {@code jumpToPhase:} fired — {@code detail} is the phase name. */
        JUMPED,
        /** {@code escalateTo:} fired — {@code detail} is the sub-strategy name,
         *  {@code escalationParams} is the merged params map. */
        ESCALATED,
        /** {@code exitLoop:} fired. */
        EXIT_LOOP,
        /** {@code exitStrategy:} fired. */
        EXIT_STRATEGY
    }

    /**
     * Carries the engine services + process context branch actions
     * may need (Doc-actions reach into {@link DocumentService}, the
     * inbox post into {@link InboxItemService}). Pure flag- and
     * flow-actions ignore the services. Pass {@link Ctx#empty()} when
     * only flow-control actions are expected.
     */
    record Ctx(
            @Nullable ThinkProcessDocument process,
            @Nullable DocumentService documentService,
            @Nullable InboxItemService inboxItemService) {
        public static Ctx empty() {
            return new Ctx(null, null, null);
        }
    }

    private BranchActionExecutor() {}

    /**
     * Run the actions in declared order. Returns the first terminal
     * action's effect (or {@link Result#continueRunning()} if none
     * fired). Strategy-state mutations from prior actions in the
     * same list survive (e.g. a {@code setFlag} before an
     * {@code escalateTo} stays in the flags map).
     */
    static Result execute(
            StrategySpec strategy,
            StrategyState state,
            List<BranchAction> actions) {
        return execute(strategy, state, actions, Ctx.empty());
    }

    /** Same as {@link #execute(StrategySpec, StrategyState, List)} but
     *  passes a {@link Ctx} so Doc-actions / inbox-post can reach the
     *  engine services. */
    static Result execute(
            StrategySpec strategy,
            StrategyState state,
            List<BranchAction> actions,
            Ctx ctx) {
        if (actions == null || actions.isEmpty()) {
            return Result.continueRunning();
        }
        for (BranchAction action : actions) {
            Result terminal = applyOne(strategy, state, action, ctx);
            if (terminal != null) return terminal;
        }
        return Result.continueRunning();
    }

    /** Returns non-null when {@code action} is terminal; null for
     *  flag-mutating non-terminals. */
    private static @Nullable Result applyOne(
            StrategySpec strategy, StrategyState state, BranchAction action, Ctx ctx) {
        if (action instanceof BranchAction.SetFlag sf) {
            state.getFlags().put(sf.name(), sf.value());
            return null;
        }
        if (action instanceof BranchAction.SetFlags sfs) {
            for (String name : sfs.names()) {
                state.getFlags().put(name, Boolean.TRUE);
            }
            return null;
        }
        if (action instanceof BranchAction.NotifyParent np) {
            // Notification is a side-channel emit — caller wires it to the
            // ProcessEventEmitter. We carry the request via a transient
            // flag so the engine can pick it up after execute() returns.
            // Contract: <storeAs>_pendingNotifyType / _pendingNotifySummary
            // — but for now we don't have an outer storeAs context. Use
            // a fixed transient key the engine drains.
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("type", np.type());
            if (np.summary() != null) pending.put("summary", np.summary());
            state.getFlags().put("__pendingNotifyParent__", pending);
            return null;
        }
        if (action instanceof BranchAction.JumpToPhase jp) {
            String target = jp.phaseName();
            if (!phaseExists(strategy, target)) {
                throw new IllegalStateException(
                        "jumpToPhase: unknown phase '" + target + "'");
            }
            // Reset path to a single top-level segment. If the target is
            // a loop-phase the next runTurn enters it via resolveActivePhase.
            state.getCurrentPhasePath().clear();
            state.getCurrentPhasePath().add(target);
            return Result.jumped(target);
        }
        if (action instanceof BranchAction.Pause p) {
            return Result.paused(p.reason());
        }
        if (action instanceof BranchAction.EscalateTo et) {
            return Result.escalated(et.strategy(), et.params());
        }
        if (action instanceof BranchAction.ExitLoop el) {
            return Result.exitedLoop(el.outcome());
        }
        if (action instanceof BranchAction.ExitStrategy es) {
            return Result.exitedStrategy(es.outcome());
        }
        if (action instanceof BranchAction.DocCreateText d) {
            requireDocService(ctx, "doc_create_text");
            String tenantId = ctx.process().getTenantId();
            String projectId = ctx.process().getProjectId();
            String content = d.content() == null ? "" : d.content();
            // Idempotent on existing path: a Vogon lector-revision
            // loop re-runs the writer which re-emits the same
            // postAction, so create-or-update is the only sensible
            // semantic here. Strict-create stays the default for
            // tool calls (separate code path).
            Optional<DocumentDocument> existing = ctx.documentService()
                    .findByPath(tenantId, projectId, d.path());
            if (existing.isPresent()) {
                ctx.documentService().update(
                        existing.get().getId(),
                        d.title(), d.tags(), content, /*newPath*/ null);
            } else {
                ctx.documentService().createText(
                        tenantId, projectId, d.path(),
                        d.title(), d.tags(), content,
                        /*createdBy*/ null);
            }
            return null;
        }
        if (action instanceof BranchAction.DocCreateKind d) {
            requireDocService(ctx, "doc_create_kind");
            String tenantId = ctx.process().getTenantId();
            String projectId = ctx.process().getProjectId();
            // Render the kind-document as YAML front-matter + body
            // matching DocCreateKindTool's on-disk layout. The
            // simplest approach: build a minimal YAML by hand for
            // the supported kinds. v1 supports kind=list with items
            // (others get an empty body).
            String body = renderKindBody(d);
            // Same idempotent semantics as DocCreateText — outline.md
            // gets rewritten on lector revisions.
            Optional<DocumentDocument> existing = ctx.documentService()
                    .findByPath(tenantId, projectId, d.path());
            if (existing.isPresent()) {
                ctx.documentService().update(
                        existing.get().getId(),
                        d.title(), d.tags(), body, /*newPath*/ null);
            } else {
                ctx.documentService().create(
                        tenantId, projectId, d.path(),
                        d.title(), d.tags(),
                        "text/markdown",
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                        /*createdBy*/ null);
            }
            return null;
        }
        if (action instanceof BranchAction.ListAppend la) {
            requireDocService(ctx, "list_append");
            String tenantId = ctx.process().getTenantId();
            String projectId = ctx.process().getProjectId();
            DocumentDocument existing = ctx.documentService()
                    .findByPath(tenantId, projectId, la.path())
                    .orElseThrow(() -> new IllegalStateException(
                            "list_append: target document '" + la.path()
                                    + "' does not exist — create it with "
                                    + "doc_create_kind first"));
            String current = existing.getInlineText() == null
                    ? "" : existing.getInlineText();
            String updated = current.endsWith("\n") || current.isEmpty()
                    ? current : current + "\n";
            updated += "- " + la.text() + "\n";
            ctx.documentService().update(
                    existing.getId(),
                    /*newTitle*/ null,
                    /*newTags*/ null,
                    updated,
                    /*newPath*/ null);
            return null;
        }
        if (action instanceof BranchAction.DocConcat dc) {
            requireDocService(ctx, "doc_concat");
            String tenantId = ctx.process().getTenantId();
            String projectId = ctx.process().getProjectId();
            String separator = dc.separator() == null ? "\n\n" : dc.separator();
            StringBuilder sb = new StringBuilder();
            if (dc.header() != null) sb.append(dc.header());
            for (int i = 0; i < dc.sources().size(); i++) {
                String path = dc.sources().get(i);
                DocumentDocument src = ctx.documentService()
                        .findByPath(tenantId, projectId, path)
                        .orElseThrow(() -> new IllegalStateException(
                                "doc_concat: source '" + path
                                        + "' not found in project '" + projectId + "'"));
                if (i > 0 || dc.header() != null) sb.append(separator);
                sb.append(src.getInlineText() == null ? "" : src.getInlineText());
            }
            if (dc.footer() != null) sb.append(separator).append(dc.footer());
            ctx.documentService().createText(
                    tenantId,
                    projectId,
                    dc.target(),
                    dc.title(),
                    /*tags*/ null,
                    sb.toString(),
                    /*createdBy*/ null);
            return null;
        }
        if (action instanceof BranchAction.InboxPost ip) {
            requireInboxService(ctx, "inbox_post");
            String tenantId = ctx.process().getTenantId();
            InboxItemType type = parseEnum(ip.type(), InboxItemType.FEEDBACK,
                    InboxItemType.class);
            Criticality crit = parseEnum(ip.criticality(), Criticality.LOW,
                    Criticality.class);
            InboxItemDocument item = InboxItemDocument.builder()
                    .tenantId(tenantId)
                    .type(type)
                    .status(InboxItemStatus.PENDING)
                    .criticality(crit)
                    .title(ip.title())
                    .body(ip.body())
                    .originProcessId(ctx.process().getId())
                    .build();
            ctx.inboxItemService().create(item);
            return null;
        }
        throw new IllegalStateException("Unknown branch action: " + action);
    }

    private static void requireDocService(Ctx ctx, String actionName) {
        if (ctx == null || ctx.documentService() == null || ctx.process() == null) {
            throw new IllegalStateException(
                    actionName + " action requires DocumentService + process context "
                            + "in the executor Ctx — engine wiring missing.");
        }
    }

    private static void requireInboxService(Ctx ctx, String actionName) {
        if (ctx == null || ctx.inboxItemService() == null || ctx.process() == null) {
            throw new IllegalStateException(
                    actionName + " action requires InboxItemService + process context "
                            + "in the executor Ctx — engine wiring missing.");
        }
    }

    private static <E extends Enum<E>> E parseEnum(
            @Nullable String value, E fallback, Class<E> type) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String renderKindBody(BranchAction.DocCreateKind d) {
        StringBuilder sb = new StringBuilder("---\nkind: ").append(d.kind()).append("\n---\n");
        if ("list".equalsIgnoreCase(d.kind()) && d.items() != null && !d.items().isEmpty()) {
            for (Map<String, Object> item : d.items()) {
                Object text = item.get("text");
                if (text == null) text = item.get("title");
                if (text == null) text = item.toString();
                sb.append("- ").append(String.valueOf(text)).append("\n");
            }
        }
        return sb.toString();
    }

    private static boolean phaseExists(StrategySpec strategy, String name) {
        for (PhaseSpec p : strategy.getPhases()) {
            if (p.getName().equals(name)) return true;
            if (p.getLoop() != null) {
                for (PhaseSpec sub : p.getLoop().getSubPhases()) {
                    if (sub.getName().equals(name)) return true;
                }
            }
        }
        return false;
    }
}
