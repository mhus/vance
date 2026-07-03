package de.mhus.vance.brain.script;

import de.mhus.vance.brain.action.ScopeLevel;
import de.mhus.vance.brain.action.SpawnToolRegistry;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.workspace.NodeHandler;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.FileSystem;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * GraalJS-backed implementation of {@link ScriptExecutor}.
 *
 * <p>Concurrency model: one shared {@link Engine} bean at the Spring
 * level (cheap to share, expensive to build); a fresh {@link Context}
 * per {@link #run} call. The watchdog runs the eval on a dedicated
 * single-thread executor so the wall-clock timeout can interrupt via
 * {@code ctx.close(true)} from the calling thread.
 */
@Service
@Slf4j
public class GraaljsScriptExecutor implements ScriptExecutor {

    private static final long DEFAULT_STATEMENT_LIMIT = 1_000_000L;
    /** Default wall-clock when no other source (header, caller,
     *  settings) specifies one. Matches the legacy hard-coded value. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final Engine engine;
    private final HostAccess hostAccess;
    private final ScriptEngineProperties props;
    private final @Nullable WorkspaceService workspaceService;
    private final @Nullable SpawnToolRegistry spawnToolRegistry;
    private final @Nullable DocumentService documentService;
    private final @Nullable LightLlmService lightLlmService;
    private final @Nullable SettingService settingService;

    @Autowired
    public GraaljsScriptExecutor(
            Engine engine,
            HostAccess hostAccess,
            ScriptEngineProperties props,
            @Autowired(required = false) @Nullable WorkspaceService workspaceService,
            @Autowired(required = false) @Nullable SpawnToolRegistry spawnToolRegistry,
            @Autowired(required = false) @Nullable DocumentService documentService,
            @Autowired(required = false) @Nullable LightLlmService lightLlmService,
            @Autowired(required = false) @Nullable SettingService settingService) {
        this.engine = engine;
        this.hostAccess = hostAccess;
        this.props = props;
        this.workspaceService = workspaceService;
        this.spawnToolRegistry = spawnToolRegistry;
        this.documentService = documentService;
        this.lightLlmService = lightLlmService;
        this.settingService = settingService;
    }

    /** Seven-arg backwards-compat constructor — pre-SettingService.
     *  {@code vance.settings} stays null. */
    public GraaljsScriptExecutor(
            Engine engine,
            HostAccess hostAccess,
            ScriptEngineProperties props,
            @Nullable WorkspaceService workspaceService,
            @Nullable SpawnToolRegistry spawnToolRegistry,
            @Nullable DocumentService documentService,
            @Nullable LightLlmService lightLlmService) {
        this(engine, hostAccess, props, workspaceService, spawnToolRegistry,
                documentService, lightLlmService, null);
    }

    /** Six-arg backwards-compat constructor — pre-LightLlmService.
     *  {@code vance.llm} stays null. */
    public GraaljsScriptExecutor(
            Engine engine,
            HostAccess hostAccess,
            ScriptEngineProperties props,
            @Nullable WorkspaceService workspaceService,
            @Nullable SpawnToolRegistry spawnToolRegistry,
            @Nullable DocumentService documentService) {
        this(engine, hostAccess, props, workspaceService, spawnToolRegistry,
                documentService, null, null);
    }

    /** Five-arg backwards-compat constructor — pre-DocumentService.
     *  Document-API is unavailable; {@code vance.documents} stays null. */
    public GraaljsScriptExecutor(
            Engine engine,
            HostAccess hostAccess,
            ScriptEngineProperties props,
            @Nullable WorkspaceService workspaceService,
            @Nullable SpawnToolRegistry spawnToolRegistry) {
        this(engine, hostAccess, props, workspaceService, spawnToolRegistry, null, null);
    }

    /** Four-arg backwards-compat constructor — pre-spawn-registry. Used by
     *  tests that pre-date the {@link SpawnToolRegistry} introduction;
     *  trigger-scoped sandbox falls back to an empty deny-set. */
    public GraaljsScriptExecutor(
            Engine engine,
            HostAccess hostAccess,
            ScriptEngineProperties props,
            @Nullable WorkspaceService workspaceService) {
        this(engine, hostAccess, props, workspaceService, null, null);
    }

    /** Three-arg constructor for tests that don't need the require
     *  pathway (workspaceService=null). */
    public GraaljsScriptExecutor(
            Engine engine, HostAccess hostAccess, ScriptEngineProperties props) {
        this(engine, hostAccess, props, null, null, null);
    }

    /** Two-arg backwards-compat constructor — auto-builds a HostAccess
     *  matching the production one. Used by tests that pre-date the
     *  HostAccess-injection refactor. */
    public GraaljsScriptExecutor(Engine engine, ScriptEngineProperties props) {
        this(engine, defaultHostAccess(), props, null, null, null);
    }

    /** Three-arg backwards-compat constructor matching the old
     *  pre-HostAccess-injection signature {@code (Engine, Props, WS)} —
     *  kept so existing tests like
     *  {@code GraaljsScriptExecutorRequireTest} don't need to change.
     *  Auto-builds a HostAccess. */
    public GraaljsScriptExecutor(
            Engine engine,
            ScriptEngineProperties props,
            @Nullable WorkspaceService workspaceService) {
        this(engine, defaultHostAccess(), props, workspaceService, null, null);
    }

    /** Legacy single-arg ctor — matches the original public contract.
     *  Used by {@code GraaljsScriptExecutorBindingsTest},
     *  {@code ScriptHarness}, and any harness that builds its own
     *  Engine and doesn't need to share HostAccess across services. */
    public GraaljsScriptExecutor(Engine engine) {
        this(engine, defaultHostAccess(), new ScriptEngineProperties(), null, null, null);
    }

    private static HostAccess defaultHostAccess() {
        return HostAccess.newBuilder()
                .allowAccessAnnotatedBy(HostAccess.Export.class)
                .allowImplementationsAnnotatedBy(HostAccess.Implementable.class)
                .allowMapAccess(true)
                .allowListAccess(true)
                .allowArrayAccess(true)
                .allowIterableAccess(true)
                .allowIteratorAccess(true)
                .build();
    }

    @Override
    public ScriptResult run(ScriptRequest request) {
        Instant start = Instant.now();
        String sourceName = request.sourceName() == null
                ? "<run>" : request.sourceName();

        // ── Phase 1: parse the optional first-block JSDoc header.
        // INVALID_HEADER bubbles up — caller fails-fast before
        // we even build a Context.
        ScriptHeader header = ScriptHeaderParser.parse(request.code(), sourceName);

        // ── Phase 2: effective resource limits.
        // Order of precedence: header > caller-supplied (request)
        // > settings.default > code-default. Each is clamped to
        // [settings.min, settings.max].
        Duration effectiveTimeout = clampDuration(
                header.timeout() != null ? header.timeout() : request.timeout(),
                props.getTimeout(), sourceName, "@timeout");
        long effectiveStatements = clampStatements(
                header.statementLimit() != null
                        ? header.statementLimit()
                        : props.getStatements().getDefault(),
                props.getStatements(), sourceName);

        // ── Phase 3: capability check — @requiresTools must all be in
        // the effective allow-set, otherwise fail-fast before eval.
        if (props.getCapabilities().isEnforceRequires()
                && !header.requiresTools().isEmpty()) {
            enforceRequiresTools(header, request.tools(), sourceName);
        }
        // ── Phase 4: optional @allowTools narrows the effective tool
        // set (header can only restrict, never expand). The intersection
        // is computed in ContextToolsApi when buildVanceApi is called.
        ContextToolsApi effectiveTools = narrowAllowedTools(
                request.tools(), header.allowTools());

        Set<String> deniedToolNames =
                request.scopeLevel() == ScopeLevel.TRIGGER_SCOPED && spawnToolRegistry != null
                        ? spawnToolRegistry.spawnToolNames()
                        : Set.of();
        // vance.params is sourced from the conventional `args` binding:
        // Hactar's ExecutingPhase wraps scriptParams as `args` for the
        // legacy top-level-variable contract; we additionally expose the
        // same map under the namespaced `vance.params.*` surface that
        // Slart-generated scripts and skill scripts reach for.
        @SuppressWarnings("unchecked")
        Map<String, Object> paramsForApi = request.bindings().get("args")
                instanceof Map<?, ?> argsMap
                        ? (Map<String, Object>) argsMap
                        : Map.of();
        VanceScriptApi api = new VanceScriptApi(
                effectiveTools, request.recipeName(), deniedToolNames,
                documentService, request.progressEmitter(),
                request.notificationEmitter(), paramsForApi,
                lightLlmService, settingService, request.documentBasePath());
        ResourceLimits limits = ResourceLimits.newBuilder()
                .statementLimit(effectiveStatements, null)
                .build();

        // ── Phase 5: optional CommonJS-require pathway. When the
        // script declares @workspaceRoot AND vance.script.require.enabled
        // is true, resolve the Node RootDir, pre-check @requires
        // packages, and configure the Context with a sandboxed
        // FileSystem that only allows reads under <root>/node_modules/.
        // Else: legacy IOAccess.NONE — require() is undefined.
        @Nullable Path requireRoot = resolveRequireRoot(
                request, header, sourceName);
        if (requireRoot != null) {
            enforceRequires(requireRoot, header.requires(), sourceName);
        }

        Context.Builder ctxBuilder = Context.newBuilder("js")
                .engine(engine)
                .allowHostAccess(hostAccess)
                .allowAllAccess(false)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .allowHostClassLoading(false)
                .allowHostClassLookup(name -> false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .resourceLimits(limits);

        if (requireRoot == null) {
            ctxBuilder
                    .allowIO(IOAccess.NONE)
                    // top-level-await is an experimental flag in GraalJS;
                    // the gate has to be open to enable it. We don't
                    // turn anything else on.
                    .allowExperimentalOptions(true);
        } else {
            ctxBuilder
                    .allowIO(buildSandboxedIo(requireRoot))
                    .allowExperimentalOptions(true)
                    .option("js.commonjs-require", "true")
                    .option("js.commonjs-require-cwd", requireRoot.toString());
        }
        // Top-level-await — lets the LLM write the natural
        //   const result = await vance.tools.call("…", …)
        // pattern straight on the top level, instead of having to wrap
        // it in `(async () => { … })()`. The wrap also worked once we
        // started resolving the returned Promise (see resolveResult
        // below), but tripping over a SyntaxError on the first attempt
        // and recovering on the second wasted a full LLM round-trip.
        ctxBuilder.option("js.top-level-await", "true");

        Context ctx = ctxBuilder.build();

        ExecutorService watchdog = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "vance-script-eval");
            t.setDaemon(true);
            return t;
        });

        try {
            ctx.getBindings("js").putMember("vance", api);
            // Tool-supplied bindings become top-level variables in the
            // script's global scope. ScriptRequest validates that
            // 'vance' is not reused as a binding name.
            for (Map.Entry<String, @Nullable Object> entry : request.bindings().entrySet()) {
                ctx.getBindings("js").putMember(entry.getKey(), entry.getValue());
            }
            Source source = Source.newBuilder("js", request.code(), sourceName).buildLiteral();

            Future<@Nullable Object> future = watchdog.submit(() -> resolveResult(ctx.eval(source)));
            try {
                Object value = future.get(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
                return new ScriptResult(value, Duration.between(start, Instant.now()));
            } catch (TimeoutException e) {
                future.cancel(true);
                ctx.close(true);
                throw new ScriptExecutionException(
                        ScriptExecutionException.ErrorClass.TIMEOUT,
                        "Script timed out after " + effectiveTimeout.toMillis() + "ms",
                        e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                ctx.close(true);
                throw new ScriptExecutionException(
                        ScriptExecutionException.ErrorClass.CANCELLED,
                        "Script execution interrupted",
                        e);
            } catch (ExecutionException e) {
                throw mapEvalFailure(e.getCause() == null ? e : e.getCause());
            }
        } finally {
            watchdog.shutdownNow();
            try {
                ctx.close();
            } catch (Exception e) {
                log.debug("ctx.close() raised after run: {}", e.toString());
            }
        }
    }

    /**
     * Clamps a duration to {@code [props.min, props.max]}. Warn-log
     * on clamp so authors notice they overshot the cap; the request
     * still proceeds with the clamped value.
     */
    private static Duration clampDuration(
            @Nullable Duration requested,
            ScriptEngineProperties.TimeoutLimits props,
            String sourceName, String tagName) {
        Duration value = requested == null ? props.getDefault() : requested;
        if (value.compareTo(props.getMax()) > 0) {
            log.warn("Script [{}] {} value {} exceeds vance.script.timeout.max "
                            + "({}), clamping",
                    sourceName, tagName, value, props.getMax());
            value = props.getMax();
        }
        if (value.compareTo(props.getMin()) < 0) {
            log.warn("Script [{}] {} value {} below vance.script.timeout.min "
                            + "({}), clamping up",
                    sourceName, tagName, value, props.getMin());
            value = props.getMin();
        }
        return value;
    }

    private static long clampStatements(
            long requested,
            ScriptEngineProperties.StatementLimits props,
            String sourceName) {
        long value = requested;
        if (value > props.getMax()) {
            log.warn("Script [{}] @statements {} exceeds "
                            + "vance.script.statements.max ({}), clamping",
                    sourceName, value, props.getMax());
            value = props.getMax();
        }
        if (value < props.getMin()) {
            log.warn("Script [{}] @statements {} below "
                            + "vance.script.statements.min ({}), clamping up",
                    sourceName, value, props.getMin());
            value = props.getMin();
        }
        return value;
    }

    /**
     * Pre-eval check: every name in {@code header.requiresTools} must
     * appear in the caller's effective allow-set. Otherwise raise
     * {@link ScriptExecutionException.ErrorClass#MISSING_CAPABILITY}
     * with a precise list of the missing tools.
     *
     * <p>{@link ContextToolsApi} doesn't expose the allow-set directly
     * but {@link ContextToolsApi#isAllowed(String)} does — we probe
     * per name. Cheap: tool names are short, requires-lists are tiny
     * in practice.
     */
    private static void enforceRequiresTools(
            ScriptHeader header, ContextToolsApi tools, String sourceName) {
        Set<String> missing = new LinkedHashSet<>();
        for (String required : header.requiresTools()) {
            if (!tools.isAllowed(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new ScriptExecutionException(
                    ScriptExecutionException.ErrorClass.MISSING_CAPABILITY,
                    "[" + sourceName + "] @requiresTools includes tools "
                            + "not in the caller's allow-set: " + missing
                            + ". Either widen the recipe / skill allow-list "
                            + "or drop the requires-declaration if the tool "
                            + "is not actually needed.");
        }
    }

    /**
     * If the header declared {@code @allowTools}, narrow the bound
     * {@link ContextToolsApi} to the intersection of (caller's allow,
     * header's allow). A header can only restrict — it can never
     * widen the caller's scope.
     */
    private static ContextToolsApi narrowAllowedTools(
            ContextToolsApi caller, Set<String> headerAllow) {
        if (headerAllow == null || headerAllow.isEmpty()) {
            return caller;
        }
        return caller.narrowTo(headerAllow);
    }

    /**
     * Resolves the absolute Node workspace path the script's
     * {@code require()} calls will be rooted at, or {@code null} when
     * the require pathway is not active. Three conditions must hold:
     *
     * <ol>
     *   <li>{@code vance.script.require.enabled=true}</li>
     *   <li>The script has a {@code @workspaceRoot} header tag (or
     *       the script declared {@code @requires} — implicit
     *       binding to the default label)</li>
     *   <li>A {@link WorkspaceService} bean is wired in</li>
     * </ol>
     *
     * <p>Failure modes raise {@link ScriptExecutionException} with
     * {@code MISSING_CAPABILITY} so the caller fails-fast before
     * the Context is even built.
     */
    private @Nullable Path resolveRequireRoot(
            ScriptRequest request, ScriptHeader header, String sourceName) {
        boolean needsRequire = header.workspaceRoot() != null
                || !header.requires().isEmpty();
        if (!needsRequire) return null;

        if (!props.getRequire().isEnabled()) {
            throw new ScriptExecutionException(
                    ScriptExecutionException.ErrorClass.MISSING_CAPABILITY,
                    "[" + sourceName + "] script uses @workspaceRoot/@requires "
                            + "but vance.script.require.enabled=false on this brain");
        }
        if (workspaceService == null) {
            throw new ScriptExecutionException(
                    ScriptExecutionException.ErrorClass.MISSING_CAPABILITY,
                    "[" + sourceName + "] script uses @workspaceRoot/@requires "
                            + "but WorkspaceService is not available in this "
                            + "executor (test fixture using the 1-arg constructor?)");
        }

        String tenantId = request.tools().scope().tenantId();
        String projectId = request.tools().scope().projectId();
        if (tenantId == null || tenantId.isBlank()
                || projectId == null || projectId.isBlank()) {
            throw new ScriptExecutionException(
                    ScriptExecutionException.ErrorClass.MISSING_CAPABILITY,
                    "[" + sourceName + "] @workspaceRoot/@requires require a "
                            + "tenant- and project-scoped invocation context");
        }

        String label = header.workspaceRoot();
        if (label == null) label = props.getRequire().getDefaultLabel();

        RootDirHandle handle = findNodeRootDirByLabel(tenantId, projectId, label);
        if (handle == null) {
            throw new ScriptExecutionException(
                    ScriptExecutionException.ErrorClass.MISSING_CAPABILITY,
                    "[" + sourceName + "] @workspaceRoot '" + label
                            + "' does not resolve to a Node RootDir in project "
                            + projectId + " — run node_create first");
        }
        return handle.getPath().toAbsolutePath().normalize();
    }

    private @Nullable RootDirHandle findNodeRootDirByLabel(
            String tenantId, String projectId, String label) {
        if (workspaceService == null) return null;
        for (RootDirHandle h : workspaceService.listRootDirs(tenantId, projectId)) {
            if (!NodeHandler.TYPE.equals(h.getType())) continue;
            String existingLabel = h.getDescriptor() == null
                    ? null : h.getDescriptor().getLabel();
            if (label.equals(existingLabel)) return h;
        }
        return null;
    }

    /**
     * Pre-eval capability check for {@code @requires}. Every declared
     * package must have a {@code <root>/node_modules/<pkg>/package.json}
     * entry; otherwise we fail-fast with MISSING_CAPABILITY so the
     * caller doesn't waste an LLM/eval cycle on a script that's
     * destined to throw {@code ReferenceError: require is not defined}
     * or {@code MODULE_NOT_FOUND} at runtime.
     */
    private static void enforceRequires(
            Path root, Set<String> requires, String sourceName) {
        if (requires == null || requires.isEmpty()) return;
        Path nodeModules = root.resolve(NodeHandler.NODE_MODULES_DIR);
        if (!Files.isDirectory(nodeModules)) {
            throw new ScriptExecutionException(
                    ScriptExecutionException.ErrorClass.MISSING_CAPABILITY,
                    "[" + sourceName + "] @requires declared but "
                            + nodeModules + " does not exist — "
                            + "run node_install first");
        }
        for (String pkg : requires) {
            Path pkgJson = nodeModules.resolve(pkg).resolve("package.json");
            if (!Files.isRegularFile(pkgJson)) {
                throw new ScriptExecutionException(
                        ScriptExecutionException.ErrorClass.MISSING_CAPABILITY,
                        "[" + sourceName + "] @requires '" + pkg
                                + "' not installed (looked for " + pkgJson + ")");
            }
        }
    }

    /**
     * Builds an {@link IOAccess} that only permits reads under
     * {@code workspaceRoot}. Everything else is hard-denied through
     * {@link FileSystem#newDenyIOFileSystem()}. The read-only
     * delegate is a default filesystem wrapped via
     * {@link FileSystem#newReadOnlyFileSystem(FileSystem)} so write
     * opens raise SecurityException even if Graal's require
     * implementation tried to open a file for write.
     */
    private static IOAccess buildSandboxedIo(Path workspaceRoot) {
        FileSystem readOnly = FileSystem.newReadOnlyFileSystem(
                FileSystem.newDefaultFileSystem());
        FileSystem deny = FileSystem.newDenyIOFileSystem();
        Path absRoot = workspaceRoot.toAbsolutePath().normalize();
        FileSystem composite = FileSystem.newCompositeFileSystem(
                deny,
                FileSystem.Selector.of(readOnly, path -> isUnderRoot(path, absRoot)));
        return IOAccess.newBuilder().fileSystem(composite).build();
    }

    /**
     * Whether {@code path} lives under {@code root}. {@link Path#startsWith}
     * alone is insufficient on macOS, where {@code /var/folders} is a
     * symlink to {@code /private/var/folders} — GraalJS's resolver hands
     * us the canonical (private-prefixed) variant while the root we
     * computed from the workspace handle has the un-prefixed one. We
     * strip {@code /private} from either side before the suffix match
     * so the same workspace tree resolves identically regardless of
     * which form Graal walked in.
     */
    static boolean isUnderRoot(@Nullable Path path, Path absRoot) {
        if (path == null) return false;
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(absRoot)) return true;
        String pathStr = stripPrivatePrefix(normalized.toString());
        String rootStr = stripPrivatePrefix(absRoot.toString());
        return pathStr.equals(rootStr)
                || pathStr.startsWith(rootStr + java.io.File.separator);
    }

    private static String stripPrivatePrefix(String s) {
        return s.startsWith("/private/") ? s.substring("/private".length()) : s;
    }

    private static ScriptExecutionException mapEvalFailure(Throwable cause) {
        // settlePromise throws a classified ScriptExecutionException itself
        // when a Promise rejects; preserve its classification instead of
        // re-wrapping as HOST_EXCEPTION via the generic branch below.
        if (cause instanceof ScriptExecutionException already) {
            return already;
        }
        if (cause instanceof PolyglotException pe) {
            if (pe.isResourceExhausted()) {
                return new ScriptExecutionException(
                        ScriptExecutionException.ErrorClass.RESOURCE_EXHAUSTED,
                        "Script exceeded resource limit: " + pe.getMessage(),
                        pe);
            }
            if (pe.isCancelled()) {
                return new ScriptExecutionException(
                        ScriptExecutionException.ErrorClass.CANCELLED,
                        "Script cancelled: " + pe.getMessage(),
                        pe);
            }
            if (pe.isHostException()) {
                return new ScriptExecutionException(
                        ScriptExecutionException.ErrorClass.HOST_EXCEPTION,
                        "Host call failed: " + pe.getMessage(),
                        pe);
            }
            return new ScriptExecutionException(
                    ScriptExecutionException.ErrorClass.GUEST_EXCEPTION,
                    "Script raised: " + pe.getMessage(),
                    pe);
        }
        return new ScriptExecutionException(
                ScriptExecutionException.ErrorClass.HOST_EXCEPTION,
                "Script execution failed: " + cause.getMessage(),
                cause);
    }

    /**
     * Maps a Polyglot {@link Value} into a JSON-friendly Java object.
     * Resolve a top-level eval result. If it's a Promise (the LLM
     * wrapped its body in {@code (async () => { … })()}, or top-level-
     * await produced a Promise), block on the settled value and map
     * <em>that</em>; otherwise route straight to {@link #mapValue}.
     *
     * <p>GraalJS drains its microtask queue at the host/guest boundary
     * (i.e. inside {@code promise.invokeMember("then", …)}), so any
     * Promise that resolves purely through synchronous code — which
     * is everything our scripts do, since {@code vance.tools.call} is
     * a synchronous Java bridge — fires its callbacks before the
     * {@code invokeMember} call returns. If the promise still hasn't
     * settled when we look (no external I/O event loop here), we
     * surface a {@link ScriptExecutionException} so the caller doesn't
     * silently get an unsettled-Promise stub.
     *
     * <p>Without this we'd map the Promise object itself: it reports
     * {@code hasMembers()=true} but exposes no enumerable keys, so
     * the script result quietly becomes the empty map {@code {}}. The
     * LLM then takes that as "all good", confirms the operation to
     * the user, and nothing actually happened — observed exactly that
     * with Eddie's "mark all unread as read" script.
     */
    @Nullable
    private static Object resolveResult(Value value) {
        Value settled = settlePromise(value);
        return mapValue(settled);
    }

    /**
     * Walks promise chains down to a non-Promise {@link Value}.
     * Heuristic for "is this a Promise?": GraalJS Promise objects
     * accept {@code .then(onFulfilled, onRejected)} invocation. That's
     * also true for any user-built thenable — which is fine, the
     * contract is the same.
     *
     * <p>Throws {@link ScriptExecutionException#ErrorClass#GUEST_EXCEPTION}
     * on rejection (so the LLM sees the rejection reason like any
     * other error) and on still-pending-after-drain (which would
     * indicate the script kicked off external async work we don't
     * support here).
     */
    private static Value settlePromise(Value value) {
        if (value == null || value.isNull() || !value.canInvokeMember("then")) {
            return value;
        }
        Value[] fulfilled = { null };
        Value[] rejected = { null };
        boolean[] done = { false };
        ProxyExecutable onFulfilled = args -> {
            fulfilled[0] = args.length > 0 ? args[0] : null;
            done[0] = true;
            return null;
        };
        ProxyExecutable onRejected = args -> {
            rejected[0] = args.length > 0 ? args[0] : null;
            done[0] = true;
            return null;
        };
        value.invokeMember("then", onFulfilled, onRejected);
        if (!done[0]) {
            throw new ScriptExecutionException(
                    ScriptExecutionException.ErrorClass.GUEST_EXCEPTION,
                    "Promise returned by script is still pending after the "
                            + "microtask queue drained. Vance's JS sandbox has no "
                            + "external event loop — only synchronous tool calls "
                            + "are supported. Did the script start a real async "
                            + "operation (setTimeout, fetch, …)?");
        }
        if (rejected[0] != null) {
            String message = rejected[0].isString()
                    ? rejected[0].asString()
                    : rejected[0].toString();
            throw new ScriptExecutionException(
                    ScriptExecutionException.ErrorClass.GUEST_EXCEPTION,
                    "Promise rejected: " + message);
        }
        // Recurse: an async function that returns another Promise
        // chains down. mapValue would otherwise see Promise<Promise<X>>.
        return settlePromise(fulfilled[0]);
    }

    /**
     * Primitives stay primitive, objects become {@link LinkedHashMap},
     * arrays become {@link ArrayList}.
     */
    @Nullable
    private static Object mapValue(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            if (value.fitsInLong()) {
                return value.asLong();
            }
            return value.asDouble();
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.hasArrayElements()) {
            long size = value.getArraySize();
            List<@Nullable Object> out = new ArrayList<>((int) Math.min(size, 1024));
            for (long i = 0; i < size; i++) {
                out.add(mapValue(value.getArrayElement(i)));
            }
            return out;
        }
        if (value.hasMembers()) {
            Map<String, @Nullable Object> out = new LinkedHashMap<>();
            for (String key : value.getMemberKeys()) {
                out.put(key, mapValue(value.getMember(key)));
            }
            return out;
        }
        return value.toString();
    }
}
