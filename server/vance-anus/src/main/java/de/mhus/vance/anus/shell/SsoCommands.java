package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.shared.sso.SsoConfig;
import de.mhus.vance.shared.sso.SsoConfigService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * The installation-wide SSO config {@code _vance/config/sso.yaml}
 * ({@code specification/public/sso-login.md} §2). It lives in the system
 * tenant, and writing it decides which e-mail domain's sign-ins land in which
 * tenant — an operator decision, so the shell is the way to write it.
 *
 * <p>{@code import} validates first and refuses a file in which any entry
 * would be dropped: the Brain loads fail-closed, so a typo would otherwise
 * silently switch a domain off.
 */
@Component
@RequiresAuth
public class SsoCommands {

    private final SsoConfigService configService;

    public SsoCommands(SsoConfigService configService) {
        this.configService = configService;
    }

    @Command(name = {"sso", "config", "show"}, description = "Show the SSO config as stored and as the Brain reads it.")
    public String show() {
        String raw = configService.readRaw().orElse(null);
        if (raw == null) {
            return "No " + SsoConfigService.CONFIG_PATH + " in the system tenant — SSO is off.";
        }
        List<String> problems = new ArrayList<>();
        SsoConfig config = configService.validate(raw, problems);
        return "--- " + SsoConfigService.CONFIG_PATH + " ---\n" + raw.stripTrailing() + "\n---\n"
                + render(config, problems);
    }

    @Command(
            name = {"sso", "config", "import"},
            description = "Validate a local YAML file and store it as the SSO config. Refuses when any entry"
                    + " would be dropped.")
    public String importConfig(
            @Option(longName = "file", shortName = 'f', required = true, description = "Path to the sso.yaml")
                    String file,
            @Option(longName = "dry-run", description = "Validate only, write nothing.", defaultValue = "false")
                    boolean dryRun) {
        String yaml;
        try {
            yaml = Files.readString(Path.of(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Import FAILED — cannot read '" + file + "': " + e.getMessage();
        }
        List<String> problems = new ArrayList<>();
        SsoConfig config = configService.validate(yaml, problems);
        String summary = render(config, problems);
        if (!problems.isEmpty()) {
            return "Import REFUSED — fix these entries first (the Brain would drop them):\n" + summary;
        }
        if (dryRun) {
            return "Dry run — valid, nothing written.\n" + summary;
        }
        configService.write(yaml, "anus");
        return "Import OK — " + SsoConfigService.CONFIG_PATH + " written. The Brain picks it up within"
                + " 30 seconds.\n" + summary
                + "\nThe client secret is read from its reference at sign-in, e.g. for"
                + " {{secret:tenant:sso.google.clientSecret}}:\n"
                + "  setting set-secret -T _vance -s tenant -k sso.google.clientSecret -t PASSWORD -v <secret>";
    }

    private static String render(SsoConfig config, List<String> problems) {
        StringBuilder out = new StringBuilder();
        if (config.providers().isEmpty()) {
            out.append("providers : (none)\n");
        }
        config.providers().values().forEach(p -> out.append("provider  : ")
                .append(p.key())
                .append(" (")
                .append(p.type())
                .append(", issuer ")
                .append(p.issuer())
                .append(", client ")
                .append(p.clientId())
                .append(")\n"));
        config.domains().values().forEach(d -> out.append("domain    : ")
                .append(d.domain())
                .append(" → tenant ")
                .append(d.tenant())
                .append(" via ")
                .append(d.provider())
                .append(d.autoCreate() ? ", autoCreate" : "")
                .append(d.allow() == null ? "" : ", allow " + d.allow().stream().sorted().collect(Collectors.joining(",")))
                .append(", max session ")
                .append(d.maxSession().toDays())
                .append("d\n"));
        if (config.consumerAccounts() != null) {
            out.append("gmail     : tenants ")
                    .append(config.consumerAccounts().tenants().stream().sorted().collect(Collectors.joining(",")))
                    .append(" via ")
                    .append(config.consumerAccounts().provider())
                    .append(", max session ")
                    .append(config.consumerAccounts().maxSession().toDays())
                    .append("d\n");
        }
        for (String problem : problems) {
            out.append("PROBLEM   : ").append(problem).append('\n');
        }
        return out.toString().stripTrailing();
    }
}
