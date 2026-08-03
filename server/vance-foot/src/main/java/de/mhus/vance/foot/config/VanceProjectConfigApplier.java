package de.mhus.vance.foot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Overlays a {@link VanceProjectConfig} (from
 * {@code .vancetope/config.yaml}) onto the running {@link FootConfig}.
 * Every non-null/non-blank field in the project config wins over the
 * {@code application.yaml} default; absent fields are left untouched.
 *
 * <p>Precedence at start-up is
 * {@code application.yaml < .vancetope/config.yaml < CLI flags} — this
 * applier runs before the flag overrides in {@code VanceFootCommand},
 * right after {@code ProjectBindingApplier} applies
 * {@code .vancetope/project.yaml}.
 */
@Component
@Slf4j
public class VanceProjectConfigApplier {

    /** Applies non-null project-config fields onto {@code config}. */
    public void apply(VanceProjectConfig project, FootConfig config) {
        VanceProjectConfig.ConversationAudit src = project.getConversationAudit();
        if (src == null) return;

        FootConfig.ConversationAudit dst = config.getConversationAudit();

        // enabled is a primitive boolean — always overlay (absent = false
        // in the default VanceProjectConfig, but the applier only runs
        // when a config.yaml was actually present, so the user's value
        // wins over the application.yaml default).
        dst.setEnabled(src.isEnabled());

        if (src.getDir() != null && !src.getDir().isBlank()) {
            dst.setDir(src.getDir().trim());
        }

        log.debug("applied project config: conversationAudit.enabled={} dir={}",
                dst.isEnabled(), dst.getDir());
    }
}
