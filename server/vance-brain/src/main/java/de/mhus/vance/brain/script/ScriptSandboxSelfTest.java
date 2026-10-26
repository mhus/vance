package de.mhus.vance.brain.script;

import de.mhus.vance.shared.workspace.WorkspaceProperties;
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
 *
 * <p>On a pod without workspace access ({@code vance.workspace.mode=off} — the shared,
 * multi-tenant setting) an unenforced limit aborts the start, and so does a runtime
 * without a heap limit: there one tenant's script must not be able to take the
 * brain's memory. With {@code local} it stays a warning (decision E5).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScriptSandboxSelfTest {

    private final Engine engine;
    private final HostAccess hostAccess;
    private final WorkspaceProperties workspaceProperties;

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
        if (requiresStrictLimits() && !strictlyPassed(result)) {
            throw new IllegalStateException("Script sandbox limits are not fully enforced on this runtime"
                    + " (heapLimitSupported=" + result.heapLimitSupported()
                    + ", heapLimitEnforced=" + result.heapLimitEnforced()
                    + ", cpuLimitEnforced=" + result.cpuLimitEnforced()
                    + ") — refusing to start with vance.workspace.mode=off");
        }
    }

    private boolean requiresStrictLimits() {
        return !workspaceProperties.getMode().accessible();
    }

    /** Every limit enforced — a missing heap limit counts as a failure here. */
    static boolean strictlyPassed(ScriptSandboxLimits.SelfTestResult result) {
        return result.heapLimitSupported() && result.heapLimitEnforced() && result.cpuLimitEnforced();
    }
}
