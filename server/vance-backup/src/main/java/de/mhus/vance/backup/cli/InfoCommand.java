package de.mhus.vance.backup.cli;

import de.mhus.vance.api.backup.BackupInfoResponse;
import de.mhus.vance.backup.api.BackupApiClient;
import de.mhus.vance.backup.config.BackupConfig;
import de.mhus.vance.backup.config.ConfigException;
import de.mhus.vance.backup.config.ConfigLoader;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.jspecify.annotations.NullMarked;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Subcommand {@code vancetope-backup info}.
 *
 * <p>Calls {@code GET /backup/info} on the configured brain and prints
 * the result in a human-readable form. This is the smallest end-to-end
 * verification the tool can do — if it works, the connection string
 * is valid, the brain accepts the token, the policy gate is open, and
 * the wire format round-trips.
 *
 * <p>Used by:
 * <ul>
 *   <li>operators who want to check whether a token is still alive
 *       (planning §7.6 sliding lifetime),
 *   <li>humans debugging a fresh {@code init},
 *   <li>CI / monitoring that wants a one-shot liveness signal.
 * </ul>
 *
 * <p>Exit codes follow planning §4.2: 0 = ok, 2 = auth/token/policy
 * problem, 4 = config problem. The {@code --json} flag switches to a
 * machine-readable dump for the monitoring case.
 *
 * <p>{@link System#out}/{@link System#err} are wrapped in
 * {@link PrintWriter}s with explicit UTF-8 — same pattern anus uses
 * in {@code DockerComposeSetupWizard}. The PMD baseline
 * (server/pmd-baseline.properties) carries the SystemPrintln entry
 * for this command.
 */
@Command(
        name = "info",
        mixinStandardHelpOptions = true,
        description = "Print server info and policy status for the configured backup token.")
@NullMarked
public final class InfoCommand implements Callable<Integer> {

    @Option(
            names = {"--config"},
            description = "Override the config file path (default: ~/.vancetope/backup/backup.yaml).")
    private @org.jspecify.annotations.Nullable Path config;

    @Option(
            names = {"--json"},
            description = "Print the raw server response as JSON.")
    private boolean jsonOutput;

    private final PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    private final PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
    private static final System.Logger LOG = System.getLogger(InfoCommand.class.getName());

    @Override
    public Integer call() {
        BackupConfig cfg;
        try {
            cfg = new ConfigLoader().load(config);
        } catch (ConfigException e) {
            // Exit 4 = config error (planning §4.2).
            err.println("Configuration error: " + e.getMessage());
            return 4;
        }

        try (var client = new BackupApiClient(URI.create(cfg.server.url), cfg.server.tenant, cfg.token)) {
            BackupInfoResponse info = client.getInfo();
            if (jsonOutput) {
                // Re-serialize via Jackson so the output is exactly
                // what the server sent (modulo field ordering). The
                // operator asked for a machine-readable dump; we
                // debackup one.
                out.println(new tools.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(info));
            } else {
                printHuman(info, client);
            }
            return 0;
        } catch (de.mhus.vance.backup.api.ApiException e) {
            // 401 / 403 / 410 — token rejected or policy closed.
            // Exit 2 = auth (planning §4.2).
            err.println("Server rejected the request: " + e.getMessage());
            if (e.responseBody() != null && !e.responseBody().isBlank()) {
                err.println("Response body: " + e.responseBody());
            }
            return 2;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Unexpected failure", e);
            err.println("Unexpected failure: " + e.getMessage());
            return 2;
        }
    }

    private void printHuman(BackupInfoResponse info, BackupApiClient client) {
        out.println("Brain           : " + client.apiBase());
        out.println("Tenant          : " + client.tenant());
        out.println("Server version  : " + info.getServerVersion());
        out.println("Manifest format : " + info.getManifestFormat());
        out.println("Policy          : " + (info.isAllowed() ? "allowed" : "DENIED — " + info.getDenialReason()));
        out.println("Idle-days ceiling: " + info.getIdleDaysCeiling());
        out.println("Allowed surfaces:");
        for (String surface : info.getAllowedSurfaces()) {
            out.println("  - " + surface);
        }
        if (info.getClientFormat() != null) {
            out.println("Client format   : " + info.getClientFormat());
        }
    }
}
