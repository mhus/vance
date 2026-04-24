package de.mhus.vance.brain.tools.exec;

import de.mhus.vance.brain.tools.workspace.WorkspaceService;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Runs shell commands in background virtual threads with live output
 * persistence. Jobs are scoped per session: the outer index is
 * {@code sessionId}, the inner is {@code jobId}, so one session
 * cannot see another's jobs by stumbling on an id.
 *
 * <p>Working directory is the caller session's workspace root, so
 * {@code git clone …} lands where {@code workspace_*} can read it.
 *
 * <p>Output flows two ways on purpose: line-by-line into a log file
 * (complete record, readable after truncation), and into an in-memory
 * {@link StringBuilder} (fast inline response). The inline buffer is
 * reset-free — we trim it later in the tool layer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecManager {

    private final ExecProperties properties;
    private final WorkspaceService workspaceService;

    private final Map<String, Map<String, ExecJob>> jobs = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    // ──────────────────── Public API ────────────────────

    /**
     * Submits {@code command} and returns the job. The job starts
     * immediately; the caller can observe progress via {@link #waitFor}
     * or later {@link #get} calls.
     */
    public ExecJob submit(String sessionId, String command) {
        requireSession(sessionId);
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        Path jobDir = jobDir(sessionId, jobId);
        try {
            Files.createDirectories(jobDir);
        } catch (IOException e) {
            throw new ExecException(
                    "Cannot create exec job dir: " + e.getMessage(), e);
        }
        ExecJob job = new ExecJob(
                jobId, sessionId, command,
                jobDir.resolve("stdout.log"),
                jobDir.resolve("stderr.log"));
        indexJob(sessionId, job);
        Path cwd = workspaceService.sessionRoot(sessionId);
        workers.submit(() -> runJob(job, cwd));
        return job;
    }

    /** Blocks up to {@code maxMillis} for a RUNNING job to finish. */
    public ExecJob waitFor(ExecJob job, long maxMillis) {
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

    /** Look up a job by session + id. */
    public Optional<ExecJob> get(String sessionId, String jobId) {
        Map<String, ExecJob> perSession = jobs.get(sessionId);
        if (perSession == null) return Optional.empty();
        return Optional.ofNullable(perSession.get(jobId));
    }

    /** All jobs for a session, insertion-ordered (oldest first). */
    public Collection<ExecJob> listBySession(String sessionId) {
        Map<String, ExecJob> perSession = jobs.get(sessionId);
        return perSession == null ? java.util.List.of() : perSession.values();
    }

    /** Force-kill a still-running job. Returns {@code false} if already terminal. */
    public boolean kill(String sessionId, String jobId) {
        ExecJob job = get(sessionId, jobId).orElse(null);
        if (job == null || job.isTerminal()) return false;
        Process p = job.process();
        if (p == null) return false;
        p.destroyForcibly();
        job.status(ExecJob.Status.KILLED);
        job.finishedAt(Instant.now());
        return true;
    }

    // ──────────────────── Runner ────────────────────

    private void runJob(ExecJob job, Path cwd) {
        try (BufferedWriter stdoutW = openLog(job.stdoutFile());
             BufferedWriter stderrW = openLog(job.stderrFile())) {

            ProcessBuilder pb = isWindows()
                    ? new ProcessBuilder("cmd.exe", "/c", job.command())
                    : new ProcessBuilder("/bin/sh", "-c", job.command());
            pb.directory(cwd.toFile());
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
            if (job.status() != ExecJob.Status.KILLED) {
                job.status(code == 0 ? ExecJob.Status.COMPLETED : ExecJob.Status.FAILED);
            }
        } catch (Exception e) {
            log.warn("Exec job '{}' failed: {}", job.id(), e.toString());
            job.appendStderr("ERROR: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            job.status(ExecJob.Status.FAILED);
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
                // disk trouble — keep the process running; inline buffer still holds output
            }
        }
    }

    private static BufferedWriter openLog(Path file) throws IOException {
        return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // ──────────────────── Indexing ────────────────────

    private void indexJob(String sessionId, ExecJob job) {
        Map<String, ExecJob> perSession = jobs.computeIfAbsent(
                sessionId, k -> java.util.Collections.synchronizedMap(new LinkedHashMap<>()));
        synchronized (perSession) {
            perSession.put(job.id(), job);
            int cap = properties.getMaxJobsPerSession();
            if (perSession.size() > cap) {
                // Drop the oldest terminal job to stay under cap.
                String victim = null;
                for (Map.Entry<String, ExecJob> e : perSession.entrySet()) {
                    if (e.getValue().isTerminal()) {
                        victim = e.getKey();
                        break;
                    }
                }
                if (victim != null) {
                    perSession.remove(victim);
                }
            }
        }
    }

    private Path jobDir(String sessionId, String jobId) {
        return Path.of(properties.getBaseDir()).toAbsolutePath().normalize()
                .resolve(sessionId).resolve(jobId);
    }

    private static void requireSession(@Nullable String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ExecException("Exec tools require a session scope");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @PreDestroy
    void shutdown() {
        for (Map<String, ExecJob> perSession : jobs.values()) {
            for (ExecJob j : perSession.values()) {
                if (!j.isTerminal() && j.process() != null) {
                    j.process().destroyForcibly();
                }
            }
        }
        workers.shutdownNow();
    }
}
