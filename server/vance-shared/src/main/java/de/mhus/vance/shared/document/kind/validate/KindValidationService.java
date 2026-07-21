package de.mhus.vance.shared.document.kind.validate;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentHeader;
import de.mhus.vance.shared.document.DocumentHeaderParser;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.KindRegistry;
import de.mhus.vance.shared.document.kind.KindHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Generic, kind-agnostic validation entry point. Resolves the {@code kind} of
 * some content (explicit, or inferred from its {@code $meta.kind} header),
 * looks up the matching {@link KindHandler}, and delegates to its
 * {@link KindHandler#validate}. The dispatch is decentralised — adding a
 * validatable kind means overriding that handler's {@code validate}, never a
 * switch here.
 *
 * <p>Unknown-tolerance (never a hard error, so new kinds / uninstalled addons
 * are not flagged as broken):
 * <ul>
 *   <li>no kind → {@code ok}, no findings;</li>
 *   <li>kind set but no handler → one {@code WARNING} ({@code kind-unknown}),
 *       {@code ok} stays true;</li>
 *   <li>handler present → its findings decide {@code ok} (errors flip it).</li>
 * </ul>
 *
 * <p>Lives in {@code vance-shared} (Datenhoheits-Schicht — needs only
 * {@link DocumentService} + {@link KindRegistry}, no LLM). The {@code
 * kind_validate} tool and the {@code /documents/validate} REST endpoint sit in
 * {@code vance-brain} on top of this.
 */
@Service
public class KindValidationService {

    private final DocumentService documentService;
    private final KindRegistry kindRegistry;
    private final DocumentHeaderParser headerParser;

    /** Mime-types tried, in order, when inferring a kind from bare content. */
    private static final List<String> INFER_MIME_TYPES =
            List.of("text/markdown", "application/yaml", "application/json");

    public KindValidationService(
            DocumentService documentService,
            KindRegistry kindRegistry,
            DocumentHeaderParser headerParser) {
        this.documentService = documentService;
        this.kindRegistry = kindRegistry;
        this.headerParser = headerParser;
    }

    /**
     * Validate an already-stored document by its project-relative {@code path}:
     * loads it, reads its {@code kind}, and validates the stored content. A
     * missing document yields a single {@code ERROR} ({@code doc-not-found},
     * {@code ok=false}).
     */
    public KindValidationResult validateByPath(String tenantId, String projectId, String path) {
        String target = path;
        Optional<DocumentDocument> found = documentService.findByPath(tenantId, projectId, path);
        if (found.isEmpty()) {
            return new KindValidationResult(target,
                    List.of(Finding.error(target, "doc-not-found",
                            "no document at path '" + path + "'")));
        }
        DocumentDocument doc = found.get();
        String content = read(doc);
        KindValidationContext ctx = new KindValidationContext(
                tenantId, projectId, path,
                new DocumentServiceDocRefs(documentService, tenantId, projectId));
        return validateCore(doc.getKind(), content, ctx, target);
    }

    /**
     * Validate content that may not (yet) be stored — the pre-write self-check.
     * {@code kind} is used when given, otherwise inferred from a {@code $meta}
     * header in {@code content}. {@code docPath} feeds the {@link
     * KindValidationContext} (reference resolution) and becomes the target when
     * present.
     */
    public KindValidationResult validateContent(
            String tenantId,
            String projectId,
            @Nullable String kind,
            String content,
            @Nullable String docPath) {
        String resolvedKind = StringUtils.isBlank(kind) ? inferKind(content) : kind;
        String target = docPath != null && !docPath.isBlank()
                ? docPath
                : (StringUtils.isBlank(resolvedKind) ? "content" : resolvedKind);
        KindValidationContext ctx = new KindValidationContext(
                tenantId, projectId, docPath == null ? "" : docPath,
                new DocumentServiceDocRefs(documentService, tenantId, projectId));
        return validateCore(resolvedKind, content, ctx, target);
    }

    /**
     * Shared dispatch: apply the unknown-tolerance ladder, then delegate to the
     * kind's handler.
     */
    private KindValidationResult validateCore(
            @Nullable String kind, String content, KindValidationContext ctx, String target) {
        if (StringUtils.isBlank(kind)) {
            return new KindValidationResult(target, List.of());
        }
        KindHandler handler = kindRegistry.handlerFor(kind);
        if (handler == null) {
            return new KindValidationResult(target, List.of(Finding.warning(
                    target, "kind-unknown",
                    "no validator for kind '" + kind + "' — is its addon installed?")));
        }
        return new KindValidationResult(target, handler.validate(content, ctx));
    }

    /**
     * Best-effort kind inference from a bare content blob: try the markdown,
     * YAML and JSON header strategies in turn, returning the first
     * {@code $meta.kind} found. {@code null} when none carries a kind.
     */
    private @Nullable String inferKind(String content) {
        for (String mime : INFER_MIME_TYPES) {
            Optional<DocumentHeader> header = headerParser.parse(mime, content);
            if (header.isPresent()) {
                String kind = header.get().getKind();
                if (!StringUtils.isBlank(kind)) return kind;
            }
        }
        return null;
    }

    private String read(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
