package de.mhus.vance.liver.cli;

import de.mhus.vance.liver.api.ApiException;
import de.mhus.vance.liver.config.BackupConfig;
import de.mhus.vance.liver.config.ConfigException;
import de.mhus.vance.liver.config.ConfigLoader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Subcommand {@code vancetope-backup logout}.
 *
 * <p>Revokes the integration token configured in the local config —
 * the same flow a browser extension or webhook consumer uses when the
 * user pulls the plug. The server endpoint is
 * {@code DELETE /brain/{tenant}/integration-tokens/current}, a
 * documented carve-out in {@code NarrowedCredentialCheck} for the
 * {@code INTEGRATION} token type on that exact path
 * (planning §7.4).
 *
 * <p>The command does not delete the local config file — the
 * operator may want to inspect the run history before erasing the
 * evidence. After a logout, the next {@code info} call lands on
 * {@code 401} and the operator is forced to {@code init} again.
 */
@Command(
        name = "logout",
        mixinStandardHelpOptions = true,
        description = "Revoke the configured backup token at the server.")
@NullMarked
public final class LogoutCommand implements Callable<Integer> {

    @Option(
            names = {"--config"},
            description = "Override the config file path (default: ~/.vancetope/liver/backup.yaml).")
    private @Nullable Path config;

    private final PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    private final PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
    private static final System.Logger LOG = System.getLogger(LogoutCommand.class.getName());

    @Override
    public Integer call() {
        BackupConfig cfg;
        try {
            cfg = new ConfigLoader().load(config);
        } catch (ConfigException e) {
            err.println("Configuration error: " + e.getMessage());
            return 4;
        }

        // DELETE is not on BackupApiClient — the only DELETE the
        // backup surface has is the self-revoke call, and adding it
        // there would force every command to share the wire code.
        // The call is small enough to inline; if a future surface
        // grows more DELETEs we can promote this to a method.
        URI url = URI.create(cfg.server.url + "/brain/" + cfg.server.tenant + "/integration-tokens/current");
        try (var http = HttpClient.newHttpClient()) {
            HttpRequest req = HttpRequest.newBuilder(url)
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + cfg.token)
                    .DELETE()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status == 204 || status == 200) {
                out.println("Token revoked at " + cfg.server.url);
                out.println("The next call from this config will fail with 401.");
                return 0;
            }
            if (status == 401 || status == 403) {
                // The token is already dead (revoked, expired, or
                // never valid). The user asked us to log out and
                // the desired state is achieved either way.
                out.println("Server says: token is no longer valid (HTTP " + status + ")." + " Already logged out.");
                return 0;
            }
            err.println("Server returned " + status + ": " + resp.body());
            return 2;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.log(System.Logger.Level.ERROR, "Logout failed", e);
            err.println("Logout failed: " + e.getMessage());
            return 2;
        } catch (ApiException e) {
            err.println("Logout failed: " + e.getMessage());
            return 2;
        }
    }
}
