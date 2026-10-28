package de.mhus.vance.runner;

import de.mhus.vance.runner.protocol.TenantKeys;
import java.io.IOException;
import java.security.PublicKey;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point: configuration from the environment, the age identity from the data
 * volume, the tenant's public keys from the deployment, then serve.
 */
public final class RunnerMain {

    private static final Logger log = LoggerFactory.getLogger(RunnerMain.class);

    /** The brain's name for local audio editing; what a runner with ffmpeg reports. */
    private static final String AUDIO_TOOLS = "audio-tools";

    private RunnerMain() {}

    /** {@code VANCE_RUNNER_NPM_REGISTRY}: the registry every npm install is pinned to. */
    private static String registry() {
        String configured = System.getenv("VANCE_RUNNER_NPM_REGISTRY");
        return configured == null || configured.isBlank() ? RunnerWorkspace.DEFAULT_NPM_REGISTRY : configured.trim();
    }

    /** {@code VANCE_RUNNER_DISK_HARD_PERCENT}: how full the volume may get before writes are refused (0 = off). */
    private static int diskLimit() {
        String configured = System.getenv("VANCE_RUNNER_DISK_HARD_PERCENT");
        if (configured == null || configured.isBlank()) {
            return RunnerWorkspace.DEFAULT_DISK_HARD_PERCENT;
        }
        try {
            int percent = Integer.parseInt(configured.trim());
            if (percent < 0 || percent > 100) {
                throw new NumberFormatException("out of range");
            }
            return percent;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("VANCE_RUNNER_DISK_HARD_PERCENT must be 0-100: " + configured, e);
        }
    }

    /** Everything this runner answers to — public so a test can stand up a real one. */
    public static List<RunnerOperation> operations(RunnerConfig config, RunnerWorkspace workspace) {
        List<RunnerOperation> operations = new ArrayList<>();
        operations.addAll(RootDirOperations.all(workspace));
        operations.addAll(FileOperations.all(workspace));
        operations.addAll(ExecOperations.all(workspace));
        operations.addAll(ProjectOperations.all(workspace));
        operations.addAll(AudioOperations.all(workspace));
        operations.addAll(VideoOperations.all(workspace.ytdlp(), workspace.whisper(), workspace.scratch()));
        List<String> capabilities = new ArrayList<>(List.of("rootdir", "file", "exec", "git", "node", "python"));
        // Reported only when the programs run here: the brain offers audio tools to a project whose
        // runner says so (ProjectCapabilities), and to none whose runner cannot.
        if (workspace.ffmpeg().available()) {
            capabilities.add(AUDIO_TOOLS);
        }
        // Mechanics the brain's video transcript tool looks for; not tool capabilities, just what is here.
        if (workspace.ytdlp().available()) {
            capabilities.add("yt-dlp");
        }
        if (workspace.whisper().available()) {
            capabilities.add("whisper");
        }
        operations.add(BasicOperations.ping(config));
        operations.add(BasicOperations.info(config, capabilities));
        return operations;
    }

    public static void main(String[] args) throws InterruptedException {
        RunnerConfig config;
        RunnerIdentity identity;
        List<PublicKey> tenantKeys;
        try {
            config = RunnerConfig.fromEnvironment(System.getenv());
            identity = RunnerIdentity.loadOrCreate(config.identityFile());
            tenantKeys = TenantKeys.load(config.tenantKeysFile());
        } catch (IllegalArgumentException | IOException e) {
            log.error("Runner cannot start: {}", e.getMessage());
            System.exit(2);
            return;
        }
        try (RunnerWorkspace workspace = new RunnerWorkspace(
                        config.dataDir(),
                        System.getenv("VANCE_RUNNER_EXEC_WRAPPER"),
                        System.getenv("VANCE_RUNNER_PROCESS_WRAPPER"),
                        registry(),
                        diskLimit());
                RunnerServer server = new RunnerServer(
                        config, identity, tenantKeys, operations(config, workspace), Clock.systemUTC())) {
            // SIGTERM runs hooks without unwinding main: stop the listener there too.
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "runner-shutdown"));
            server.start();
            log.info(
                    "Runner '{}' for tenant '{}' {} listening on port {} — recipient to register with the brain: {}",
                    config.runnerId(),
                    config.tenantId(),
                    BasicOperations.version(),
                    server.port(),
                    identity.recipient());
            Thread.currentThread().join();
        }
    }
}
