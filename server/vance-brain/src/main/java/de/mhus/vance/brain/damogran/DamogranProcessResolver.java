package de.mhus.vance.brain.damogran;

import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.brain.eddie.EddieEngine;
import de.mhus.vance.brain.tools.worktarget.BaseEngineTools;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import lombok.extern.slf4j.Slf4j;
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

    /** Stable system session + carrier-process name, per project. */
    static final String SYSTEM_NAME = "_damogran";

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;

    public DamogranProcessResolver(SessionService sessionService,
                                   ThinkProcessService thinkProcessService) {
        this.sessionService = sessionService;
        this.thinkProcessService = thinkProcessService;
    }

    /** Reuse-or-create the project's chatless compose carrier process; returns its id. */
    public String resolveProjectComposeProcess(String tenantId, String projectId) {
        String sessionId = sessionService.findSystemSession(tenantId, projectId, SYSTEM_NAME)
                .map(SessionDocument::getSessionId)
                .orElseGet(() -> {
                    SessionDocument created = sessionService.create(
                            tenantId, SYSTEM_NAME, projectId, SYSTEM_NAME,
                            Profiles.DAEMON, "damogran", null, /*system*/ true);
                    sessionService.markBootstrapped(created.getSessionId());
                    log.debug("Damogran: created chatless carrier session for {}/{}", tenantId, projectId);
                    return created.getSessionId();
                });

        return thinkProcessService.findByName(tenantId, sessionId, SYSTEM_NAME)
                .map(ThinkProcessDocument::getId)
                .orElseGet(() -> thinkProcessService.create(
                        tenantId, projectId, sessionId, SYSTEM_NAME, EddieEngine.NAME,
                        /*version*/ null, /*title*/ "Damogran compose", /*goal*/ null,
                        /*parentProcessId*/ null, /*engineParams*/ null,
                        /*recipeName*/ null, /*promptOverride*/ null, /*promptMode*/ null,
                        /*allowedToolsOverride*/ BaseEngineTools.WORK_TARGET).getId());
    }
}
