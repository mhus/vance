package de.mhus.vance.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.mhus.vance.shared.toolusage.ToolUsageService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The demand counter has to measure what the model asked for — once per
 * ask. A wrapper dispatching to its backend is one ask, and observers must
 * not be able to break a tool call.
 */
class ToolInvocationListenersTest {

    @Test
    void successfulCall_isCountedForItsRole() {
        ToolUsageService usage = mock(ToolUsageService.class);
        ToolInvocationListener l = ToolInvocationListeners.usageRecorder(
                usage, "acme", "proj", "coding");

        l.before("file_read");
        l.after("file_read", 12, null);

        verify(usage).recordCall("acme", "proj", "coding", "file_read", "file");
    }

    @Test
    void delegatedLeg_isNotCounted() {
        // file_read → client_file_read is the same ask. Counting both put
        // every wrapper call in the stats twice (measured 2026-08-12).
        ToolUsageService usage = mock(ToolUsageService.class);
        ToolInvocationListener l = ToolInvocationListeners.usageRecorder(
                usage, "acme", "proj", "coding");

        l.beforeDelegate("client_file_read");
        l.afterDelegate("client_file_read", 12, null);

        verify(usage, never()).recordCall(any(), any(), any(), any(), any());
    }

    @Test
    void failedCall_isNotCounted() {
        ToolUsageService usage = mock(ToolUsageService.class);
        ToolInvocationListener l = ToolInvocationListeners.usageRecorder(
                usage, "acme", "proj", "coding");

        l.after("file_read", 12, new IllegalStateException("boom"));

        verify(usage, never()).recordCall(any(), any(), any(), any(), any());
    }

    @Test
    void delegateHooks_defaultToTheNormalOnes_soProgressPingsStay() {
        // A progress listener wants to see the backend dispatch too — the
        // opt-out is for demand measurement only, so the default must not
        // silence anyone who didn't ask for it.
        List<String> seen = new ArrayList<>();
        ToolInvocationListener plain = new ToolInvocationListener() {
            @Override
            public void before(String toolName) {
                seen.add("before:" + toolName);
            }

            @Override
            public void after(String toolName, long elapsedMs, Throwable error) {
                seen.add("after:" + toolName);
            }
        };

        plain.beforeDelegate("client_file_read");
        plain.afterDelegate("client_file_read", 1, null);

        assertThat(seen).containsExactly("before:client_file_read", "after:client_file_read");
    }

    @Test
    void composite_keepsGoingWhenOneObserverThrows() {
        ToolUsageService usage = mock(ToolUsageService.class);
        ToolInvocationListener broken = new ToolInvocationListener() {
            @Override
            public void before(String toolName) {
                throw new IllegalStateException("observer down");
            }

            @Override
            public void after(String toolName, long elapsedMs, Throwable error) {
                throw new IllegalStateException("observer down");
            }
        };
        ToolInvocationListener l = ToolInvocationListeners.of(
                broken, ToolInvocationListeners.usageRecorder(usage, "acme", "proj", "coding"));

        l.before("file_read");
        l.after("file_read", 5, null);

        verify(usage).recordCall(eq("acme"), eq("proj"), eq("coding"), eq("file_read"), any());
    }
}
