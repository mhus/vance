package de.mhus.vance.foot.script;

import de.mhus.vance.foot.tools.ClientToolService;
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
 * GraalJS-backed implementation of {@link ClientScriptExecutor}.
 * Mirrors {@code GraaljsScriptExecutor} on the brain side: shared
 * {@link Engine} bean, fresh {@link Context} per run, watchdog-driven
 * wall-clock timeout, host-access whitelist via {@link HostAccess.Export}.
 */
@Service
@Slf4j
public class GraaljsClientScriptExecutor implements ClientScriptExecutor {

    private static final long DEFAULT_STATEMENT_LIMIT = 1_000_000L;

    private final Engine engine;
    private final ClientToolService toolService;
    private final HostAccess hostAccess;
    private final long statementLimit;

    public GraaljsClientScriptExecutor(Engine clientScriptEngine, ClientToolService toolService) {
        this.engine = clientScriptEngine;
        this.toolService = toolService;
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
    public ClientScriptResult run(ClientScriptRequest request) {
        Instant start = Instant.now();
        ClientScriptApi api = new ClientScriptApi(
                toolService::find,
                request.executionContext());
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
            Thread t = new Thread(r, "vance-client-script-eval");
            t.setDaemon(true);
            return t;
        });

        try {
            ctx.getBindings("js").putMember("client", api);
            String sourceName = request.sourceName() == null ? "<run>" : request.sourceName();
            Source source = Source.newBuilder("js", request.code(), sourceName).buildLiteral();

            Future<@Nullable Object> future = watchdog.submit(() -> mapValue(ctx.eval(source)));
            try {
                Object value = future.get(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
                return new ClientScriptResult(value, Duration.between(start, Instant.now()));
            } catch (TimeoutException e) {
                future.cancel(true);
                ctx.close(true);
                throw new ClientScriptExecutionException(
                        ClientScriptExecutionException.ErrorClass.TIMEOUT,
                        "Script timed out after " + request.timeout().toMillis() + "ms",
                        e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                ctx.close(true);
                throw new ClientScriptExecutionException(
                        ClientScriptExecutionException.ErrorClass.CANCELLED,
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

    private static ClientScriptExecutionException mapEvalFailure(Throwable cause) {
        if (cause instanceof PolyglotException pe) {
            if (pe.isResourceExhausted()) {
                return new ClientScriptExecutionException(
                        ClientScriptExecutionException.ErrorClass.RESOURCE_EXHAUSTED,
                        "Script exceeded resource limit: " + pe.getMessage(),
                        pe);
            }
            if (pe.isCancelled()) {
                return new ClientScriptExecutionException(
                        ClientScriptExecutionException.ErrorClass.CANCELLED,
                        "Script cancelled: " + pe.getMessage(),
                        pe);
            }
            if (pe.isHostException()) {
                return new ClientScriptExecutionException(
                        ClientScriptExecutionException.ErrorClass.HOST_EXCEPTION,
                        "Host call failed: " + pe.getMessage(),
                        pe);
            }
            return new ClientScriptExecutionException(
                    ClientScriptExecutionException.ErrorClass.GUEST_EXCEPTION,
                    "Script raised: " + pe.getMessage(),
                    pe);
        }
        return new ClientScriptExecutionException(
                ClientScriptExecutionException.ErrorClass.HOST_EXCEPTION,
                "Script execution failed: " + cause.getMessage(),
                cause);
    }

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
