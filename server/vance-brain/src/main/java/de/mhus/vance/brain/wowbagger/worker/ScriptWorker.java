package de.mhus.vance.brain.wowbagger.worker;

import de.mhus.vance.brain.action.ScopeLevel;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.brain.script.ScriptResult;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Script worker: one JavaScript run per chunk — no process, no chat history.
 * The script receives the chunk and returns one result per record; inside it
 * may orchestrate several steps per record ({@code vance.llm},
 * {@code vance.zem}, {@code vance.tools.call}), which is what "Hactar/Vogon as
 * a worker" means in bulk (planning/wowbagger-workers.md §2).
 *
 * <p>Worker recipe: {@code engine: hactar} with {@code internal: true} —
 * Hactar is the script engine, the profile is never spawned. Params:
 * {@code script} (document path, cascade-resolved) and
 * {@code timeoutSeconds} (per chunk, default 120). Without {@code script}
 * the structure's {@code workerScript} — a file in the run's RootDir the
 * agent wrote — is the script (bundled profile
 * {@code wowbagger-script-worker}).
 *
 * <p>Contract: {@code vance.params.records} (JSONL records as objects, lines
 * as strings), {@code vance.params.task}, {@code vance.params.chunk}
 * ({@code index}, {@code startRecord}); the return value is a list of the same
 * length — strings become the line as-is, objects/lists one JSON line.
 *
 * <p>Tools: the script sees its process's tool surface, like any
 * process-scoped script — what it does with it is the caller's call.
 * Models: every {@code vance.llm}/{@code vance.zem} call passes a
 * {@link WorkerCallPolicy} — model on the operator allowlist,
 * {@code maxCallsPerRecord × records} calls per chunk at most.
 */
@Component
public class ScriptWorker implements WowbaggerWorker {

    public static final String KIND = "script";
    /** Bundled profile used when the structure names only a {@code workerScript}. */
    public static final String DEFAULT_RECIPE = "wowbagger-script-worker";

    static final String ENGINE = "hactar";
    static final String PARAM_SCRIPT = "script";
    static final String PARAM_TIMEOUT = "timeoutSeconds";
    static final int DEFAULT_TIMEOUT_SECONDS = 120;

    private final ScriptExecutor scriptExecutor;
    private final DocumentService documentService;
    private final WorkspaceService workspaceService;
    private final ObjectProvider<ThinkEngineService> thinkEngineService;
    private final RecipeResolver recipeResolver;
    private final AiModelResolver aiModelResolver;
    private final ObjectMapper objectMapper;

    public ScriptWorker(
            ScriptExecutor scriptExecutor,
            DocumentService documentService,
            WorkspaceService workspaceService,
            ObjectProvider<ThinkEngineService> thinkEngineService,
            RecipeResolver recipeResolver,
            AiModelResolver aiModelResolver,
            ObjectMapper objectMapper) {
        this.scriptExecutor = scriptExecutor;
        this.documentService = documentService;
        this.workspaceService = workspaceService;
        this.thinkEngineService = thinkEngineService;
        this.recipeResolver = recipeResolver;
        this.aiModelResolver = aiModelResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String describe() {
        return "a worker script — recipe with engine: hactar and internal: true, or workerScript in the structure";
    }

    @Override
    public boolean supports(ResolvedRecipe recipe) {
        return ENGINE.equals(recipe.engine()) && recipe.internal();
    }

    @Override
    public PreparedWorker prepare(WorkerSetup setup) {
        ThinkProcessDocument process = setup.process();
        WorkerSpec spec = setup.spec();
        Map<String, Object> params = setup.recipe().params();
        String documentPath = params.get(PARAM_SCRIPT) instanceof String s && !s.isBlank() ? s.trim() : null;
        String code;
        String sourceName;
        if (documentPath != null) {
            code = documentService
                    .lookupCascade(process.getTenantId(), process.getProjectId(), documentPath)
                    .map(LookupResult::content)
                    .orElseThrow(
                            () -> new IllegalStateException("worker script document '" + documentPath + "' not found"));
            sourceName = documentPath;
        } else if (spec.workerScript() != null && !spec.workerScript().isBlank()) {
            code = readWorkspaceScript(process, spec);
            sourceName = spec.workRoot() + "/" + spec.workerScript();
        } else {
            throw new IllegalStateException("script worker '" + spec.recipeName()
                    + "' has no script — set params.script in the profile or workerScript in the structure");
        }
        if (code.isBlank()) {
            throw new IllegalStateException("worker script '" + sourceName + "' is empty");
        }
        int timeout = params.get(PARAM_TIMEOUT) instanceof Number n && n.intValue() > 0
                ? n.intValue()
                : DEFAULT_TIMEOUT_SECONDS;
        return new Prepared(this, process, spec, code, sourceName, Duration.ofSeconds(timeout));
    }

    private String readWorkspaceScript(ThinkProcessDocument process, WorkerSpec spec) {
        if (spec.workRoot() == null) {
            throw new IllegalStateException("workerScript needs the run's RootDir — configure the run first");
        }
        try {
            return Files.readString(
                    workspaceService.readablePath(
                            process.getTenantId(), process.getProjectId(), spec.workRoot(), spec.workerScript()),
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "worker script '" + spec.workerScript() + "' unreadable in RootDir '" + spec.workRoot() + "': "
                            + e.getMessage(),
                    e);
        }
    }

    /** {@code instance:model} a LightLlm recipe resolves to — what the call will hit. */
    String llmModelOf(ThinkProcessDocument process, String recipeName) {
        ResolvedRecipe recipe = recipeResolver
                .resolve(process.getTenantId(), process.getProjectId(), recipeName)
                .orElseThrow(() -> new IllegalStateException("LightLlm recipe '" + recipeName + "' not found"));
        AiModelResolver.Resolved resolved = aiModelResolver.resolveOrDefault(
                AiModelResolver.parseModelSpec(recipe.params()),
                process.getTenantId(),
                process.getProjectId(),
                process.getId());
        return resolved.providerInstance() + ":" + resolved.modelName();
    }

    private static final class Prepared implements PreparedWorker {

        private final ScriptWorker owner;
        private final ThinkProcessDocument process;
        private final WorkerSpec spec;
        private final String code;
        private final String sourceName;
        private final Duration timeout;
        /** LightLlm recipe → model, resolved once per run. */
        private final Map<String, String> llmModels = new ConcurrentHashMap<>();

        Prepared(
                ScriptWorker owner,
                ThinkProcessDocument process,
                WorkerSpec spec,
                String code,
                String sourceName,
                Duration timeout) {
            this.owner = owner;
            this.process = process;
            this.spec = spec;
            this.code = code;
            this.sourceName = sourceName;
            this.timeout = timeout;
        }

        @Override
        public String kind() {
            return KIND;
        }

        @Override
        public String recipeName() {
            return spec.recipeName();
        }

        /** No fixed model — every call is gated by the {@link WorkerCallPolicy}. */
        @Override
        public @Nullable String resolvedModel() {
            return null;
        }

        @Override
        public WorkerReply process(List<String> records, ChunkContext chunk) {
            WorkerCallPolicy policy = new WorkerCallPolicy(
                    recipe -> llmModels.computeIfAbsent(recipe, r -> owner.llmModelOf(process, r)),
                    spec.modelAllowlist(),
                    Math.max(1, spec.maxCallsPerRecord()) * records.size());
            Map<String, @Nullable Object> args = new LinkedHashMap<>();
            args.put("records", records.stream().map(this::record).toList());
            args.put("task", spec.task());
            args.put("chunk", Map.of("index", chunk.index(), "startRecord", chunk.startRecord()));
            ScriptResult result;
            try {
                result = owner.scriptExecutor.run(new ScriptRequest(
                                "js",
                                code,
                                sourceName,
                                tools(),
                                timeout,
                                Map.of("args", args),
                                spec.recipeName(),
                                ScopeLevel.PROCESS_SCOPED,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)
                        .withCallPolicy(policy));
            } catch (ScriptExecutionException e) {
                throw new IllegalStateException("worker script failed [" + e.errorClass() + "]: " + e.getMessage(), e);
            }
            return new WorkerReply(lines(result.value(), records.size()), policy.calls(), 0, 0);
        }

        /** The process's tool surface, built per chunk (a context is not shared across threads). */
        private ContextToolsApi tools() {
            return owner.thinkEngineService.getObject().newContext(process).tools();
        }

        private Object record(String raw) {
            return spec.inputJsonl() ? owner.objectMapper.readValue(raw, Map.class) : raw;
        }

        private List<String> lines(@Nullable Object value, int expected) {
            if (!(value instanceof List<?> list)) {
                throw new IllegalStateException("worker script must return a list with one result per record, got "
                        + (value == null ? "nothing" : value.getClass().getSimpleName()));
            }
            if (list.size() != expected) {
                throw new IllegalStateException(
                        "worker script returned " + list.size() + " results for " + expected + " records");
            }
            List<String> out = new ArrayList<>(expected);
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item == null) {
                    throw new IllegalStateException("worker script returned no result for record " + i);
                }
                String line = item instanceof String s ? s : owner.objectMapper.writeValueAsString(item);
                if (line.contains("\n")) {
                    throw new IllegalStateException("result for record " + i + " spans several lines");
                }
                out.add(line);
            }
            return out;
        }

        @Override
        public String toString() {
            return "ScriptWorker[" + sourceName + "]";
        }
    }
}
