package de.mhus.vance.foot.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Reads and writes the central permissions file
 * {@code ~/.vance/permissions.yaml} (override the location with the
 * {@code vance.permissions.file} property — mainly for tests) and, on
 * top of it, an optional project-local {@code ./.vance/permissions.yaml}
 * ({@code vance.permissions.local-file}).
 *
 * <p><b>Tightening-only cascade.</b> The central file carries the full
 * policy (allow + deny + sandbox switch). The local file may only make
 * the policy <em>stricter</em>:
 * <ul>
 *   <li>its {@code deny} rules (paths + commands) are appended to the
 *       central denies;</li>
 *   <li>{@code sandbox: true} forces the sandbox on even when the
 *       central file turned it off;</li>
 *   <li>its {@code allow} rules and {@code sandbox: false} are
 *       <b>ignored</b> — a project must never be able to widen the
 *       user's policy.</li>
 * </ul>
 * See {@link #effectiveConfig()} for the merge. The "always" writer only
 * ever touches the central file; the local file is read-only here.
 *
 * <p>Lives outside the Spring property tree on purpose — the allow/deny
 * lists are append-only managed here (the "always" writer), not bound as
 * {@code @ConfigurationProperties}.
 */
@Component
@Slf4j
public class PermissionConfigLoader {

    /**
     * Non-overridable deny floor. Always merged ahead of the file's own
     * deny rules so a missing or empty file still protects credentials,
     * and the user cannot accidentally widen access to these by editing
     * the allow list.
     */
    public static final List<String> DEFAULT_PATH_DENY = List.of(
            "~/.ssh/**",
            "~/.aws/**",
            "~/.gnupg/**",
            "~/.vance/**");

    private final YAMLMapper mapper = (YAMLMapper) YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final @org.jspecify.annotations.Nullable String configuredPath;
    private final @org.jspecify.annotations.Nullable String configuredLocalPath;

    public PermissionConfigLoader(
            @Value("${vance.permissions.file:}") String configuredPath,
            @Value("${vance.permissions.local-file:}") String configuredLocalPath) {
        this.configuredPath = configuredPath;
        this.configuredLocalPath = configuredLocalPath;
    }

    /** Central permissions file: explicit override, else {@code ~/.vance/permissions.yaml}. */
    public Path file() {
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath.trim());
        }
        return Path.of(System.getProperty("user.home", ""), ".vance", "permissions.yaml");
    }

    /** Project-local file: explicit override, else {@code ./.vance/permissions.yaml} (CWD). */
    public Path localFile() {
        if (configuredLocalPath != null && !configuredLocalPath.isBlank()) {
            return Path.of(configuredLocalPath.trim());
        }
        return Path.of(System.getProperty("user.dir", ""), ".vance", "permissions.yaml");
    }

    /**
     * Reads the central config. A missing file yields defaults (sandbox
     * unset → on, empty rule lists). A present-but-broken file raises
     * {@link PermissionConfigException}.
     */
    public PermissionConfig load() {
        return loadFrom(file());
    }

    /** Reads the project-local config, or defaults when the file is absent. */
    public PermissionConfig loadLocal() {
        return loadFrom(localFile());
    }

    private PermissionConfig loadFrom(Path file) {
        if (!Files.exists(file)) {
            log.debug("permissions file {} absent — using defaults", file);
            return new PermissionConfig();
        }
        try {
            Root root = mapper.readValue(file.toFile(), Root.class);
            if (root == null || root.getPermissions() == null) {
                return new PermissionConfig();
            }
            return root.getPermissions();
        } catch (Exception e) {
            throw new PermissionConfigException(
                    "Failed to read permissions file " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Merges central + local into the effective config (tightening only,
     * see class doc). The returned config has a non-null {@link
     * PermissionConfig#getSandbox() sandbox} value resolved from both
     * scopes, central allow rules, and the concatenation of central +
     * local deny rules per domain. The local allow rules and a local
     * {@code sandbox: false} are intentionally dropped.
     */
    public PermissionConfig effectiveConfig() {
        PermissionConfig central = load();
        PermissionConfig local = loadLocal();

        boolean sandbox = !Boolean.FALSE.equals(central.getSandbox()); // null/true → on
        if (Boolean.TRUE.equals(local.getSandbox())) {
            sandbox = true; // local may only tighten
        }

        PermissionConfig eff = new PermissionConfig();
        eff.setSandbox(sandbox);
        eff.getPaths().getAllow().addAll(central.getPaths().getAllow());
        eff.getPaths().getDeny().addAll(central.getPaths().getDeny());
        eff.getPaths().getDeny().addAll(local.getPaths().getDeny());
        eff.getCommands().getAllow().addAll(central.getCommands().getAllow());
        eff.getCommands().getDeny().addAll(central.getCommands().getDeny());
        eff.getCommands().getDeny().addAll(local.getCommands().getDeny());
        return eff;
    }

    /**
     * Loads and compiles the effective policy (central + local
     * tightening), merging in {@link #DEFAULT_PATH_DENY}.
     */
    public PermissionPolicy loadPolicy() {
        return PermissionPolicy.compile(effectiveConfig(), DEFAULT_PATH_DENY);
    }

    /**
     * Appends an "always" rule and persists it. Idempotent — an
     * identical rule already present is not duplicated. Creates the file
     * (and {@code ~/.vance/}) if needed. The floor denies are never
     * written; they are added at compile time.
     */
    public synchronized void appendRule(PermissionDomain domain, boolean allow, String rule) {
        PermissionConfig config = load();
        PermissionConfig.DomainRules rules =
                domain == PermissionDomain.PATHS ? config.getPaths() : config.getCommands();
        List<String> list = allow ? rules.getAllow() : rules.getDeny();
        if (!list.contains(rule)) {
            list.add(rule);
        }
        write(config);
        log.info("permissions: persisted {} {} rule '{}'",
                allow ? "allow" : "deny", domain, rule);
    }

    private void write(PermissionConfig config) {
        Path file = file();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Root root = new Root();
            root.setPermissions(config);
            mapper.writeValue(file.toFile(), root);
        } catch (IOException | RuntimeException e) {
            throw new PermissionConfigException(
                    "Failed to write permissions file " + file + ": " + e.getMessage(), e);
        }
    }

    /** Top-level YAML wrapper: a single {@code permissions:} key. */
    @Data
    static class Root {
        private @org.jspecify.annotations.Nullable PermissionConfig permissions;
    }
}
