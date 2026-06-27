package de.mhus.vance.brain.applications;

import de.mhus.vance.api.thinkprocess.ActiveAppContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Bridges per-message {@link ActiveAppContext active-app hints} and the
 * {@code VanceApplication.promptInject(...)} SPI. Engines that field
 * chat turns (Eddie, Arthur, …) call {@link #resolve} once per drain
 * batch to obtain the markdown chunk for the Pebble
 * {@code appInstructions} variable; degrades silently on unknown apps
 * or when the SPI returns {@code null}.
 *
 * <p>Strict-mode validation in v1 is intentionally light: we only
 * check that the app discriminator is registered. Stricter checks
 * (manifest exists, manifest parses, …) belong inside each
 * {@code VanceApplication.promptInject(...)} implementation — the
 * engine should never block a turn because an app's prompt-inject
 * happened to fail.
 *
 * <p>See {@code planning/apps-in-cortex-and-live.md} §5.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ActiveAppPromptResolver {

    private final VanceApplicationRegistry registry;

    /**
     * Resolve the prompt-inject markdown for the given active-app hint
     * in the scope of {@code process}. Returns {@code null} when the
     * hint is missing, the app type is unknown, or the SPI returned
     * nothing useful — the engine then leaves both {@code activeApp}
     * and {@code appInstructions} unset on the Pebble context so the
     * template block falls away cleanly.
     */
    public @Nullable String resolve(
            ThinkProcessDocument process,
            @Nullable ActiveAppContext active) {
        if (active == null) return null;
        String appName = active.getApp();
        String folder = active.getFolder();
        if (appName == null || appName.isBlank() || folder == null || folder.isBlank()) {
            return null;
        }
        var appOpt = registry.find(appName);
        if (appOpt.isEmpty()) {
            log.debug("activeApp inject: unknown app '{}' from process {} — skipping",
                    appName, process.getId());
            return null;
        }
        try {
            return appOpt.get().promptInject(new VanceApplication.PromptInjectContext(
                    process.getTenantId(),
                    process.getProjectId(),
                    folder,
                    process.getSessionId(),
                    process.getId()));
        } catch (RuntimeException e) {
            log.warn("activeApp inject for '{}' at '{}' threw: {}",
                    appName, folder, e.toString());
            return null;
        }
    }
}
