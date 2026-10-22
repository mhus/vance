package de.mhus.vance.liver.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

/**
 * Reads {@code ~/.vancetope/liver/backup.yaml} (or an override passed
 * via {@code --config <file>}) into a {@link BackupConfig}.
 *
 * <p>The loader follows the conventions from
 * {@code planning/user-backup.md} §4.3:
 *
 * <ul>
 *   <li>Default path: {@code ${user.home}/.vancetope/liver/backup.yaml}.
 *       Foot's {@code ~/.vancetope/} is the workbench-wide spot for
 *       per-user state; {@code liver/} keeps this tool isolated
 *       (planning §7 + the follow-up note that foot itself should
 *       migrate to {@code ~/.vancetope/foot/}).
 *   <li>{@code --config <file>} overrides the default and wins.
 *   <li>{@code connectionFile:} in the YAML may point at a separate
 *       file containing just the connection string — so a team-shared
 *       config can stay secret-free.
 *   <li>The target directory defaults to {@code ~/VancetopeBackup} when
 *       the user omits it.
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
    public static final String STATE_DIR = "liver";

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
     * directory {@code ~/.vancetope/liver/} is <em>not</em> created
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
     *   <li>{@code connection} (after optional {@code connectionFile}
     *       dereferencing) must be present and start with {@code vancetope1.}
     *       — the planning §4.3 connection-string scheme.
     *   <li>{@code target} defaults to {@code ~/VancetopeBackup} when
     *       empty.
     *   <li>{@code retention} numbers must be non-negative (a value
     *       of zero means "skip that bucket").
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

    /**
     * Loads the config from an explicit path. Splitting this out
     * keeps the test surface narrow.
     */
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
        // Jackson 3.x throws 'No content to map due to end-of-input'
        // for an empty file instead of returning null — handle the
        // empty-file case explicitly so the user gets the helpful
        // 'init' hint instead of a parse error.
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
            // jackson returns null when the YAML is literally empty.
            // Treat that as "missing" — the init command owns file creation.
            throw new ConfigException("Config file is empty: " + file + " — run 'vancetope-backup init' first.");
        }

        // Resolve connectionFile if present — the inline `connection`
        // field then becomes optional. The two are mutually exclusive:
        // if both are set, the explicit `connection` wins so a mistake
        // doesn't silently swap the token.
        if (cfg.connection == null && cfg.connectionFile != null) {
            cfg.connection = readConnectionFile(Path.of(cfg.connectionFile));
        }

        if (cfg.connection == null || cfg.connection.isBlank()) {
            throw new ConfigException("Config is missing 'connection' (or 'connectionFile')."
                    + " Run 'vancetope-backup init' to set up an INTEGRATION token.");
        }
        if (!cfg.connection.startsWith("vancetope1.")) {
            throw new ConfigException(
                    "Connection string must start with 'vancetope1.' (got: " + redact(cfg.connection) + ").");
        }

        if (cfg.target == null || cfg.target.isBlank()) {
            cfg.target = System.getProperty("user.home") + "/" + DEFAULT_TARGET_NAME;
        }

        validateRetention(cfg);
        validateDeny(cfg);

        return cfg;
    }

    private static String readConnectionFile(Path file) {
        if (!Files.exists(file)) {
            throw new ConfigException("connectionFile does not exist: " + file);
        }
        try {
            return Files.readString(file).trim();
        } catch (IOException e) {
            throw new ConfigException("Failed to read connectionFile " + file + ": " + e.getMessage(), e);
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
     * Redacts everything past the {@code vancetope1.} prefix so an
     * error message can confirm the scheme without leaking the token.
     */
    static String redact(String connection) {
        if (connection.length() <= "vancetope1.".length() + 6) {
            return connection.substring(0, Math.min(connection.length(), "vancetope1.".length())) + "…";
        }
        return connection.substring(0, "vancetope1.".length() + 6) + "…";
    }

    /**
     * Convenience for the {@code init} command — writes a starter YAML
     * to {@code file} (creating parent directories if needed). The
     * caller is expected to inject a connection string afterwards.
     */
    public void writeStarter(Path file) {
        try {
            Files.createDirectories(file.getParent());
            BackupConfig starter = new BackupConfig();
            // Keep all defaults from the field initializers — they
            // already encode the planning §4.3 example. The user fills
            // in connection (or connectionFile) and edits target.
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
