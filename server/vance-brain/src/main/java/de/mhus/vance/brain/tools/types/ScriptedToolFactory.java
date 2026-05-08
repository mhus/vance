package de.mhus.vance.brain.tools.types;

import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.brain.script.ScriptResult;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code scripted} — a server-tool whose body is a JavaScript snippet
 * supplied by the kit author. The factory validates the descriptor at
 * write time and produces a {@link Tool} that, on invocation, hands
 * the script plus the LLM-supplied input values to
 * {@link ScriptExecutor}. The script sees those inputs as top-level
 * variables and the {@code vance.*} host API for sibling-tool calls.
 *
 * <p>Configuration shape (in {@code ServerToolDocument#parameters}):
 * <pre>
 * engine:    "javascript"        # default. Future: "python", etc.
 * timeoutMs: 5000                 # default 5000, max 30000.
 * inputs:                         # the LLM-visible argument schema.
 *   - name: a
 *     type: number               # string|number|integer|boolean|object|array
 *     description: First operand
 *     required: true             # default true
 * source:    |                    # one of {source, scriptPath} required.
 *   a + b
 * scriptPath: scripts/add.js     # alternative — looked up via cascade.
 * </pre>
 *
 * <p>Spec: {@code specification/server-tools.md} (built-in types).
 */
@Component
@RequiredArgsConstructor
public class ScriptedToolFactory implements ToolFactory {

    public static final String TYPE_ID = "scripted";

    private static final String ENGINE_JS = "javascript";

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

    private static final Set<String> ALLOWED_INPUT_TYPES = Set.of(
            "string", "number", "integer", "boolean", "object", "array");

    private static final Map<String, Object> PARAMETERS_SCHEMA = Map.ofEntries(
            Map.entry("type", "object"),
            Map.entry("properties", Map.of(
                    "engine", Map.of(
                            "type", "string",
                            "enum", List.of(ENGINE_JS),
                            "description",
                                    "Script engine. Currently only 'javascript'."),
                    "timeoutMs", Map.of(
                            "type", "integer",
                            "description",
                                    "Wall-clock timeout in ms (default 5000, max 30000)."),
                    "inputs", Map.of(
                            "type", "array",
                            "description",
                                    "Declared tool inputs — each becomes a top-level"
                                            + " variable in the script and an entry in"
                                            + " the LLM-visible parameters schema."),
                    "source", Map.of(
                            "type", "string",
                            "description",
                                    "Inline script source (mutually exclusive with scriptPath)."),
                    "scriptPath", Map.of(
                            "type", "string",
                            "description",
                                    "Document path of a script file resolved via the"
                                            + " project → _vance → classpath cascade"
                                            + " (mutually exclusive with source)."))),
            Map.entry("required", List.of("inputs")));

    private final ScriptExecutor scriptExecutor;
    private final DocumentService documentService;

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return PARAMETERS_SCHEMA;
    }

    @Override
    public Collection<Tool> create(ServerToolDocument document) {
        Map<String, Object> params = document.getParameters() == null
                ? Map.of() : document.getParameters();

        String engine = stringOr(params.get("engine"), ENGINE_JS);
        if (!ENGINE_JS.equals(engine)) {
            throw new IllegalArgumentException(
                    "scripted tool '" + document.getName()
                            + "': unknown engine '" + engine
                            + "' (only 'javascript' is supported)");
        }

        @Nullable String inlineSource = stringOrNull(params.get("source"));
        @Nullable String scriptPath = stringOrNull(params.get("scriptPath"));
        if ((inlineSource == null) == (scriptPath == null)) {
            throw new IllegalArgumentException(
                    "scripted tool '" + document.getName()
                            + "' must set exactly one of 'source' or 'scriptPath'");
        }

        List<InputSpec> inputs = parseInputs(params.get("inputs"), document.getName());

        Duration timeout = resolveTimeout(params.get("timeoutMs"));

        return List.of(new ScriptedTool(
                document, engine, inlineSource, scriptPath, inputs, timeout,
                scriptExecutor, documentService));
    }

    // ──────────────────── helpers ────────────────────

    record InputSpec(String name, String type, @Nullable String description, boolean required) {}

    @SuppressWarnings("unchecked")
    private static List<InputSpec> parseInputs(@Nullable Object raw, String toolName) {
        if (raw == null) {
            throw new IllegalArgumentException(
                    "scripted tool '" + toolName + "': 'inputs' is required");
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "scripted tool '" + toolName + "': 'inputs' must be a list");
        }
        List<InputSpec> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            if (!(element instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException(
                        "scripted tool '" + toolName + "': inputs[" + i + "] must be a map");
            }
            Map<String, Object> input = (Map<String, Object>) m;
            String name = stringOrNull(input.get("name"));
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(
                        "scripted tool '" + toolName + "': inputs[" + i + "].name is required");
            }
            if ("vance".equals(name)) {
                throw new IllegalArgumentException(
                        "scripted tool '" + toolName + "': input name 'vance' is reserved");
            }
            if (!seen.add(name)) {
                throw new IllegalArgumentException(
                        "scripted tool '" + toolName + "': duplicate input name '" + name + "'");
            }
            String type = stringOr(input.get("type"), "string");
            if (!ALLOWED_INPUT_TYPES.contains(type)) {
                throw new IllegalArgumentException(
                        "scripted tool '" + toolName + "': inputs[" + i + "].type '" + type
                                + "' is not one of " + ALLOWED_INPUT_TYPES);
            }
            String description = stringOrNull(input.get("description"));
            // Default required=true: if the kit author cares enough to
            // declare an input, it's almost always part of the contract.
            boolean required = boolOr(input.get("required"), true);
            out.add(new InputSpec(name, type, description, required));
        }
        return out;
    }

    private static Duration resolveTimeout(@Nullable Object raw) {
        if (!(raw instanceof Number n)) {
            return DEFAULT_TIMEOUT;
        }
        long ms = Math.max(1, Math.min(MAX_TIMEOUT.toMillis(), n.longValue()));
        return Duration.ofMillis(ms);
    }

    private static @Nullable String stringOrNull(@Nullable Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String stringOr(@Nullable Object v, String fallback) {
        String s = stringOrNull(v);
        return s == null ? fallback : s;
    }

    private static boolean boolOr(@Nullable Object v, boolean fallback) {
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString().trim());
    }

    // ──────────────────── tool ────────────────────

    static final class ScriptedTool implements Tool {

        private final ServerToolDocument doc;
        private final String engine;
        private final @Nullable String inlineSource;
        private final @Nullable String scriptPath;
        private final List<InputSpec> inputs;
        private final Duration timeout;
        private final ScriptExecutor scriptExecutor;
        private final DocumentService documentService;
        private final Map<String, Object> paramsSchema;

        ScriptedTool(
                ServerToolDocument doc,
                String engine,
                @Nullable String inlineSource,
                @Nullable String scriptPath,
                List<InputSpec> inputs,
                Duration timeout,
                ScriptExecutor scriptExecutor,
                DocumentService documentService) {
            this.doc = doc;
            this.engine = engine;
            this.inlineSource = inlineSource;
            this.scriptPath = scriptPath;
            this.inputs = List.copyOf(inputs);
            this.timeout = timeout;
            this.scriptExecutor = scriptExecutor;
            this.documentService = documentService;
            this.paramsSchema = buildParamsSchema(inputs);
        }

        @Override
        public String name() {
            return doc.getName();
        }

        @Override
        public String description() {
            return doc.getDescription();
        }

        @Override
        public boolean primary() {
            return doc.isPrimary();
        }

        @Override
        public Map<String, Object> paramsSchema() {
            return paramsSchema;
        }

        @Override
        public Set<String> labels() {
            List<String> ls = doc.getLabels();
            return ls == null ? Set.of() : Set.copyOf(ls);
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            throw new ToolException(
                    "scripted tool '" + name() + "' requires the bound tools surface — "
                            + "call via the engine's ContextToolsApi");
        }

        @Override
        public Map<String, Object> invoke(
                Map<String, Object> params, ToolInvocationContext ctx, de.mhus.vance.toolpack.ToolBus bus) {
            ContextToolsApi tools = (ContextToolsApi) bus;

            Map<String, @Nullable Object> bindings = new LinkedHashMap<>();
            for (InputSpec spec : inputs) {
                Object value = params == null ? null : params.get(spec.name());
                if (value == null && spec.required()) {
                    throw new ToolException(
                            "scripted tool '" + name() + "': required input '"
                                    + spec.name() + "' is missing");
                }
                bindings.put(spec.name(), value);
            }

            String code = resolveSource(ctx);
            try {
                ScriptResult result = scriptExecutor.run(new ScriptRequest(
                        "js",
                        code,
                        "scripted:" + name(),
                        tools,
                        timeout,
                        bindings));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("value", result.value());
                out.put("durationMs", result.duration().toMillis());
                return out;
            } catch (ScriptExecutionException e) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("error", e.errorClass().name());
                out.put("message", e.getMessage());
                return out;
            }
        }

        private String resolveSource(ToolInvocationContext ctx) {
            if (inlineSource != null) {
                return inlineSource;
            }
            // scriptPath != null guaranteed by the factory's XOR check.
            String path = scriptPath;
            if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
                throw new ToolException(
                        "scripted tool '" + name() + "' with scriptPath requires a tenant scope");
            }
            Optional<LookupResult> hit = documentService.lookupCascade(
                    ctx.tenantId(), ctx.projectId(), path);
            if (hit.isEmpty()) {
                throw new ToolException(
                        "scripted tool '" + name() + "': script not found at path '" + path + "'");
            }
            String content = hit.get().content();
            if (content == null || content.isEmpty()) {
                throw new ToolException(
                        "scripted tool '" + name() + "': script at '" + path + "' is empty");
            }
            return content;
        }

        private static Map<String, Object> buildParamsSchema(List<InputSpec> inputs) {
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (InputSpec spec : inputs) {
                Map<String, Object> property = new LinkedHashMap<>();
                property.put("type", spec.type());
                if (spec.description() != null) {
                    property.put("description", spec.description());
                }
                properties.put(spec.name(), property);
                if (spec.required()) {
                    required.add(spec.name());
                }
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            return schema;
        }

        // Engine getter kept for tests / future multi-engine extension.
        @SuppressWarnings("unused")
        String engine() {
            return engine;
        }
    }
}
