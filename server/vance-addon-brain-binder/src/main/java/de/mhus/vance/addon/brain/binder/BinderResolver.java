package de.mhus.vance.addon.brain.binder;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Loads a binder manifest and resolves every anchored entry against the
 * document store. The single place that normalises {@code vance:} refs
 * ⇄ project paths, so the rest of the addon deals in canonical forms.
 *
 * <p>Dangling refs (target deleted / moved) are NOT rejected — the
 * resolved entry carries {@code exists=false} so the UI can render it as
 * "missing" with a remove action.
 *
 * <p>Data ownership: all foreign-document access goes through
 * {@link DocumentService} — never a raw {@code MongoTemplate} or another
 * service's repository.
 */
@Component
public class BinderResolver {

    private final DocumentService documentService;

    public BinderResolver(DocumentService documentService) {
        this.documentService = documentService;
    }

    /** A binder entry resolved against the live document store. */
    public record ResolvedEntry(
            String ref,
            @Nullable String id,
            String path,
            String title,
            @Nullable String kind,
            @Nullable String mimeType,
            @Nullable String section,
            boolean exists) {}

    /** Full scan of a binder folder: manifest + resolved entries. */
    public record Scan(
            String folder,
            DocumentDocument manifest,
            ApplicationDocument manifestDoc,
            BinderConfig config,
            List<ResolvedEntry> entries) {}

    public Scan scan(String tenantId, String projectId, String folder) {
        String normalized = normaliseFolder(folder);
        String manifestPath = normalized + "/" + VanceApplication.APP_MANIFEST;
        Optional<DocumentDocument> manifest =
                documentService.findByPath(tenantId, projectId, manifestPath);
        if (manifest.isEmpty()) {
            throw new ToolException("No binder manifest at '" + manifestPath + "'.");
        }
        ApplicationDocument manifestDoc = parseManifest(manifest.get());
        BinderConfig config = BinderConfig.from(manifestDoc);

        List<ResolvedEntry> resolved = new ArrayList<>();
        for (BinderEntry e : config.entries()) {
            resolved.add(resolve(tenantId, projectId, e));
        }
        return new Scan(normalized, manifest.get(), manifestDoc, config, resolved);
    }

    /** Resolve a single manifest entry against the document store. */
    public ResolvedEntry resolve(String tenantId, String projectId, BinderEntry entry) {
        String path = stripToPath(entry.ref());
        Optional<DocumentDocument> doc = documentService.findByPath(tenantId, projectId, path);
        if (doc.isEmpty()) {
            String title = entry.title() != null ? entry.title() : leafStem(path);
            return new ResolvedEntry(canonicalRef(path, null), null, path, title,
                    null, null, entry.section(), false);
        }
        DocumentDocument d = doc.get();
        String docTitle = d.getTitle() != null && !d.getTitle().isBlank()
                ? d.getTitle() : leafStem(path);
        String title = entry.title() != null ? entry.title() : docTitle;
        return new ResolvedEntry(canonicalRef(d.getPath(), d.getKind()), d.getId(), d.getPath(),
                title, d.getKind(), d.getMimeType(), entry.section(), true);
    }

    private ApplicationDocument parseManifest(DocumentDocument manifest) {
        String mime = manifest.getMimeType();
        if (!ApplicationCodec.supports(mime)) {
            throw new ToolException("Binder manifest '" + manifest.getPath()
                    + "' has mime '" + mime + "' — must be YAML or JSON.");
        }
        try (InputStream in = documentService.loadContent(manifest)) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return ApplicationCodec.parse(body, mime);
        } catch (IOException | RuntimeException e) {
            throw new ToolException("Could not parse binder manifest '"
                    + manifest.getPath() + "': " + e.getMessage());
        }
    }

    // ── ref ⇄ path normalisation ──────────────────────────────────

    /**
     * Reduce a stored/authored ref to a bare project path. Accepts
     * {@code vance://<project>/<path>}, {@code vance:/<path>},
     * {@code vance:<path>} and bare paths; strips any {@code ?query};
     * URL-decodes segments; drops the leading slash.
     */
    public static String stripToPath(String ref) {
        if (ref == null) throw new ToolException("ref is required");
        String s = ref.trim();
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        if (s.startsWith("vance://")) {
            // vance://<project>/<path> — drop scheme + authority (project).
            String rest = s.substring("vance://".length());
            int slash = rest.indexOf('/');
            s = slash >= 0 ? rest.substring(slash + 1) : "";
        } else if (s.startsWith("vance:")) {
            s = s.substring("vance:".length());
        }
        while (s.startsWith("/")) s = s.substring(1);
        s = decodePath(s);
        if (s.isBlank()) throw new ToolException("ref '" + ref + "' has no path");
        return s;
    }

    /** Build the canonical stored ref: {@code vance:/<encoded-path>?kind=<kind>}. */
    public static String canonicalRef(String path, @Nullable String kind) {
        String k = kind != null && !kind.isBlank() ? kind.toLowerCase() : "document";
        return DocumentLinkBuilder.buildVanceUri(
                null, path, k, DocumentLinkBuilder.defaultModeForKind(k));
    }

    public static String normaliseFolder(String folder) {
        if (folder == null) throw new ToolException("folder is required");
        String f = folder.trim();
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        while (f.startsWith("/")) f = f.substring(1);
        if (f.isEmpty()) throw new ToolException("folder must not be empty");
        return f;
    }

    public static String resolveOutputPath(String folder, @Nullable String configured) {
        String c = configured == null || configured.isBlank() ? "_index.md" : configured.trim();
        if (c.startsWith("/")) return c.substring(1);
        return folder + "/" + c;
    }

    private static String decodePath(String path) {
        StringBuilder sb = new StringBuilder();
        String[] parts = path.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            try {
                sb.append(URLDecoder.decode(parts[i], StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                sb.append(parts[i]); // not percent-encoded — keep raw
            }
        }
        return sb.toString();
    }

    private static String leafStem(String path) {
        String leaf = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        int dot = leaf.indexOf('.');
        return dot < 0 ? leaf : leaf.substring(0, dot);
    }
}
