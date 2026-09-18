package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.addon.brain.scribble.model.ScribbleSheet;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * High-level operations on {@code kind: scribble} documents — built on
 * top of {@link DocumentService} (no MongoDB collections of its own).
 *
 * <p>Every mutation is a read-modify-write through {@link ScribbleCodec};
 * the sheet is stored whole, which the size calculus in
 * {@code planning/scribble.md} §4.4 covers for v1 (no append API).
 * Concurrent edits resolve via {@link DocumentService}'s optimistic
 * locking, as with every other document kind.
 */
@Service
@Slf4j
public class ScribbleService {

    public static final String KIND = "scribble";
    private static final String DEFAULT_MIME = "application/yaml";

    private final DocumentService documentService;
    private final SecurityContextFactory contextFactory;

    public ScribbleService(DocumentService documentService, SecurityContextFactory contextFactory) {
        this.documentService = documentService;
        this.contextFactory = contextFactory;
    }

    // ── Create / read / write ─────────────────────────────────────

    public DocumentDocument create(
            String tenantId, String projectId, String path, @Nullable String title, @Nullable String userId) {
        String normalisedPath = ensureExtension(path.trim());
        Optional<DocumentDocument> existing = documentService.findByPath(tenantId, projectId, normalisedPath);
        if (existing.isPresent()) {
            throw new ToolException("Scribble already exists at '" + normalisedPath + "'.");
        }
        String mime = mimeForPath(normalisedPath);
        String body = ScribbleCodec.serialize(ScribbleSheet.empty(title), mime);
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            DocumentDocument stored = documentService.create(
                    tenantId,
                    projectId,
                    normalisedPath,
                    title,
                    List.of(KIND),
                    mime,
                    in,
                    userId,
                    contextFactory.writeActor(tenantId, userId, normalisedPath));
            log.info("ScribbleService.create tenant='{}' project='{}' path='{}'", tenantId, projectId, normalisedPath);
            return stored;
        } catch (IOException e) {
            throw new ToolException("Could not write scribble '" + normalisedPath + "': " + e.getMessage(), e);
        }
    }

    public ScribbleSheet readSheet(DocumentDocument doc) {
        String body = readBody(doc);
        String mime = ScribbleCodec.supports(doc.getMimeType()) ? doc.getMimeType() : DEFAULT_MIME;
        return ScribbleCodec.parse(body, mime);
    }

    public DocumentDocument writeSheet(DocumentDocument doc, ScribbleSheet sheet, @Nullable String userId) {
        String mime = ScribbleCodec.supports(doc.getMimeType()) ? doc.getMimeType() : DEFAULT_MIME;
        String body = ScribbleCodec.serialize(sheet, mime);
        // A scribble save is user-initiated, so the write must carry the acting
        // user's subject — never a hardcoded null, which SecurityContextFactory
        // maps to SecurityContext.SYSTEM and thereby fail-opens the per-document
        // authz (reserved-prefix ADMIN + $meta.privileged gate). Matches create().
        return documentService.update(
                doc.getId(),
                sheet.title() != null ? sheet.title() : doc.getTitle(),
                null, // tags
                body,
                null, // path
                null, // autoSummary
                null, // summaryDirty
                null, // ragEnabled
                mime,
                DocumentService.TOOL_IDENTITY,
                contextFactory.writeActor(doc.getTenantId(), userId, doc.getPath()));
    }

    private String readBody(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("Could not load scribble '" + doc.getPath() + "': " + e.getMessage(), e);
        }
    }

    // ── Path helpers ──────────────────────────────────────────────

    private static String ensureExtension(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".json")) {
            return path;
        }
        if (lower.endsWith(".scribble")) return path + ".yaml";
        return path + ".scribble.yaml";
    }

    private static String mimeForPath(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".json") ? "application/json" : DEFAULT_MIME;
    }
}
