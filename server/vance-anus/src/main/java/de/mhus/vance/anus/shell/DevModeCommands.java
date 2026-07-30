package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.shared.settings.SettingService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

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
@Component
@RequiresAuth
@ConditionalOnProperty(name = "vance.anus.dev-mode.enabled", havingValue = "true")
public class DevModeCommands {

    private static final Logger log = LoggerFactory.getLogger(DevModeCommands.class);

    private final SettingService settingService;

    public DevModeCommands(SettingService settingService) {
        this.settingService = settingService;
    }

    @Command(name = {"setting", "show-password"},
            description = "DEV-MODE — print the decrypted plaintext of a PASSWORD setting.")
    public String showPassword(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "scope", shortName = 's', required = true) String scope,
            @Option(longName = "ref", shortName = 'r') @Nullable String ref,
            @Option(longName = "key", shortName = 'k', required = true) String key) {

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
