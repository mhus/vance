package de.mhus.vance.brain.script;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup check that the script sandbox limits are enforced on this runtime, not
 * merely accepted ({@code planning/multi-tenant-isolation.md} §3.1). Probes the
 * shared engine once the application is ready and logs the outcome, so "script
 * memory and CPU are bounded" is a checked statement per pod instead of an
 * assumption about the JDK.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScriptSandboxSelfTest {

    private final Engine engine;
    private final HostAccess hostAccess;

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        ScriptSandboxLimits.SelfTestResult result = ScriptSandboxLimits.selfTest(engine, hostAccess);
        if (result.passed()) {
            log.info(
                    "Script sandbox self-test passed: heap limit {}, CPU limit enforced",
                    result.heapLimitSupported() ? "enforced" : "not supported on this runtime");
        } else {
            log.warn(
                    "Script sandbox self-test FAILED: heapLimitSupported={}, heapLimitEnforced={},"
                            + " cpuLimitEnforced={} — scripts are not bounded as configured",
                    result.heapLimitSupported(),
                    result.heapLimitEnforced(),
                    result.cpuLimitEnforced());
        }
    }
}
