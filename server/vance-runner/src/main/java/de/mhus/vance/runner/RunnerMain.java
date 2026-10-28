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

    private RunnerMain() {}

    /** Everything this runner answers to — public so a test can stand up a real one. */
    public static List<RunnerOperation> operations(RunnerConfig config, RunnerWorkspace workspace) {
        List<RunnerOperation> operations = new ArrayList<>();
        operations.addAll(RootDirOperations.all(workspace));
        operations.addAll(FileOperations.all(workspace));
        operations.addAll(ExecOperations.all(workspace));
        List<String> capabilities = List.of("rootdir", "file", "exec");
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
        try (RunnerWorkspace workspace =
                        new RunnerWorkspace(config.dataDir(), System.getenv("VANCE_RUNNER_EXEC_WRAPPER"));
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
