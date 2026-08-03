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
 * {@code .vancetope/project.eddie.yaml}.
 */
@Component
@Slf4j
public class VanceProjectConfigApplier {

    /** Stock IntelliJ MCP endpoint — mirrors {@code VanceFootCommand.INTELLIJ_MCP_DEFAULT_URL}. */
    static final String DEFAULT_INTELLIJ_MCP_URL = "http://127.0.0.1:64342/stream";

    /** Applies non-null project-config fields onto {@code config}. */
    public void apply(VanceProjectConfig project, FootConfig config) {
        applyConversationCapture(project, config);
        applyDefaults(project, config);
    }

    private void applyConversationCapture(VanceProjectConfig project, FootConfig config) {
        VanceProjectConfig.ConversationCapture src = project.getConversationCapture();
        if (src == null) return;

        FootConfig.ConversationCapture dst = config.getConversationCapture();

        // enabled is a primitive boolean — always overlay (absent = false
        // in the default VanceProjectConfig, but the applier only runs
        // when a config.yaml was actually present, so the user's value
        // wins over the application.yaml default).
        dst.setEnabled(src.isEnabled());

        if (src.getDir() != null && !src.getDir().isBlank()) {
            dst.setDir(src.getDir().trim());
        }

        log.debug("applied project config: conversationCapture.enabled={} dir={}",
                dst.isEnabled(), dst.getDir());
    }

    private void applyDefaults(VanceProjectConfig project, FootConfig config) {
        VanceProjectConfig.Defaults src = project.getDefaults();
        if (src == null) return;

        if (src.isIntellijClaude()) {
            config.getIde().getClaude().setEnabled(true);
        }
        if (src.isIntellijMcpDefault()) {
            config.getIde().getIntellijMcp().setUrl(DEFAULT_INTELLIJ_MCP_URL);
        }
        if (src.getRecipe() != null && !src.getRecipe().isBlank()) {
            config.getBootstrap().setChatRecipe(src.getRecipe().trim());
        }
        if (!src.isSandbox()) {
            config.getIde().setNoSandboxDefault(true);
        }

        log.debug("applied project config: defaults.intellijClaude={} intellijMcpDefault={} recipe={} sandbox={}",
                src.isIntellijClaude(), src.isIntellijMcpDefault(),
                src.getRecipe(), src.isSandbox());
    }
}
