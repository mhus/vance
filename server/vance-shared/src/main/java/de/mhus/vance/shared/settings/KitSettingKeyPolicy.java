package de.mhus.vance.shared.settings;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Setting keys a kit may not write into a project.
 *
 * <p>The third list of this shape, and it answers a third question. The
 * other two are about an <em>agent</em> ({@link AgentSettingKeyPolicy})
 * and about a <em>reference</em> ({@link SecretReferenceKeyPolicy}); this
 * one is about a <em>bundle of files from somewhere else</em>. A kit is
 * not an agent — no model chose its contents — but it is also not the
 * operator: it arrives over the network, possibly assembled per request
 * by a host we do not control, and it installs unattended.
 *
 * <p>What that buys: a kit that could set {@code ai.provider.*} would
 * redirect the project's model traffic to an endpoint of its choosing,
 * and one that could set {@code vault.*} would redirect its secret
 * lookups. Both are operator territory and both are reachable through the
 * settings a kit legitimately ships.
 *
 * <p><b>Not overridable per kit or per provisioning entry, deliberately.</b>
 * The obvious shape — an allow-list on the entry — was considered and
 * dropped twice over. Carried on the import request it would be a bypass
 * for anyone who can call the import endpoint, which is the wrong trade
 * for a rule whose whole point is that the caller is not the author. Read
 * from the project's provisioning document instead, it would require the
 * installer to know whether this particular install came from provisioning
 * or from a hand-typed command, which it does not and should not.
 *
 * <p>So the knob is the operator's: shorten the list in
 * {@code application.yml} for a deployment where kits are trusted to
 * configure providers. Same character as
 * {@code vance.settings.agentWriteDenyKeys} — operator config, never a
 * setting, because a setting an agent can write would let it widen its own
 * reach.
 */
@Component
@Slf4j
public class KitSettingKeyPolicy {

    private final List<String> denyPatterns;

    public KitSettingKeyPolicy(
            // Keep this default in step with the shipped application.yml. A
            // deployment that does not ship our config must not end up with a
            // weaker list than one that does — that asymmetry is exactly how
            // kit.* went missing (code review 4, B5).
            @Value("${vance.kits.settingDenyKeys:"
                    + "ai.provider.*,vault.*,store.*,kit.*,jaglan.mount.*}") String raw) {
        this.denyPatterns = SettingKeyPatterns.parse(raw);
        log.debug("KitSettingKeyPolicy: {} deny pattern(s): {}",
                denyPatterns.size(), denyPatterns);
    }

    /** Whether a kit is refused this key. */
    public boolean isDenied(String key) {
        return SettingKeyPatterns.matches(denyPatterns, key);
    }

    /** Visible for tests and for the exact patterns in effect. */
    public List<String> denyPatterns() {
        return denyPatterns;
    }
}
