package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.runner.protocol.TenantKeys;
import de.mhus.vance.shared.keystore.KeyPurpose;
import de.mhus.vance.shared.keystore.KeyService;
import de.mhus.vance.shared.runner.RunnerBinding;
import de.mhus.vance.shared.runner.RunnerBindingService;
import de.mhus.vance.shared.runner.RunnerBindingService.Effective;
import de.mhus.vance.shared.runner.RunnerClient;
import de.mhus.vance.shared.runner.RunnerClient.Reply;
import de.mhus.vance.shared.runner.RunnerClient.RunnerCallException;
import java.security.PublicKey;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * Runner registration ({@code planning/runner.md} §6). The runner's address and key
 * live on the tenant or project record and are written here and nowhere else — a
 * tenant, an agent or a kit cannot point the brain at another host.
 */
@Component
@RequiresAuth
@RequiredArgsConstructor
public class RunnerCommands {

    private static final String ACTING_USER = "_anus";

    private final RunnerBindingService bindings;
    private final RunnerClient client;
    private final KeyService keys;

    @Command(
            name = {"runner", "set"},
            description = "Register the runner of a tenant, or of one project when --project is given.")
    public String set(
            @Option(longName = "tenant", shortName = 'T', required = true, description = "Tenant name.") String tenant,
            @Option(longName = "project", shortName = 'p', description = "Register for this project only.") @Nullable
                    String project,
            @Option(longName = "id", required = true, description = "Runner id (lowercase, digits, '-').") String id,
            @Option(longName = "url", required = true, description = "Base URL of the runner, e.g. http://runner:9840.")
                    String url,
            @Option(longName = "recipient", required = true, description = "The runner's age public key (age1…).")
                    String recipient) {
        String scope = blankToNull(project);
        try {
            RunnerBinding binding = bindings.set(tenant.trim(), scope, id.trim(), url.trim(), recipient.trim());
            return "Runner '" + binding.getRunnerId() + "' registered for " + label(tenant, scope) + " at "
                    + binding.getUrl() + "\nVerify with: runner check --tenant " + tenant.trim()
                    + (scope == null ? "" : " --project " + scope);
        } catch (IllegalArgumentException e) {
            return "Not registered: " + e.getMessage();
        }
    }

    @Command(
            name = {"runner", "clear"},
            description = "Remove the runner registered on exactly this level (tenant, or project with --project).")
    public String clear(
            @Option(longName = "tenant", shortName = 'T', required = true, description = "Tenant name.") String tenant,
            @Option(longName = "project", shortName = 'p', description = "Clear this project's registration.") @Nullable
                    String project) {
        String scope = blankToNull(project);
        return bindings.clear(tenant.trim(), scope)
                ? "Runner registration removed for " + label(tenant, scope)
                : "No runner was registered on " + label(tenant, scope);
    }

    @Command(
            name = {"runner", "show"},
            description = "The runner that serves a tenant or project, and where the registration comes from.")
    public String show(
            @Option(longName = "tenant", shortName = 'T', required = true, description = "Tenant name.") String tenant,
            @Option(longName = "project", shortName = 'p', description = "Resolve for this project.") @Nullable
                    String project) {
        String scope = blankToNull(project);
        return bindings.resolve(tenant.trim(), scope)
                .map(RunnerCommands::describe)
                .orElse("No runner is registered for " + label(tenant, scope));
    }

    @Command(
            name = {"runner", "check"},
            description = "Call the runner (ping, info) through the real path: token, envelope, answer.")
    public String check(
            @Option(longName = "tenant", shortName = 'T', required = true, description = "Tenant name.") String tenant,
            @Option(longName = "project", shortName = 'p', description = "Check the runner of this project.") @Nullable
                    String project) {
        String scope = blankToNull(project);
        StringBuilder out = new StringBuilder();
        try {
            for (String op : List.of("ping", "info")) {
                Reply reply = client.call(tenant.trim(), scope, ACTING_USER, null, op, null, new byte[0]);
                out.append(op).append(": ");
                if (reply.ok()) {
                    out.append("ok in ").append(reply.elapsed().toMillis()).append(" ms");
                    if (reply.header().result() != null) {
                        out.append("  ").append(reply.header().result());
                    }
                } else {
                    out.append("FAILED [")
                            .append(reply.header().error().code())
                            .append("] ")
                            .append(reply.header().error().message());
                }
                out.append('\n');
            }
        } catch (RunnerCallException e) {
            return out.append(e.reason()).append(": ").append(e.getMessage()).toString();
        }
        return out.toString().stripTrailing();
    }

    @Command(
            name = {"runner", "keys"},
            description =
                    "The tenant's public signing keys as PEM — the file a runner trusts (VANCE_RUNNER_TENANT_KEYS).")
    public String keys(
            @Option(longName = "tenant", shortName = 'T', required = true, description = "Tenant name.")
                    String tenant) {
        List<PublicKey> publicKeys = keys.getPublicKeys(tenant.trim(), KeyPurpose.JWT_SIGNING);
        if (publicKeys.isEmpty()) {
            return "Tenant '" + tenant.trim() + "' has no signing key yet.";
        }
        StringBuilder pem = new StringBuilder();
        for (PublicKey key : publicKeys) {
            pem.append(TenantKeys.toPem(key));
        }
        return pem.toString().stripTrailing();
    }

    private static String describe(Effective effective) {
        RunnerBinding binding = effective.binding();
        return "runner:     " + binding.getRunnerId() + "\nurl:        " + binding.getUrl() + "\nrecipient:  "
                + binding.getRecipient() + "\nset on:     "
                + effective.source().name().toLowerCase()
                + "\nregistered: " + binding.getRegisteredAt();
    }

    private static String label(String tenant, @Nullable String project) {
        return project == null ? "tenant " + tenant.trim() : "project " + tenant.trim() + "/" + project;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }
}
