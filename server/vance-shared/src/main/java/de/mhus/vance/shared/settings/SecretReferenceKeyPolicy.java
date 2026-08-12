package de.mhus.vance.shared.settings;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Setting keys that no {@code {{secret:…}}} reference may resolve — not from a
 * script, not from a compose task, and <b>not from a connector</b>.
 *
 * <h2>Why the connector path needs this</h2>
 * {@link de.mhus.vance.api.settings.SettingType#PASSWORD} is usable by
 * connectors: an SMTP/IMAP tool document, a REST or MCP tool pack resolves it
 * through {@code SecretResolver.resolveForConnector}. That is deliberate — a
 * tool credential should be usable without being readable by agents. But it
 * means the type alone no longer keeps a reference away from
 * {@code ai.provider.<instance>.apiKey}: a connector document names both the
 * target URL <em>and</em> its headers, so a reference to the provider key in a
 * header would send that key wherever the document points.
 *
 * <p>Connector documents live under the reserved {@code _vance/} namespace and
 * therefore need ADMIN to write, which is a real barrier — but it is an
 * authorisation barrier, not a containment one, and an agent runs with its
 * human's own {@code SecurityContext}. The keys below are read by compiled
 * server code at fixed names and are never legitimately part of a connector's
 * configuration, so refusing them costs nothing and closes the path.
 *
 * <h2>Relationship to {@link AgentSettingKeyPolicy}</h2>
 * Same grammar ({@link SettingKeyPatterns}), same default, separate list. One
 * governs writing a key, the other resolving it; folding them into a single
 * property would mean that protecting a new key from agent writes silently also
 * breaks any connector that legitimately references it.
 *
 * <p>Operator config from {@code application.yml} only — never a setting, since
 * an agent with settings-write access could otherwise widen its own reach.
 */
@Component
@Slf4j
public class SecretReferenceKeyPolicy {

    private final List<String> denyPatterns;

    public SecretReferenceKeyPolicy(
            @Value("${vance.settings.secretReferenceDenyKeys:ai.provider.*,vault.*}") String raw) {
        this.denyPatterns = SettingKeyPatterns.parse(raw);
        log.debug("SecretReferenceKeyPolicy: {} deny pattern(s): {}",
                denyPatterns.size(), denyPatterns);
    }

    /** Whether {@code key} is off-limits for reference resolution. */
    public boolean isDenied(String key) {
        return SettingKeyPatterns.matches(denyPatterns, key);
    }

    /**
     * Throws {@link SecretAccessDeniedException} when {@code key} may not be
     * resolved through a reference. A named error rather than the fail-closed
     * empty substitution: an operator who wired a connector to a reserved key
     * has to see <em>why</em> it is empty, otherwise the symptom is an opaque
     * downstream 401.
     */
    public void requireReferenceReadable(String key) {
        if (isDenied(key)) {
            log.warn("Refusing to resolve reserved setting key '{}' through a secret reference "
                    + "(vance.settings.secretReferenceDenyKeys)", key);
            throw new SecretAccessDeniedException(key,
                    "setting '" + key + "' is server-internal configuration and cannot be read "
                            + "through a {{secret:…}} reference — connectors that need a "
                            + "credential get their own key");
        }
    }

    /** Visible for tests and for the exact patterns in effect. */
    public List<String> denyPatterns() {
        return denyPatterns;
    }
}
