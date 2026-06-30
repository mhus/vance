package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Backend for the {@code vance-input} block — a single editable text value
 * bound to a plain text document. Save writes the whole document content;
 * embedded views of the same document refresh live via the documents
 * channel. Single- vs. multi-line is a per-block UI flag (no backend).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceInputService {

    private final DocumentService documentService;

    /** Current text content of the bound document (empty if missing). */
    public String loadText(String tenantId, String projectId, String docPath) {
        return documentService.findByPath(tenantId, projectId, docPath)
                .map(this::read)
                .orElse("");
    }

    /** Persist the whole text content into the bound document. */
    public void saveText(
            String tenantId, String projectId, String docPath,
            @Nullable String content, String editorId) {
        String text = content != null ? content : "";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, docPath);
        if (existing.isPresent()) {
            documentService.replaceContent(
                    existing.get().getId(),
                    new ByteArrayInputStream(bytes),
                    DocumentService.mimeFromPath(docPath),
                    editorId);
        } else {
            documentService.createText(
                    tenantId, projectId, docPath, null, null, text, editorId);
        }
        log.info("WorkspaceInputService.saveText tenant='{}' doc='{}' len={}",
                tenantId, docPath, text.length());
    }

    /**
     * Create a fresh empty text document in {@code folder} with the first
     * free {@code input-<n>.md} name, returning its path so the caller can
     * insert a {@code vance-input} block for it.
     */
    public String createInput(
            String tenantId, String projectId, String folder, String editorId) {
        String base = folder == null ? "" : folder.strip();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        for (int n = 1; n <= 9999; n++) {
            String path = (base.isEmpty() ? "" : base + "/") + "input-" + n + ".md";
            if (documentService.findByPath(tenantId, projectId, path).isEmpty()) {
                documentService.createText(tenantId, projectId, path, null, null, "", editorId);
                log.info("WorkspaceInputService.createInput tenant='{}' doc='{}'", tenantId, path);
                return path;
            }
        }
        throw new ToolException("Could not allocate a free input document name in " + base);
    }

    private String read(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("Could not read '" + doc.getPath() + "': " + e.getMessage());
        }
    }
}
