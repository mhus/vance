package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * Option mapping and narrowing of {@link ScriptSandboxLimits}, plus the runtime
 * self-test. The heap limit exists only on the Oracle GraalVM runtime, so the
 * self-test assertions follow what the running JDK supports.
 */
class ScriptSandboxLimitsTest {

    private static final long MB = 1024L * 1024L;

    private static Engine engine;
    private static HostAccess hostAccess;

    @BeforeAll
    static void start() {
        engine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        hostAccess = new ScriptEngineConfig().scriptHostAccess();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    @Test
    void contextOptions_withoutHeader_usesConfiguredLimits() {
        ScriptSandboxLimits limits = new ScriptSandboxLimits(props(256, Duration.ofSeconds(60), 1000), true);

        Map<String, String> options = limits.contextOptions(ScriptHeader.empty(), "test");

        assertThat(options)
                .containsEntry(ScriptSandboxLimits.MAX_HEAP_MEMORY, (256 * MB) + "B")
                .containsEntry(ScriptSandboxLimits.MAX_CPU_TIME, "60000ms")
                .containsEntry(ScriptSandboxLimits.STRING_LENGTH_LIMIT, "1000");
    }

    @Test
    void contextOptions_headerBelowLimit_narrows() {
        ScriptSandboxLimits limits = new ScriptSandboxLimits(props(256, Duration.ofSeconds(60), 1000), true);

        Map<String, String> options = limits.contextOptions(header(64 * MB, Duration.ofSeconds(5)), "test");

        assertThat(options)
                .containsEntry(ScriptSandboxLimits.MAX_HEAP_MEMORY, (64 * MB) + "B")
                .containsEntry(ScriptSandboxLimits.MAX_CPU_TIME, "5000ms");
    }

    @Test
    void contextOptions_headerAboveLimit_isClampedToLimit() {
        ScriptSandboxLimits limits = new ScriptSandboxLimits(props(256, Duration.ofSeconds(60), 1000), true);

        Map<String, String> options = limits.contextOptions(header(1024 * MB, Duration.ofHours(1)), "test");

        assertThat(options)
                .containsEntry(ScriptSandboxLimits.MAX_HEAP_MEMORY, (256 * MB) + "B")
                .containsEntry(ScriptSandboxLimits.MAX_CPU_TIME, "60000ms");
    }

    @Test
    void contextOptions_heapUnsupported_omitsHeapOption() {
        ScriptSandboxLimits limits = new ScriptSandboxLimits(props(256, Duration.ofSeconds(60), 1000), false);

        Map<String, String> options = limits.contextOptions(header(64 * MB, null), "test");

        assertThat(options)
                .doesNotContainKey(ScriptSandboxLimits.MAX_HEAP_MEMORY)
                .containsKeys(ScriptSandboxLimits.MAX_CPU_TIME, ScriptSandboxLimits.STRING_LENGTH_LIMIT);
    }

    @Test
    void selfTest_enforcesEveryLimitTheRuntimeSupports() {
        boolean heapSupported = ScriptSandboxLimits.detect(engine, hostAccess, new ScriptEngineProperties())
                .heapLimitSupported();

        ScriptSandboxLimits.SelfTestResult result = ScriptSandboxLimits.selfTest(engine, hostAccess);

        assertThat(result.heapLimitSupported()).isEqualTo(heapSupported);
        assertThat(result.heapLimitEnforced()).isEqualTo(heapSupported);
        assertThat(result.cpuLimitEnforced()).isTrue();
        assertThat(result.passed()).isTrue();
    }

    private static ScriptEngineProperties props(long heapMb, Duration cpu, int maxStringLength) {
        ScriptEngineProperties p = new ScriptEngineProperties();
        p.getLimits().setMaxHeapMemory(DataSize.ofMegabytes(heapMb));
        p.getLimits().setMaxCpuTime(cpu);
        p.getLimits().setMaxStringLength(maxStringLength);
        return p;
    }

    private static ScriptHeader header(@Nullable Long maxHeapMemory, @Nullable Duration maxCpuTime) {
        return new ScriptHeader(
                null, null, null, Set.of(), Set.of(), null, null, Set.of(), null, Set.of(), maxHeapMemory, maxCpuTime);
    }
}
