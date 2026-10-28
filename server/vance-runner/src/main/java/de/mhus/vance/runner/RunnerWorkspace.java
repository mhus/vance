package de.mhus.vance.runner;

import de.mhus.vance.exec.EphemeralHandler;
import de.mhus.vance.exec.ExecEnvPolicy;
import de.mhus.vance.exec.ExecJob;
import de.mhus.vance.exec.ExecJobs;
import de.mhus.vance.exec.FfmpegEngine;
import de.mhus.vance.exec.GitHandler;
import de.mhus.vance.exec.NodeHandler;
import de.mhus.vance.exec.PythonHandler;
import de.mhus.vance.exec.RootDirStore;
import de.mhus.vance.exec.ScratchArea;
import de.mhus.vance.exec.TempHandler;
import de.mhus.vance.exec.WhisperEngine;
import de.mhus.vance.exec.YtDlpEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the runner's file and exec operations share: the RootDir store and the exec
 * job table, both on the runner's volume, both from {@code vance-exec} — the same
 * code the brain's own workspace runs.
 *
 * <p>Layout under the data directory: {@code workspaces/} (RootDirs),
 * {@code exec/} (job logs), {@code homes/} (the scoped {@code HOME} of exec jobs).
 */
public final class RunnerWorkspace implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RunnerWorkspace.class);

    private static final int SOFT_LIMIT_PERCENT = 85;
    private static final long KILL_GRACE_MS = 2_000;
    private static final int MAX_JOBS_PER_SCOPE = 100;
    private static final Duration ORPHAN_TTL = Duration.ofMinutes(10);

    static final int DEFAULT_DISK_HARD_PERCENT = 95;
    private static final long FFMPEG_TIMEOUT_SECONDS = 600;
    static final String DEFAULT_NPM_REGISTRY = "https://registry.npmjs.org/";
    private static final long NODE_MODULES_MAX_BYTES = 512L * 1024 * 1024;

    private final Path dataDir;
    private final int hardLimitPercent;
    private final FfmpegEngine ffmpeg;
    private final YtDlpEngine ytdlp;
    private final WhisperEngine whisper;
    private final ScratchArea scratch;
    private final ProgramGitOperations git;
    private final RootDirStore store;
    private final ExecJobs jobs;
    private final Path homes;
    private final @Nullable String execWrapper;

    /**
     * @param execWrapper an isolation template for exec commands ({@code {workdir}} and
     *     {@code {cmd}} placeholders, see {@code ExecIsolation}), or {@code null} to run
     *     under the plain shell — the runner process itself is then the boundary
     */
    public RunnerWorkspace(Path dataDir, @Nullable String execWrapper) {
        this(dataDir, execWrapper, null, DEFAULT_NPM_REGISTRY);
    }

    /**
     * @param processWrapper the wrapper for the programs the runner starts itself (git, npm,
     *     pip — see {@link RunnerProcessLauncher}), or {@code null} for none
     * @param npmRegistry    the registry every {@code npm install} is pinned to
     */
    public RunnerWorkspace(
            Path dataDir, @Nullable String execWrapper, @Nullable String processWrapper, String npmRegistry) {
        this(dataDir, execWrapper, processWrapper, npmRegistry, DEFAULT_DISK_HARD_PERCENT);
    }

    /**
     * @param hardLimitPercent how full the data volume may be before the runner refuses to
     *     write more or start commands ({@code 0} = no check); new RootDirs are refused at the
     *     same limit by the store
     */
    public RunnerWorkspace(
            Path dataDir,
            @Nullable String execWrapper,
            @Nullable String processWrapper,
            String npmRegistry,
            int hardLimitPercent) {
        this.dataDir = dataDir;
        this.hardLimitPercent = hardLimitPercent;
        RunnerProcessLauncher launcher = new RunnerProcessLauncher(dataDir, processWrapper);
        this.git = new ProgramGitOperations(() -> launcher);
        this.scratch = new ScratchArea(dataDir.resolve("scratch"));
        this.ffmpeg = new FfmpegEngine("ffmpeg", "ffprobe", FFMPEG_TIMEOUT_SECONDS, launcher, scratch);
        this.ytdlp = new YtDlpEngine(launcher);
        try {
            this.whisper = new WhisperEngine("python3", launcher);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("The whisper wrapper script cannot be put on disk", e);
        }
        GitHandler gitHandler = new GitHandler(git);
        this.store = new RootDirStore(
                () -> new RootDirStore.Settings(dataDir.resolve("workspaces"), SOFT_LIMIT_PERCENT, hardLimitPercent),
                List.of(
                        new TempHandler(),
                        new EphemeralHandler(),
                        gitHandler,
                        new NodeHandler(
                                gitHandler,
                                git,
                                () -> new NodeHandler.Config("npm", npmRegistry, NODE_MODULES_MAX_BYTES),
                                () -> launcher),
                        new PythonHandler(gitHandler, git, () -> launcher)));
        this.jobs = new ExecJobs(
                new ExecJobs.Config(dataDir.resolve("exec"), KILL_GRACE_MS, MAX_JOBS_PER_SCOPE, ORPHAN_TTL),
                new LoggingListener());
        this.homes = dataDir.resolve("homes");
        this.execWrapper = execWrapper == null || execWrapper.isBlank() ? null : execWrapper.trim();
    }

    /**
     * Refuses {@code what} when the volume is fuller than the limit: a file or a command that
     * grows the data would only run the volume out, and every project on it with it.
     *
     * @throws RunnerOperation.OperationException {@code disk-full}
     */
    void requireDiskSpace(String what) throws RunnerOperation.OperationException {
        if (hardLimitPercent <= 0) {
            return;
        }
        int used = usedPercent();
        if (used >= hardLimitPercent) {
            throw new RunnerOperation.OperationException(
                    "disk-full",
                    "The runner's volume is " + used + " % full (limit " + hardLimitPercent + " %) — " + what
                            + " is refused. Delete RootDirs or files you no longer need first.");
        }
    }

    /** How full the data volume is, in percent of its capacity. */
    int usedPercent() {
        try {
            Files.createDirectories(dataDir);
            var store = Files.getFileStore(dataDir);
            long total = store.getTotalSpace();
            return total <= 0 ? 0 : (int) (100 - (store.getUsableSpace() * 100 / total));
        } catch (IOException e) {
            return 0; // not measurable: do not refuse on a guess
        }
    }

    /** The scoped {@code HOME} of a project's jobs and starters. */
    Path home(String tenantId, String projectId) {
        return homes.resolve(tenantId).resolve(projectId);
    }

    YtDlpEngine ytdlp() {
        return ytdlp;
    }

    WhisperEngine whisper() {
        return whisper;
    }

    /** The call-scoped scratch directories of the mechanics. */
    ScratchArea scratch() {
        return scratch;
    }

    /** ffmpeg and ffprobe, for the audio operations. */
    FfmpegEngine ffmpeg() {
        return ffmpeg;
    }

    ProgramGitOperations git() {
        return git;
    }

    RootDirStore store() {
        return store;
    }

    ExecJobs jobs() {
        return jobs;
    }

    @Nullable
    String execWrapper() {
        return execWrapper;
    }

    /** The sealed child environment of a job: the inheritable base and a per-project {@code HOME}. */
    Map<String, String> execEnv(String tenantId, String projectId) throws IOException {
        Map<String, String> env = ExecEnvPolicy.seal(System.getenv(), List.of());
        Path home = home(tenantId, projectId);
        Files.createDirectories(home);
        env.put("HOME", home.toString());
        return env;
    }

    @Override
    public void close() {
        jobs.close();
    }

    /** Terminal jobs need no mirroring here; the brain polls the runner. */
    private static final class LoggingListener implements ExecJobs.Listener {

        @Override
        public void stateChanged(ExecJob job) {
            log.debug("Exec job {} is now {}", job.id(), job.status());
        }

        @Override
        public void finished(ExecJob job) {
            log.debug("Exec job {} finished with {}", job.id(), job.exitCode());
        }

        @Override
        public void evicted(ExecJob job) {
            log.debug("Exec job {} dropped from the table", job.id());
        }
    }
}
