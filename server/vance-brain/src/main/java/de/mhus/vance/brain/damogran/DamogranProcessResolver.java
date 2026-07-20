package de.mhus.vance.brain.damogran;

import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.brain.eddie.EddieEngine;
import de.mhus.vance.brain.tools.worktarget.BaseEngineTools;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves the process a Web-driven compose run binds to when there is
 * <em>no</em> active chat session (chatless workbook / cortex). A compose
 * always has a workspace, so its scripts should be able to reach it via the
 * file tools — but that needs a process to carry the WorkTarget + tool surface.
 *
 * <p>Reuse-or-create a project-scoped, <b>inert carrier process</b>: it is
 * created at status {@code INIT} and never enqueued on a lane (the compose runs
 * through {@link DamogranComposeService} directly, not a think-turn), so it just
 * sits there as a WorkTarget/tool-surface holder. Its tool surface is pinned to
 * the {@link BaseEngineTools#WORK_TARGET} file/exec tools via
 * {@code allowedToolsOverride} — so it does not depend on the (formal) engine
 * and a chatless compose script gets the same file tools a coding chat grants.
 *
 * <p>Project-scoped, system-owned ({@code _damogran}) — mirrors the compose
 * workspace, which is itself project-scoped. Concurrent chatless runs on one
 * project share it (the last run's WorkTarget wins — a narrow race, acceptable
 * since the workspace is already project-shared). When a chat session is
 * present the run binds to <em>its</em> process instead (see ComposeController).
 */
@Slf4j
@Service
public class DamogranProcessResolver {

    /** Base name; the carrier is scoped **per app** by appending the app key. */
    static final String SYSTEM_NAME = "_damogran";

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;

    public DamogranProcessResolver(SessionService sessionService,
                                   ThinkProcessService thinkProcessService) {
        this.sessionService = sessionService;
        this.thinkProcessService = thinkProcessService;
    }

    /**
     * Reuse-or-create the chatless compose carrier process, scoped by
     * {@code appKey} (the compose's app/document identity — the Workbook app
     * folder or the Cortex compose doc path). Same app ⇒ same carrier (same
     * workspace, collaborative — as the UI's presence shows); different apps get
     * separate carriers so their WorkTargets don't collide. {@code null} appKey
     * falls back to a single project-wide carrier.
     */
    public String resolveComposeCarrier(String tenantId, String projectId, @Nullable String appKey) {
        String name = carrierName(appKey);
        String sessionId = sessionService.findSystemSession(tenantId, projectId, name)
                .map(SessionDocument::getSessionId)
                .orElseGet(() -> {
                    SessionDocument created = sessionService.create(
                            tenantId, SYSTEM_NAME, projectId, name,
                            Profiles.DAEMON, "damogran", null, /*system*/ true);
                    sessionService.markBootstrapped(created.getSessionId());
                    log.debug("Damogran: created chatless carrier '{}' for {}/{}", name, tenantId, projectId);
                    return created.getSessionId();
                });

        return thinkProcessService.findByName(tenantId, sessionId, name)
                .map(ThinkProcessDocument::getId)
                .orElseGet(() -> thinkProcessService.create(
                        tenantId, projectId, sessionId, name, EddieEngine.NAME,
                        /*version*/ null, /*title*/ "Damogran compose", /*goal*/ null,
                        /*parentProcessId*/ null, /*engineParams*/ null,
                        /*recipeName*/ null, /*promptOverride*/ null, /*promptMode*/ null,
                        /*allowedToolsOverride*/ BaseEngineTools.WORK_TARGET).getId());
    }

    /** {@code _damogran} (project-wide) or {@code _damogran_<sanitized-appKey>} (per app). */
    private static String carrierName(@Nullable String appKey) {
        if (appKey == null || appKey.isBlank()) {
            return SYSTEM_NAME;
        }
        return SYSTEM_NAME + "_" + appKey.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
