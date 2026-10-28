package de.mhus.vance.exec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Collects a child's output with a wall-clock timeout that actually fires.
 *
 * <p>Reading the output to EOF on the calling thread and only then calling
 * {@code waitFor(timeout)} makes the timeout dead code: a hung child keeps its pipe
 * open, the read never returns and the caller leaks together with the process. The
 * drain therefore runs on its own thread and the timeout is applied to the process.
 */
public final class ProcessOutput {

    private static final ExecutorService DRAIN = Executors.newVirtualThreadPerTaskExecutor();

    private ProcessOutput() {}

    /** What a finished child produced. */
    public record Result(byte[] bytes, int exitValue) {

        public String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * Waits for {@code process} to exit and returns its (merged) output and exit code.
     * On timeout the process is killed.
     *
     * @throws TimeoutException when the process did not exit within {@code timeoutSeconds}
     * @throws IOException when the output could not be read
     * @throws InterruptedException when the calling thread was interrupted — the process is killed
     */
    public static Result await(Process process, long timeoutSeconds)
            throws IOException, InterruptedException, TimeoutException {
        Future<byte[]> output = DRAIN.submit(() -> {
            try (var in = process.getInputStream()) {
                return in.readAllBytes();
            }
        });
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                output.cancel(true);
                throw new TimeoutException("process did not exit within " + timeoutSeconds + " s");
            }
            // The process exited, so EOF stands — the drain returns promptly.
            return new Result(output.get(), process.exitValue());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            output.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw new IOException("process output could not be read: " + e.getCause(), e);
        }
    }
}
