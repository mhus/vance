package de.mhus.vance.runner;

import de.mhus.vance.runner.protocol.TenantKeys;
import java.io.IOException;
import java.security.PublicKey;
import java.time.Clock;
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
        try (RunnerServer server = new RunnerServer(
                config,
                identity,
                tenantKeys,
                List.of(BasicOperations.ping(config), BasicOperations.info(config, List.of())),
                Clock.systemUTC())) {
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
