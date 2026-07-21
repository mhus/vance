package de.mhus.vance.addon.brain.workbook.validate;

import de.mhus.vance.addon.brain.workbook.WorkbookFolderReader;
import de.mhus.vance.addon.brain.workbook.WorkbookPage;
import de.mhus.vance.addon.brain.workpage.Block;
import de.mhus.vance.addon.brain.workpage.WorkPageParser;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Read-only static validator for a workbook folder or a single workpage.
 * Parses each workpage with the <b>canonical</b> {@link WorkPageParser} (the
 * one server-side fence parser — no second parser) and dispatches every
 * {@link Block} to the matching {@link BlockValidator} (Spring injects the
 * full registry — new block types plug in with no change here), then adds
 * folder-level structure checks. Checks structure + references only, never
 * runtime logic.
 */
@Service
public class WorkbookValidationService {

    /** Fence types this validator covers — used to flag malformed ones. */
    private static final Set<String> KNOWN_FENCES = Set.of(
            "vance-form", "vance-input", "vance-button", "vance-embed");

    private final DocumentService documentService;
    private final WorkbookFolderReader folderReader;
    private final WorkPageParser workPageParser;
    private final List<BlockValidator> blockValidators;
    private final WorkbookStructureValidator structureValidator;

    public WorkbookValidationService(
            DocumentService documentService,
            WorkbookFolderReader folderReader,
            WorkPageParser workPageParser,
            List<BlockValidator> blockValidators,
            WorkbookStructureValidator structureValidator) {
        this.documentService = documentService;
        this.folderReader = folderReader;
        this.workPageParser = workPageParser;
        this.blockValidators = blockValidators;
        this.structureValidator = structureValidator;
    }

    /** Aggregated validation result for one workbook/page. */
    public record Result(
            String target,
            List<Finding> findings,
            int pagesChecked,
            int blocksChecked) {

        public boolean ok() {
            return findings.stream().noneMatch(f -> f.level() == Finding.Level.ERROR);
        }

        public Map<String, Object> toMap() {
            long errors = findings.stream().filter(f -> f.level() == Finding.Level.ERROR).count();
            long warnings = findings.size() - errors;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("target", target);
            m.put("ok", ok());
            m.put("errors", errors);
            m.put("warnings", warnings);
            m.put("pagesChecked", pagesChecked);
            m.put("blocksChecked", blocksChecked);
            m.put("findings", findings.stream().map(Finding::toMap).toList());
            return m;
        }
    }

    /**
     * Validate {@code path}: a workbook folder (has {@code _app.yaml}) or a
     * single {@code kind: workpage} document.
     */
    public Result validate(String tenantId, String projectId, String path) {
        DocRefs docs = new ServiceDocRefs(tenantId, projectId);
        String p = path == null ? "" : path.strip();
        if (p.endsWith("/_app.yaml")) {
            p = p.substring(0, p.length() - "/_app.yaml".length());
        }

        Optional<DocumentDocument> asDoc = documentService.findByPath(tenantId, projectId, p);
        if (asDoc.isPresent() && "workpage".equals(asDoc.get().getKind())) {
            List<Finding> findings = new ArrayList<>();
            int blocks = validatePage(asDoc.get(), docs, findings);
            return new Result(p, findings, 1, blocks);
        }

        if (documentService.findByPath(tenantId, projectId, p + "/" + WorkbookFolderReader.APP_MANIFEST)
                .isPresent()) {
            return validateFolder(tenantId, projectId, p, docs);
        }

        throw new ToolException(
                "'" + path + "' is neither a workbook folder (no _app.yaml) nor a "
                        + "kind: workpage document.");
    }

    /**
     * Content-based core for a single workpage. Parses {@code content} with the
     * canonical parser and dispatches its blocks to the {@link BlockValidator}s
     * — no document load, so the {@code workpage} {@code KindHandler} can reuse
     * it for a pre-write (unsaved content) self-check. {@code docPath} is the
     * page's path used to resolve {@code vance:} references, {@code docs} the
     * reference facade for cross-document checks.
     */
    public Result validate(String content, String docPath, DocRefs docs) {
        List<Finding> findings = new ArrayList<>();
        int blocks = walkContent(content, docPath, docs, findings);
        return new Result(docPath, findings, 1, blocks);
    }

    private Result validateFolder(String tenantId, String projectId, String folder, DocRefs docs) {
        WorkbookFolderReader.Scan scan = folderReader.scan(tenantId, projectId, folder);
        List<Finding> findings = new ArrayList<>(structureValidator.validate(scan, docs));
        int blocks = 0;
        for (WorkbookPage page : scan.pages()) {
            blocks += validatePage(page.doc(), docs, findings);
        }
        return new Result(scan.folder(), findings, scan.pages().size(), blocks);
    }

    private int validatePage(DocumentDocument doc, DocRefs docs, List<Finding> findings) {
        return walkContent(read(doc), doc.getPath(), docs, findings);
    }

    private int walkContent(String content, String docPath, DocRefs docs, List<Finding> findings) {
        List<Block> blocks = workPageParser.parseDocument(content).blocks();
        return walk(blocks, docPath, docs, findings, new int[]{0});
    }

    /** Depth-first walk (descends into columns); returns count of checked fences. */
    private int walk(List<Block> blocks, String docPath, DocRefs docs,
                     List<Finding> findings, int[] fenceCount) {
        int checked = 0;
        for (Block b : blocks) {
            if (b instanceof Block.Columns cols) {
                for (Block.Column c : cols.columns()) {
                    checked += walk(c.blocks(), docPath, docs, findings, fenceCount);
                }
                continue;
            }
            if (b instanceof Block.UnknownFence uf && KNOWN_FENCES.contains(uf.infoString())) {
                findings.add(Finding.warning(docPath + " (" + uf.infoString() + ")",
                        "fence-unparsed",
                        "could not parse the " + uf.infoString() + " fence — malformed YAML?"));
                continue;
            }
            List<BlockValidator> matching = blockValidators.stream()
                    .filter(v -> v.supports(b)).toList();
            if (matching.isEmpty()) continue;
            String location = docPath + " (" + fenceTag(b) + " #" + (++fenceCount[0]) + ")";
            ValidationContext ctx = new ValidationContext(docPath, location, docs);
            for (BlockValidator v : matching) {
                findings.addAll(v.validate(b, ctx));
            }
            checked++;
        }
        return checked;
    }

    private static String fenceTag(Block b) {
        return switch (b) {
            case Block.Form ignored -> "vance-form";
            case Block.Input ignored -> "vance-input";
            case Block.Button ignored -> "vance-button";
            case Block.Embed ignored -> "vance-embed";
            default -> b.getClass().getSimpleName();
        };
    }

    private String read(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("Could not read '" + doc.getPath() + "': " + e.getMessage());
        }
    }

    /** {@link DocRefs} backed by {@link DocumentService} for one scope. */
    private final class ServiceDocRefs implements DocRefs {
        private final String tenantId;
        private final String projectId;

        ServiceDocRefs(String tenantId, String projectId) {
            this.tenantId = tenantId;
            this.projectId = projectId;
        }

        @Override
        public boolean exists(String path) {
            return documentService.findByPath(tenantId, projectId, path).isPresent();
        }

        @Override
        public @Nullable String kindOf(String path) {
            return documentService.findByPath(tenantId, projectId, path)
                    .map(DocumentDocument::getKind).orElse(null);
        }

        @Override
        public @Nullable Map<String, Object> readYaml(String path) {
            Optional<DocumentDocument> doc = documentService.findByPath(tenantId, projectId, path);
            if (doc.isEmpty()) return null;
            try {
                Object loaded = new Yaml().load(read(doc.get()));
                if (!(loaded instanceof Map<?, ?> m)) return null;
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getKey() != null) out.put(e.getKey().toString(), e.getValue());
                }
                return out;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
