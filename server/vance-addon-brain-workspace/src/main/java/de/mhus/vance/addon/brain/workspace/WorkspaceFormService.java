package de.mhus.vance.addon.brain.workspace;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import de.mhus.vance.api.form.FormFieldDto;
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
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Backend for the {@code vance-form} block (reactive-data, Schritt 3).
 *
 * <p>An <em>edit-config</em> document ({@code $meta.kind: edit-form})
 * declares a {@link FormFieldDto} schema under {@code form.fields} plus
 * a {@code target} data file. This service loads the schema + the
 * target's current values for the editor, and writes the submitted
 * values back into the target as a flat {@code fieldName: value} YAML
 * map (the agreed data-mapping shape).
 *
 * <p>Datenhoheit: all YAML parsing / serialisation lives here, never
 * in the client. The {@code onSave} script run + rebuild are wired in a
 * later step ({@code planning/workspace-reactive-data.md} §4 / Schritt
 * 4); this service only persists the data file.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceFormService {

    private final DocumentService documentService;
    private final ObjectMapper objectMapper;
    private final ScriptExecutor scriptExecutor;
    private final ToolDispatcher toolDispatcher;

    /** Wall-clock cap for an onSave recompute script. */
    private static final Duration ON_SAVE_TIMEOUT = Duration.ofSeconds(30);

    /** Schema + current values + resolved target path for one form. */
    public record LoadedForm(
            List<FormFieldDto> fields,
            Map<String, Object> values,
            String target) {}

    /**
     * Resolve the edit-config at {@code configPath}, returning its field
     * schema and the target data file's current values.
     */
    public LoadedForm loadForm(String tenantId, String projectId, String configPath) {
        Map<String, Object> config = readYamlMap(tenantId, projectId, configPath,
                "edit-config '" + configPath + "' not found");

        String target = stringValue(config.get("target"));
        if (target == null || target.isBlank()) {
            throw new ToolException("edit-config '" + configPath + "' has no 'target'");
        }
        String targetPath = resolveRelative(configPath, target);

        List<FormFieldDto> fields = parseFields(objectMapper, config, configPath);

        Map<String, Object> values = new LinkedHashMap<>();
        Optional<DocumentDocument> targetDoc =
                documentService.findByPath(tenantId, projectId, targetPath);
        if (targetDoc.isPresent()) {
            Map<String, Object> loaded = loadYaml(readContent(targetDoc.get()));
            // The data file is a flat fieldName -> value map. Drop any
            // accidental $meta so it never leaks into the form values.
            loaded.remove("$meta");
            values.putAll(loaded);
        }
        return new LoadedForm(fields, values, targetPath);
    }

    /**
     * Write the submitted {@code values} into the edit-config's target
     * as a flat {@code fieldName: value} YAML map. Creates the target
     * document if it does not exist yet, otherwise replaces its content.
     */
    public void saveForm(
            String tenantId, String projectId, String configPath,
            Map<String, Object> values, String editorId) {
        Map<String, Object> config = readYamlMap(tenantId, projectId, configPath,
                "edit-config '" + configPath + "' not found");
        String target = stringValue(config.get("target"));
        if (target == null || target.isBlank()) {
            throw new ToolException("edit-config '" + configPath + "' has no 'target'");
        }
        String targetPath = resolveRelative(configPath, target);

        Map<String, Object> data = values != null ? values : new LinkedHashMap<>();
        String yaml = dumpYaml(data);
        byte[] bytes = yaml.getBytes(StandardCharsets.UTF_8);

        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, targetPath);
        if (existing.isPresent()) {
            documentService.replaceContent(
                    existing.get().getId(),
                    new ByteArrayInputStream(bytes),
                    DocumentService.mimeFromPath(targetPath),
                    editorId);
        } else {
            documentService.createText(
                    tenantId, projectId, targetPath, null, null, yaml, editorId);
        }
        log.info("WorkspaceFormService.saveForm tenant='{}' config='{}' target='{}' fields={}",
                tenantId, configPath, targetPath, data.size());

        runOnSave(tenantId, projectId, configPath, config, editorId);
    }

    /**
     * Run the edit-config's {@code onSave.runScript} synchronously after
     * the data file was written (Schritt 4, sync variant). The script
     * reads the freshly-written target via {@code vance.documents.*} and
     * recomputes derived files; their writes fan out to the client via
     * the documents live-push. A script failure surfaces as a
     * {@link ToolException} (→ HTTP 500), the data file stays written.
     *
     * <p>{@code onSave.session} is accepted but informational in v1:
     * {@code vance.documents.*} and {@code vance.llm.*} both operate on
     * the tenant/project scope and need no session. A dedicated per-form
     * system session is a later refinement.
     */
    private void runOnSave(
            String tenantId, String projectId, String configPath,
            Map<String, Object> config, String editorId) {
        Object onSave = config.get("onSave");
        if (!(onSave instanceof Map<?, ?> onSaveMap)) return;
        String runScript = stringValue(onSaveMap.get("runScript"));
        if (runScript == null || runScript.isBlank()) return;

        String scriptPath = resolveRelative(configPath, runScript);
        if (!scriptPath.toLowerCase(java.util.Locale.ROOT).endsWith(".js")) {
            throw new ToolException(
                    "onSave.runScript must be a .js document (got '" + scriptPath
                            + "') — only in-JVM JavaScript is supported in v1");
        }
        DocumentDocument scriptDoc = documentService.findByPath(tenantId, projectId, scriptPath)
                .orElseThrow(() -> new ToolException("onSave script not found: " + scriptPath));
        String code = readContent(scriptDoc);

        ToolInvocationContext scope =
                new ToolInvocationContext(tenantId, projectId, null, null, editorId);
        ContextToolsApi tools = new ContextToolsApi(toolDispatcher, scope);
        try {
            scriptExecutor.run(new ScriptRequest(
                    "js", code, "form-onSave:" + scriptPath, tools, ON_SAVE_TIMEOUT));
            log.info("WorkspaceFormService.runOnSave tenant='{}' script='{}' ok",
                    tenantId, scriptPath);
        } catch (ScriptExecutionException e) {
            log.warn("WorkspaceFormService.runOnSave tenant='{}' script='{}' failed [{}]: {}",
                    tenantId, scriptPath, e.errorClass(), e.getMessage());
            throw new ToolException(
                    "onSave script '" + scriptPath + "' failed: " + e.getMessage());
        }
    }

    /**
     * Create a new edit-config skeleton ({@code <folder>/<slug>-edit-config.yaml},
     * {@code $meta.kind: edit-form}) with one starter field and a target
     * data file {@code <slug>.yaml}. Returns the created config path so
     * the caller can drop a {@code vance-form} block referencing it. The
     * target data file is created lazily on first save.
     */
    public String createForm(
            String tenantId, String projectId, String folder,
            String name, @org.jspecify.annotations.Nullable String title, String editorId) {
        String slug = slugify(name);
        if (slug.isBlank()) {
            throw new ToolException("form name must not be empty");
        }
        String base = folder == null ? "" : folder.strip();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String configPath = base.isEmpty()
                ? slug + "-edit-config.yaml"
                : base + "/" + slug + "-edit-config.yaml";
        if (documentService.findByPath(tenantId, projectId, configPath).isPresent()) {
            throw new ToolException("edit-config already exists: " + configPath);
        }
        String displayTitle = (title != null && !title.isBlank()) ? title.strip() : name.strip();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("kind", "edit-form");
        Map<String, Object> starterLabel = new LinkedHashMap<>();
        starterLabel.put("en", "Field 1");
        Map<String, Object> starterField = new LinkedHashMap<>();
        starterField.put("name", "field1");
        starterField.put("type", "string");
        starterField.put("label", starterLabel);
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("fields", new java.util.ArrayList<>(List.of(starterField)));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$meta", meta);
        root.put("title", displayTitle);
        root.put("target", slug + ".yaml");
        root.put("form", form);

        documentService.createText(
                tenantId, projectId, configPath, displayTitle, null, dumpYaml(root), editorId);
        log.info("WorkspaceFormService.createForm tenant='{}' config='{}'", tenantId, configPath);
        return configPath;
    }

    private static String slugify(String name) {
        if (name == null) return "";
        return name.strip().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
    }

    // ---- helpers -------------------------------------------------------

    @SuppressWarnings("unchecked")
    static List<FormFieldDto> parseFields(
            ObjectMapper objectMapper, Map<String, Object> config, String configPath) {
        Object form = config.get("form");
        if (!(form instanceof Map<?, ?> formMap)) return new ArrayList<>();
        Object fields = ((Map<String, Object>) formMap).get("fields");
        if (!(fields instanceof List<?> list)) return new ArrayList<>();
        // Be lenient: a field that omits a primitive key (e.g. `required`)
        // is valid YAML and must default, not blow up. Jackson 3 enables
        // FAIL_ON_NULL_FOR_PRIMITIVES by default, so derive a tolerant view.
        ObjectMapper lenient = objectMapper.rebuild()
                .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
                .build();
        try {
            return lenient.convertValue(list, new TypeReference<List<FormFieldDto>>() {});
        } catch (RuntimeException e) {
            throw new ToolException(
                    "edit-config '" + configPath + "' has an invalid form.fields schema: "
                            + e.getMessage());
        }
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(String text) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        Object loaded = new Yaml().load(text);
        if (loaded instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) out.put(e.getKey().toString(), e.getValue());
            }
            return out;
        }
        return new LinkedHashMap<>();
    }

    private String dumpYaml(Map<String, Object> data) {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(false);
        return new Yaml(opts).dump(data);
    }

    private static @org.jspecify.annotations.Nullable String stringValue(Object o) {
        return o == null ? null : o.toString();
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
