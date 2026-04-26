package de.mhus.vance.foot.tools.exec;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Local equivalent of the brain's {@code ExecManager}: runs shell
 * commands on the foot host with virtual-thread pumps and per-job
 * stdout/stderr log files. Singleton — every session connected to
 * this foot shares the same job index (it's the user's machine).
 *
 * <p>Storage layout: {@code data/client-exec/<jobId>/{stdout,stderr}.log},
 * relative to the foot's working directory. Logs survive a restart;
 * the live process does not. RUNNING jobs from a previous boot are
 * not auto-recovered (we'd be re-attaching to a process that's gone).
 */
@Service
@Slf4j
public class ClientExecutorService {

    private static final String BASE_DIR = "data/client-exec";
    private static final int MAX_JOBS = 32;

    private final Map<String, ClientExecJob> jobs = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public ClientExecJob submit(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        Path jobDir = Path.of(BASE_DIR).toAbsolutePath().normalize().resolve(jobId);
        try {
            Files.createDirectories(jobDir);
        } catch (IOException e) {
            throw new ClientExecException(
                    "Cannot create exec job dir: " + e.getMessage(), e);
        }
        ClientExecJob job = new ClientExecJob(
                jobId, command,
                jobDir.resolve("stdout.log"),
                jobDir.resolve("stderr.log"));
        index(job);
        workers.submit(() -> runJob(job));
        return job;
    }

    public ClientExecJob waitFor(ClientExecJob job, long maxMillis) {
        long deadline = System.currentTimeMillis() + maxMillis;
        while (!job.isTerminal() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return job;
    }

    public Optional<ClientExecJob> get(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public Collection<ClientExecJob> list() {
        synchronized (jobs) {
            return new java.util.ArrayList<>(jobs.values());
        }
    }

    public boolean kill(String id) {
        ClientExecJob job = jobs.get(id);
        if (job == null || job.isTerminal()) return false;
        Process p = job.process();
        if (p == null) return false;
        p.destroyForcibly();
        job.status(ClientExecJob.Status.KILLED);
        job.finishedAt(Instant.now());
        return true;
    }

    // ──────────────────── Runner ────────────────────

    private void runJob(ClientExecJob job) {
        try (BufferedWriter stdoutW = openLog(job.stdoutFile());
             BufferedWriter stderrW = openLog(job.stderrFile())) {

            ProcessBuilder pb = isWindows()
                    ? new ProcessBuilder("cmd.exe", "/c", job.command())
                    : new ProcessBuilder("/bin/sh", "-c", job.command());
            pb.redirectErrorStream(false);
            Process p = pb.start();
            job.process(p);

            Thread out = pumpVirtual(p.getInputStream(), line -> {
                job.appendStdout(line);
                writeLine(stdoutW, line);
            });
            Thread err = pumpVirtual(p.getErrorStream(), line -> {
                job.appendStderr(line);
                writeLine(stderrW, line);
            });
            out.start();
            err.start();

            int code = p.waitFor();
            out.join();
            err.join();

            job.exitCode(code);
            if (job.status() != ClientExecJob.Status.KILLED) {
                job.status(code == 0 ? ClientExecJob.Status.COMPLETED
                        : ClientExecJob.Status.FAILED);
            }
        } catch (Exception e) {
            log.warn("Client exec job '{}' failed: {}", job.id(), e.toString());
            job.appendStderr("ERROR: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            job.status(ClientExecJob.Status.FAILED);
        } finally {
            job.finishedAt(Instant.now());
        }
    }

    private static Thread pumpVirtual(InputStream in, Consumer<String> sink) {
        return Thread.ofVirtual().unstarted(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sink.accept(line);
                }
            } catch (IOException ignored) {
                // stream closed — fine
            }
        });
    }

    private static void writeLine(BufferedWriter w, String line) {
        synchronized (w) {
            try {
                w.write(line);
                w.newLine();
                w.flush();
            } catch (IOException ignored) {
                // disk trouble — process keeps running, inline buffer holds output
            }
        }
    }

    private static BufferedWriter openLog(Path file) throws IOException {
        return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // ──────────────────── Indexing ────────────────────

    private void index(ClientExecJob job) {
        synchronized (jobs) {
            jobs.put(job.id(), job);
            if (jobs.size() > MAX_JOBS) {
                String victim = null;
                for (Map.Entry<String, ClientExecJob> e : jobs.entrySet()) {
                    if (e.getValue().isTerminal()) {
                        victim = e.getKey();
                        break;
                    }
                }
                if (victim != null) {
                    jobs.remove(victim);
                }
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @PreDestroy
    void shutdown() {
        synchronized (jobs) {
            for (ClientExecJob j : jobs.values()) {
                if (!j.isTerminal() && j.process() != null) {
                    j.process().destroyForcibly();
                }
            }
        }
        workers.shutdownNow();
    }

    /** Thrown when the executor itself can't get a job started (filesystem, etc.). */
    public static class ClientExecException extends RuntimeException {
        public ClientExecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
