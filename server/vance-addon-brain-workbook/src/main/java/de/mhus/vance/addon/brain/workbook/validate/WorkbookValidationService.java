package de.mhus.vance.addon.brain.workbook.validate;

import de.mhus.vance.addon.brain.workbook.WorkbookFolderReader;
import de.mhus.vance.addon.brain.workbook.WorkbookPage;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Read-only static validator for a workbook folder or a single workpage.
 * Extracts every {@code vance-*} fence and dispatches it to the matching
 * {@link BlockValidator} (Spring injects the full registry — new block types
 * plug in with no change here), then adds folder-level structure checks.
 * Checks structure + references only, never runtime logic.
 */
@Service
public class WorkbookValidationService {

    private final DocumentService documentService;
    private final WorkbookFolderReader folderReader;
    private final FenceExtractor fenceExtractor;
    private final List<BlockValidator> blockValidators;
    private final WorkbookStructureValidator structureValidator;

    public WorkbookValidationService(
            DocumentService documentService,
            WorkbookFolderReader folderReader,
            FenceExtractor fenceExtractor,
            List<BlockValidator> blockValidators,
            WorkbookStructureValidator structureValidator) {
        this.documentService = documentService;
        this.folderReader = folderReader;
        this.fenceExtractor = fenceExtractor;
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
        String body = read(doc);
        List<FenceBlock> fences = fenceExtractor.extract(doc.getPath(), body);
        ValidationContext ctx = new ValidationContext(doc.getPath(), docs);
        for (FenceBlock fence : fences) {
            if (fence.parseError() != null) {
                findings.add(Finding.error(fence.location(), "fence-parse",
                        fence.parseError()));
                continue;
            }
            for (BlockValidator v : blockValidators) {
                if (v.supports(fence.type())) {
                    findings.addAll(v.validate(fence, ctx));
                }
            }
        }
        return fences.size();
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
