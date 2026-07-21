package de.mhus.vance.brain.damogran;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.brain.eddie.EddieEngine;
import de.mhus.vance.brain.tools.worktarget.BaseEngineTools;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
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
    private final ChatMessageService chatMessageService;
    private final ObjectProvider<ActionExecutorRegistry> actionRegistryProvider;

    public DamogranProcessResolver(SessionService sessionService,
                                   ThinkProcessService thinkProcessService,
                                   ChatMessageService chatMessageService,
                                   ObjectProvider<ActionExecutorRegistry> actionRegistryProvider) {
        this.sessionService = sessionService;
        this.thinkProcessService = thinkProcessService;
        this.chatMessageService = chatMessageService;
        this.actionRegistryProvider = actionRegistryProvider;
    }

    /**
     * Reuse-or-create the chatless compose session process, scoped by
     * {@code key} (the compose's session identity — an explicit
     * {@code session.name}, else the Workbook app folder / per-user fallback).
     * Same key ⇒ same process (memory continuity across runs); different keys
     * get separate processes so their WorkTargets don't collide.
     *
     * <p>When {@code recipe} is set, a freshly created process is a conversational
     * <b>agent</b> spawned from that recipe (via {@link ActionExecutorRegistry} —
     * the single spawn surface, so recipe resolution / engine start stay DRY);
     * otherwise it is a plain {@link BaseEngineTools#WORK_TARGET} holder (eddie,
     * inert). {@code clean} drops an existing process (and its conversation)
     * first, so the run starts fresh on the same stable name.
     */
    public String resolveComposeSession(
            String tenantId, String projectId, @Nullable String key,
            @Nullable String recipe, boolean clean) {
        String name = sessionName(key);
        String sessionId = sessionService.findSystemSession(tenantId, projectId, name)
                .map(SessionDocument::getSessionId)
                .orElseGet(() -> {
                    SessionDocument created = sessionService.create(
                            tenantId, SYSTEM_NAME, projectId, name,
                            Profiles.DAEMON, "damogran", null, /*system*/ true);
                    sessionService.markBootstrapped(created.getSessionId());
                    log.debug("Damogran: created session process '{}' for {}/{}", name, tenantId, projectId);
                    return created.getSessionId();
                });

        if (clean) {
            resetExisting(tenantId, sessionId, name);
        }

        ThinkProcessDocument existing = thinkProcessService.findByName(tenantId, sessionId, name).orElse(null);
        if (existing != null) {
            return existing.getId();
        }
        if (recipe != null && !recipe.isBlank()) {
            return createAgent(tenantId, projectId, sessionId, name, recipe);
        }
        return thinkProcessService.create(
                tenantId, projectId, sessionId, name, EddieEngine.NAME,
                /*version*/ null, /*title*/ "Damogran compose", /*goal*/ null,
                /*parentProcessId*/ null, /*engineParams*/ null,
                /*recipeName*/ null, /*promptOverride*/ null, /*promptMode*/ null,
                /*allowedToolsOverride*/ BaseEngineTools.WORK_TARGET).getId();
    }

    /**
     * Create the session process as a conversational agent from {@code recipe}
     * via the shared spawn surface — a <b>primary</b> process (no parent) in the
     * system session, created + engine-started. Reuses {@code SpawnActionExecutor}
     * so recipe resolution, tool-set and lifecycle match every other spawn path.
     */
    private String createAgent(
            String tenantId, String projectId, String sessionId, String name, String recipe) {
        TriggerAction.Recipe action = new TriggerAction.Recipe(
                recipe, /*processName*/ name, /*title*/ "Damogran agent", /*goal*/ null,
                /*inheritContextLevel*/ null, /*connectionProfile*/ null,
                /*initialMessage*/ null, /*params*/ Map.of(), /*runAs*/ null);
        TriggerContext ctx = TriggerContext.sessioned(
                tenantId, projectId, /*resolvedRunAs*/ null, /*correlationId*/ null,
                "damogran:session", sessionId, /*parentProcessId*/ null);
        ActionResult result = actionRegistryProvider.getObject().execute(action, ctx, TriggerKind.TOOL);
        if (result.outcome().isFailure() || result.spawnedId() == null) {
            throw new DamogranException("could not create agent session process '" + name
                    + "' from recipe '" + recipe + "': "
                    + (result.errorMessage() != null ? result.errorMessage() : result.outcome()));
        }
        log.debug("Damogran: created agent session process '{}' (recipe='{}') id='{}'",
                name, recipe, result.spawnedId());
        return result.spawnedId();
    }

    /** Drop the existing session process + its conversation so the run starts fresh. */
    private void resetExisting(String tenantId, String sessionId, String name) {
        thinkProcessService.findByName(tenantId, sessionId, name).ifPresent(existing -> {
            chatMessageService.deleteByProcess(tenantId, sessionId, existing.getId());
            thinkProcessService.delete(existing.getId());
            log.debug("Damogran: reset session process '{}' (clean) for tenant='{}'", name, tenantId);
        });
    }

    /** {@code _damogran} (project-wide) or {@code _damogran_<sanitized-key>} (per key). */
    private static String sessionName(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return SYSTEM_NAME;
        }
        return SYSTEM_NAME + "_" + key.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
