package de.mhus.vance.addon.brain.finance;

import de.mhus.vance.addon.brain.finance.model.FinanceComputed;
import de.mhus.vance.addon.brain.finance.model.FinanceProjection;
import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import de.mhus.vance.addon.brain.finance.model.NodeSnapshot;
import de.mhus.vance.addon.brain.finance.model.PeriodUnit;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * High-level operations on {@code kind: finance-tree} documents — built on top
 * of {@link DocumentService} (no MongoDB collections of its own; data
 * sovereignty via the shared document machinery).
 *
 * <p>The math lives once in the pure {@link FinanceCalculator}; this service is
 * the I/O boundary: read the body via {@link FinanceTreeCodec}, compute the
 * snapshot, write it back server-authoritatively under {@code $computed}. A
 * write is a full read-modify-write with {@link DocumentService}'s optimistic
 * locking, like every other document kind.
 */
@Service
@Slf4j
public class FinanceService {

    public static final String KIND = FinanceTreeCodec.KIND;
    private static final String DEFAULT_MIME = "application/yaml";

    private final DocumentService documentService;
    private final SecurityContextFactory contextFactory;

    public FinanceService(DocumentService documentService,
                          SecurityContextFactory contextFactory) {
        this.documentService = documentService;
        this.contextFactory = contextFactory;
    }

    // ── Read / write ──────────────────────────────────────────────

    public FinanceTreeDocument readDocument(DocumentDocument doc) {
        String mime = FinanceTreeCodec.supports(doc.getMimeType()) ? doc.getMimeType() : DEFAULT_MIME;
        return FinanceTreeCodec.parse(readBody(doc), mime);
    }

    public DocumentDocument writeDocument(DocumentDocument doc, FinanceTreeDocument tree,
                                          @Nullable FinanceComputed computed,
                                          @Nullable String userId) {
        String mime = FinanceTreeCodec.supports(doc.getMimeType()) ? doc.getMimeType() : DEFAULT_MIME;
        String body = FinanceTreeCodec.serialize(tree, computed, mime);
        return documentService.update(
                doc.getId(),
                tree.title() != null ? tree.title() : doc.getTitle(),
                null, body, null, null, null, null, mime,
                DocumentService.TOOL_IDENTITY,
                contextFactory.writeActor(doc.getTenantId(), userId, doc.getPath()));
    }

    // ── Snapshot recalculation ("reload") ─────────────────────────

    /**
     * Recompute the current-value snapshot for {@code today} and write it back
     * under {@code $computed}. Returns the computed overlay (also handed to the
     * caller for an immediate response, e.g. the {@code finance_tree_calc} tool
     * / REST {@code /calc}).
     */
    public FinanceComputed recalculate(DocumentDocument doc, @Nullable String userId) {
        FinanceTreeDocument tree = readDocument(doc);
        List<NodeSnapshot> nodes = tree.root() == null
                ? List.of()
                : FinanceCalculator.compute(tree.root(), LocalDate.now(ZoneOffset.UTC));
        FinanceComputed computed = new FinanceComputed(Instant.now().toString(), nodes);
        writeDocument(doc, tree, computed, userId);
        log.debug("FinanceService.recalculate path='{}' nodes={}", doc.getPath(), nodes.size());
        return computed;
    }

    // ── Projection (on-demand, not persisted) ─────────────────────

    /**
     * Project the tree over {@code [from, to)} at {@code granularity}. Pure
     * read — never writes the document; the result feeds the editor preview,
     * the {@code /project} REST endpoint and the report processors.
     */
    public FinanceProjection project(DocumentDocument doc, LocalDate from, LocalDate to,
                                     PeriodUnit granularity) {
        FinanceTreeDocument tree = readDocument(doc);
        if (tree.root() == null) {
            return new FinanceProjection(List.of(), List.of());
        }
        return FinanceProjector.project(tree.root(), from, to, granularity);
    }

    // ── Internal ──────────────────────────────────────────────────

    private String readBody(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException(
                    "Could not load finance-tree '" + doc.getPath() + "': " + e.getMessage());
        }
    }
}
