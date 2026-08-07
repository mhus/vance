package de.mhus.vance.brain.script;

import de.mhus.vance.api.notification.NotificationSeverity;
import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.ai.light.SchemaValidationException;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.SecretResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Host-API surface exposed to brain-side JavaScript as the {@code vance}
 * binding. Reachable members are annotated {@link HostAccess.Export};
 * the script context is configured to allow nothing else.
 *
 * <p>Identity (tenant/project/session/process) comes from the bound
 * {@link ContextToolsApi}, never from script-supplied parameters. A
 * script cannot escape its scope by passing a different tenant or
 * project to a tool call.
 */
public final class VanceScriptApi {

    private static final Logger LOG = LoggerFactory.getLogger("vance.script");

    /**
     * Out-of-band hook that lets callers tap every {@code vance.log.*}
     * call from inside the script context. Used by Script Cortex to
     * surface server-side log lines in the Execute dialog's Output
     * pane alongside {@code console.*} output — without it, users
     * who reach for {@code vance.log.info(...)} see nothing.
     *
     * <p>The hook is {@link InheritableThreadLocal} on purpose: the
     * GraalJS watchdog runs the eval on a child thread of the caller,
     * and ThreadLocal isn't inherited automatically. Inheritable copy
     * propagates the active tee into the watchdog thread on creation.
     *
     * <p>Caller contract: set right before the script runs, clear in
     * a finally block. {@link ScriptLog} reads the value on every
     * log call and pushes a {@code (stream, formattedLine)} tuple
     * when present. The SLF4J log is unaffected.
     */
    private static final InheritableThreadLocal<BiConsumer<String, String>>
            ACTIVE_LOG_TEE = new InheritableThreadLocal<>();

    public static void setActiveLogTee(@Nullable BiConsumer<String, String> tee) {
        if (tee == null) ACTIVE_LOG_TEE.remove();
        else ACTIVE_LOG_TEE.set(tee);
    }

    public static void clearActiveLogTee() {
        ACTIVE_LOG_TEE.remove();
    }

    /**
     * Per-run sink for secret values pulled via {@code vance.secret(...)}, so the
     * executor can mask them out of the run's string output. Same
     * {@link InheritableThreadLocal} rationale as {@link #ACTIVE_LOG_TEE}: the
     * eval runs on a watchdog child thread that inherits the sink at creation.
     * Set/clear around the eval; the set must be thread-safe.
     */
    private static final InheritableThreadLocal<Set<String>> ACTIVE_SECRET_TEE =
            new InheritableThreadLocal<>();

    public static void setActiveSecretTee(@Nullable Set<String> tee) {
        if (tee == null) ACTIVE_SECRET_TEE.remove();
        else ACTIVE_SECRET_TEE.set(tee);
    }

    public static void clearActiveSecretTee() {
        ACTIVE_SECRET_TEE.remove();
    }

    @HostAccess.Export
    public final ScriptToolsApi tools;

    /**
     * Ergonomic file-access surface exposed as {@code vance.files} — thin,
     * capability-guarded wrappers over the {@code file_*} work-target tools.
     * {@code isEnabled()} probes whether the bound process grants file tools.
     */
    @HostAccess.Export
    public final ScriptFilesApi files;

    @HostAccess.Export
    public final ScriptContextView context;

    @HostAccess.Export
    public final ScriptLog log;

    @HostAccess.Export
    public final ScriptProcessApi process;

    /**
     * Caller-supplied parameters exposed as {@code vance.params.<key>}.
     * Mirrors the legacy top-level {@code args.<key>} binding so
     * scripts can read inputs through the {@code vance.*}-namespaced
     * surface (which Slart-generated SCRIPT_JS scripts default to,
     * by analogy with {@code vance.context.*}).
     *
     * <p>Always non-null — empty map when the caller supplied no
     * params. Accessing {@code vance.params.missing} from JS
     * returns {@code undefined} rather than throwing. Wrapped
     * immutable in the constructor.
     */
    @HostAccess.Export
    public final Map<String, Object> params;

    /**
     * Document-access surface exposed as {@code vance.documents}. Resolves
     * scope from the bound {@link ContextToolsApi}; scripts cannot reach
     * outside their tenant/project. {@code null} when no
     * {@link DocumentService} was wired into the constructor — legacy
     * call-sites that pre-date Phase 3 still build the API without it
     * (trigger-scoped scripts, unit-test stubs) and would NPE on first
     * access via {@code vance.documents.*} with a clear
     * {@link ScriptHostException}.
     */
    @HostAccess.Export
    public final @Nullable ScriptDocumentApi documents;

    /**
     * Light-LLM surface exposed as {@code vance.llm}. Single-shot calls
     * via a recipe-as-config profile (must be {@code internal: true}).
     * {@code null} when no {@link LightLlmService} was wired into the
     * constructor — legacy call-sites (trigger-scoped scripts,
     * unit-test stubs) leave this null; {@code vance.llm} is then
     * {@code null} in JavaScript. See {@link ScriptLightLlmApi}.
     */
    @HostAccess.Export
    public final @Nullable ScriptLightLlmApi llm;

    /**
     * Settings-cascade surface exposed as {@code vance.settings}.
     * Cascade {@code think-process → project → _vance} (user-layer
     * deliberately excluded). {@code null} when no
     * {@link SettingService} was wired — accesses via JS throw
     * the usual TypeError on null. See {@link ScriptSettingsApi}.
     */
    @HostAccess.Export
    public final @Nullable ScriptSettingsApi settings;

    /**
     * Vault-secret surface exposed as {@code vance.secret}. Resolves a secret
     * reference ({@code vault:key}, {@code project:key}, …) server-side via the
     * shared {@link SecretResolver} and returns the value to the script — the
     * leak-free pull counterpart to compose {@code secrets:} env injection.
     * {@code null} when no {@link SecretResolver} was wired (unit-test stubs);
     * {@code vance.secret} is then {@code null} in JavaScript.
     * See {@link ScriptSecretApi}.
     */
    @HostAccess.Export
    public final @Nullable ScriptSecretApi secret;

    /**
     * Completion-guard surface exposed as {@code vance.guard}. Present
     * only for guard runs (the {@code CompletionGuardService} builds it
     * with the yield context + a cap-aware continue hook + the loop /
     * session scratch stores). {@code null} for every other script run
     * — trigger-scoped, Cortex, skill, Damogran-js — where
     * {@code vance.guard} is {@code null} in JavaScript. See
     * {@code planning/completion-guard.md} v2.5.
     */
    @HostAccess.Export
    public final @Nullable ScriptGuardApi guard;

    public VanceScriptApi(ContextToolsApi toolsApi) {
        this(toolsApi, null, Set.of(), null, null, null);
    }

    public VanceScriptApi(ContextToolsApi toolsApi, @Nullable String recipeName) {
        this(toolsApi, recipeName, Set.of(), null, null, null);
    }

    public VanceScriptApi(ContextToolsApi toolsApi,
                          @Nullable String recipeName,
                          Set<String> deniedToolNames) {
        this(toolsApi, recipeName, deniedToolNames, null, null, null);
    }

    public VanceScriptApi(ContextToolsApi toolsApi,
                          @Nullable String recipeName,
                          Set<String> deniedToolNames,
                          @Nullable DocumentService documentService) {
        this(toolsApi, recipeName, deniedToolNames, documentService, null, null);
    }

    public VanceScriptApi(ContextToolsApi toolsApi,
                          @Nullable String recipeName,
                          Set<String> deniedToolNames,
                          @Nullable DocumentService documentService,
                          @Nullable BiConsumer<String,
                                  @Nullable Map<String, Object>> progressEmitter) {
        this(toolsApi, recipeName, deniedToolNames, documentService, progressEmitter, null);
    }

    /**
     * Full constructor. {@code deniedToolNames} is the set of tools
     * that {@link ScriptToolsApi#call} refuses outright — typically the
     * spawn-tool set in trigger-scoped runs (see
     * {@link de.mhus.vance.brain.action.SpawnToolRegistry} and
     * {@code specification/trigger-actions.md} §8).
     *
     * <p>{@code documentService} enables the {@code vance.documents.*}
     * binding. Pass {@code null} for scripts that mustn't touch documents
     * (legacy trigger-scoped runs); accesses then throw a
     * {@link ScriptHostException} with a clear message instead of NPE.
     *
     * <p>{@code progressEmitter} enables {@code vance.process.progress(...)}.
     * Pass {@code null} for scripts that don't run inside a parent
     * think-process (trigger-scoped runs, unit-test stubs); calls
     * then degrade gracefully to a no-op + a SLF4J trace line.
     * Hactar's ExecutingPhase wires this to
     * {@link de.mhus.vance.brain.progress.ProgressEmitter#emitStatus}
     * with {@link de.mhus.vance.api.progress.StatusTag#SCRIPT_PROGRESS}.
     *
     * <p>{@code notificationEmitter} enables {@code vance.process.notify(...)}.
     * Same null-degrade contract as {@code progressEmitter}. Hactar's
     * ExecutingPhase wires this to
     * {@link de.mhus.vance.brain.notification.NotificationService#publish}.
     */
    public VanceScriptApi(ContextToolsApi toolsApi,
                          @Nullable String recipeName,
                          Set<String> deniedToolNames,
                          @Nullable DocumentService documentService,
                          @Nullable BiConsumer<String,
                                  @Nullable Map<String, Object>> progressEmitter,
                          @Nullable BiConsumer<String,
                                  @Nullable NotificationSeverity> notificationEmitter) {
        this.tools = new ScriptToolsApi(toolsApi, deniedToolNames);
        this.files = new ScriptFilesApi(this.tools);
        this.context = new ScriptContextView(toolsApi.scope(), recipeName);
        this.log = new ScriptLog(toolsApi.scope());
        this.process = new ScriptProcessApi(this, progressEmitter, notificationEmitter);
        // params start empty — call sites that wire script-level params
        // (Hactar's ExecutingPhase, ScriptCortexExecutionService) replace
        // this with the actual map via the params-aware constructor below.
        this.params = Map.of();
        this.documents = documentService == null
                ? null
                : new ScriptDocumentApi(documentService, toolsApi.scope(), null, null);
        this.llm = null;
        this.settings = null;
        this.secret = null;
        this.guard = null;
    }

    /**
     * 7-arg constructor adding {@code paramsMap} — the per-call
     * caller-supplied input bindings exposed as {@code vance.params.*}.
     * Used by callers that bind script-level parameters (Hactar
     * v2's ExecutingPhase, the Cortex run-pipeline, Skill scripts).
     * Existing 6-arg callers get an empty {@code vance.params} via
     * the 6-arg constructor above.
     */
    public VanceScriptApi(ContextToolsApi toolsApi,
                          @Nullable String recipeName,
                          Set<String> deniedToolNames,
                          @Nullable DocumentService documentService,
                          @Nullable BiConsumer<String,
                                  @Nullable Map<String, Object>> progressEmitter,
                          @Nullable BiConsumer<String,
                                  @Nullable NotificationSeverity> notificationEmitter,
                          @Nullable Map<String, Object> paramsMap) {
        this(toolsApi, recipeName, deniedToolNames, documentService,
                progressEmitter, notificationEmitter, paramsMap, null, null, null, null, null);
    }

    public VanceScriptApi(ContextToolsApi toolsApi,
                          @Nullable String recipeName,
                          Set<String> deniedToolNames,
                          @Nullable DocumentService documentService,
                          @Nullable BiConsumer<String,
                                  @Nullable Map<String, Object>> progressEmitter,
                          @Nullable BiConsumer<String,
                                  @Nullable NotificationSeverity> notificationEmitter,
                          @Nullable Map<String, Object> paramsMap,
                          @Nullable LightLlmService lightLlmService) {
        this(toolsApi, recipeName, deniedToolNames, documentService,
                progressEmitter, notificationEmitter, paramsMap, lightLlmService, null, null, null, null);
    }

    /**
     * 9-arg constructor adding {@code settingService} — wires the
     * {@code vance.settings} surface. Delegates with a {@code null}
     * {@code documentBasePath} (project-root-relative document paths).
     */
    public VanceScriptApi(ContextToolsApi toolsApi,
                          @Nullable String recipeName,
                          Set<String> deniedToolNames,
                          @Nullable DocumentService documentService,
                          @Nullable BiConsumer<String,
                                  @Nullable Map<String, Object>> progressEmitter,
                          @Nullable BiConsumer<String,
                                  @Nullable NotificationSeverity> notificationEmitter,
                          @Nullable Map<String, Object> paramsMap,
                          @Nullable LightLlmService lightLlmService,
                          @Nullable SettingService settingService) {
        this(toolsApi, recipeName, deniedToolNames, documentService, progressEmitter,
                notificationEmitter, paramsMap, lightLlmService, settingService, null, null, null);
    }

    /**
     * 11-arg overload (adds {@code contextFactory}) — pre-secret call surface,
     * delegates with a {@code null} {@link SecretResolver}.
     */
    public VanceScriptApi(ContextToolsApi toolsApi,
                          @Nullable String recipeName,
                          Set<String> deniedToolNames,
                          @Nullable DocumentService documentService,
                          @Nullable BiConsumer<String,
                                  @Nullable Map<String, Object>> progressEmitter,
                          @Nullable BiConsumer<String,
                                  @Nullable NotificationSeverity> notificationEmitter,
                          @Nullable Map<String, Object> paramsMap,
                          @Nullable LightLlmService lightLlmService,
                          @Nullable SettingService settingService,
                          @Nullable String documentBasePath,
                          de.mhus.vance.brain.permission.@Nullable SecurityContextFactory contextFactory) {
        this(toolsApi, recipeName, deniedToolNames, documentService, progressEmitter,
                notificationEmitter, paramsMap, lightLlmService, settingService,
                documentBasePath, contextFactory, null);
    }

    /**
     * 12-arg canonical constructor — adds {@code documentBasePath}, the
     * "current path" that {@code vance.documents.*} resolves relative paths
     * against ({@code /abs} stays project-root). {@code null}/empty keeps
     * document paths project-root-relative. GraaljsScriptExecutor uses this,
     * threading {@code ScriptRequest#documentBasePath}.
     */
    public VanceScriptApi(ContextToolsApi toolsApi,
                          @Nullable String recipeName,
                          Set<String> deniedToolNames,
                          @Nullable DocumentService documentService,
                          @Nullable BiConsumer<String,
                                  @Nullable Map<String, Object>> progressEmitter,
                          @Nullable BiConsumer<String,
                                  @Nullable NotificationSeverity> notificationEmitter,
                          @Nullable Map<String, Object> paramsMap,
                          @Nullable LightLlmService lightLlmService,
                          @Nullable SettingService settingService,
                          @Nullable String documentBasePath,
                          de.mhus.vance.brain.permission.@Nullable SecurityContextFactory contextFactory,
                          @Nullable SecretResolver secretResolver) {
        this(toolsApi, recipeName, deniedToolNames, documentService, progressEmitter,
                notificationEmitter, paramsMap, lightLlmService, settingService,
                documentBasePath, contextFactory, secretResolver, /*guardApi*/ null);
    }

    /**
     * 13-arg canonical constructor — adds {@code guardApi}, the
     * {@code vance.guard} surface for a completion-guard run. Every other
     * constructor delegates here with a {@code null} guard. The
     * {@code CompletionGuardService} is the only caller that passes a
     * non-null value (via {@code ScriptRequest}); all other script runs
     * leave {@code vance.guard} unset.
     */
    public VanceScriptApi(ContextToolsApi toolsApi,
                          @Nullable String recipeName,
                          Set<String> deniedToolNames,
                          @Nullable DocumentService documentService,
                          @Nullable BiConsumer<String,
                                  @Nullable Map<String, Object>> progressEmitter,
                          @Nullable BiConsumer<String,
                                  @Nullable NotificationSeverity> notificationEmitter,
                          @Nullable Map<String, Object> paramsMap,
                          @Nullable LightLlmService lightLlmService,
                          @Nullable SettingService settingService,
                          @Nullable String documentBasePath,
                          de.mhus.vance.brain.permission.@Nullable SecurityContextFactory contextFactory,
                          @Nullable SecretResolver secretResolver,
                          @Nullable ScriptGuardApi guardApi) {
        this.tools = new ScriptToolsApi(toolsApi, deniedToolNames);
        this.files = new ScriptFilesApi(this.tools);
        this.context = new ScriptContextView(toolsApi.scope(), recipeName);
        this.log = new ScriptLog(toolsApi.scope());
        this.process = new ScriptProcessApi(this, progressEmitter, notificationEmitter);
        this.params = paramsMap == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(paramsMap));
        this.documents = documentService == null
                ? null
                : new ScriptDocumentApi(documentService, toolsApi.scope(), documentBasePath, contextFactory);
        this.llm = lightLlmService == null
                ? null
                : new ScriptLightLlmApi(lightLlmService, toolsApi.scope());
        this.settings = settingService == null
                ? null
                : new ScriptSettingsApi(settingService, toolsApi.scope());
        this.secret = secretResolver == null
                ? null
                : new ScriptSecretApi(secretResolver, toolsApi.scope());
        this.guard = guardApi;
    }

    /** Tool-dispatch surface exposed as {@code vance.tools}. */
    public static final class ScriptToolsApi {

        private final ContextToolsApi delegate;
        private final Set<String> deniedToolNames;

        ScriptToolsApi(ContextToolsApi delegate, Set<String> deniedToolNames) {
            this.delegate = delegate;
            this.deniedToolNames = deniedToolNames == null ? Set.of() : Set.copyOf(deniedToolNames);
        }

        @HostAccess.Export
        public Map<String, Object> call(String name, @Nullable Map<String, Object> params) {
            if (deniedToolNames.contains(name)) {
                throw new ScriptHostException(
                        "Tool '" + name + "' not allowed in trigger-scoped script — "
                                + "wrap in a workflow if you need it",
                        null);
            }
            try {
                return delegate.invoke(name, params == null ? Map.of() : params);
            } catch (ToolException e) {
                throw new ScriptHostException(e.getMessage(), e);
            } catch (RuntimeException e) {
                throw new ScriptHostException(
                        "Tool '" + name + "' failed: " + e.getMessage(), e);
            }
        }

        /**
         * The tool names this script may call — the bound process's effective
         * allow-set minus any trigger-scoped denials. Sorted for stable output.
         * Empty for an unrestricted engine (then {@link #has} still answers
         * per-tool via the dispatcher's own gating).
         */
        @HostAccess.Export
        public java.util.List<String> list() {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (String name : delegate.invocableToolNames()) {
                if (!deniedToolNames.contains(name)) {
                    names.add(name);
                }
            }
            java.util.Collections.sort(names);
            return names;
        }

        /** Whether {@code name} is callable in this context (allow-set + not denied). */
        @HostAccess.Export
        public boolean has(String name) {
            return name != null && !deniedToolNames.contains(name) && delegate.isAllowed(name);
        }
    }

    /**
     * {@code vance.files} — capability-guarded convenience over the
     * {@code file_*} work-target tools (which dispatch to the process's active
     * WorkTarget). {@link #isEnabled()} reports whether the bound process grants
     * file tools; every file method throws {@link ScriptHostException} when it
     * does not, so a script fails loudly rather than silently no-op-ing. Return
     * values are the raw tool results (e.g. {@code read(path).content}).
     */
    public static final class ScriptFilesApi {

        private final ScriptToolsApi tools;

        ScriptFilesApi(ScriptToolsApi tools) {
            this.tools = tools;
        }

        /** True when the bound process grants file tools (probes {@code file_read}). */
        @HostAccess.Export
        public boolean isEnabled() {
            return tools.has("file_read");
        }

        private void requireEnabled() {
            if (!isEnabled()) {
                throw new ScriptHostException(
                        "vance.files: file tools are not available in this context — "
                                + "the bound process/engine grants no file_* tools", null);
            }
        }

        private static String requirePath(String path) {
            if (path == null || path.isBlank()) {
                throw new ScriptHostException("vance.files: 'path' must not be empty", null);
            }
            return path;
        }

        /** Read a text file and return its {@code content} string (null if absent). */
        @HostAccess.Export
        public @Nullable String read(String path) {
            Object content = readRaw(path).get("content");
            return content == null ? null : content.toString();
        }

        /** Read a text file and return the full tool result (content + path/truncated/…). */
        @HostAccess.Export
        public Map<String, Object> readRaw(String path) {
            requireEnabled();
            return tools.call("file_read", Map.of("path", requirePath(path)));
        }

        @HostAccess.Export
        public Map<String, Object> write(String path, String content) {
            requireEnabled();
            return tools.call("file_write", Map.of(
                    "path", requirePath(path), "content", content == null ? "" : content));
        }

        @HostAccess.Export
        public Map<String, Object> list(@Nullable String path) {
            requireEnabled();
            return tools.call("file_list", Map.of("path", path == null ? "" : path));
        }
    }

    /** Read-only scope info exposed as {@code vance.context}. */
    public static final class ScriptContextView {

        @HostAccess.Export
        public final String tenantId;

        @HostAccess.Export
        public final @Nullable String projectId;

        @HostAccess.Export
        public final @Nullable String sessionId;

        @HostAccess.Export
        public final @Nullable String processId;

        @HostAccess.Export
        public final @Nullable String userId;

        /** Recipe name that spawned the running process — exposed so
         *  scripts (e.g. Hactar-generated orchestrators) can
         *  branch on their invocation context. {@code null} when the
         *  caller didn't supply a recipe (direct engine spawns,
         *  legacy 5-/6-arg {@code ScriptRequest} constructors). */
        @HostAccess.Export
        public final @Nullable String recipe;

        ScriptContextView(ToolInvocationContext scope, @Nullable String recipeName) {
            this.tenantId = scope.tenantId();
            this.projectId = scope.projectId();
            this.sessionId = scope.sessionId();
            this.processId = scope.processId();
            this.userId = scope.userId();
            this.recipe = recipeName;
        }
    }

    /** Structured logger exposed as {@code vance.log}. */
    public static final class ScriptLog {

        private final ToolInvocationContext scope;

        ScriptLog(ToolInvocationContext scope) {
            this.scope = scope;
        }

        @HostAccess.Export
        public void info(String message, @Nullable Map<String, Object> fields) {
            LOG.info("[script] tenant={} project={} process={} {} {}",
                    scope.tenantId(), scope.projectId(), scope.processId(),
                    message, fields == null ? Map.of() : fields);
            tee("info", message, fields);
        }

        /** Convenience overload — {@code vance.log.info("just a message")}.
         *  GraalJS does not auto-resolve missing optional args, so each
         *  arity must be its own export. */
        @HostAccess.Export
        public void info(String message) {
            info(message, null);
        }

        @HostAccess.Export
        public void warn(String message, @Nullable Map<String, Object> fields) {
            LOG.warn("[script] tenant={} project={} process={} {} {}",
                    scope.tenantId(), scope.projectId(), scope.processId(),
                    message, fields == null ? Map.of() : fields);
            tee("warn", message, fields);
        }

        @HostAccess.Export
        public void warn(String message) {
            warn(message, null);
        }

        @HostAccess.Export
        public void error(String message, @Nullable Map<String, Object> fields) {
            LOG.error("[script] tenant={} project={} process={} {} {}",
                    scope.tenantId(), scope.projectId(), scope.processId(),
                    message, fields == null ? Map.of() : fields);
            tee("error", message, fields);
        }

        @HostAccess.Export
        public void error(String message) {
            error(message, null);
        }

        private static void tee(String stream, String message,
                                @Nullable Map<String, Object> fields) {
            BiConsumer<String, String> hook = ACTIVE_LOG_TEE.get();
            if (hook == null) return;
            String line = (fields == null || fields.isEmpty())
                    ? message : message + " " + fields;
            try {
                hook.accept(stream, line);
            } catch (RuntimeException ignored) {
                // Hook failures must never leak back into the script.
            }
        }
    }

    /**
     * Process surface exposed as {@code vance.process}. Three members:
     * <ul>
     *   <li>{@link #spawn(Map)} routes to the {@code process_create}
     *       tool — same allow-filter / permission / quota path as the
     *       LLM tool loop.</li>
     *   <li>{@link #progress(String, Map)} emits a
     *       {@code PROCESS_PROGRESS} ping for the running script's
     *       parent think-process. No-op when the script wasn't
     *       launched with a progress emitter (trigger-scoped runs,
     *       unit-test stubs).</li>
     *   <li>{@link #notify(String, String)} fires an attention-grabbing
     *       {@code NOTIFY} ping (terminal bell / WebAudio beep / iOS
     *       local notification) on the user's client. Use sparingly —
     *       only at notable boundaries. No-op without a notification
     *       emitter, same as {@code progress}.</li>
     * </ul>
     */
    public static final class ScriptProcessApi {

        private final VanceScriptApi parent;
        private final @Nullable BiConsumer<String,
                @Nullable Map<String, Object>> progressEmitter;
        private final @Nullable BiConsumer<String,
                @Nullable NotificationSeverity> notificationEmitter;

        ScriptProcessApi(VanceScriptApi parent,
                @Nullable BiConsumer<String,
                        @Nullable Map<String, Object>> progressEmitter,
                @Nullable BiConsumer<String,
                        @Nullable NotificationSeverity> notificationEmitter) {
            this.parent = parent;
            this.progressEmitter = progressEmitter;
            this.notificationEmitter = notificationEmitter;
        }

        @HostAccess.Export
        public Map<String, Object> spawn(Map<String, Object> params) {
            return parent.tools.call("process_spawn", params);
        }

        /**
         * Emit a live progress ping on the parent think-process. The
         * payload becomes the {@code PROCESS_PROGRESS} status text +
         * optional extra fields the Web-UI / Cortex run-panel can
         * surface — see {@code specification/user-progress-channel.md}.
         *
         * <p>No-op (with a trace log) when the script wasn't launched
         * with a progress-capable host (e.g. trigger-scoped sandboxes,
         * unit-test stubs). Long-running scripts (Mail-Bot, batch
         * pipelines) should call this every few hundred items to
         * surface progress without blowing up the event log.
         *
         * @param message  short human-readable progress text. Required.
         * @param payload  optional structured fields (e.g.
         *                 {@code { processed: 47, total: 200 }}).
         */
        @HostAccess.Export
        public void progress(String message, @Nullable Map<String, Object> payload) {
            Objects.requireNonNull(message,
                    "vance.process.progress: message must not be null");
            if (progressEmitter == null) {
                LOG.trace("[script] tenant={} process={} progress (no-emitter) {} {}",
                        parent.context.tenantId, parent.context.processId,
                        message, payload == null ? Map.of() : payload);
                return;
            }
            try {
                progressEmitter.accept(message, payload);
            } catch (RuntimeException e) {
                // A broken emitter must never leak back into the script.
                LOG.warn("[script] tenant={} process={} progress emit failed: {}",
                        parent.context.tenantId, parent.context.processId,
                        e.toString());
            }
        }

        /**
         * Fire an attention-grabbing notification on the user's client
         * (terminal bell / WebAudio beep / iOS local notification). Use
         * sparingly — only at notable boundaries (batch done, long wait
         * resolved, escalation). Status chatter belongs in
         * {@link #progress(String, Map)}.
         *
         * <p>See {@code specification/user-notification-channel.md}.
         *
         * <p>No-op (with a trace log) when no notification emitter is
         * wired — trigger-scoped sandboxes, unit-test stubs.
         *
         * @param message  short attention text. Required.
         * @param severity {@code "INFO"} | {@code "WARN"} | {@code "ERROR"} (case-insensitive);
         *                 {@code null} or unknown → {@link NotificationSeverity#INFO}.
         */
        @HostAccess.Export
        public void notify(String message, @Nullable String severity) {
            Objects.requireNonNull(message,
                    "vance.process.notify: message must not be null");
            NotificationSeverity sev = parseSeverity(severity);
            if (notificationEmitter == null) {
                LOG.trace("[script] tenant={} process={} notify (no-emitter) [{}] {}",
                        parent.context.tenantId, parent.context.processId,
                        sev, message);
                return;
            }
            try {
                notificationEmitter.accept(message, sev);
            } catch (RuntimeException e) {
                LOG.warn("[script] tenant={} process={} notify emit failed: {}",
                        parent.context.tenantId, parent.context.processId,
                        e.toString());
            }
        }

        /** Convenience overload — no severity, defaults to INFO. */
        @HostAccess.Export
        public void notify(String message) {
            notify(message, null);
        }

        private static NotificationSeverity parseSeverity(@Nullable String raw) {
            if (raw == null || raw.isBlank()) return NotificationSeverity.INFO;
            try {
                return NotificationSeverity.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                LOG.debug("vance.process.notify: unknown severity '{}', defaulting to INFO", raw);
                return NotificationSeverity.INFO;
            }
        }
    }

    /**
     * Completion-guard surface exposed as {@code vance.guard} — present
     * only for guard runs. Read-only yield context ({@link #task},
     * {@link #output}, {@link #round}, {@link #maxRounds},
     * {@link #naturalStop}), the cap-aware {@link #continueWith(String)}
     * action, and the two transient scratch stores {@link #loopValues}
     * (per process/loop) and {@link #sessionValues} (per session) — both
     * {@link ScriptGuardScratchApi} instances backed by host-side maps
     * that survive across the re-entrant guard runs.
     *
     * <p>Judge and follow-up prompt are no longer config fields — the
     * script decides both (typically via {@code vance.llm.judge(...)} +
     * {@code vance.guard.continueWith(...)}). See
     * {@code planning/completion-guard.md} v2.
     */
    public static final class ScriptGuardApi {

        /** First user message of the task under evaluation. */
        @HostAccess.Export
        public final String task;

        /** The final output the engine would yield. */
        @HostAccess.Export
        public final String output;

        /** Guard rounds already fired for this process (0 at the first yield). */
        @HostAccess.Export
        public final long round;

        /** Hard cap on guard injections — {@link #continueWith} refuses past it. */
        @HostAccess.Export
        public final long maxRounds;

        /** {@code true} for a natural stop, {@code false} for an explicit terminate. */
        @HostAccess.Export
        public final boolean naturalStop;

        /** Per-process / per-loop scratch store (reset on a genuine user turn). */
        @HostAccess.Export
        public final ScriptGuardScratchApi loopValues;

        /** Per-session scratch store (survives multiple processes of the session). */
        @HostAccess.Export
        public final ScriptGuardScratchApi sessionValues;

        private final GuardScriptHost host;

        public ScriptGuardApi(String task,
                              String output,
                              long round,
                              long maxRounds,
                              boolean naturalStop,
                              ScriptGuardScratchApi loopValues,
                              ScriptGuardScratchApi sessionValues,
                              GuardScriptHost host) {
            this.task = task == null ? "" : task;
            this.output = output == null ? "" : output;
            this.round = round;
            this.maxRounds = maxRounds;
            this.naturalStop = naturalStop;
            this.loopValues = Objects.requireNonNull(loopValues, "loopValues");
            this.sessionValues = Objects.requireNonNull(sessionValues, "sessionValues");
            this.host = Objects.requireNonNull(host, "host");
        }

        /**
         * Inject {@code prompt} into the process's own pending queue and
         * keep the engine running instead of yielding. Cap-aware: returns
         * {@code false} (no injection) once the round cap is reached, so a
         * script can react to being capped. Named {@code continueWith}
         * because {@code continue} is a JavaScript reserved word.
         *
         * @return {@code true} if injected, {@code false} if capped
         */
        @HostAccess.Export
        public boolean continueWith(String prompt) {
            if (prompt == null || prompt.isBlank()) {
                throw new ScriptHostException(
                        "vance.guard.continueWith: prompt must not be blank", null);
            }
            return host.continueWith(prompt);
        }
    }

    /**
     * A transient key/value scratch store handed to guard scripts as
     * {@code vance.guard.loopValues} / {@code vance.guard.sessionValues}.
     * Backed by a host-side map that lives in the
     * {@code CompletionGuardService} (in-memory, non-persistent) so
     * values survive across the re-entrant guard runs of a loop/session.
     *
     * <p>Exposed as an explicit wrapper — not the raw map — so the only
     * write path is {@link #set(String, Object)}, which deep-copies the
     * value into a context-independent plain-Java form via
     * {@link ScriptValueMarshaller}. That prevents a guest value backed
     * by the (soon-closed) script context from dangling in the store, and
     * keeps {@link #get()} handing out a read-only copy rather than a live
     * handle. Both {@code get()} arities the user asked for work through
     * GraalJS arity-overload: {@code lv.get('key')} and
     * {@code lv.get().key}.
     */
    public static final class ScriptGuardScratchApi {

        private static final long MAX_NODES = 10_000L;
        private static final int MAX_DEPTH = 32;

        private final Map<String, Object> backing;

        public ScriptGuardScratchApi(Map<String, Object> backing) {
            this.backing = Objects.requireNonNull(backing, "backing");
        }

        /** Whole store as a read-only plain-Java copy (for {@code lv.get().key}). */
        @HostAccess.Export
        public @Nullable Object get() {
            return ScriptValueMarshaller.toStorable(backing, MAX_NODES, MAX_DEPTH);
        }

        /**
         * Single value by key, or {@code null} (→ {@code undefined} in JS)
         * if absent. Copied out like {@link #get()}: handing back the
         * stored {@code Map}/{@code List} itself would give the script a
         * live handle into the shared store, so a nested value could be
         * mutated behind {@link #set} — and the session store is reachable
         * from two processes of the same session running on different
         * lanes, where that means concurrent access to a plain
         * {@code LinkedHashMap}/{@code ArrayList}.
         */
        @HostAccess.Export
        public @Nullable Object get(String key) {
            if (key == null) {
                return null;
            }
            Object stored = backing.get(key);
            // Scalars are immutable — no copy needed, and this keeps the
            // common "flag" case free of marshalling.
            if (stored == null || stored instanceof String
                    || stored instanceof Boolean || stored instanceof Number) {
                return stored;
            }
            return ScriptValueMarshaller.toStorable(stored, MAX_NODES, MAX_DEPTH);
        }

        /**
         * Store {@code value} under {@code key}, deep-copied to plain Java.
         * A {@code null}/{@code undefined} value removes the key (so the
         * backing map never holds a null — lets it be a plain
         * {@code ConcurrentHashMap} host-side).
         */
        @HostAccess.Export
        public void set(String key, @Nullable Object value) {
            if (key == null || key.isBlank()) {
                throw new ScriptHostException(
                        "vance.guard scratch set: key must not be blank", null);
            }
            Object stored = ScriptValueMarshaller.toStorable(value, MAX_NODES, MAX_DEPTH);
            if (stored == null) {
                backing.remove(key);
            } else {
                backing.put(key, stored);
            }
        }

        /** Whether {@code key} is present. */
        @HostAccess.Export
        public boolean has(String key) {
            return key != null && backing.containsKey(key);
        }

        /** Remove {@code key} from the store. */
        @HostAccess.Export
        public void remove(String key) {
            if (key != null) {
                backing.remove(key);
            }
        }
    }

    /**
     * Document surface exposed as {@code vance.documents}. All operations
     * scope to the run's tenant + project; cross-project access is
     * impossible because the path is the only script-supplied input.
     *
     * <p>Paths use the same convention as {@link DocumentDocument#getPath()}
     * (no leading slash, forward-slash-separated). Writes to the trash
     * folder ({@link DocumentService#TRASH_FOLDER_PREFIX}) are refused.
     */
    public static final class ScriptDocumentApi {

        private final DocumentService documentService;
        private final ToolInvocationContext scope;
        /** "Current directory" — relative paths resolve against it. "" = project root. */
        private final String basePath;
        /**
         * Resolves the script run's real subject (user + teams) for the write
         * authorization. {@code null} only in legacy/test call-sites that build
         * the API without permission wiring — then a teamless subject is built
         * inline (fail-closed; prod always has the factory via
         * {@link GraaljsScriptExecutor}).
         */
        private final de.mhus.vance.brain.permission.@Nullable SecurityContextFactory contextFactory;

        ScriptDocumentApi(DocumentService documentService, ToolInvocationContext scope,
                          @Nullable String basePath,
                          de.mhus.vance.brain.permission.@Nullable SecurityContextFactory contextFactory) {
            this.documentService = documentService;
            this.scope = scope;
            this.basePath = normalizeBasePath(basePath);
            this.contextFactory = contextFactory;
        }

        /**
         * The write actor for a script-driven document write. {@code vance.documents.*}
         * takes a caller-supplied {@code path}, so this is a user-driven write (never
         * a trusted internal one): it must carry the run's real subject with
         * {@link de.mhus.vance.shared.permission.WriteReason#USER} so the permission
         * provider applies the normal role check (R3 project role, R4 reserved
         * {@code _vance/} → ADMIN, document locks). A script may only write {@code _vance/}
         * when started under an admin user, or via a dedicated tool that owns that policy.
         * A headless run (no userId) resolves to {@code SecurityContext.SYSTEM}, which the
         * provider trusts (R1) — genuine system scripts still pass.
         */
        private de.mhus.vance.shared.permission.WriteActor writeActor() {
            de.mhus.vance.shared.permission.SecurityContext subject =
                    contextFactory != null
                            ? contextFactory.forToolSubject(scope.tenantId(), scope.userId())
                            : subjectFallback();
            return de.mhus.vance.shared.permission.WriteActor.user(subject);
        }

        private de.mhus.vance.shared.permission.SecurityContext subjectFallback() {
            String uid = scope.userId();
            return uid == null || uid.isBlank()
                    ? de.mhus.vance.shared.permission.SecurityContext.SYSTEM
                    : de.mhus.vance.shared.permission.SecurityContext.user(
                            uid, scope.tenantId(), java.util.List.of());
        }

        /**
         * Resolve a script-supplied path: a leading {@code /} is
         * project-root-absolute (slash stripped); anything else is relative
         * to {@link #basePath} (the script's folder). With an empty basePath
         * (default) relative paths stay project-root-relative — unchanged
         * behaviour for non-workbook script runs.
         */
        private String resolve(String path) {
            if (path.startsWith("/")) return path.substring(1);
            if (basePath.isEmpty()) return path;
            if (path.isEmpty()) return basePath;
            return basePath + "/" + path;
        }

        /**
         * Resolve a single script-supplied <em>document</em> path through
         * the central {@link de.mhus.vance.shared.document.DocumentRefResolver}
         * (relative to {@link #basePath}): canonicalises {@code .}/{@code ..}
         * and rejects a cross-project ref, so the script sandbox stays
         * same-project. Distinct from {@link #resolve(String)}, which is used
         * for {@code list()} prefixes — there a trailing slash is significant
         * (startsWith match) and must not be canonicalised away.
         */
        private String resolveDoc(String path) {
            de.mhus.vance.shared.document.DocumentRef ref;
            try {
                ref = de.mhus.vance.shared.document.DocumentRefResolver.resolveRef(
                        path,
                        de.mhus.vance.shared.document.DocumentRefContext.of(
                                scope.projectId(), basePath));
            } catch (de.mhus.vance.shared.document.DocumentRefException e) {
                throw new ScriptHostException(
                        "vance.documents: bad path '" + path + "': " + e.getMessage(), null);
            }
            if (!scope.projectId().equals(ref.projectId())) {
                throw new ScriptHostException(
                        "vance.documents: cross-project access is not allowed "
                                + "from scripts ('" + path + "')", null);
            }
            return ref.path();
        }

        private static String normalizeBasePath(@Nullable String base) {
            if (base == null) return "";
            String b = base.strip();
            while (b.startsWith("/")) b = b.substring(1);
            while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
            return b;
        }

        /**
         * Read a document as UTF-8 text. Throws {@link ScriptHostException}
         * when no such document exists — JS catches it as a normal Error.
         */
        @HostAccess.Export
        public String read(String path) {
            DocumentDocument doc = requireDoc(path);
            return documentService.readContent(doc);
        }

        /**
         * Idempotent write — creates the document if it doesn't exist,
         * updates content if it does. {@code title} and {@code tags} on
         * an existing document stay untouched.
         */
        @HostAccess.Export
        public void write(String path, String content) {
            requireProject();
            requirePath(path);
            if (content == null) {
                throw new ScriptHostException(
                        "vance.documents.write: content must not be null", null);
            }
            String resolved = resolveDoc(path);
            if (resolved.startsWith(DocumentService.TRASH_FOLDER_PREFIX)) {
                throw new ScriptHostException(
                        "vance.documents.write: cannot write under '"
                                + DocumentService.TRASH_FOLDER_PREFIX + "'", null);
            }
            documentService.upsertText(
                    scope.tenantId(), scope.projectId(),
                    resolved, null, null, content, scope.userId(),
                    writeActor());
        }

        @HostAccess.Export
        public boolean exists(String path) {
            requireProject();
            requirePath(path);
            return documentService.findByPath(
                    scope.tenantId(), scope.projectId(), resolveDoc(path)).isPresent();
        }

        /**
         * Soft-delete: moves the document to {@link
         * DocumentService#TRASH_FOLDER_PREFIX}. Idempotent — deleting a
         * non-existing document is a no-op (returns {@code false}).
         */
        @HostAccess.Export
        public boolean delete(String path) {
            requireProject();
            requirePath(path);
            return documentService.findByPath(
                            scope.tenantId(), scope.projectId(), resolveDoc(path))
                    .map(doc -> {
                        documentService.trash(doc.getId(), writeActor());
                        return true;
                    })
                    .orElse(false);
        }

        /**
         * List documents under an optional path prefix. Returns
         * lightweight summary maps (id, path, name, kind, mimeType, size,
         * tags, createdAt, updatedAt) so JS doesn't see internal storage
         * fields. {@code prefix} is matched as {@code startsWith}; pass
         * {@code null} or empty for project-wide.
         *
         * <p>Trash folder is excluded automatically (consistent with
         * {@link DocumentService#listByProjectPaged}).
         */
        @HostAccess.Export
        public List<Map<String, Object>> list(@Nullable String prefix) {
            requireProject();
            // Resolve the prefix against basePath; null prefix lists the whole
            // basePath (or project-wide when no basePath is set).
            String effectivePrefix = prefix == null
                    ? (basePath.isEmpty() ? null : basePath)
                    : resolve(prefix);
            List<Map<String, Object>> out = new ArrayList<>();
            // Page through up to 200 at a time — caller can pass a more
            // specific prefix if they hit the cap in practice.
            documentService.listByProjectPaged(
                            scope.tenantId(), scope.projectId(), 0, 200, effectivePrefix)
                    .forEach(doc -> out.add(toSummary(doc)));
            return out;
        }

        /**
         * Metadata snapshot for the given path. Same shape as the entries
         * returned by {@link #list(String)}; throws
         * {@link ScriptHostException} when the document doesn't exist.
         */
        @HostAccess.Export
        public Map<String, Object> meta(String path) {
            return toSummary(requireDoc(path));
        }

        private DocumentDocument requireDoc(String path) {
            requireProject();
            requirePath(path);
            String resolved = resolveDoc(path);
            return documentService.findByPath(
                            scope.tenantId(), scope.projectId(), resolved)
                    .orElseThrow(() -> new ScriptHostException(
                            "vance.documents: not found '" + resolved + "'", null));
        }

        private void requireProject() {
            if (scope.projectId() == null || scope.projectId().isBlank()) {
                throw new ScriptHostException(
                        "vance.documents requires a project-scoped run", null);
            }
        }

        private static void requirePath(String path) {
            if (path == null || path.isBlank()) {
                throw new ScriptHostException(
                        "vance.documents: path must not be empty", null);
            }
        }

        private static Map<String, Object> toSummary(DocumentDocument doc) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", doc.getId());
            m.put("path", doc.getPath());
            m.put("name", doc.getName());
            m.put("title", doc.getTitle());
            m.put("kind", doc.getKind());
            m.put("mimeType", doc.getMimeType());
            m.put("size", doc.getSize());
            m.put("tags", doc.getTags() == null ? List.of() : List.copyOf(doc.getTags()));
            m.put("createdAt", doc.getCreatedAt() == null ? null : doc.getCreatedAt().toString());
            m.put("version", doc.getVersion());
            return m;
        }
    }

    /**
     * Light-LLM surface exposed as {@code vance.llm}. Synchronous
     * single-shot LLM calls via a recipe-as-config profile — no
     * process spawn, no engine lane, no async event flow.
     *
     * <p>The recipe MUST be marked {@code internal: true}; the
     * underlying {@link LightLlmService} rejects others. This keeps
     * config-profile recipes (only ever invoked here) distinct from
     * recipes spawnable as workers.
     *
     * <p>Tenant / project / process scope is sourced from the bound
     * {@link ToolInvocationContext} — scripts cannot escape their
     * scope. Setting cascades therefore honour per-project and
     * per-process overrides automatically.
     */
    public static final class ScriptLightLlmApi {

        private final LightLlmService service;
        private final ToolInvocationContext scope;

        ScriptLightLlmApi(LightLlmService service, ToolInvocationContext scope) {
            this.service = service;
            this.scope = scope;
        }

        /**
         * Single-shot LLM call returning the LLM's reply text verbatim.
         * Use when the script post-processes the text itself (free-text
         * classification label, generated title, summary, etc.).
         */
        @HostAccess.Export
        public String call(String recipeName, String userPrompt,
                           @Nullable Map<String, Object> pebbleVars) {
            validateInputs(recipeName, userPrompt);
            try {
                return service.call(buildRequest(recipeName, userPrompt, pebbleVars, null));
            } catch (LightLlmException e) {
                throw new ScriptHostException(
                        "vance.llm.call(" + recipeName + "): " + e.getMessage(), e);
            }
        }

        /** Convenience overload — no pebble vars. */
        @HostAccess.Export
        public String call(String recipeName, String userPrompt) {
            return call(recipeName, userPrompt, null);
        }

        /**
         * Schema-validated single-shot LLM call. Returns the parsed
         * JSON object as a {@code Map<String, Object>}. The recipe's
         * Pebble-rendered prompt is expected to instruct the LLM to
         * reply with a JSON object; {@link LightLlmService#callForJson}
         * runs the Jeltz-style schema-retry loop and surfaces a
         * {@link ScriptHostException} when the retry budget is
         * exhausted.
         */
        @HostAccess.Export
        public Map<String, Object> callForJson(String recipeName, String userPrompt,
                                               @Nullable Map<String, Object> pebbleVars) {
            validateInputs(recipeName, userPrompt);
            try {
                return service.callForJson(
                        buildRequest(recipeName, userPrompt, pebbleVars, null));
            } catch (SchemaValidationException e) {
                throw new ScriptHostException(
                        "vance.llm.callForJson(" + recipeName + "): "
                                + "schema validation exhausted: " + e.getMessage(), e);
            } catch (LightLlmException e) {
                throw new ScriptHostException(
                        "vance.llm.callForJson(" + recipeName + "): " + e.getMessage(), e);
            }
        }

        /** Convenience overload — no pebble vars. */
        @HostAccess.Export
        public Map<String, Object> callForJson(String recipeName, String userPrompt) {
            return callForJson(recipeName, userPrompt, null);
        }

        private void validateInputs(String recipeName, String userPrompt) {
            if (recipeName == null || recipeName.isBlank()) {
                throw new ScriptHostException(
                        "vance.llm: recipeName must not be empty", null);
            }
            if (userPrompt == null) {
                throw new ScriptHostException(
                        "vance.llm: userPrompt must not be null", null);
            }
            if (scope.tenantId() == null || scope.tenantId().isBlank()) {
                throw new ScriptHostException(
                        "vance.llm requires a tenant-scoped run", null);
            }
        }

        private LightLlmRequest buildRequest(
                String recipeName, String userPrompt,
                @Nullable Map<String, Object> vars,
                @Nullable Map<String, Object> schema) {
            return LightLlmRequest.builder()
                    .recipeName(recipeName)
                    .userPrompt(userPrompt)
                    .pebbleVars(vars)
                    .schema(schema)
                    .tenantId(scope.tenantId())
                    .projectId(scope.projectId())
                    .processId(scope.processId())
                    .build();
        }
    }

    /**
     * Settings-cascade surface exposed as {@code vance.settings}.
     * Walks the cascade {@code think-process → project → _vance}
     * (user-layer deliberately excluded — see
     * {@link SettingService#getStringValueCascade}). All accessors
     * return the requested setting or fall back to the supplied
     * default when no scope in the cascade defines the key.
     *
     * <p>Tenant / project / process scope is auto-bound from the
     * script's {@link ToolInvocationContext} — a script cannot
     * read another tenant's settings.
     *
     * <p>Password settings are filtered out by the underlying
     * service — scripts cannot accidentally exfiltrate credentials
     * via {@code vance.settings.get(...)}.
     */
    public static final class ScriptSettingsApi {

        private final SettingService service;
        private final ToolInvocationContext scope;

        ScriptSettingsApi(SettingService service, ToolInvocationContext scope) {
            this.service = service;
            this.scope = scope;
        }

        /** Returns the raw string value, or {@code null} when no
         *  scope in the cascade defines the key. */
        @HostAccess.Export
        public @Nullable String get(String key) {
            requireScope(key);
            return service.getStringValueCascade(
                    scope.tenantId(), scope.projectId(), scope.processId(), key);
        }

        /** Returns the string value, or {@code defaultValue} when
         *  no scope defines the key (or it is blank). */
        @HostAccess.Export
        public String get(String key, String defaultValue) {
            String v = get(key);
            return (v == null || v.isBlank()) ? defaultValue : v;
        }

        /** Integer accessor — parses the cascade-resolved string,
         *  returns {@code defaultValue} on missing or unparseable. */
        @HostAccess.Export
        public int getInt(String key, int defaultValue) {
            String v = get(key);
            if (v == null || v.isBlank()) return defaultValue;
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        /** Long accessor — same semantics as {@link #getInt}. */
        @HostAccess.Export
        public long getLong(String key, long defaultValue) {
            String v = get(key);
            if (v == null || v.isBlank()) return defaultValue;
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        /** Double accessor — same semantics as {@link #getInt}. */
        @HostAccess.Export
        public double getDouble(String key, double defaultValue) {
            String v = get(key);
            if (v == null || v.isBlank()) return defaultValue;
            try {
                return Double.parseDouble(v.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        /** Boolean accessor — accepts {@code true|1|yes|on}
         *  (case-insensitive). Anything else parses as false. */
        @HostAccess.Export
        public boolean getBoolean(String key, boolean defaultValue) {
            requireScope(key);
            return service.getBooleanValueCascade(
                    scope.tenantId(), scope.projectId(), scope.processId(),
                    key, defaultValue);
        }

        private void requireScope(String key) {
            if (key == null || key.isBlank()) {
                throw new ScriptHostException(
                        "vance.settings: key must not be empty", null);
            }
            if (scope.tenantId() == null || scope.tenantId().isBlank()) {
                throw new ScriptHostException(
                        "vance.settings requires a tenant-scoped run", null);
            }
        }
    }

    /**
     * Vault-secret pull surface exposed as {@code vance.secret(...)}. Resolves a
     * reference through the shared {@link SecretResolver} using the run's bound
     * scope (never a script-supplied scope), returns the value to the script, and
     * records it in the active secret-tee so the executor can mask it out of the
     * run's string output. Full grammar: {@code vault:key} / {@code project:key} /
     * {@code tenant:key} / {@code user:key} / bare key (cascade default).
     */
    /**
     * Callable secret surface: {@code vance.secret('vault:key')}. Implemented as a
     * {@link ProxyExecutable} so JavaScript invokes {@code vance.secret(ref)} as a
     * plain function — the same shape the Python helper ({@code vance.secret(ref)})
     * and the manuals/spec document, so both languages read identically. The Java
     * method {@link #get(String)} carries the logic and stays directly callable
     * from host-side unit tests.
     */
    public static final class ScriptSecretApi implements ProxyExecutable {

        private final SecretResolver resolver;
        private final ToolInvocationContext scope;

        ScriptSecretApi(SecretResolver resolver, ToolInvocationContext scope) {
            this.resolver = resolver;
            this.scope = scope;
        }

        @Override
        public @Nullable Object execute(Value... arguments) {
            if (arguments.length == 0 || arguments[0].isNull()) {
                throw new ScriptHostException("vance.secret: reference must not be empty", null);
            }
            return get(arguments[0].asString());
        }

        /**
         * Resolve a secret reference to its value, or {@code null} when nothing is
         * bound / the reference does not resolve. The value never enters the
         * script's environment or persisted state — it lives only in the returned
         * JS value (and is masked out of the run's string output).
         */
        public @Nullable String get(String ref) {
            if (ref == null || ref.isBlank()) {
                throw new ScriptHostException("vance.secret: reference must not be empty", null);
            }
            if (scope.tenantId() == null || scope.tenantId().isBlank()) {
                throw new ScriptHostException("vance.secret requires a tenant-scoped run", null);
            }
            String wrapped = "{{secret:" + ref + "}}";
            String resolved = resolver.resolve(wrapped, scope);
            // resolved.equals(wrapped) == no substitution (unbound, or a ref whose
            // shape the resolver's pattern can't match) — treat as unresolved and
            // return null rather than leak a literal placeholder.
            if (resolved == null || resolved.isEmpty() || resolved.equals(wrapped)) {
                return null;
            }
            Set<String> tee = ACTIVE_SECRET_TEE.get();
            if (tee != null) {
                tee.add(resolved);
            }
            return resolved;
        }
    }

    /** Host-side exception surfaced to JS as a regular Error. */
    public static final class ScriptHostException extends RuntimeException {
        public ScriptHostException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
