package de.mhus.vance.addon.brain.workbook;

import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
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
import tools.jackson.databind.json.JsonMapper;

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
public class WorkbookFormService {

    private final DocumentService documentService;
    private final ScriptExecutor scriptExecutor;
    private final ToolDispatcher toolDispatcher;
    private final SessionService sessionService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

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
     * {@code saveScript}. When {@code session} is set, the script runs inside
     * a per-form system session (fence {@code session: true}).
     */
    public void saveForm(
            String tenantId, String projectId, String docPath,
            @Nullable List<Map<String, Object>> records,
            @Nullable List<String> schema,
            @Nullable String saveScript, boolean session, String editorId) {
        DocumentDocument docDoc = documentService.findByPath(tenantId, projectId, docPath)
                .orElseThrow(() -> new ToolException("form document '" + docPath + "' not found"));
        Map<String, Object> doc = loadYaml(readContent(docDoc));

        List<Map<String, Object>> rows = records != null ? records : new ArrayList<>();
        doc.put("items", rows);
        doc.put("schema", (schema != null && !schema.isEmpty()) ? schema : unionKeys(rows));

        writeDoc(docDoc.getId(), tenantId, docPath, doc, editorId);
        log.info("WorkbookFormService.saveForm tenant='{}' doc='{}' records={}",
                tenantId, docPath, rows.size());

        runOnSave(tenantId, projectId, docPath, saveScript, session, editorId);
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
                tenantId, projectId, docPath, displayTitle, null, serialize(docPath, doc), editorId,
                contextFactory.writeActor(tenantId, editorId, docPath));
        log.info("WorkbookFormService.createForm tenant='{}' doc='{}'", tenantId, docPath);
        return docPath;
    }

    // ---- saveScript ----------------------------------------------------

    /**
     * Run the fence {@code saveScript} synchronously after {@code items} was
     * written: it reads the fresh data via {@code vance.documents.*} and
     * recomputes derived files (live-push updates embeds). A failure surfaces
     * as {@link ToolException} (→ HTTP 500); the data stays written. No
     * {@code saveScript} → no-op. When {@code session} is set, the run gets a
     * per-form system session so session-bound tools / LLM are available.
     */
    private void runOnSave(
            String tenantId, String projectId, String docPath,
            @Nullable String fenceScript, boolean session, String editorId) {
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

        String sessionId = session
                ? resolveFormSession(tenantId, projectId, docPath, editorId)
                : null;
        ToolInvocationContext scope =
                new ToolInvocationContext(tenantId, projectId, sessionId, null, editorId);
        ContextToolsApi tools = new ContextToolsApi(toolDispatcher, scope);
        try {
            scriptExecutor.run(new ScriptRequest(
                    "js", code, "form-saveScript:" + scriptPath, tools, ON_SAVE_TIMEOUT)
                    .withDocumentBasePath(parentPath(scriptPath)));
            log.info("WorkbookFormService.runOnSave tenant='{}' script='{}' session={} ok",
                    tenantId, scriptPath, session);
        } catch (ScriptExecutionException e) {
            log.warn("WorkbookFormService.runOnSave tenant='{}' script='{}' failed [{}]: {}",
                    tenantId, scriptPath, e.errorClass(), e.getMessage());
            throw new ToolException(
                    "saveScript '" + scriptPath + "' failed: " + e.getMessage());
        }
    }

    /**
     * Reuse-or-create a per-form system session (deterministic display name
     * {@code _form_<docPath>}, {@code system=true}) so a fence {@code saveScript}
     * that opted in ({@code session: true}) runs inside a session scope. Same
     * lazy-reuse pattern as the scheduler / hook system sessions, but
     * workbook-form-owned.
     */
    private String resolveFormSession(
            String tenantId, String projectId, String docPath, String runAs) {
        String displayName = "_form_" + docPath.replaceAll("[^a-zA-Z0-9._-]", "_");
        return sessionService.findSystemSession(tenantId, projectId, displayName)
                .map(SessionDocument::getSessionId)
                .orElseGet(() -> {
                    SessionDocument created = sessionService.create(
                            tenantId, runAs, projectId, displayName,
                            Profiles.DAEMON, "workbook-form", null, /*system*/ true);
                    sessionService.markBootstrapped(created.getSessionId());
                    return created.getSessionId();
                });
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

    private void writeDoc(String docId, String tenantId, String docPath,
            Map<String, Object> doc, String editorId) {
        documentService.replaceContent(
                docId,
                new ByteArrayInputStream(serialize(docPath, doc).getBytes(StandardCharsets.UTF_8)),
                DocumentService.mimeFromPath(docPath),
                DocumentService.WriterIdentity.of(editorId, null, null),
                contextFactory.writeActor(tenantId, editorId, docPath));
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

    private static String dumpYaml(Object data) {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(false);
        return new Yaml(opts).dump(data);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Serialize the data document matching its extension so the on-disk
     * content is what the file name promises: a {@code .json} records doc gets
     * real JSON (so a saveScript's {@code JSON.parse(read(...))} works),
     * everything else gets YAML. Reads stay format-agnostic — SnakeYAML parses
     * JSON as a YAML subset.
     */
    static String serialize(String docPath, Object data) {
        if (docPath.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        }
        return dumpYaml(data);
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

    /**
     * Folder containing {@code path} (empty when at the project root) — the
     * "current directory" a script's relative {@code vance.documents.*} paths
     * resolve against.
     */
    static String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
}
