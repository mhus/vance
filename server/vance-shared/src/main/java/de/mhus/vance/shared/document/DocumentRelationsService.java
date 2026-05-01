package de.mhus.vance.shared.document;

import de.mhus.vance.shared.home.HomeBootstrapService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Resolves and parses inter-document relations stored as YAML documents
 * with {@code kind: relations} in their front matter. The on-disk shape
 * (text is truth) follows the same multi-document YAML convention used
 * elsewhere in Vance — first document is the typed header, second
 * document is a list of relation records:
 *
 * <pre>
 * kind: relations
 * title: Project links
 * ---
 * - source: notes/thesis.md
 *   type: cites
 *   target: papers/vaswani2017.pdf
 *   note: Foundational reference for chapter 3
 * - source: notes/thesis.md
 *   target: notes/related-work.md
 * </pre>
 *
 * <p>Files live anywhere the user wants — convention is
 * {@code _vance/relations/default.yaml} for the tenant-wide fallback and
 * {@code relations/*.yaml} (or any path) for project-scoped overrides. The
 * service collects every {@code kind: relations} document in the cascade
 * (project + tenant {@code _vance}) and concatenates their entries; there
 * is no precedence — relations from both layers compose.
 *
 * <p>Truth lives in the source files: this service never writes back, it
 * only reads. Agents and the future relations-editor UI mutate by editing
 * the underlying YAML documents through {@link DocumentService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentRelationsService {

    /**
     * The {@code kind:} value that marks a YAML document as a Vance
     * relations file. Keep in sync with the convention in {@code
     * specification/data-management.md}.
     */
    public static final String KIND = "relations";

    private final DocumentService documentService;

    /**
     * Every relation declared anywhere in the cascade for the project —
     * tenant-wide defaults from {@code _vance} first, then project-specific
     * files. Order is best-effort, matching {@code DocumentService.listByKind}.
     *
     * <p>Unparseable or malformed bodies are skipped with a warning, never
     * thrown — the service is tolerant by design so a single broken YAML
     * file does not blind the agent to every other relation in the project.
     */
    public List<DocumentRelation> listRelations(String tenantId, String projectId) {
        List<DocumentRelation> result = new ArrayList<>();
        // _vance first so we read tenant defaults even when the caller is
        // editing a project; skip when projectId == _vance to avoid reading
        // the same documents twice.
        appendRelationsFrom(result, tenantId, HomeBootstrapService.VANCE_PROJECT_NAME);
        if (!HomeBootstrapService.VANCE_PROJECT_NAME.equals(projectId)) {
            appendRelationsFrom(result, tenantId, projectId);
        }
        return result;
    }

    /** All relations whose {@link DocumentRelation#getSource} matches the path. */
    public List<DocumentRelation> findBySource(
            String tenantId, String projectId, String sourcePath) {
        String needle = sourcePath.trim();
        return listRelations(tenantId, projectId).stream()
                .filter(r -> needle.equals(r.getSource()))
                .toList();
    }

    /** All relations whose {@link DocumentRelation#getTarget} matches the path. */
    public List<DocumentRelation> findByTarget(
            String tenantId, String projectId, String targetPath) {
        String needle = targetPath.trim();
        return listRelations(tenantId, projectId).stream()
                .filter(r -> needle.equals(r.getTarget()))
                .toList();
    }

    /** All relations of a given {@link DocumentRelation#getType type}. */
    public List<DocumentRelation> findByType(
            String tenantId, String projectId, String type) {
        String needle = type.trim();
        return listRelations(tenantId, projectId).stream()
                .filter(r -> needle.equals(r.getType()))
                .toList();
    }

    /**
     * Convenience for "everything connected to {@code path}" — relations
     * where the path is either source or target. Useful when an agent wants
     * the full neighbourhood of a document without two separate calls.
     */
    public List<DocumentRelation> findRelated(
            String tenantId, String projectId, String path) {
        String needle = path.trim();
        return listRelations(tenantId, projectId).stream()
                .filter(r -> needle.equals(r.getSource()) || needle.equals(r.getTarget()))
                .toList();
    }

    // ─── parsing ────────────────────────────────────────────────────────

    private void appendRelationsFrom(
            List<DocumentRelation> acc, String tenantId, String projectId) {
        List<DocumentDocument> docs = documentService.listByKind(tenantId, projectId, KIND);
        for (DocumentDocument doc : docs) {
            try {
                acc.addAll(parseDocument(doc));
            } catch (RuntimeException e) {
                // Tolerant by design — see class javadoc.
                log.warn("Failed to parse relations from doc tenantId='{}' path='{}': {}",
                        tenantId, doc.getPath(), e.toString());
            }
        }
    }

    private List<DocumentRelation> parseDocument(DocumentDocument doc) {
        String body = readBody(doc);
        if (body == null || body.isEmpty()) return List.of();

        // Same SafeConstructor / alias-cap profile as the YAML header
        // strategy — never instantiate arbitrary classes via tags, never
        // expand pathological alias graphs.
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        opts.setMaxAliasesForCollections(50);
        Yaml yaml = new Yaml(new SafeConstructor(opts));

        Iterable<Object> docs;
        try {
            docs = yaml.loadAll(body);
        } catch (RuntimeException e) {
            log.warn("Malformed YAML in relations doc path='{}': {}",
                    doc.getPath(), e.toString());
            return List.of();
        }

        // Multi-document stream: skip the header (first), grab the body
        // (second). Files without a body document carry no relations even
        // when they parse.
        Object body_doc = null;
        int seen = 0;
        for (Object d : docs) {
            if (seen == 1) {
                body_doc = d;
                break;
            }
            seen++;
        }
        if (!(body_doc instanceof List<?> entries)) return List.of();

        List<DocumentRelation> result = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) continue;
            DocumentRelation parsed = entryToRelation(map, doc.getPath());
            if (parsed != null) result.add(parsed);
        }
        return result;
    }

    private static @Nullable DocumentRelation entryToRelation(
            Map<?, ?> map, String definedIn) {
        String source = stringValue(map.get("source"));
        String target = stringValue(map.get("target"));
        if (source.isEmpty() || target.isEmpty()) {
            // Skip silently — partial entries are common during hand-editing.
            return null;
        }
        String type = stringValue(map.get("type"));
        if (type.isEmpty()) {
            // Some YAML authors will write `relation:` instead of `type:`.
            // Accept both so the front-matter convention does not surprise.
            type = stringValue(map.get("relation"));
        }
        if (type.isEmpty()) type = DocumentRelation.DEFAULT_TYPE;
        String note = stringValue(map.get("note"));

        Map<String, Object> extras = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) continue;
            switch (key) {
                case "source", "target", "type", "relation", "note" -> {}
                default -> extras.put(key, e.getValue());
            }
        }

        return DocumentRelation.builder()
                .source(source)
                .target(target)
                .type(type)
                .note(note.isEmpty() ? null : note)
                .definedIn(definedIn)
                .extras(extras)
                .build();
    }

    private static String stringValue(@Nullable Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s.trim();
        return value.toString().trim();
    }

    private String readBody(DocumentDocument doc) {
        String inline = doc.getInlineText();
        if (inline != null) return inline;
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read relations doc path='{}': {}",
                    doc.getPath(), e.toString());
            return "";
        }
    }
}
