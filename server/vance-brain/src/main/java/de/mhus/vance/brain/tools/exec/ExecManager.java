package de.mhus.vance.brain.tools.exec;

import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
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
 * persistence. Jobs are scoped per <em>project</em>: the outer index
 * is {@code projectId}, the inner is {@code jobId}, so one project
 * cannot see another's jobs by stumbling on an id, while sessions in
 * the same project share their job view (a long build started in one
 * session is visible to the next session in the same project).
 *
 * <p>Working directory is the caller project's workspace root, so
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
     * immediately in the named workspace RootDir; the caller can
     * observe progress via {@link #waitFor} or later {@link #get}
     * calls.
     */
    public ExecJob submit(String tenantId, String projectId, String dirName, String command) {
        requireTenant(tenantId);
        requireProject(projectId);
        Path cwd = resolveCwd(tenantId, projectId, dirName);
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        String scopeKey = scopeKey(tenantId, projectId);
        Path jobDir = jobDir(scopeKey, jobId);
        try {
            Files.createDirectories(jobDir);
        } catch (IOException e) {
            throw new ExecException(
                    "Cannot create exec job dir: " + e.getMessage(), e);
        }
        ExecJob job = new ExecJob(
                jobId, projectId, command,
                jobDir.resolve("stdout.log"),
                jobDir.resolve("stderr.log"));
        indexJob(scopeKey, job);
        workers.submit(() -> runJob(job, cwd));
        return job;
    }

    private Path resolveCwd(String tenantId, String projectId, String dirName) {
        try {
            RootDirHandle handle = workspaceService.getRootDir(tenantId, projectId, dirName)
                    .orElseThrow(() -> new ExecException(
                            "Unknown workspace RootDir: "
                                    + tenantId + "/" + projectId + "/" + dirName));
            return handle.getPath();
        } catch (WorkspaceException e) {
            throw new ExecException(e.getMessage(), e);
        }
    }

    private static String scopeKey(String tenantId, String projectId) {
        return tenantId + "/" + projectId;
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

    /** Look up a job by tenant + project + id. */
    public Optional<ExecJob> get(String tenantId, String projectId, String jobId) {
        Map<String, ExecJob> perProject = jobs.get(scopeKey(tenantId, projectId));
        if (perProject == null) return Optional.empty();
        return Optional.ofNullable(perProject.get(jobId));
    }

    /** All jobs for a project, insertion-ordered (oldest first). */
    public Collection<ExecJob> listByProject(String tenantId, String projectId) {
        Map<String, ExecJob> perProject = jobs.get(scopeKey(tenantId, projectId));
        return perProject == null ? java.util.List.of() : perProject.values();
    }

    /** Force-kill a still-running job. Returns {@code false} if already terminal. */
    public boolean kill(String tenantId, String projectId, String jobId) {
        ExecJob job = get(tenantId, projectId, jobId).orElse(null);
        if (job == null || job.isTerminal()) return false;
        Process p = job.process();
        if (p == null) return false;
        p.destroyForcibly();
        job.status(ExecJob.Status.KILLED);
        job.finishedAt(Instant.now());
        return true;
    }

    /**
     * Force-kills every running job for a project — used by
     * {@code ProjectManagerService.archive(...)} once that lands.
     */
    public int killAllForProject(String tenantId, String projectId) {
        Map<String, ExecJob> perProject = jobs.get(scopeKey(tenantId, projectId));
        if (perProject == null) return 0;
        int killed = 0;
        synchronized (perProject) {
            for (ExecJob j : perProject.values()) {
                if (!j.isTerminal() && j.process() != null) {
                    j.process().destroyForcibly();
                    j.status(ExecJob.Status.KILLED);
                    j.finishedAt(Instant.now());
                    killed++;
                }
            }
        }
        return killed;
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

    private void indexJob(String scopeKey, ExecJob job) {
        Map<String, ExecJob> perProject = jobs.computeIfAbsent(
                scopeKey, k -> java.util.Collections.synchronizedMap(new LinkedHashMap<>()));
        synchronized (perProject) {
            perProject.put(job.id(), job);
            int cap = properties.getMaxJobsPerProject();
            if (perProject.size() > cap) {
                // Drop the oldest terminal job to stay under cap.
                String victim = null;
                for (Map.Entry<String, ExecJob> e : perProject.entrySet()) {
                    if (e.getValue().isTerminal()) {
                        victim = e.getKey();
                        break;
                    }
                }
                if (victim != null) {
                    perProject.remove(victim);
                }
            }
        }
    }

    private Path jobDir(String scopeKey, String jobId) {
        return Path.of(properties.getBaseDir()).toAbsolutePath().normalize()
                .resolve(scopeKey).resolve(jobId);
    }

    private static void requireTenant(@Nullable String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ExecException("Exec tools require a tenant scope");
        }
    }

    private static void requireProject(@Nullable String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new ExecException("Exec tools require a project scope");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @PreDestroy
    void shutdown() {
        for (Map<String, ExecJob> perProject : jobs.values()) {
            for (ExecJob j : perProject.values()) {
                if (!j.isTerminal() && j.process() != null) {
                    j.process().destroyForcibly();
                }
            }
        }
        workers.shutdownNow();
    }
}
