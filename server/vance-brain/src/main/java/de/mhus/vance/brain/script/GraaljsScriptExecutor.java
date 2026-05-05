package de.mhus.vance.brain.script;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final Engine engine;
    private final HostAccess hostAccess;
    private final long statementLimit;

    public GraaljsScriptExecutor(Engine engine) {
        this.engine = engine;
        this.hostAccess = HostAccess.newBuilder()
                .allowAccessAnnotatedBy(HostAccess.Export.class)
                .allowImplementationsAnnotatedBy(HostAccess.Implementable.class)
                .allowMapAccess(true)
                .allowListAccess(true)
                .allowArrayAccess(true)
                .allowIterableAccess(true)
                .allowIteratorAccess(true)
                .build();
        this.statementLimit = DEFAULT_STATEMENT_LIMIT;
    }

    @Override
    public ScriptResult run(ScriptRequest request) {
        Instant start = Instant.now();
        VanceScriptApi api = new VanceScriptApi(request.tools());
        ResourceLimits limits = ResourceLimits.newBuilder()
                .statementLimit(statementLimit, null)
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
            String sourceName = request.sourceName() == null ? "<run>" : request.sourceName();
            Source source = Source.newBuilder("js", request.code(), sourceName).buildLiteral();

            Future<@Nullable Object> future = watchdog.submit(() -> mapValue(ctx.eval(source)));
            try {
                Object value = future.get(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
                return new ScriptResult(value, Duration.between(start, Instant.now()));
            } catch (TimeoutException e) {
                future.cancel(true);
                ctx.close(true);
                throw new ScriptExecutionException(
                        ScriptExecutionException.ErrorClass.TIMEOUT,
                        "Script timed out after " + request.timeout().toMillis() + "ms",
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
