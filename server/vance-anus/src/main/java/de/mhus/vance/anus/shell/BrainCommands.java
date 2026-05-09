package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.anus.brain.AnusBrainClient;
import de.mhus.vance.anus.brain.AnusBrainClient.Response;
import de.mhus.vance.anus.brain.AnusTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

/**
 * Low-level Brain plumbing: mint an admin JWT for a tenant or fire a
 * raw HTTP call. Higher-level admin commands (project lifecycle, …)
 * build on top of {@link AnusBrainClient}; these commands exist for
 * debugging and for ad-hoc Brain interaction during development.
 */
@ShellComponent
@RequiresAuth
@RequiredArgsConstructor
public class BrainCommands {

    private final AnusTokenService tokenService;
    private final AnusBrainClient brainClient;

    @ShellMethod(key = "brain token",
            value = "Mint a fresh admin JWT for a tenant. Lazily ensures _vance-admin in that tenant.")
    public String token(@ShellOption(value = {"--tenant", "-T"}) String tenant) {
        return tokenService.mintAdminToken(tenant);
    }

    @ShellMethod(key = "brain get",
            value = "GET <path> on the configured Brain with a fresh admin JWT for --tenant.")
    public String get(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--path", "-p"},
                    help = "Path beginning with '/', e.g. '/brain/acme/admin/users'")
            String path) {
        Response response = brainClient.get(tenant, path);
        return formatResponse(response);
    }

    @ShellMethod(key = "brain post",
            value = "POST <path> with a JSON --body on the configured Brain.")
    public String post(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--path", "-p"}) String path,
            @ShellOption(value = {"--body", "-b"}, defaultValue = "{}",
                    help = "Raw JSON body. Defaults to '{}'.")
            String body) {
        Response response = brainClient.post(tenant, path, body);
        return formatResponse(response);
    }

    private static String formatResponse(Response response) {
        return "HTTP " + response.statusCode() + "\n" + response.body();
    }
}
