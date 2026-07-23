package de.mhus.vance.shared.document.kind.validate;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * {@link DocRefs} implementation backed by {@link DocumentService} for a single
 * tenant/project scope. Mirrors the behaviour of the workbook addon's private
 * {@code ServiceDocRefs}: reference lookups delegate to the service (Datenhoheit
 * — never a raw MongoTemplate/repository access), and {@link #readYaml} loads
 * the content and parses it as a YAML map, returning {@code null} on a missing
 * document or a non-mapping / malformed body.
 */
public final class DocumentServiceDocRefs implements DocRefs {

    private final DocumentService documentService;
    private final String tenantId;
    private final String projectId;

    public DocumentServiceDocRefs(DocumentService documentService, String tenantId, String projectId) {
        this.documentService = documentService;
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
            // SafeConstructor: never instantiate arbitrary classpath types
            // from !!<java-type> tags in referenced-document YAML — this is
            // a validation read of untrusted document content (code-review
            // Phase 2). Mirrors the Kind codecs (DataCodec/DiagramCodec/…).
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object loaded = yaml.load(read(doc.get()));
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

    private String read(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
