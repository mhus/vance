package de.mhus.vance.shared.settings;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Setting keys an agent may never write, regardless of type and regardless of
 * whether the setting already exists (rule W3 in
 * {@code planning/setting-type-hidden.md} §6.4).
 *
 * <p>The read-side counterpart is {@link SecretReferenceKeyPolicy}: this one
 * stops a key from being <em>created</em> by an agent, that one stops any key
 * from being <em>resolved</em> through a {@code {{secret:…}}} reference.
 *
 * <p>Rules W1 and W2 alone leave a gap: if {@code ai.provider.x.apiKey} does not
 * exist yet, an agent could create it as {@link
 * de.mhus.vance.api.settings.SettingType#HIDDEN} through a kit or tool template
 * and then read it back through a secret reference — for that session and every
 * later one. The value would have come from the model itself, so it is not a new
 * leak, but it turns a transient exposure into a permanent one. Infrastructure
 * credentials are operator territory; they get set through setting forms.
 *
 * <p>Operator config only, from {@code application.yml} — never an
 * LLM-controllable flag, and deliberately not a setting (an agent with settings
 * write access could otherwise widen its own permissions). Same character as
 * {@code vance.net.ssrf.allowPrivate}.
 *
 * <p>Grammar: comma-separated, each entry either an exact key or a prefix ending
 * in {@code *}. Deliberately not a full glob — this is security configuration and
 * has to stay readable at a glance.
 */
@Component
@Slf4j
public class AgentSettingKeyPolicy {

    private final List<String> denyPatterns;

    public AgentSettingKeyPolicy(
            @Value("${vance.settings.agentWriteDenyKeys:ai.provider.*,vault.*,store.*}") String raw) {
        this.denyPatterns = SettingKeyPatterns.parse(raw);
        log.debug("AgentSettingKeyPolicy: {} deny pattern(s): {}", denyPatterns.size(), denyPatterns);
    }

    /** Whether {@code key} is off-limits for agent-originated writes. */
    public boolean isDenied(String key) {
        return SettingKeyPatterns.matches(denyPatterns, key);
    }

    /**
     * Throws {@link SecretAccessDeniedException} when {@code key} is deny-listed.
     * Call before any agent-originated setting write.
     */
    public void requireAgentWritable(String key) {
        if (isDenied(key)) {
            log.warn("Refusing agent-originated write to reserved setting key '{}' "
                    + "(vance.settings.agentWriteDenyKeys)", key);
            throw new SecretAccessDeniedException(
                    "setting '" + key + "' is reserved for operator configuration and cannot be "
                            + "written by an agent — a human has to set it through the settings "
                            + "editor or the matching setting form");
        }
    }

    /** Visible for tests and for the exact patterns in effect. */
    public List<String> denyPatterns() {
        return denyPatterns;
    }
}
