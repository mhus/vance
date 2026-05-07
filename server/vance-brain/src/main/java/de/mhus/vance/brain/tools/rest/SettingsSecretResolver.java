package de.mhus.vance.brain.tools.rest;

import de.mhus.vance.toolpack.core.SecretResolver;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.settings.SettingService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Server-side {@link SecretResolver}. Substitutes
 * {@code {{secret:<key>}}} with the plain-text value of a
 * PASSWORD-typed setting via
 * {@link SettingService#getDecryptedPasswordCascade}.
 *
 * <p>Cascade walks {@code think-process → project → tenant} so a
 * tenant-wide API token applies unless a project pins its own.
 *
 * <p>Unresolved keys substitute to the empty string with a
 * warn-level log line — REST calls that depend on the auth header
 * will then fail with a 401, which is the right escalation path
 * for the LLM (it sees the failure and asks the user to provision
 * the secret).
 *
 * <p>References that can't be resolved (no setting found, decryption
 * failure) are replaced with empty string. Non-template input
 * passes through unchanged.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettingsSecretResolver implements SecretResolver {

    /** Matches {@code {{secret:any.dotted.key}}}. */
    private static final Pattern REF = Pattern.compile("\\{\\{\\s*secret\\s*:\\s*([^}\\s]+)\\s*\\}\\}");

    private final SettingService settings;

    @Override
    public @Nullable String resolve(@Nullable String input, ToolInvocationContext ctx) {
        if (input == null || input.isEmpty()) return input;
        Matcher m = REF.matcher(input);
        if (!m.find()) return input;
        StringBuilder out = new StringBuilder();
        m.reset();
        int last = 0;
        while (m.find()) {
            out.append(input, last, m.start());
            String key = m.group(1);
            String resolved = lookupSecret(key, ctx);
            if (resolved == null) {
                log.warn("SettingsSecretResolver: no PASSWORD setting found for key='{}' "
                                + "(tenant='{}', project='{}', process='{}') — substituting empty string",
                        key, ctx.tenantId(), ctx.projectId(), ctx.processId());
                resolved = "";
            }
            out.append(resolved);
            last = m.end();
        }
        out.append(input, last, input.length());
        return out.toString();
    }

    private @Nullable String lookupSecret(String key, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            return null;
        }
        return settings.getDecryptedPasswordCascade(
                ctx.tenantId(), ctx.projectId(), ctx.processId(), key);
    }
}
