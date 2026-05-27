package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.jline.reader.LineReader;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;
import org.yaml.snakeyaml.Yaml;

/**
 * CRUD over {@link SettingDocument} via {@link SettingService}. The wire-format
 * here mirrors the brain admin REST controller — operators address scopes as
 * {@code tenant} / {@code user/<login>} / {@code project/<name>} /
 * {@code think-process/<id>}; storage collapses {@code tenant} and
 * {@code user} onto the project layer under {@code _tenant} /
 * {@code _user_<login>}.
 *
 * <p>{@code setting set} writes any non-password type with a plaintext value;
 * {@code setting set-password} encrypts via the shared
 * {@link de.mhus.vance.shared.crypto.AesEncryptionService}. Password values
 * never leak through {@code list} / {@code show} — they are rendered as
 * {@value #PASSWORD_MASK}.
 */
@ShellComponent
@RequiresAuth
public class SettingCommands {

    private static final String PASSWORD_MASK = "[set]";

    private final SettingService settingService;
    private final ObjectProvider<LineReader> lineReader;

    public SettingCommands(SettingService settingService, ObjectProvider<LineReader> lineReader) {
        this.settingService = settingService;
        this.lineReader = lineReader;
    }

    @ShellMethod(key = "setting list",
            value = "List settings. Either --scope+--ref for a scope, or --key alone for that key across the tenant.")
    public String list(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--scope", "-s"}, defaultValue = ShellOption.NULL,
                    help = "tenant | user | project | think-process")
            @Nullable String scope,
            @ShellOption(value = {"--ref", "-r"}, defaultValue = ShellOption.NULL,
                    help = "user login / project name / think-process id; auto-filled for scope=tenant.")
            @Nullable String ref,
            @ShellOption(value = {"--key", "-k"}, defaultValue = ShellOption.NULL)
            @Nullable String key) {

        if (scope == null && StringUtils.isBlank(key)) {
            return "Provide either (--scope + --ref) or --key.";
        }
        if (scope != null) {
            StorageRef storage;
            try {
                storage = mapToStorage(scope, ref);
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
            List<SettingDocument> docs = settingService.findAll(tenant, storage.type(), storage.id());
            if (StringUtils.isNotBlank(key)) {
                docs = docs.stream().filter(d -> key.equals(d.getKey())).toList();
            }
            if (docs.isEmpty()) {
                return "(no settings in tenant='" + tenant + "' scope='" + scope + "' ref='" + storage.id() + "')";
            }
            return renderTable(docs);
        }
        // Key-only fallthrough — find every scope in the tenant that holds this key.
        List<SettingDocument> docs = settingService.findByKey(tenant, key);
        if (docs.isEmpty()) {
            return "(no settings with key='" + key + "' in tenant='" + tenant + "')";
        }
        return renderTable(docs);
    }

    @ShellMethod(key = "setting show", value = "Show a single setting.")
    public String show(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--scope", "-s"}) String scope,
            @ShellOption(value = {"--ref", "-r"}, defaultValue = ShellOption.NULL) @Nullable String ref,
            @ShellOption(value = {"--key", "-k"}) String key) {

        StorageRef storage;
        try {
            storage = mapToStorage(scope, ref);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        Optional<SettingDocument> doc = settingService.find(tenant, storage.type(), storage.id(), key);
        if (doc.isEmpty()) {
            return "Setting not found — tenant='" + tenant + "' scope='" + scope
                    + "' ref='" + storage.id() + "' key='" + key + "'.";
        }
        return renderOne(doc.get());
    }

    @ShellMethod(key = "setting set",
            value = "Set a non-password value. Use 'setting set-password' for PASSWORD.")
    public String set(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--scope", "-s"}) String scope,
            @ShellOption(value = {"--ref", "-r"}, defaultValue = ShellOption.NULL) @Nullable String ref,
            @ShellOption(value = {"--key", "-k"}) String key,
            @ShellOption(value = {"--value", "-v"}, defaultValue = ShellOption.NULL,
                    help = "Plain string value. Omit to clear the value while keeping the document.")
            @Nullable String value,
            @ShellOption(value = {"--type", "-t"}, defaultValue = "STRING",
                    help = "STRING | INT | LONG | DOUBLE | BOOLEAN — PASSWORD is rejected here.")
            SettingType type,
            @ShellOption(value = {"--description", "-d"}, defaultValue = ShellOption.NULL) @Nullable String description) {

        if (type == SettingType.PASSWORD) {
            return "Refusing to set PASSWORD via 'setting set' — use 'setting set-password' instead.";
        }
        StorageRef storage;
        try {
            storage = mapToStorage(scope, ref);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (value != null && !isParseable(type, value)) {
            return "Value '" + value + "' is not a valid " + type + ".";
        }
        SettingDocument saved = settingService.set(
                tenant, storage.type(), storage.id(), key, value, type, description);
        return "Set:\n" + renderOne(saved);
    }

    @ShellMethod(key = "setting set-password",
            value = "Store an encrypted PASSWORD setting. Plaintext is prompted (masked) when --value is omitted.")
    public String setPassword(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--scope", "-s"}) String scope,
            @ShellOption(value = {"--ref", "-r"}, defaultValue = ShellOption.NULL) @Nullable String ref,
            @ShellOption(value = {"--key", "-k"}) String key,
            @ShellOption(value = {"--value", "-v"}, defaultValue = ShellOption.NULL,
                    help = "Plaintext. Stored AES-GCM-encrypted with the shared encryption key.")
            @Nullable String value) {

        StorageRef storage;
        try {
            storage = mapToStorage(scope, ref);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        String plain = value;
        if (plain == null) {
            plain = lineReader.getObject().readLine("Password for '" + key + "': ", '*');
        }
        if (StringUtils.isBlank(plain)) {
            return "Empty password — refusing.";
        }
        SettingDocument saved = settingService.setEncryptedPassword(
                tenant, storage.type(), storage.id(), key, plain);
        return "Set (encrypted):\n" + renderOne(saved);
    }

    @ShellMethod(key = "setting import",
            value = "Bulk-import settings from a YAML file (init-settings.yaml format). "
                    + "Top-level YAML key is the tenant; CLI --scope/--ref decide where the "
                    + "settings land. Idempotent upsert; PASSWORD entries are encrypted.")
    public String importYaml(
            @ShellOption(value = {"--file", "-f"},
                    help = "Path to the YAML file. Relative paths walk parent directories.")
            String fileArg,
            @ShellOption(value = {"--tenant", "-T"}, defaultValue = ShellOption.NULL,
                    help = "Tenant to import into. When set, must match the YAML top-level "
                            + "key (single-tenant files); when omitted, every tenant entry "
                            + "in the YAML is applied to its own tenant.")
            @Nullable String tenantFilter,
            @ShellOption(value = {"--scope", "-s"}, defaultValue = "tenant",
                    help = "tenant | user | project | think-process. Default 'tenant' lands "
                            + "in the tenant-wide _vance system project.")
            String scope,
            @ShellOption(value = {"--ref", "-r"}, defaultValue = ShellOption.NULL,
                    help = "user login / project name / think-process id; auto-filled for scope=tenant.")
            @Nullable String ref,
            @ShellOption(value = {"--dry-run"}, defaultValue = "false",
                    help = "Print what would happen, without writing.")
            boolean dryRun) {

        // 1. Resolve scope/ref to storage early so a bad combination
        //    fails before we read the file.
        StorageRef storage;
        try {
            storage = mapToStorage(scope, ref);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }

        // 2. Locate the file — try the given path, walk up parents
        //    (so this works from any sub-directory of the workbench).
        Optional<Path> located = locateFile(fileArg);
        if (located.isEmpty()) {
            return "File not found: '" + fileArg + "' (looked in cwd and parents).";
        }
        Path path = located.get();

        // 3. Parse YAML; top-level must be a tenant→settings map.
        Map<String, Object> root;
        try {
            root = parseYaml(path);
        } catch (RuntimeException e) {
            return "YAML parse FAILED for '" + path + "': " + e.getMessage();
        }
        if (root == null || root.isEmpty()) {
            return "File '" + path + "' is empty — nothing to import.";
        }

        // 4. Walk tenants → settings, apply each.
        List<String> report = new ArrayList<>();
        int applied = 0;
        int skipped = 0;
        int failed = 0;
        for (Map.Entry<String, Object> tenantEntry : root.entrySet()) {
            String tenant = tenantEntry.getKey();
            if (tenantFilter != null && !tenantFilter.equals(tenant)) {
                report.add("[skip-tenant] " + tenant + " (filter='" + tenantFilter + "')");
                skipped++;
                continue;
            }
            if (!(tenantEntry.getValue() instanceof Map<?, ?> entries)) {
                report.add("[skip-tenant] " + tenant + " (value is not a settings map)");
                skipped++;
                continue;
            }
            for (Map.Entry<?, ?> e : entries.entrySet()) {
                String key = e.getKey() == null ? null : e.getKey().toString();
                if (StringUtils.isBlank(key)) {
                    report.add("[skip] " + tenant + " <blank-key>");
                    skipped++;
                    continue;
                }
                if (!(e.getValue() instanceof Map<?, ?> spec)) {
                    report.add("[skip] " + tenant + "/" + key + " (not a {type,value} map)");
                    skipped++;
                    continue;
                }
                try {
                    Outcome outcome = applyOne(
                            tenant, storage, key, spec, dryRun);
                    report.add(outcome.line());
                    if (outcome.applied()) applied++;
                    else skipped++;
                } catch (RuntimeException ex) {
                    report.add("[fail] " + tenant + "/" + key + ": " + ex.getMessage());
                    failed++;
                }
            }
        }
        String header = String.format(
                "%s from '%s'%nScope: %s%s%nTenants in file: %d%nApplied: %d, skipped: %d, failed: %d%n",
                dryRun ? "DRY-RUN — no writes" : "Imported settings",
                path,
                scope,
                ref == null || ref.isBlank() ? "" : " (ref=" + ref + ")",
                root.size(),
                applied, skipped, failed);
        return header + "\n" + String.join("\n", report);
    }

    /**
     * Applies a single {@code key: {type, value, description?}} block.
     * Returns a one-line report ({@code [ok]} / {@code [skip-empty]} /
     * {@code [dry]}); throws on hard errors so the caller increments
     * the failure counter.
     */
    private Outcome applyOne(
            String tenant, StorageRef storage,
            String key, Map<?, ?> spec, boolean dryRun) {
        String typeStr = spec.get("type") == null ? "STRING" : spec.get("type").toString();
        SettingType type;
        try {
            type = SettingType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown setting type '" + typeStr + "'");
        }
        Object rawValue = spec.get("value");
        String value = rawValue == null ? null : rawValue.toString();
        if (StringUtils.isBlank(value)) {
            return new Outcome(false,
                    "[skip-empty] " + tenant + "/" + key + " (" + type + ")");
        }
        String description = spec.get("description") == null
                ? null : spec.get("description").toString();
        if (dryRun) {
            String valueRender = type == SettingType.PASSWORD
                    ? "<encrypted>" : value;
            return new Outcome(true,
                    "[dry] " + tenant + "/" + key + " (" + type + ") = " + valueRender);
        }
        if (type == SettingType.PASSWORD) {
            settingService.setEncryptedPassword(
                    tenant, storage.type(), storage.id(), key, value);
            return new Outcome(true, "[ok] " + tenant + "/" + key + " (PASSWORD, encrypted)");
        }
        settingService.set(
                tenant, storage.type(), storage.id(), key, value, type, description);
        return new Outcome(true, "[ok] " + tenant + "/" + key + " (" + type + ")");
    }

    private record Outcome(boolean applied, String line) {}

    /**
     * Locates {@code fileArg} as an absolute path, then as a path
     * relative to cwd, then walks parent directories trying the same
     * relative path. Mirrors {@code InitSettingsLoader.locateFile} so
     * the same conventions work from anywhere in the workbench tree.
     */
    private static Optional<Path> locateFile(String fileArg) {
        if (StringUtils.isBlank(fileArg)) return Optional.empty();
        Path abs = Paths.get(fileArg).toAbsolutePath().normalize();
        if (Files.isRegularFile(abs)) return Optional.of(abs);
        Path rel = Paths.get(fileArg);
        if (rel.isAbsolute()) return Optional.empty();
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        while (cwd != null) {
            Path candidate = cwd.resolve(rel);
            if (Files.isRegularFile(candidate)) return Optional.of(candidate);
            cwd = cwd.getParent();
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(Path path) {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(path)) {
            Object loaded = yaml.load(in);
            if (loaded instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
            throw new IllegalStateException(
                    "Top level is not a map; got "
                            + (loaded == null ? "null" : loaded.getClass().getSimpleName()));
        } catch (IOException e) {
            throw new IllegalStateException("Read failed: " + e.getMessage(), e);
        }
    }

    @ShellMethod(key = "setting delete", value = "Delete a setting.")
    public String delete(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--scope", "-s"}) String scope,
            @ShellOption(value = {"--ref", "-r"}, defaultValue = ShellOption.NULL) @Nullable String ref,
            @ShellOption(value = {"--key", "-k"}) String key) {

        StorageRef storage;
        try {
            storage = mapToStorage(scope, ref);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (settingService.find(tenant, storage.type(), storage.id(), key).isEmpty()) {
            return "Setting not found — nothing to delete.";
        }
        settingService.delete(tenant, storage.type(), storage.id(), key);
        return "Deleted — tenant='" + tenant + "' scope='" + scope
                + "' ref='" + storage.id() + "' key='" + key + "'.";
    }

    // ─── Wire ↔ Storage mapping ─────────────────────────────────────────────

    /**
     * Persisted reference resolved from a wire-format scope + ref pair.
     * Kept package-private so the unit test can exercise the mapping
     * without spinning up the shell.
     */
    record StorageRef(String type, String id) {}

    static StorageRef mapToStorage(String wireScope, @Nullable String wireRef) {
        return switch (wireScope) {
            case SettingService.SCOPE_TENANT -> new StorageRef(
                    SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME);
            case SettingService.SCOPE_USER -> {
                if (StringUtils.isBlank(wireRef)) {
                    throw new IllegalArgumentException(
                            "scope=user requires --ref <login>.");
                }
                yield new StorageRef(SettingService.SCOPE_PROJECT,
                        HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + wireRef);
            }
            case SettingService.SCOPE_PROJECT, SettingService.SCOPE_THINK_PROCESS -> {
                if (StringUtils.isBlank(wireRef)) {
                    throw new IllegalArgumentException(
                            "scope=" + wireScope + " requires --ref.");
                }
                yield new StorageRef(wireScope, wireRef);
            }
            default -> throw new IllegalArgumentException(
                    "Unknown scope '" + wireScope + "' — use tenant | user | project | think-process.");
        };
    }

    static StorageRef storageToWire(String storedType, @Nullable String storedId) {
        if (SettingService.SCOPE_PROJECT.equals(storedType)) {
            if (HomeBootstrapService.TENANT_PROJECT_NAME.equals(storedId)) {
                return new StorageRef(SettingService.SCOPE_TENANT, "");
            }
            if (storedId != null
                    && storedId.startsWith(HomeBootstrapService.HUB_PROJECT_NAME_PREFIX)) {
                return new StorageRef(SettingService.SCOPE_USER,
                        storedId.substring(HomeBootstrapService.HUB_PROJECT_NAME_PREFIX.length()));
            }
        }
        return new StorageRef(storedType, storedId == null ? "" : storedId);
    }

    // ─── Render ─────────────────────────────────────────────────────────────

    private static String renderTable(List<SettingDocument> docs) {
        return Tables.render(
                List.of("SCOPE", "REF", "KEY", "TYPE", "VALUE"),
                List.<Function<SettingDocument, @Nullable Object>>of(
                        d -> storageToWire(d.getReferenceType(), d.getReferenceId()).type(),
                        d -> storageToWire(d.getReferenceType(), d.getReferenceId()).id(),
                        SettingDocument::getKey,
                        SettingDocument::getType,
                        SettingCommands::displayValue),
                docs);
    }

    private static String renderOne(SettingDocument d) {
        StorageRef wire = storageToWire(d.getReferenceType(), d.getReferenceId());
        return "  tenantId    : " + d.getTenantId() + "\n"
                + "  scope       : " + wire.type() + "\n"
                + "  ref         : " + wire.id() + "\n"
                + "  key         : " + d.getKey() + "\n"
                + "  type        : " + d.getType() + "\n"
                + "  value       : " + displayValue(d) + "\n"
                + "  description : " + (d.getDescription() == null ? "" : d.getDescription()) + "\n"
                + "  created     : " + (d.getCreatedAt() == null ? "" : d.getCreatedAt()) + "\n"
                + "  updated     : " + (d.getUpdatedAt() == null ? "" : d.getUpdatedAt()) + "\n"
                + "  id          : " + (d.getId() == null ? "" : d.getId());
    }

    private static String displayValue(SettingDocument d) {
        if (d.getType() == SettingType.PASSWORD) {
            return d.getValue() == null ? "" : PASSWORD_MASK;
        }
        return d.getValue() == null ? "" : d.getValue();
    }

    private static boolean isParseable(SettingType type, String value) {
        try {
            switch (type) {
                case INT -> Integer.parseInt(value.trim());
                case LONG -> Long.parseLong(value.trim());
                case DOUBLE -> Double.parseDouble(value.trim());
                case BOOLEAN -> {
                    String n = value.trim().toLowerCase();
                    if (!n.equals("true") && !n.equals("false")
                            && !n.equals("1") && !n.equals("0")
                            && !n.equals("yes") && !n.equals("no")
                            && !n.equals("on") && !n.equals("off")) {
                        return false;
                    }
                }
                case STRING, PASSWORD -> { /* always parseable */ }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
