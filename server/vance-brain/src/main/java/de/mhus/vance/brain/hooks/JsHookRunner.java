package de.mhus.vance.brain.hooks;

import java.time.Duration;
import java.time.Instant;
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
import org.graalvm.polyglot.io.IOAccess;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GraalJS-backed runner for {@code type: js} hooks. Shares the
 * {@link Engine} bean with the brain's {@code ScriptExecutor} but
 * builds its own {@link Context} with the hook host-API bound as
 * top-level globals (no {@code vance} namespace — hooks have a
 * narrower surface than tool-calling scripts).
 *
 * <p>Context lifecycle is identical to the regular script executor:
 * one fresh context per run, watchdog-thread for the wall-clock
 * timeout, hard {@code ctx.close(true)} on timeout. Statement limit
 * is settable via {@code vance.hooks.statementLimit} (default
 * 200_000 — smaller than the executor's 1M because hooks should be
 * tight).
 */
@Component
@Slf4j
public class JsHookRunner implements HookRunner {

    private final Engine engine;
    private final HostAccess hostAccess;

    @Value("${vance.hooks.statementLimit:200000}")
    private long statementLimit;

    public JsHookRunner(Engine engine) {
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
    }

    @Override
    public HookRunResult run(
            HookDef def,
            HookContext context,
            Map<String, @Nullable Object> eventPayload,
            HookHostApi hostApi) {
        if (def.script() == null) {
            return HookRunResult.failed(Duration.ZERO,
                    "parse", "JS hook has no script");
        }
        Instant start = Instant.now();
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
            Thread t = new Thread(r, "vance-hook-eval");
            t.setDaemon(true);
            return t;
        });

        try {
            var bindings = ctx.getBindings("js");
            bindings.putMember("event", hostApi.event);
            bindings.putMember("context", hostApi.context);
            bindings.putMember("http", hostApi.http);
            bindings.putMember("inbox", hostApi.inbox);
            bindings.putMember("log", hostApi.log);

            String sourceName = "hook:" + def.event().wireName() + ":" + def.name();
            Source source = Source.newBuilder("js", def.script(), sourceName).buildLiteral();

            Future<?> future = watchdog.submit(() -> { ctx.eval(source); return null; });
            try {
                future.get(def.timeout().toMillis(), TimeUnit.MILLISECONDS);
                return HookRunResult.completed(
                        Duration.between(start, Instant.now()), /*actionCount*/ 0);
            } catch (TimeoutException e) {
                future.cancel(true);
                ctx.close(true);
                return HookRunResult.failed(
                        Duration.between(start, Instant.now()),
                        "timeout",
                        "Hook timed out after " + def.timeout().toMillis() + "ms");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                ctx.close(true);
                return HookRunResult.failed(
                        Duration.between(start, Instant.now()),
                        "cancelled",
                        "Hook execution interrupted");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                return HookRunResult.failed(
                        Duration.between(start, Instant.now()),
                        classifyPhase(cause),
                        cause.getMessage() == null
                                ? cause.getClass().getSimpleName()
                                : cause.getMessage());
            }
        } finally {
            watchdog.shutdownNow();
            try {
                ctx.close();
            } catch (Exception e) {
                log.debug("hook ctx.close() raised: {}", e.toString());
            }
        }
    }

    private static String classifyPhase(Throwable cause) {
        if (cause instanceof PolyglotException pe) {
            if (pe.isResourceExhausted()) return "resourceExhausted";
            if (pe.isCancelled()) return "cancelled";
            if (pe.isHostException()) return "hostException";
            return "guestException";
        }
        return "exec";
    }
}
