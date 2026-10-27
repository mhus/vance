package de.mhus.vance.backup.cli;

import de.mhus.vance.backup.config.ConfigLoader;
import java.io.Console;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

/**
 * Subcommand {@code vancetope-backup init}.
 *
 * <p>Interactively walks the operator through a fresh setup:
 *
 * <ol>
 *   <li>Brain URL (e.g. {@code http://localhost:9991} for dev or
 *       {@code https://brain.example} for prod).
 *   <li>Tenant name (e.g. {@code acme}).
 *   <li>Username + password (password read via
 *       {@link Console#readPassword(String)} so it never lands in
 *       a tty echo or shell history).
 *   <li>Token label (default: {@code "vancetope-backup on <hostname>"}).
 *   <li>{@code idleDays} (default: 30, max 365).
 *   <li>{@code expiresInDays} — fixed-end tokens only; sliding
 *       tokens carry no separate expiry.
 * </ol>
 *
 * <p>The command then {@code POST}s the access login, mints an
 * integration token with the {@code backup} profile, and writes
 * the config file at the default location (or the path passed via
 * {@code --config}). The connection string is the raw JWT from the
 * mint response.
 *
 * <p>Non-interactive use: every prompt can be passed as a flag —
 * the password as {@code --password-file <path>} (never on the
 * command line, which would land in shell history).
 */
@Command(
        name = "init",
        mixinStandardHelpOptions = true,
        description =
                "Interactively create a fresh config: prompts for credentials, mints a backup token, writes the YAML.")
@NullMarked
public final class InitCommand implements Callable<Integer> {

    @Option(
            names = {"--config"},
            description = "Override the config file path (default: ~/.vancetope/backup/backup.yaml).")
    private @Nullable Path config;

    @Option(
            names = {"--brain-url"},
            description = "Brain base URL (e.g. http://localhost:9991 or https://brain.example).")
    private @Nullable String brainUrl;

    @Option(
            names = {"--tenant"},
            description = "Tenant name (e.g. acme).")
    private @Nullable String tenant;

    @Option(
            names = {"--user"},
            description = "Username for the access login.")
    private @Nullable String username;

    @Option(
            names = {"--password-file"},
            description =
                    "Path to a file containing the password (one line). Use this in scripts; the password is never echoed.")
    private @Nullable Path passwordFile;

    @Option(
            names = {"--label"},
            description = "Label for the integration token. Defaults to 'vancetope-backup on <hostname>'.")
    private @Nullable String label;

    @Option(
            names = {"--idle-days"},
            description = "Idle-days ceiling for the sliding-lifetime token (default: 30, max 365).")
    private int idleDays = 30;

    @Option(
            names = {"--expires-in-days"},
            description =
                    "Fixed-end expiry in days. For sliding tokens the lifetime is governed by idleDays, so this is informational only.")
    private int expiresInDays = 7;

    @Option(
            names = {"--yes", "-y"},
            description = "Skip the confirmation prompt before writing the config.")
    private boolean assumeYes;

    private final PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    private final PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);

    private static final System.Logger LOG = System.getLogger(InitCommand.class.getName());

    @Override
    public Integer call() {
        // 1. Resolve config path
        ConfigLoader loader = new ConfigLoader();
        Path configFile = loader.resolveConfigPath(config);

        // 2. Collect missing inputs interactively (unless all flags were given)
        String url = brainUrl != null ? brainUrl : prompt("Brain URL", "http://localhost:9991");
        String ten = tenant != null ? tenant : prompt("Tenant", null);
        String user = username != null ? username : prompt("Username", null);
        char[] password = readPassword();
        if (password == null || password.length == 0) {
            err.println("Password is required.");
            return 4;
        }
        String labelValue = label != null ? label : "vancetope-backup on " + hostname();

        // 3. Confirm
        if (!assumeYes) {
            out.println();
            out.println("About to write:");
            out.println("  config file : " + configFile);
            out.println("  brain       : " + url);
            out.println("  tenant      : " + ten);
            out.println("  user        : " + user);
            out.println("  label       : " + labelValue);
            out.println("  idleDays    : " + idleDays);
            String confirm = prompt("Continue? [y/N]", "N");
            if (!confirm.equalsIgnoreCase("y") && !confirm.equalsIgnoreCase("yes")) {
                out.println("Aborted.");
                return 0;
            }
        }

        // 4. POST /access to get an access JWT
        String accessToken = login(url, ten, user, password);
        // Wipe the password from memory as early as possible — we
        // still need it for the password grant response, but once
        // we have the access token we don't need it anymore.
        java.util.Arrays.fill(password, '\0');
        if (accessToken == null) {
            err.println("Login failed — could not get an access token. Check URL/tenant/credentials.");
            return 2;
        }

        // 5. POST /integration-tokens with profile 'backup'
        String backupToken = mintBackupToken(url, ten, accessToken, labelValue, idleDays, expiresInDays);
        if (backupToken == null) {
            err.println("Token mint failed — see error above.");
            return 2;
        }

        // 6. Write the config
        try {
            writeConfig(configFile, url, ten, backupToken);
        } catch (java.io.IOException e) {
            err.println("Failed to write config " + configFile + ": " + e.getMessage());
            return 2;
        }
        out.println();
        out.println("Config written to " + configFile);
        out.println("Test it: vancetope-backup --config " + configFile + " info");
        return 0;
    }

    // -- prompts --------------------------------------------------------------

    private String prompt(String label, @Nullable String defaultValue) {
        Console console = System.console();
        if (console == null) {
            // Non-tty: read from stdin. The tool stays usable from
            // cron scripts and CI pipelines.
            err.print(label + (defaultValue != null ? " [" + defaultValue + "]: " : ": "));
            try (var scanner = new java.util.Scanner(System.in, StandardCharsets.UTF_8)) {
                String line = scanner.nextLine();
                return (line == null || line.isBlank()) && defaultValue != null ? defaultValue : line;
            }
        }
        String line = console.readLine("%s%s: ", label, defaultValue != null ? " [" + defaultValue + "]" : "");
        return (line == null || line.isBlank()) && defaultValue != null ? defaultValue : line;
    }

    private char @Nullable [] readPassword() {
        if (passwordFile != null) {
            try {
                String s = Files.readString(passwordFile).trim();
                return s.toCharArray();
            } catch (java.io.IOException e) {
                err.println("Cannot read password file: " + e.getMessage());
                return null;
            }
        }
        Console console = System.console();
        if (console == null) {
            err.println("No terminal available for password input." + " Use --password-file <path>.");
            return null;
        }
        return console.readPassword("Password: ");
    }

    private static String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "localhost";
        }
    }

    // -- HTTP flows -----------------------------------------------------------

    /**
     * Login to {@code POST /brain/{tenant}/access/{user}}. The body
     * shape is {@code {username, password}} per the existing login
     * endpoint (verified in earlier live tests).
     */
    private @Nullable String login(String url, String tenant, String user, char[] password) {
        try {
            URI uri = URI.create(url + "/brain/" + tenant + "/access/" + user);
            String body = "{\"username\":\"" + jsonEscape(user) + "\",\"password\":\""
                    + jsonEscape(new String(password)) + "\"}";
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                err.println("Login failed: HTTP " + resp.statusCode() + " — " + resp.body());
                return null;
            }
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> json = mapper.readValue(resp.body(), Map.class);
            return (String) json.get("token");
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            err.println("Login I/O error: " + e.getMessage());
            return null;
        }
    }

    /** Mint a backup-profile integration token. */
    private @Nullable String mintBackupToken(
            String url, String tenant, String accessToken, String label, int idleDays, int expiresInDays) {
        try {
            URI uri = URI.create(url + "/brain/" + tenant + "/integration-tokens");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("scopeProfiles", java.util.List.of("backup"));
            body.put("label", label);
            body.put("expiresInDays", expiresInDays);
            body.put("idleDays", idleDays);
            String json = new ObjectMapper().writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                err.println("Token mint failed: HTTP " + resp.statusCode() + " — " + resp.body());
                return null;
            }
            Map<String, Object> respBody = new ObjectMapper().readValue(resp.body(), Map.class);
            return (String) respBody.get("token");
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            err.println("Token mint I/O error: " + e.getMessage());
            return null;
        }
    }

    // -- YAML writing ---------------------------------------------------------

    /**
     * Writes the YAML config to {@code file} using Jackson YAML.
     * Direct construction (no BackupConfig deserializer round-trip)
     * so we control the field order — operators diff'ing the file
     * notice when a field moved.
     */
    private void writeConfig(Path file, String url, String tenant, String token) throws java.io.IOException {
        Files.createDirectories(file.getParent());
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("url", url);
        server.put("tenant", tenant);
        root.put("server", server);
        root.put("token", token);
        // Defaults from ConfigLoader's field initializers are
        // already sensible — we do not write them out, the loader
        // fills them when the file is read.
        Map<String, Object> projects = new LinkedHashMap<>();
        projects.put("include", java.util.List.of("*"));
        projects.put("exclude", java.util.List.of());
        projects.put("includeDisabled", false);
        root.put("projects", projects);
        root.put("deny", java.util.List.of("_vance/**"));
        Map<String, Object> retention = new LinkedHashMap<>();
        retention.put("hourly", 24);
        retention.put("daily", 30);
        retention.put("weekly", 52);
        root.put("retention", retention);
        root.put("notify", true);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (var writer = Files.newBufferedWriter(file)) {
            writer.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        }
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
