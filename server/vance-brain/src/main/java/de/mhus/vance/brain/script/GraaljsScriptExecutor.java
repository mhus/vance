package de.mhus.vance.brain.script;

import de.mhus.vance.brain.tools.ContextToolsApi;
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
import org.graalvm.polyglot.io.IOAccess;
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

    @Autowired
    public GraaljsScriptExecutor(Engine engine, ScriptEngineProperties props) {
        this.engine = engine;
        this.props = props;
        this.hostAccess = HostAccess.newBuilder()
                .allowAccessAnnotatedBy(HostAccess.Export.class)
                .allowImplementationsAnnotatedBy(HostAccess.Implementable.class)
                .allowMapAccess(true)
                .allowListAccess(true)
                .allowArrayAccess(true)
                .allowIterableAccess(true)
                .allowIteratorAccess(true)
                .build();
    }

    /** Legacy constructor for unit tests that build the executor
     *  directly (e.g. {@code GraaljsScriptExecutorBindingsTest},
     *  {@code ScriptHarness}). Uses framework defaults — equivalent
     *  to the JVM-wide config a Spring-managed instance would
     *  receive when no {@code vance.script.*} properties are set. */
    public GraaljsScriptExecutor(Engine engine) {
        this(engine, new ScriptEngineProperties());
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

        VanceScriptApi api = new VanceScriptApi(effectiveTools, request.recipeName());
        ResourceLimits limits = ResourceLimits.newBuilder()
                .statementLimit(effectiveStatements, null)
                .build();

        Context ctx = Context.newBuilder("js")
                .engine(engine)
                .allowHostAccess(hostAccess)
                .allowAllAccess(false)
                .allowIO(IOAccess.NONE)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .allowHostClassLoading(false)
                .allowHostClassLookup(name -> false)
                .allowExperimentalOptions(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .resourceLimits(limits)
                .build();

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

            Future<@Nullable Object> future = watchdog.submit(() -> mapValue(ctx.eval(source)));
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

    private static ScriptExecutionException mapEvalFailure(Throwable cause) {
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
