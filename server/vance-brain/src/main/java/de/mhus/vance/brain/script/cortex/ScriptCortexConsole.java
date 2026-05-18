package de.mhus.vance.brain.script.cortex;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.graalvm.polyglot.HostAccess;
import org.jspecify.annotations.Nullable;

/**
 * {@code console} binding for Script Cortex executions — sinks every
 * call into a {@link ScriptLogSink} so the live-log channel can stream
 * it to the subscribed WebSocket client.
 *
 * <p>The JS contract mirrors browser/Node console:
 * <pre>
 *   console.log("hello", { x: 1 });
 *   console.warn("careful");
 *   console.error("nope");
 *   console.info("fyi");
 * </pre>
 *
 * <p>Variadic args are stringified and joined with a single space,
 * matching the browser convention. Maps and arrays go through their
 * default {@code toString} — good enough for v1.
 */
public final class ScriptCortexConsole {

    private final ScriptLogSink sink;

    public ScriptCortexConsole(ScriptLogSink sink) {
        this.sink = sink;
    }

    @HostAccess.Export
    public void log(@Nullable Object... args) {
        sink.accept("log", format(args));
    }

    @HostAccess.Export
    public void info(@Nullable Object... args) {
        sink.accept("info", format(args));
    }

    @HostAccess.Export
    public void warn(@Nullable Object... args) {
        sink.accept("warn", format(args));
    }

    @HostAccess.Export
    public void error(@Nullable Object... args) {
        sink.accept("error", format(args));
    }

    private static String format(@Nullable Object[] args) {
        if (args == null || args.length == 0) return "";
        return Arrays.stream(args)
                .map(a -> a == null ? "null" : a.toString())
                .collect(Collectors.joining(" "));
    }
}
