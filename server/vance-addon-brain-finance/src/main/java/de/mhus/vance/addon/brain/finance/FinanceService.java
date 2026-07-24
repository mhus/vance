package de.mhus.vance.addon.brain.finance;

import de.mhus.vance.addon.brain.finance.model.FinanceComputed;
import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceProjection;
import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import de.mhus.vance.addon.brain.finance.model.FinanceValue;
import de.mhus.vance.addon.brain.finance.model.NodeSnapshot;
import de.mhus.vance.addon.brain.finance.model.PeriodUnit;
import de.mhus.vance.addon.brain.finance.report.FinanceReport;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    // ── Create ────────────────────────────────────────────────────

    /** Create a new empty {@code finance-tree} document (no root yet). */
    public DocumentDocument create(String tenantId, String projectId, String path,
                                   @Nullable String title, @Nullable String description,
                                   @Nullable String userId) {
        String normalised = ensureExtension(path.trim());
        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, normalised);
        if (existing.isPresent()) {
            throw new ToolException("A finance-tree already exists at '" + normalised + "'.");
        }
        String mime = mimeForPath(normalised);
        String body = FinanceTreeCodec.serialize(FinanceTreeDocument.empty(title, description), mime);
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            DocumentDocument stored = documentService.create(
                    tenantId, projectId, normalised, title, List.of(KIND), mime, in, userId,
                    contextFactory.writeActor(tenantId, userId, normalised));
            log.info("FinanceService.create path='{}'", normalised);
            return stored;
        } catch (IOException e) {
            throw new ToolException(
                    "Could not write finance-tree '" + normalised + "': " + e.getMessage());
        }
    }

    /** Persist a generated report as a new document (its kind lives in the body). */
    public DocumentDocument createReport(String tenantId, String projectId, String path,
                                         FinanceReport report, @Nullable String userId) {
        try (InputStream in = new ByteArrayInputStream(
                report.body().getBytes(StandardCharsets.UTF_8))) {
            return documentService.create(
                    tenantId, projectId, path.trim(), null, List.of(report.outputKind()),
                    report.mimeType(), in, userId,
                    contextFactory.writeActor(tenantId, userId, path.trim()));
        } catch (IOException e) {
            throw new ToolException("Could not write report '" + path + "': " + e.getMessage());
        }
    }

    // ── Node / value mutations (read-modify-write) ────────────────

    public DocumentDocument addNode(DocumentDocument doc, @Nullable String parentName,
                                    FinanceNode child, @Nullable String userId) {
        return writeDocument(doc, FinanceTreeOps.addChild(readDocument(doc), parentName, child),
                null, userId);
    }

    public DocumentDocument updateNode(DocumentDocument doc, String name,
                                       Map<String, Object> patch, @Nullable String userId) {
        return writeDocument(doc, FinanceTreeOps.updateNode(readDocument(doc), name, patch),
                null, userId);
    }

    public DocumentDocument removeNode(DocumentDocument doc, String name, @Nullable String userId) {
        return writeDocument(doc, FinanceTreeOps.removeNode(readDocument(doc), name), null, userId);
    }

    public DocumentDocument setValues(DocumentDocument doc, String name,
                                      List<FinanceValue> values, @Nullable String userId) {
        return writeDocument(doc, FinanceTreeOps.setValues(readDocument(doc), name, values),
                null, userId);
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
    /**
     * Compute the snapshot <em>without</em> persisting — a read-only view for
     * the embedded summary / preview. {@link #recalculate} is the write path.
     */
    public FinanceComputed snapshot(DocumentDocument doc) {
        FinanceTreeDocument tree = readDocument(doc);
        List<NodeSnapshot> nodes = tree.root() == null
                ? List.of()
                : FinanceCalculator.compute(tree.root(), LocalDate.now(ZoneOffset.UTC));
        return new FinanceComputed(Instant.now().toString(), nodes);
    }

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

    private static String ensureExtension(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".json")) {
            return path;
        }
        if (lower.endsWith(".finance-tree")) return path + ".yaml";
        return path + ".finance-tree.yaml";
    }

    private static String mimeForPath(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".json") ? "application/json" : DEFAULT_MIME;
    }
}
