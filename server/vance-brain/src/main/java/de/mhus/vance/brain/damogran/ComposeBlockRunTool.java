package de.mhus.vance.brain.damogran;

import de.mhus.vance.api.ws.SignalFrame;
import de.mhus.vance.brain.tools.client.CortexTurnSelectionHolder;
import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.brain.ws.signals.SignalBroadcaster;
import de.mhus.vance.shared.compose.ComposeBlockCodec;
import de.mhus.vance.shared.compose.ComposeFenceLocator;
import de.mhus.vance.shared.compose.ComposeRunMarker;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The {@code compose_block_run} tool — runs the compose manifest held by a
 * document and writes the result back into the doc's managed
 * {@code $output:}/{@code $run:} blocks, so an open editor updates live.
 *
 * <p>Two shapes are handled (see {@code planning/agent-compose-run.md}):
 * <ul>
 *   <li>a top-level {@code kind: compose} document — the whole document is the
 *       manifest;</li>
 *   <li>an inline {@code ```vance-compose} block inside a workpage — the block
 *       the user has selected (via the chat-bound selection) is run, or the
 *       single block if there is exactly one. The result is spliced back into
 *       <em>that</em> fence, leaving the rest of the page untouched.</li>
 * </ul>
 *
 * <p>Server-authoritative: the run reads the <b>stored</b> document, so a prior
 * {@code doc_edit} in the same turn is already reflected — no client round-trip,
 * no race. The write-back goes through the same buffer/identity path the
 * {@code doc_*} tools use, so {@code documents.changed} fires with the tool as
 * author. Runs are async like {@code compose_run} (inline result or a
 * {@code COMPOSE_FINISHED} event; a long run parks a {@code $run:} marker).
 */
@Component
public class ComposeBlockRunTool implements Tool {

    /** How long the tool blocks for a quick result before handing back a runId. */
    private static final long FAST_PATH_WAIT_MS = 15_000;

    private final DamogranComposeService composeService;
    private final DocumentService documentService;
    private final KindToolSupport support;
    private final ComposeFinishedNotifier finishedNotifier;
    private final CortexTurnSelectionHolder selectionHolder;
    private final SignalBroadcaster signalBroadcaster;

    public ComposeBlockRunTool(DamogranComposeService composeService,
                               DocumentService documentService,
                               KindToolSupport support,
                               ComposeFinishedNotifier finishedNotifier,
                               CortexTurnSelectionHolder selectionHolder,
                               SignalBroadcaster signalBroadcaster) {
        this.composeService = composeService;
        this.documentService = documentService;
        this.support = support;
        this.finishedNotifier = finishedNotifier;
        this.selectionHolder = selectionHolder;
        this.signalBroadcaster = signalBroadcaster;
    }

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", KindToolSupport.documentSelectorProperties(),
            "required", List.of());

    /** What to run + how to write the result back: an inline fence, or the whole doc. */
    private record Target(String manifest, ComposeFenceLocator.@Nullable Fence fence) {}

    @Override
    public String name() {
        return "compose_block_run";
    }

    @Override
    public String description() {
        return "Run the compose held by a document and write the produced artifacts back into "
                + "its $output: block — so an open editor shows them live, exactly as if the user "
                + "had pressed Run. Works on a top-level kind:compose document, and on an inline "
                + "compose block inside a workpage: the block the user selected is run (or the "
                + "single block if there is only one). Use this to actually EXECUTE a compose the "
                + "user is working on, not just to edit it. The run reads the stored document, so "
                + "edits you made earlier this turn are included. Runs async: a quick compose "
                + "returns per-task status + outputs inline; a long one returns {runId, running:true} "
                + "and you receive a COMPOSE_FINISHED event when it completes. Halts at the first "
                + "failing task.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("write", "document");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("compose_block_run requires a tenant scope");
        }
        DocumentDocument doc = support.loadDocument(params, ctx);
        String docId = doc.getId();
        String path = doc.getPath();
        String body = support.readBody(doc, ctx);
        Target target = resolveTarget(body, docId, ctx);
        String projectId = ctx.resolveLocalProjectId();
        // Relative vance: imports resolve against the compose document's directory.
        String baseDir = DamogranUri.parentDir(path);

        ComposeRun run;
        try {
            run = composeService.runAsync(ctx.tenantId(), projectId, ctx.processId(), target.manifest(), baseDir);
        } catch (DamogranException e) {
            throw new ToolException(e.getMessage());
        }
        String tenantId = ctx.tenantId();
        // Ephemeral "running" signal so an open editor reflects it immediately —
        // no document write (see planning/agent-compose-run.md §5).
        emitSignal(tenantId, path, run.runId(), "running", run.workspaceName());
        try {
            run.awaitDone(FAST_PATH_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (run.isTerminal() && run.result() != null) {
            writeResultBack(docId, target, run.result(), ctx);
            emitSignal(tenantId, path, run.runId(), statusOf(run.result()), run.workspaceName());
            return DamogranResponse.toMap(run.result());
        }
        // Still running: park the $run marker (an open editor reflects it / a
        // refresh resumes polling), then write the result back + notify this
        // process on completion so it can sleep and resume on the event.
        String parked = target.fence() == null
                ? ComposeBlockCodec.writeComposeRun(body, marker(run))
                : ComposeFenceLocator.replaceYaml(body, target.fence(),
                        ComposeBlockCodec.writeComposeRun(target.manifest(), marker(run)));
        support.writeBody(doc, parked, ctx);
        String ownerProcessId = ctx.processId();
        run.onDone(finished -> {
            if (finished.result() != null) {
                writeResultBack(docId, target, finished.result(), ctx);
            }
            emitSignal(tenantId, path, finished.runId(),
                    finished.result() != null ? statusOf(finished.result()) : "failed",
                    finished.workspaceName());
            if (ownerProcessId != null && !ownerProcessId.isBlank()) {
                finishedNotifier.notifyFinished(finished, ownerProcessId);
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.runId());
        out.put("running", true);
        out.put("status", "running");
        out.put("workspace", run.workspaceName());
        out.put("documentId", docId);
        out.put("note", "Compose is running in the background; end your turn — you will receive "
                + "a COMPOSE_FINISHED event and the block's $output will be updated when it completes.");
        return out;
    }

    private static ComposeRunMarker marker(ComposeRun run) {
        return new ComposeRunMarker(run.runId(), Instant.now().toString());
    }

    private static String statusOf(DamogranComposeResult result) {
        return result.isSuccess() ? "done" : "failed";
    }

    /** Fire an ephemeral {@code compose-run} signal on the doc's path (no DB write). */
    private void emitSignal(String tenantId, String path, String runId, String status,
                            @Nullable String workspace) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", runId);
        data.put("status", status);
        if (workspace != null) {
            data.put("workspace", workspace);
        }
        signalBroadcaster.broadcast(tenantId,
                SignalFrame.builder().path(path).signal("compose-run").data(data).build());
    }

    /**
     * Decide what to run: an inline {@code ```vance-compose} fence (the selected
     * one, or the single one), or the whole document when it carries no fence
     * (a top-level {@code kind: compose} doc).
     */
    private Target resolveTarget(String body, String docId, ToolInvocationContext ctx) {
        List<ComposeFenceLocator.Fence> fences = ComposeFenceLocator.findAll(body);
        if (fences.isEmpty()) {
            return new Target(body, null);
        }
        CortexTurnSelectionHolder.Selection sel = selectionHolder.get(ctx.processId());
        if (sel != null && Objects.equals(sel.documentId(), docId)) {
            ComposeFenceLocator.Fence hit = ComposeFenceLocator.findAt(fences, sel.from());
            if (hit != null) {
                return new Target(hit.yaml(), hit);
            }
        }
        if (fences.size() == 1) {
            return new Target(fences.get(0).yaml(), fences.get(0));
        }
        throw new ToolException("This document has multiple compose blocks — select the one to run "
                + "(click into it) so I know which to execute.");
    }

    /**
     * Write a finished run's outcome back: the {@code $output:} list on success
     * (unless a user-authored {@code output:} override is present), else drop the
     * managed block. Reads the current stored content fresh and — for an inline
     * fence — re-locates the fence (selection → manifest content-match → single)
     * so a concurrent edit elsewhere on the page isn't clobbered. Skips silently
     * if the fence can no longer be identified.
     */
    private void writeResultBack(String docId, Target target, DamogranComposeResult result, ToolInvocationContext ctx) {
        DocumentDocument fresh = documentService.findById(docId).orElse(null);
        if (fresh == null) {
            return;
        }
        String current = documentService.readContent(fresh);
        if (target.fence() == null) {
            String updated = applyResult(current, result);
            if (!updated.equals(current)) {
                support.writeBody(fresh, updated, ctx);
            }
            return;
        }
        ComposeFenceLocator.Fence f = relocate(current, target.manifest(), docId, ctx);
        if (f == null) {
            return;
        }
        String newYaml = applyResult(f.yaml(), result);
        if (!newYaml.equals(f.yaml())) {
            support.writeBody(fresh, ComposeFenceLocator.replaceYaml(current, f, newYaml), ctx);
        }
    }

    private static String applyResult(String yaml, DamogranComposeResult result) {
        return result.isSuccess() && ComposeBlockCodec.readFixedOutputs(yaml).isEmpty()
                ? ComposeBlockCodec.writeComposeOutputs(yaml, DamogranResponse.managedOutputs(result))
                : ComposeBlockCodec.clearComposeManaged(yaml);
    }

    /** Re-identify the fence in the (possibly changed) fresh content. */
    private ComposeFenceLocator.@Nullable Fence relocate(
            String current, String runManifest, String docId, ToolInvocationContext ctx) {
        List<ComposeFenceLocator.Fence> fences = ComposeFenceLocator.findAll(current);
        if (fences.isEmpty()) {
            return null;
        }
        CortexTurnSelectionHolder.Selection sel = selectionHolder.get(ctx.processId());
        if (sel != null && Objects.equals(sel.documentId(), docId)) {
            ComposeFenceLocator.Fence hit = ComposeFenceLocator.findAt(fences, sel.from());
            if (hit != null) {
                return hit;
            }
        }
        String key = ComposeBlockCodec.stripManagedBlock(runManifest);
        for (ComposeFenceLocator.Fence f : fences) {
            if (ComposeBlockCodec.stripManagedBlock(f.yaml()).equals(key)) {
                return f;
            }
        }
        return fences.size() == 1 ? fences.get(0) : null;
    }
}
