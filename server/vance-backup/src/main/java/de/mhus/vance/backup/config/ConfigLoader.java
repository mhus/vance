package de.mhus.vance.backup.config;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

/**
 * Reads {@code ~/.vancetope/backup/backup.yaml} (or an override passed
 * via {@code --config <file>}) into a {@link BackupConfig}.
 *
 * <p>The loader follows the conventions from
 * {@code planning/user-backup.md} §4.3:
 *
 * <ul>
 *   <li>Default path: {@code ${user.home}/.vancetope/backup/backup.yaml}.
 *       Foot's {@code ~/.vancetope/} is the workbench-wide spot for
 *       per-user state; {@code backup/} keeps this tool isolated.
 *   <li>{@code --config <file>} overrides the default and wins.
 *   <li>{@code tokenFile:} in the YAML may point at a separate file
 *       containing just the JWT — so a team-shared config can stay
 *       secret-free.
 *   <li>The target directory defaults to {@code ~/VancetopeBackup}
 *       when the user omits it.
 *   <li>A missing config file is a hard error (exit code 4). A file
 *       that exists but does not parse is also a hard error. We do
 *       <em>not</em> fall back to defaults silently — a fresh install
 *       must run {@code vancetope-backup init} first.
 * </ul>
 *
 * <p>This class is intentionally tiny and pure: it reads from disk,
 * deserializes, validates the few invariants that always hold, and
 * returns. The CLI layer wraps it.
 */
@NullMarked
public final class ConfigLoader {

    /** Name of the per-user state directory inside {@code ~/.vancetope/}. */
    public static final String STATE_DIR = "backup";

    /** Default config file name inside the state directory. */
    public static final String DEFAULT_CONFIG_NAME = "backup.yaml";

    /** Default backup target — the user can override it in the YAML. */
    public static final String DEFAULT_TARGET_NAME = "VancetopeBackup";

    private final ObjectMapper yaml;

    public ConfigLoader() {
        this.yaml = new ObjectMapper(new YAMLFactory());
    }

    /** Test-friendly constructor that injects a custom mapper. */
    ConfigLoader(ObjectMapper yaml) {
        this.yaml = yaml;
    }

    /**
     * Returns the default config path for the current user. The
     * directory {@code ~/.vancetope/backup/} is <em>not</em> created
     * here — that is the job of {@code init}.
     */
    public Path defaultConfigPath() {
        return defaultConfigPath(Path.of(System.getProperty("user.home")));
    }

    /** Same as {@link #defaultConfigPath()} but with a configurable home — for tests. */
    public Path defaultConfigPath(Path home) {
        return home.resolve(".vancetope").resolve(STATE_DIR).resolve(DEFAULT_CONFIG_NAME);
    }

    /**
     * Resolves the config file the loader should read:
     * {@code override} wins; otherwise the default path is used.
     */
    public Path resolveConfigPath(@Nullable Path override) {
        return override != null ? override : defaultConfigPath();
    }

    /**
     * Loads and validates the config. The {@code override} argument
     * is whatever the user passed via {@code --config} (may be
     * {@code null} for the default path).
     *
     * <p>Validation enforced here:
     * <ul>
     *   <li>{@code server.url} must be a valid {@code http://} or
     *       {@code https://} URI. No path component — the client
     *       builds the path from {@code /brain/<tenant>/backup}.
     *   <li>{@code server.tenant} must be non-blank.
     *   <li>{@code token} (or {@code tokenFile}, after dereferencing)
     *       must be non-blank.
     *   <li>{@code target} defaults to {@code ~/VancetopeBackup}
     *       when empty.
     *   <li>{@code retention} numbers must be non-negative.
     * </ul>
     *
     * <p>Globs are not compiled here — they are passed through to the
     * server as-is and re-validated there. The client keeps them as
     * opaque strings so the round-trip stays symmetric.
     */
    public BackupConfig load(@Nullable Path override) {
        Path file = resolveConfigPath(override);
        return loadFrom(file);
    }

    /** Loads the config from an explicit path. Split out for tests. */
    public BackupConfig loadFrom(Path file) {
        if (!Files.exists(file)) {
            throw new ConfigException("Config file not found: " + file
                    + " — run 'vancetope-backup init' first, or pass --config <file>.");
        }
        if (!Files.isRegularFile(file)) {
            throw new ConfigException("Config path is not a regular file: " + file);
        }
        if (!Files.isReadable(file)) {
            throw new ConfigException("Config file is not readable: " + file);
        }
        try {
            if (Files.size(file) == 0) {
                throw new ConfigException("Config file is empty: " + file + " — run 'vancetope-backup init' first.");
            }
        } catch (IOException e) {
            throw new ConfigException("Failed to stat config " + file + ": " + e.getMessage(), e);
        }

        BackupConfig cfg;
        try (var in = Files.newBufferedReader(file)) {
            cfg = yaml.readValue(in, BackupConfig.class);
        } catch (IOException e) {
            throw new ConfigException("Failed to read config " + file + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // Jackson 3.x throws its own parse / type errors as
            // RuntimeException subclasses (JacksonYAMLParseException,
            // MismatchedInputException). Without this catch they would
            // bubble past ConfigLoader with a stack trace pointing
            // into jackson internals — useless for the operator.
            throw new ConfigException("Failed to parse config " + file + ": " + e.getMessage(), e);
        }
        if (cfg == null) {
            throw new ConfigException("Config file is empty: " + file + " — run 'vancetope-backup init' first.");
        }

        // Resolve tokenFile if present — the inline `token` field
        // then becomes optional. The two are mutually exclusive: if
        // both are set, the explicit `token` wins so a mistake
        // doesn't silently swap the credential.
        if (cfg.token == null && cfg.tokenFile != null) {
            cfg.token = readTokenFile(Path.of(cfg.tokenFile));
        }

        validateServer(cfg.server);
        if (cfg.token == null || cfg.token.isBlank()) {
            throw new ConfigException("Config is missing 'token' (or 'tokenFile')."
                    + " Mint one with POST /integration-tokens (scope profile 'backup')"
                    + " and paste the JWT here.");
        }

        if (cfg.target == null || cfg.target.isBlank()) {
            cfg.target = System.getProperty("user.home") + "/" + DEFAULT_TARGET_NAME;
        }

        validateRetention(cfg);
        validateDeny(cfg);

        return cfg;
    }

    private static void validateServer(BackupConfig.Server server) {
        if (server.url == null || server.url.isBlank()) {
            throw new ConfigException(
                    "Config is missing 'server.url' (e.g." + " 'https://brain.example' or 'http://localhost:9991').");
        }
        URI uri;
        try {
            uri = URI.create(server.url);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("server.url is not a valid URI: " + e.getMessage(), e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
            throw new ConfigException("server.url must use http:// or https:// (got: " + server.url + ").");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ConfigException("server.url is missing a host (got: " + server.url + ").");
        }
        if (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")) {
            throw new ConfigException("server.url must not include a path (got: " + server.url
                    + "). The backup routes are appended as /brain/<tenant>/backup.");
        }
        if (server.tenant == null || server.tenant.isBlank()) {
            throw new ConfigException("Config is missing 'server.tenant' (e.g. 'acme').");
        }
    }

    private static String readTokenFile(Path file) {
        if (!Files.exists(file)) {
            throw new ConfigException("tokenFile does not exist: " + file);
        }
        try {
            return Files.readString(file).trim();
        } catch (IOException e) {
            throw new ConfigException("Failed to read tokenFile " + file + ": " + e.getMessage(), e);
        }
    }

    private static void validateRetention(BackupConfig cfg) {
        var r = cfg.retention;
        if (r.hourly < 0 || r.daily < 0 || r.weekly < 0) {
            throw new ConfigException("retention buckets must be non-negative (got hourly=" + r.hourly + " daily="
                    + r.daily + " weekly=" + r.weekly + ")");
        }
    }

    private static void validateDeny(BackupConfig cfg) {
        for (String glob : cfg.deny) {
            if (glob == null || glob.isBlank()) {
                throw new ConfigException("deny list contains a blank entry");
            }
        }
        for (var entry : cfg.projectDeny.entrySet()) {
            for (String glob : entry.getValue()) {
                if (glob == null || glob.isBlank()) {
                    throw new ConfigException("projectDeny[" + entry.getKey() + "] contains a blank entry");
                }
            }
        }
    }

    /**
     * Convenience for the {@code init} command — writes a starter YAML
     * to {@code file} (creating parent directories if needed). The
     * caller is expected to inject the token afterwards.
     */
    public void writeStarter(Path file) {
        try {
            Files.createDirectories(file.getParent());
            BackupConfig starter = new BackupConfig();
            // Keep all defaults from the field initializers — they
            // already encode the planning §4.3 example. The user
            // fills in server.url, server.tenant, and token.
            try (var out = Files.newBufferedWriter(file)) {
                yaml.writerWithDefaultPrettyPrinter().writeValue(out, starter);
            }
        } catch (IOException e) {
            throw new ConfigException("Failed to write starter config to " + file + ": " + e.getMessage(), e);
        }
    }

    /** Exposed for tests — the deny-list default entries the loader expects. */
    static List<String> defaultDeny() {
        return List.of("_vance/**");
    }
}
