package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.shared.settings.SettingService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

/**
 * Dev-mode-only shell commands. The whole component is gated by
 * {@code vance.anus.dev-mode.enabled=true} via {@link ConditionalOnProperty}
 * — when the flag is off, the bean does not exist, the commands are not
 * registered, and shell completion will not even hint at them.
 *
 * <p>{@code setting show-password} prints the AES-decrypted plaintext of a
 * PASSWORD-setting. Every disclosure is logged at warn level (without the
 * plaintext) so the regular log file doubles as an audit trail.
 */
@ShellComponent
@RequiresAuth
@ConditionalOnProperty(name = "vance.anus.dev-mode.enabled", havingValue = "true")
public class DevModeCommands {

    private static final Logger log = LoggerFactory.getLogger(DevModeCommands.class);

    private final SettingService settingService;

    public DevModeCommands(SettingService settingService) {
        this.settingService = settingService;
    }

    @ShellMethod(key = "setting show-password",
            value = "DEV-MODE — print the decrypted plaintext of a PASSWORD setting.")
    public String showPassword(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--scope", "-s"}) String scope,
            @ShellOption(value = {"--ref", "-r"}, defaultValue = ShellOption.NULL) @Nullable String ref,
            @ShellOption(value = {"--key", "-k"}) String key) {

        SettingCommands.StorageRef storage;
        try {
            storage = SettingCommands.mapToStorage(scope, ref);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }

        log.warn("DEV-MODE password disclosure requested: tenant='{}' scope='{}' ref='{}' key='{}'",
                tenant, scope, storage.id(), key);

        String plain = settingService.getDecryptedPassword(
                tenant, storage.type(), storage.id(), key);
        if (plain == null) {
            return "Setting not found, not a PASSWORD, or decryption failed — tenant='"
                    + tenant + "' scope='" + scope + "' ref='" + storage.id()
                    + "' key='" + key + "'.";
        }
        return plain;
    }
}
