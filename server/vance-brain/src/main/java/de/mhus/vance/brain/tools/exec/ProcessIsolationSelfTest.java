package de.mhus.vance.brain.tools.exec;

import de.mhus.vance.exec.ProcessSpec;
import de.mhus.vance.shared.workspace.WorkspaceProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup check that the configured process wrapper actually starts a child
 * ({@code planning/multi-tenant-isolation.md} phase 2c): runs {@code true} through
 * the {@link SealedProcessLauncher} once the application is ready. A wrapper that
 * fails here (bwrap without user namespaces, a missing AppArmor permission, a typo
 * in the template) would otherwise fail every npm, pip, ffmpeg or whisper call.
 *
 * <p>On a pod without workspace access ({@code vance.workspace.mode=off}) the
 * failure aborts the start; with {@code local} it stays a warning — the same split
 * as {@code ScriptSandboxSelfTest} (decision E5). Without a process wrapper there is
 * nothing to probe; on {@code off} that is logged as a warning.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessIsolationSelfTest {

    private static final long TIMEOUT_SECONDS = 30;

    private final SealedProcessLauncher launcher;
    private final WorkspaceProperties workspaceProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        boolean strict = !workspaceProperties.getMode().accessible();
        if (!launcher.isolated()) {
            if (strict) {
                log.warn("No vance.exec.isolation.process-wrapper configured — starters (npm, pip, ffmpeg,"
                        + " whisper, yt-dlp, latexmk, Rserve) run without OS isolation");
            }
            return;
        }
        String failure = probe();
        if (failure == null) {
            log.info("Process isolation self-test passed: starters run inside the process wrapper");
            return;
        }
        log.warn("Process isolation self-test FAILED: {}", failure);
        if (strict) {
            throw new IllegalStateException("The process wrapper cannot start a child (" + failure
                    + ") — refusing to start with vance.workspace.mode=off");
        }
    }

    /** {@code null} on success, otherwise what went wrong. */
    private @Nullable String probe() {
        try {
            Process process = launcher.start(ProcessSpec.of(List.of("true"), null));
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "no exit within " + TIMEOUT_SECONDS + "s";
            }
            if (process.exitValue() != 0) {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                return "exit " + process.exitValue() + (output.isEmpty() ? "" : ": " + output);
            }
            return null;
        } catch (IOException e) {
            return "cannot start: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }
}
