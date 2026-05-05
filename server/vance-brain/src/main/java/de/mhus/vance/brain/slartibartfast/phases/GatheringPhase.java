package de.mhus.vance.brain.slartibartfast.phases;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.EvidenceSource;
import de.mhus.vance.api.slartibartfast.EvidenceType;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.Rationale;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * GATHERING phase — reads every available manual document under
 * the project's {@code manuals/} folder and turns each into an
 * {@link EvidenceSource} with a {@link Rationale} explaining why
 * the source was consulted. No LLM call in v1; the planner reads
 * everything available and lets CLASSIFYING decide what's
 * relevant.
 *
 * <p>v1 strategy ("read all available manuals, no LLM filter"):
 * deterministic, cheap, predictable. v2 could add an LLM-driven
 * relevance filter that culls obviously-irrelevant manuals before
 * classification — premature now, since CLASSIFYING already has
 * to look at every claim anyway.
 *
 * <p>Idempotent on re-entry: re-running rebuilds
 * {@code evidenceSources} from scratch off the document cascade.
 * Same as CONFIRMING — recovery rollbacks must produce a fresh
 * gather, not pile new entries onto an old set.
 *
 * <p>Empty-manuals case is fine: the resulting empty
 * {@code evidenceSources} list is recorded in the iteration audit
 * with {@code outputSummary="0 manuals"}; downstream phases
 * handle it (CLASSIFYING produces zero claims, DECOMPOSING marks
 * subgoals speculative).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GatheringPhase {

    /** Document-cascade prefix Slartibartfast scans for manuals.
     *  Same convention the existing {@code manual_read} tool uses. */
    public static final String MANUALS_PREFIX = "manuals/";

    /** Per-source content cap. Manuals over this size get
     *  truncated with a marker — protects engineParams against
     *  pathological doc sizes. Adjust if real-world manuals
     *  routinely exceed it. */
    private static final int MAX_CONTENT_CHARS = 32_000;

    private final DocumentService documentService;

    /**
     * Mutates {@code state.evidenceSources} and
     * {@code state.rationales}; appends one
     * {@link PhaseIteration} entry. Caller advances the status.
     */
    public void execute(
            ArchitectState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        String tenantId = process.getTenantId();
        String projectId = process.getProjectId();

        List<DocumentDocument> manuals = listManuals(tenantId, projectId);

        // Rebuild from scratch — recovery rollback must not pile.
        List<EvidenceSource> sources = new ArrayList<>(manuals.size());
        List<Rationale> rationalePool = new ArrayList<>(state.getRationales());
        int seq = nextEvidenceSeq(state);
        int rationaleSeq = rationalePool.size() + 1;

        for (DocumentDocument doc : manuals) {
            String content = readContent(doc);
            String sourceId = "ev" + seq++;
            String rationaleId = "rt" + rationaleSeq++;

            rationalePool.add(Rationale.builder()
                    .id(rationaleId)
                    .text("manual '" + doc.getPath()
                            + "' available in project — read for "
                            + "potential relevance to the framed goal")
                    .sourceRefs(List.of(sourceId))
                    .inferredAt(ArchitectStatus.GATHERING)
                    .build());

            sources.add(EvidenceSource.builder()
                    .id(sourceId)
                    .type(EvidenceType.MANUAL)
                    .path(doc.getPath())
                    .content(content)
                    .gatheringRationaleId(rationaleId)
                    .build());
        }

        state.setEvidenceSources(sources);
        state.setRationales(rationalePool);

        appendIteration(state,
                "scanned project for manuals/ documents",
                manuals.size() + " manual" + (manuals.size() == 1 ? "" : "s"),
                PhaseIteration.IterationOutcome.PASSED);

        log.info("Slartibartfast id='{}' GATHERING ingested {} manual(s)",
                process.getId(), manuals.size());
    }

    // ──────────────────── Listing + reading ────────────────────

    private List<DocumentDocument> listManuals(String tenantId, String projectId) {
        // listByProject is the simplest path — pulls everything
        // and we filter by path-prefix in memory. Manuals are
        // typically a handful per project, so the in-memory filter
        // costs nothing.
        List<DocumentDocument> all = documentService.listByProject(tenantId, projectId);
        List<DocumentDocument> manuals = new ArrayList<>();
        for (DocumentDocument doc : all) {
            String path = doc.getPath();
            if (path != null && path.startsWith(MANUALS_PREFIX)) {
                manuals.add(doc);
            }
        }
        // Stable ordering — alphabetical by path. Determines
        // ev-id assignment so audit reproducibility doesn't depend
        // on the document repository's iteration order.
        manuals.sort(Comparator.comparing(DocumentDocument::getPath));
        return manuals;
    }

    private String readContent(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            int total = 0;
            int cap = MAX_CONTENT_CHARS;
            while ((read = in.read(chunk)) > 0) {
                int toCopy = Math.min(read, cap - total);
                if (toCopy > 0) {
                    buf.write(chunk, 0, toCopy);
                    total += toCopy;
                }
                if (total >= cap) break;
            }
            String content = buf.toString(StandardCharsets.UTF_8);
            if (in.read() != -1) {
                content = content + "\n\n[…content truncated at "
                        + MAX_CONTENT_CHARS + " chars…]";
            }
            return content;
        } catch (IOException e) {
            log.warn("Slartibartfast GATHERING: failed to read manual '{}': {}",
                    doc.getPath(), e.toString());
            return "";
        }
    }

    private static int nextEvidenceSeq(ArchitectState state) {
        // ev-ids are assigned sequentially; on a fresh GATHERING
        // pass we start from 1. We DON'T preserve old ev-ids on
        // recovery — the source list is rebuilt and downstream
        // claims/subgoals that referenced old ids must be
        // re-derived too.
        return 1;
    }

    private static void appendIteration(
            ArchitectState state,
            String inputSummary,
            String outputSummary,
            PhaseIteration.IterationOutcome outcome) {
        int attempt = (int) state.getIterations().stream()
                .filter(it -> it.getPhase() == ArchitectStatus.GATHERING).count() + 1;
        List<PhaseIteration> log = new ArrayList<>(state.getIterations());
        log.add(PhaseIteration.builder()
                .iteration(attempt)
                .phase(ArchitectStatus.GATHERING)
                .triggeredBy(attempt == 1 ? "initial" : "recovery")
                .inputSummary(inputSummary)
                .outputSummary(outputSummary)
                .outcome(outcome)
                .build());
        state.setIterations(log);
    }
}
