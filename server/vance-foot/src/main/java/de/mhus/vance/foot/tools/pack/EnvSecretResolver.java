package de.mhus.vance.foot.tools.pack;

import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.SecretResolver;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Foot-side {@link SecretResolver}. Substitutes
 * {@code ${env:NAME}} with the value of {@code System.getenv("NAME")}.
 *
 * <p>Distinct syntax from the server-side {@code SettingsSecretResolver}
 * ({@code {{secret:foo.bar}}}) so a single config file is unambiguously
 * "foot-side env" or "server-side settings". A foot config that
 * accidentally uses {@code {{secret:...}}} would simply pass through
 * literally and the resulting auth would fail with a clear error;
 * vice versa for a server-side config with {@code ${env:...}}.
 *
 * <p>Unresolved env-vars expand to the empty string with a warn-log
 * line — REST/MCP calls that depend on the auth header will then fail
 * with a 401 at the host, which surfaces to the LLM as the natural
 * escalation path ("provision the env var").
 */
@Service
@Slf4j
public class EnvSecretResolver implements SecretResolver {

    /** Matches {@code ${env:NAME}}. NAME may contain letters, digits, and underscore. */
    private static final Pattern REF = Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)\\}");

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
            String name = m.group(1);
            String value = System.getenv(name);
            if (value == null) {
                log.warn("EnvSecretResolver: env var '{}' is not set — substituting empty string. "
                        + "Tool calls that need this secret will fail.", name);
                value = "";
            }
            out.append(value);
            last = m.end();
        }
        out.append(input, last, input.length());
        return out.toString();
    }
}
