package de.mhus.vance.exec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Whether a program runs on this host: starts {@code argv} through the
 * {@link ProcessLauncher} — so inside the process wrapper when isolation is on —
 * and checks for exit code 0. Used to derive capabilities from the image instead
 * of from config (an absent ffmpeg is a missing capability, not a failing tool).
 */
public final class ProgramProbe {

    private ProgramProbe() {}

    public static boolean runs(ProcessLauncher launcher, List<String> argv, Duration timeout) {
        try {
            Process process = launcher.start(ProcessSpec.of(argv, null));
            // Drain off the calling thread so the child never blocks on a full
            // pipe and a hung program still hits the timeout below.
            Thread.ofVirtual().start(() -> {
                try (InputStream out = process.getInputStream()) {
                    out.transferTo(OutputStream.nullOutputStream());
                } catch (IOException ignored) {
                    // the process is gone; nothing to drain
                }
            });
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
