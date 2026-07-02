package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Backend for the {@code vance-form} block (reactive-data).
 *
 * <p><b>Split of concerns:</b> the data document ({@code kind: records})
 * holds only the <em>data</em> — a bare {@code schema} (column names) +
 * {@code items}. The <em>form definition</em> (fields + single) and the
 * recompute {@code saveScript} are block-specific and live in the
 * {@code vance-form} <b>fence</b>, not in the data file. There is no
 * {@code $meta.form} / {@code $meta.onSave} in the record — no migration,
 * no fallback.
 *
 * <p>This service therefore only: reads {@code items} for the editor,
 * writes {@code items} (+ syncs {@code schema} from the fence field names)
 * on save, and runs the fence {@code saveScript}. Datenhoheit: all YAML
 * parsing / serialisation lives here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceFormService {

    private final DocumentService documentService;
    private final ScriptExecutor scriptExecutor;
    private final ToolDispatcher toolDispatcher;

    /** Wall-clock cap for a saveScript recompute run. */
    private static final Duration ON_SAVE_TIMEOUT = Duration.ofSeconds(30);
    private static final String FORM_KIND = "records";

    /** Current {@code items} records of the data document. */
    public List<Map<String, Object>> loadForm(String tenantId, String projectId, String docPath) {
        Map<String, Object> doc = readYamlMap(tenantId, projectId, docPath,
                "form document '" + docPath + "' not found");
        List<Map<String, Object>> records = new ArrayList<>();
        Object items = doc.get("items");
        if (items instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) records.add(toStringMap(m));
            }
        }
        return records;
    }

    /**
     * Write the submitted {@code records} into the data document's
     * {@code items}, sync the native {@code schema} column list (from the
     * fence field names, else the union of item keys), then run the fence
     * {@code saveScript}.
     */
    public void saveForm(
            String tenantId, String projectId, String docPath,
            @Nullable List<Map<String, Object>> records,
            @Nullable List<String> schema,
            @Nullable String saveScript, String editorId) {
        DocumentDocument docDoc = documentService.findByPath(tenantId, projectId, docPath)
                .orElseThrow(() -> new ToolException("form document '" + docPath + "' not found"));
        Map<String, Object> doc = loadYaml(readContent(docDoc));

        List<Map<String, Object>> rows = records != null ? records : new ArrayList<>();
        doc.put("items", rows);
        doc.put("schema", (schema != null && !schema.isEmpty()) ? schema : unionKeys(rows));

        writeDoc(docDoc.getId(), docPath, doc, editorId);
        log.info("WorkspaceFormService.saveForm tenant='{}' doc='{}' records={}",
                tenantId, docPath, rows.size());

        runOnSave(tenantId, projectId, docPath, saveScript, editorId);
    }

    /**
     * Create a new empty data document ({@code <folder>/<slug>.yaml},
     * {@code kind: records}) — just {@code schema} + {@code items}. The form
     * definition lives in the block's fence. Returns the created path.
     */
    public String createForm(
            String tenantId, String projectId, String folder,
            String name, @Nullable String title, String editorId) {
        String slug = slugify(name);
        if (slug.isBlank()) {
            throw new ToolException("form name must not be empty");
        }
        String base = folder == null ? "" : folder.strip();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String docPath = base.isEmpty() ? slug + ".yaml" : base + "/" + slug + ".yaml";
        if (documentService.findByPath(tenantId, projectId, docPath).isPresent()) {
            throw new ToolException("document already exists: " + docPath);
        }
        String displayTitle = (title != null && !title.isBlank()) ? title.strip() : name.strip();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("kind", FORM_KIND);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("$meta", meta);
        doc.put("title", displayTitle);
        doc.put("schema", new ArrayList<>());
        doc.put("items", new ArrayList<>());

        documentService.createText(
                tenantId, projectId, docPath, displayTitle, null, dumpYaml(doc), editorId);
        log.info("WorkspaceFormService.createForm tenant='{}' doc='{}'", tenantId, docPath);
        return docPath;
    }

    // ---- saveScript ----------------------------------------------------

    /**
     * Run the fence {@code saveScript} synchronously after {@code items} was
     * written: it reads the fresh data via {@code vance.documents.*} and
     * recomputes derived files (live-push updates embeds). A failure surfaces
     * as {@link ToolException} (→ HTTP 500); the data stays written. No
     * {@code saveScript} → no-op.
     */
    private void runOnSave(
            String tenantId, String projectId, String docPath,
            @Nullable String fenceScript, String editorId) {
        if (fenceScript == null || fenceScript.isBlank()) return;
        String scriptPath = resolveRelative(docPath, stripVanceScheme(fenceScript));
        if (!scriptPath.toLowerCase(Locale.ROOT).endsWith(".js")) {
            throw new ToolException(
                    "saveScript must be a .js document (got '" + scriptPath
                            + "') — only in-JVM JavaScript is supported in v1");
        }
        DocumentDocument scriptDoc = documentService.findByPath(tenantId, projectId, scriptPath)
                .orElseThrow(() -> new ToolException("saveScript not found: " + scriptPath));
        String code = readContent(scriptDoc);

        ToolInvocationContext scope =
                new ToolInvocationContext(tenantId, projectId, null, null, editorId);
        ContextToolsApi tools = new ContextToolsApi(toolDispatcher, scope);
        try {
            scriptExecutor.run(new ScriptRequest(
                    "js", code, "form-saveScript:" + scriptPath, tools, ON_SAVE_TIMEOUT));
            log.info("WorkspaceFormService.runOnSave tenant='{}' script='{}' ok",
                    tenantId, scriptPath);
        } catch (ScriptExecutionException e) {
            log.warn("WorkspaceFormService.runOnSave tenant='{}' script='{}' failed [{}]: {}",
                    tenantId, scriptPath, e.errorClass(), e.getMessage());
            throw new ToolException(
                    "saveScript '" + scriptPath + "' failed: " + e.getMessage());
        }
    }

    // ---- helpers -------------------------------------------------------

    /** Ordered union of keys across all record rows (schema fallback). */
    private static List<String> unionKeys(List<Map<String, Object>> rows) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            if (row != null) for (String k : row.keySet()) keys.add(k);
        }
        return new ArrayList<>(keys);
    }

    private void writeDoc(String docId, String docPath, Map<String, Object> doc, String editorId) {
        documentService.replaceContent(
                docId,
                new ByteArrayInputStream(dumpYaml(doc).getBytes(StandardCharsets.UTF_8)),
                DocumentService.mimeFromPath(docPath),
                editorId);
    }

    private Map<String, Object> readYamlMap(
            String tenantId, String projectId, String path, String missingMessage) {
        DocumentDocument doc = documentService.findByPath(tenantId, projectId, path)
                .orElseThrow(() -> new ToolException(missingMessage));
        return loadYaml(readContent(doc));
    }

    private String readContent(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("Could not read '" + doc.getPath() + "': " + e.getMessage());
        }
    }

    private Map<String, Object> loadYaml(String text) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        Object loaded = new Yaml().load(text);
        return loaded instanceof Map<?, ?> m ? toStringMap(m) : new LinkedHashMap<>();
    }

    private static Map<String, Object> toStringMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() != null) out.put(e.getKey().toString(), e.getValue());
        }
        return out;
    }

    private String dumpYaml(Object data) {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(false);
        return new Yaml(opts).dump(data);
    }

    private static String slugify(String name) {
        if (name == null) return "";
        return name.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
    }

    /**
     * Strip a leading {@code vance:} scheme from a fence-supplied script
     * reference. The remainder keeps its leading {@code /} (if any) so
     * {@link #resolveRelative} treats it as project-absolute; a bare name
     * ({@code vance:update_all.js}) resolves relative to the doc's folder.
     */
    static String stripVanceScheme(String ref) {
        String s = ref.strip();
        if (s.startsWith("vance:")) s = s.substring("vance:".length());
        return s;
    }

    /**
     * Resolve {@code rel} against the folder of {@code basePath}. A
     * leading-slash {@code rel} is treated as project-absolute.
     */
    static String resolveRelative(String basePath, String rel) {
        if (rel.startsWith("/")) return rel.substring(1);
        int slash = basePath.lastIndexOf('/');
        String parent = slash >= 0 ? basePath.substring(0, slash) : "";
        return parent.isEmpty() ? rel : parent + "/" + rel;
    }
}
