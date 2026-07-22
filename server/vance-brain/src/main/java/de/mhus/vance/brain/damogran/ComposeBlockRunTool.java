package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.compose.ComposeBlockCodec;
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
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The {@code compose_block_run} tool — runs the compose manifest held by a
 * document (a {@code kind: compose} doc, addressed by {@code id} or
 * {@code path}) and writes the result back into the doc's managed
 * {@code $output:}/{@code $run:} blocks, so an open editor updates live.
 *
 * <p>This is the server-authoritative counterpart of the browser's ▶ button
 * (see {@code planning/agent-compose-run.md}): the run reads the manifest from
 * the <b>stored</b> document, so a prior {@code doc_edit} in the same turn is
 * already reflected — no client round-trip, no race. The result is written back
 * through the same buffer/identity path the {@code doc_*} tools use, so the
 * {@code documents.changed} live event fires with the tool as author.
 *
 * <p>Runs are <b>async</b>: a quick compose returns its per-task status +
 * outputs inline and updates {@code $output:} immediately; a longer one parks a
 * {@code $run:} marker, returns {@code {runId, running:true}}, and the calling
 * process receives a {@code COMPOSE_FINISHED} event (and the block's
 * {@code $output:} is updated) when it completes.
 */
@Component
public class ComposeBlockRunTool implements Tool {

    /** How long the tool blocks for a quick result before handing back a runId. */
    private static final long FAST_PATH_WAIT_MS = 15_000;

    private final DamogranComposeService composeService;
    private final DocumentService documentService;
    private final KindToolSupport support;
    private final ComposeFinishedNotifier finishedNotifier;

    public ComposeBlockRunTool(DamogranComposeService composeService,
                               DocumentService documentService,
                               KindToolSupport support,
                               ComposeFinishedNotifier finishedNotifier) {
        this.composeService = composeService;
        this.documentService = documentService;
        this.support = support;
        this.finishedNotifier = finishedNotifier;
    }

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", KindToolSupport.documentSelectorProperties(),
            "required", List.of());

    @Override
    public String name() {
        return "compose_block_run";
    }

    @Override
    public String description() {
        return "Run the compose manifest held by a document (a kind:compose document, "
                + "addressed by id or path) and write the produced artifacts back into the "
                + "document's $output: block — so an open editor shows them live, exactly as "
                + "if the user had pressed Run. Use this to actually EXECUTE a compose the "
                + "user is working on, not just to edit it. The run reads the stored "
                + "document, so edits you made earlier this turn are included. Runs async: a "
                + "quick compose returns per-task status + outputs inline; a long one returns "
                + "{runId, running:true} and you receive a COMPOSE_FINISHED event when it "
                + "completes. Halts at the first failing task.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Set<String> labels() {
        return Set.of("write", "document");
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
        String manifest = support.readBody(doc, ctx);
        String projectId = ctx.resolveLocalProjectId();
        // Relative vance: imports resolve against the compose document's directory.
        String baseDir = DamogranUri.parentDir(doc.getPath());

        ComposeRun run;
        try {
            run = composeService.runAsync(ctx.tenantId(), projectId, ctx.processId(), manifest, baseDir);
        } catch (DamogranException e) {
            throw new ToolException(e.getMessage());
        }
        try {
            run.awaitDone(FAST_PATH_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (run.isTerminal() && run.result() != null) {
            writeResultBack(docId, run.result(), ctx);
            return DamogranResponse.toMap(run.result());
        }
        // Still running: park the $run marker (an open editor reflects it / a
        // refresh resumes polling), then write the result back + notify this
        // process on completion so it can sleep and resume on the event.
        support.writeBody(doc, ComposeBlockCodec.writeComposeRun(
                manifest, new ComposeRunMarker(run.runId(), Instant.now().toString())), ctx);
        String ownerProcessId = ctx.processId();
        run.onDone(finished -> {
            if (finished.result() != null) {
                writeResultBack(docId, finished.result(), ctx);
            }
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

    /**
     * Write a finished run's outcome into the document's managed block: the
     * {@code $output:} list on success (unless a user-authored {@code output:}
     * override is present), otherwise just drop the managed block. Reads the
     * current stored content fresh so a concurrent edit isn't clobbered wholesale.
     */
    private void writeResultBack(String docId, DamogranComposeResult result, ToolInvocationContext ctx) {
        DocumentDocument fresh = documentService.findById(docId).orElse(null);
        if (fresh == null) {
            return;
        }
        String current = documentService.readContent(fresh);
        String updated = result.isSuccess() && ComposeBlockCodec.readFixedOutputs(current).isEmpty()
                ? ComposeBlockCodec.writeComposeOutputs(current, DamogranResponse.managedOutputs(result))
                : ComposeBlockCodec.clearComposeManaged(current);
        if (!updated.equals(current)) {
            support.writeBody(fresh, updated, ctx);
        }
    }
}
