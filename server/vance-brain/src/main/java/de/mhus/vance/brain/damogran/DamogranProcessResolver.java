package de.mhus.vance.brain.damogran;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.api.action.TriggerKind;
import de.mhus.vance.brain.eddie.EddieEngine;
import de.mhus.vance.brain.session.SessionLifecycleService;
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
 * <p>Project-scoped and named {@code _damogran[_<key>]} — mirrors the compose
 * workspace, which is itself project-scoped. Concurrent chatless runs on one
 * project share it (the last run's WorkTarget wins — a narrow race, acceptable
 * since the workspace is already project-shared). When a chat session is
 * present the run binds to <em>its</em> process instead (see ComposeController).
 *
 * <p><b>Identity.</b> The session is system-flagged but never owner-less by
 * accident: {@code runAs} is a mandatory argument, so every call states whose
 * authority the run carries. A conversational agent ({@code session.recipe})
 * must name a real user — it is free-prompted and therefore may not hold the
 * system trust boundary. The inert carrier may name
 * {@link SessionService#SYSTEM_OWNER}, which resolves to no user and thus to
 * {@code SecurityContext.SYSTEM} — the same authority the compose runners
 * already use for their own tool contexts, and harmless here because the
 * carrier is never enqueued on a lane and runs no turn.
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
    /** Lazy: the lifecycle service pulls in the engine stack this resolver sits under. */
    private final ObjectProvider<SessionLifecycleService> lifecycleProvider;

    public DamogranProcessResolver(SessionService sessionService,
                                   ThinkProcessService thinkProcessService,
                                   ChatMessageService chatMessageService,
                                   ObjectProvider<ActionExecutorRegistry> actionRegistryProvider,
                                   ObjectProvider<SessionLifecycleService> lifecycleProvider) {
        this.sessionService = sessionService;
        this.thinkProcessService = thinkProcessService;
        this.chatMessageService = chatMessageService;
        this.actionRegistryProvider = actionRegistryProvider;
        this.lifecycleProvider = lifecycleProvider;
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
     *
     * <p>{@code runAs} is the identity the session — and therefore every think-turn
     * running in it — acts under. It is mandatory and has no default: callers
     * either name the user whose authority the run carries, or explicitly name
     * {@link SessionService#SYSTEM_OWNER} for server-owned work with no user.
     * A conversational agent may never take the latter route: a free-prompted
     * process must not hold the system trust boundary, so a {@code recipe} plus
     * {@code SYSTEM_OWNER} is rejected. An existing session with a different
     * owner is closed and re-created rather than reused — reuse would let the
     * new caller act with the previous owner's grants (same rule as
     * {@code SystemSessionResolver} applies to scheduler {@code runAs} edits).
     *
     * @throws DamogranException if {@code runAs} is blank, or names the system
     *                           owner while {@code recipe} asks for an agent
     */
    public String resolveComposeSession(
            String tenantId, String projectId, String runAs, @Nullable String key,
            @Nullable String recipe, boolean clean) {
        if (runAs == null || runAs.isBlank()) {
            throw new DamogranException(
                    "compose session requires a runAs identity — pass the triggering user, "
                            + "or SessionService.SYSTEM_OWNER for server-owned work");
        }
        boolean agent = recipe != null && !recipe.isBlank();
        if (agent && SessionService.SYSTEM_OWNER.equals(runAs)) {
            throw new DamogranException(
                    "compose agent (session.recipe='" + recipe + "') cannot run without a user — "
                            + "a free-prompted process must act under a real principal, "
                            + "not with system authority");
        }
        String name = sessionName(key);
        String sessionId = resolveSessionId(tenantId, projectId, name, runAs);

        if (clean) {
            resetExisting(tenantId, sessionId, name);
        }

        ThinkProcessDocument existing = thinkProcessService.findByName(tenantId, sessionId, name).orElse(null);
        if (existing != null) {
            return existing.getId();
        }
        if (agent) {
            return createAgent(tenantId, projectId, sessionId, name, recipe, runAs);
        }
        return thinkProcessService.create(
                tenantId, projectId, sessionId, name, EddieEngine.NAME,
                /*version*/ null, /*title*/ "Damogran compose", /*goal*/ null,
                /*parentProcessId*/ null, /*engineParams*/ null,
                /*recipeName*/ null, /*promptOverride*/ null, /*promptMode*/ null,
                /*allowedToolsOverride*/ BaseEngineTools.WORK_TARGET).getId();
    }

    /**
     * Reuse the session for {@code name} when it is owned by {@code runAs},
     * otherwise create a fresh one. An owner change closes the old session:
     * a compose session carries the authority of its owner, so handing a
     * running one to the next caller would silently lend them the previous
     * owner's grants. Continuity is per (key, owner) — that is the price of
     * not confusing the two authorities.
     *
     * <p>Closed through {@link SessionLifecycleService#closeWithCascade},
     * not through {@code SessionService.close}: the session row is the
     * smallest part of what has to end. Its think-processes — the carrier,
     * or a whole conversational agent — would otherwise stay non-terminal
     * under an owner who no longer has the session, keep their pending
     * engine messages, and never fire the lifecycle hooks that clean up
     * what hangs off them.
     */
    private String resolveSessionId(
            String tenantId, String projectId, String name, String runAs) {
        SessionDocument existing =
                sessionService.findSystemSession(tenantId, projectId, name).orElse(null);
        if (existing != null) {
            if (runAs.equals(existing.getUserId())) {
                return existing.getSessionId();
            }
            log.info("Damogran: compose session '{}' owner changed in {}/{} "
                            + "oldUser='{}' newUser='{}' — closing old session '{}' and creating fresh",
                    name, tenantId, projectId, existing.getUserId(), runAs, existing.getSessionId());
            try {
                lifecycleProvider.getObject().closeWithCascade(existing.getSessionId());
            } catch (RuntimeException e) {
                // The old session is being abandoned either way; a failed
                // cascade must not stop the caller from getting a session
                // under their own identity, which is the point of the swap.
                log.warn("Damogran: cascade-close of compose session '{}' failed: {}",
                        existing.getSessionId(), e.toString());
            }
        }
        SessionDocument created = sessionService.create(
                tenantId, runAs, projectId, name,
                Profiles.DAEMON, "damogran", null, /*system*/ true);
        sessionService.markBootstrapped(created.getSessionId());
        log.debug("Damogran: created session process '{}' for {}/{} runAs='{}'",
                name, tenantId, projectId, runAs);
        return created.getSessionId();
    }

    /**
     * Create the session process as a conversational agent from {@code recipe}
     * via the shared spawn surface — a <b>primary</b> process (no parent) in the
     * system session, created + engine-started. Reuses {@code SpawnActionExecutor}
     * so recipe resolution, tool-set and lifecycle match every other spawn path.
     */
    private String createAgent(
            String tenantId, String projectId, String sessionId, String name,
            String recipe, String runAs) {
        TriggerAction.Recipe action = new TriggerAction.Recipe(
                recipe, /*processName*/ name, /*title*/ "Damogran agent", /*goal*/ null,
                /*inheritContextLevel*/ null, /*connectionProfile*/ null,
                /*initialMessage*/ null, /*params*/ Map.of(), /*runAs*/ null);
        // The turn identity comes from the session owner (resolveSessionId
        // guarantees it is runAs); resolvedRunAs carries the same user into
        // the event-log so the spawn is attributable to whoever triggered it.
        TriggerContext ctx = TriggerContext.sessioned(
                tenantId, projectId, /*resolvedRunAs*/ runAs, /*correlationId*/ null,
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
