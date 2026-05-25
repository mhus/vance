package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.jline.reader.LineReader;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

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
