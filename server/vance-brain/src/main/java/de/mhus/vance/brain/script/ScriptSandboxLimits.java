package de.mhus.vance.brain.script;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.jspecify.annotations.Nullable;

/**
 * Per-context sandbox limits for a script run: heap ({@code sandbox.MaxHeapMemory}),
 * CPU time ({@code sandbox.MaxCPUTime}) and string length
 * ({@code js.string-length-limit}). Values come from
 * {@link ScriptEngineProperties.Limits}; a script header may narrow them, never
 * widen.
 *
 * <p>These options work under the default {@code TRUSTED} sandbox policy with the
 * shared engine and the custom {@link HostAccess} — no stricter policy is needed
 * ({@code planning/multi-tenant-isolation.md} G3). The heap limit is only
 * available on the Oracle GraalVM runtime; elsewhere (e.g. Temurin) building a
 * context with it fails, so support is detected once up front and the option is
 * left out instead of breaking every run (G16).
 */
@Slf4j
public final class ScriptSandboxLimits {

    static final String MAX_HEAP_MEMORY = "sandbox.MaxHeapMemory";
    static final String MAX_CPU_TIME = "sandbox.MaxCPUTime";
    static final String STRING_LENGTH_LIMIT = "js.string-length-limit";

    private final ScriptEngineProperties props;
    private final boolean heapLimitSupported;

    ScriptSandboxLimits(ScriptEngineProperties props, boolean heapLimitSupported) {
        this.props = props;
        this.heapLimitSupported = heapLimitSupported;
    }

    /**
     * Builds the limits for {@code engine}, probing once whether the runtime
     * accepts a heap limit.
     */
    public static ScriptSandboxLimits detect(Engine engine, HostAccess hostAccess, ScriptEngineProperties props) {
        return new ScriptSandboxLimits(props, isHeapLimitSupported(engine, hostAccess));
    }

    public boolean heapLimitSupported() {
        return heapLimitSupported;
    }

    /**
     * Context options for one run. Header values above the configured limit are
     * clamped down with a warn-log; values below pass through.
     */
    public Map<String, String> contextOptions(ScriptHeader header, String sourceName) {
        ScriptEngineProperties.Limits limits = props.getLimits();
        Map<String, String> options = new LinkedHashMap<>();
        if (heapLimitSupported) {
            long heap = narrow(
                    header.maxHeapMemory(),
                    limits.getMaxHeapMemory().toBytes(),
                    sourceName,
                    "@maxHeapMemory",
                    "vance.script.limits.max-heap-memory");
            options.put(MAX_HEAP_MEMORY, heap + "B");
        }
        long cpuMillis = narrow(
                header.maxCpuTime() == null ? null : header.maxCpuTime().toMillis(),
                limits.getMaxCpuTime().toMillis(),
                sourceName,
                "@maxCpuTime",
                "vance.script.limits.max-cpu-time");
        options.put(MAX_CPU_TIME, cpuMillis + "ms");
        options.put(STRING_LENGTH_LIMIT, Integer.toString(limits.getMaxStringLength()));
        return options;
    }

    private static long narrow(
            @Nullable Long requested, long configured, String sourceName, String tag, String property) {
        if (requested == null) {
            return configured;
        }
        if (requested > configured) {
            log.warn(
                    "Script [{}] {} value {} exceeds {} ({}), clamping",
                    sourceName,
                    tag,
                    requested,
                    property,
                    configured);
            return configured;
        }
        return requested;
    }

    private static boolean isHeapLimitSupported(Engine engine, HostAccess hostAccess) {
        try (Context ignored = Context.newBuilder("js")
                .engine(engine)
                .allowHostAccess(hostAccess)
                .option(MAX_HEAP_MEMORY, "1MB")
                .build()) {
            return true;
        } catch (PolyglotException | IllegalArgumentException e) {
            log.warn(
                    "Script heap limit ({}) is not supported on this runtime ({} {}) — scripts run without a"
                            + " heap cap: {}",
                    MAX_HEAP_MEMORY,
                    System.getProperty("java.vm.name"),
                    System.getProperty("java.vm.version"),
                    e.getMessage());
            return false;
        }
    }

    /** Outcome of {@link #selfTest}: whether each limit actually stopped a probe script. */
    public record SelfTestResult(boolean heapLimitSupported, boolean heapLimitEnforced, boolean cpuLimitEnforced) {

        /** {@code true} when every limit this runtime offers did its job. */
        public boolean passed() {
            return cpuLimitEnforced && (!heapLimitSupported || heapLimitEnforced);
        }
    }

    /**
     * Runs probe scripts against {@code engine} and reports whether the limits are
     * enforced, not just accepted. Each probe terminates on its own even when the
     * limit is not enforced: the heap probe allocates a bounded amount, the CPU
     * probe spins against the wall clock.
     */
    public static SelfTestResult selfTest(Engine engine, HostAccess hostAccess) {
        boolean heapSupported = isHeapLimitSupported(engine, hostAccess);
        boolean heapEnforced = heapSupported
                && stoppedByLimit(
                        engine,
                        hostAccess,
                        MAX_HEAP_MEMORY,
                        "4MB",
                        "const a = []; for (let i = 0; i < 1000000; i++) { a.push({ i: i }); } a.length");
        boolean cpuEnforced = stoppedByLimit(
                engine,
                hostAccess,
                MAX_CPU_TIME,
                "500ms",
                "const end = Date.now() + 3000; while (Date.now() < end) {} 0");
        return new SelfTestResult(heapSupported, heapEnforced, cpuEnforced);
    }

    private static boolean stoppedByLimit(
            Engine engine, HostAccess hostAccess, String option, String value, String js) {
        // A context cancelled by a limit rethrows the cancellation on close; with
        // try-with-resources that lands as suppressed on the eval exception.
        try (Context ctx = Context.newBuilder("js")
                .engine(engine)
                .allowHostAccess(hostAccess)
                .option(option, value)
                .build()) {
            ctx.eval("js", js);
            return false;
        } catch (PolyglotException e) {
            return e.isResourceExhausted();
        }
    }
}
