package de.mhus.vance.brain.session;

import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.EngineBundledConfig;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Ensures every interactive session has its session-chat
 * think-process — Arthur by default, or whatever
 * {@code session.defaultChatEngine} resolves to.
 *
 * <p>Called by the session-create / session-bootstrap WS handlers
 * after a session is bound. The first call wins; subsequent calls
 * for the same session are no-ops (idempotent across retries and
 * across the {@code session-create} → {@code session-bootstrap}
 * doubling).
 *
 * <p>The chat-process always uses the conventional name
 * {@code "chat"} — there is exactly one per session, the orchestrator
 * the client routes default chat input at. Closing the session
 * stops the chat-process via the regular cascade.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionChatBootstrapper {

    public static final String CHAT_PROCESS_NAME = "chat";

    private static final String SETTING_DEFAULT_CHAT_ENGINE = "session.defaultChatEngine";
    private static final String DEFAULT_CHAT_ENGINE = "arthur";
    private static final String HUB_CHAT_ENGINE = "eddie";

    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final SessionService sessionService;
    private final SettingService settingService;
    private final LaneScheduler laneScheduler;
    private final RecipeResolver recipeResolver;
    private final ProjectService projectService;

    /**
     * @see #ensureChatProcess(SessionDocument, String, String) — defaults
     *      to no parent and no recipe override.
     */
    public Optional<ThinkProcessDocument> ensureChatProcess(SessionDocument session) {
        return ensureChatProcess(session, null, null);
    }

    /**
     * @see #ensureChatProcess(SessionDocument, String, String) — defaults
     *      to no recipe override.
     */
    public Optional<ThinkProcessDocument> ensureChatProcess(
            SessionDocument session,
            @org.jspecify.annotations.Nullable String parentProcessId) {
        return ensureChatProcess(session, parentProcessId, null);
    }

    /**
     * Returns the session's chat-process, creating + starting it on
     * the first call. Empty only when the session itself is gone.
     *
     * <p>{@code parentProcessId} (optional) is the cross-project
     * parent — used by hub engines (Vance) when they create a
     * worker project and want the project's Arthur-chat-process to
     * report status/done events back to them. {@code null} for
     * regular session-create flows.
     *
     * <p>{@code chatRecipeOverride} (optional) names the recipe to
     * spawn the chat-process from. When set, the cascade-resolved
     * recipe wins over the tenant default
     * ({@code session.defaultChatEngine} setting and bundled
     * {@code arthur} fallback); the engine is read from the recipe.
     * Hub projects ignore this — they always use the hub engine.
     *
     * <p>The engine's {@code start} runs on the chat-process's lane
     * and is awaited synchronously, so the caller can read the
     * greeting from {@code chat-message-history} the moment this
     * returns.
     */
    public Optional<ThinkProcessDocument> ensureChatProcess(
            SessionDocument session,
            @org.jspecify.annotations.Nullable String parentProcessId,
            @org.jspecify.annotations.Nullable String chatRecipeOverride) {
        // Already linked → just resolve the doc.
        if (session.getChatProcessId() != null) {
            Optional<ThinkProcessDocument> linked = thinkProcessService.findById(
                    session.getChatProcessId());
            if (linked.isPresent()) {
                return linked;
            }
            // Linked id points nowhere — log and fall through to a fresh attempt.
            log.warn("Session '{}' has chatProcessId='{}' but the process is gone; re-creating",
                    session.getSessionId(), session.getChatProcessId());
        }

        // A previous attempt may have created the process by name but
        // crashed before linking it back. Pick it up.
        Optional<ThinkProcessDocument> existing = thinkProcessService.findByName(
                session.getTenantId(), session.getSessionId(), CHAT_PROCESS_NAME);
        if (existing.isPresent()) {
            sessionService.setChatProcessId(
                    session.getSessionId(), existing.get().getId());
            return existing;
        }

        boolean hubProject = isHubProject(session.getTenantId(), session.getProjectId());
        // Hub-chat sessions live inside SYSTEM-kind projects (see
        // specification/vance-engine.md §2). They always run the
        // hub engine, regardless of the tenant default or the
        // per-bootstrap recipe override.
        String recipeLookup;
        if (hubProject) {
            recipeLookup = HUB_CHAT_ENGINE;
        } else if (chatRecipeOverride != null && !chatRecipeOverride.isBlank()) {
            recipeLookup = chatRecipeOverride;
        } else {
            String configured = settingService.getStringValueCascade(
                    session.getTenantId(), session.getProjectId(),
                    /*processId*/ null, SETTING_DEFAULT_CHAT_ENGINE);
            recipeLookup = (configured == null || configured.isBlank())
                    ? DEFAULT_CHAT_ENGINE : configured;
        }

        // Engines that ship their own bundled config (Vance, future
        // hub recipes) bypass recipe resolution — their persona and
        // params live in code, not in recipes.yaml. See
        // specification/vance-engine.md §1.2.
        boolean explicitRecipeOverride = !hubProject
                && chatRecipeOverride != null && !chatRecipeOverride.isBlank();
        ThinkEngine engine = thinkEngineService.resolve(recipeLookup)
                .orElse(null);

        AppliedRecipe applied = null;
        if (explicitRecipeOverride) {
            // Explicit override: recipe must exist; surface failure.
            applied = recipeResolver.applyDefaulting(
                    session.getTenantId(),
                    /*projectId*/ session.getProjectId(),
                    recipeLookup,
                    session.getProfile(),
                    /*callerParams*/ null);
            final AppliedRecipe override = applied;
            engine = thinkEngineService.resolve(applied.engine())
                    .orElseThrow(() -> new IllegalStateException(
                            "Recipe '" + override.name()
                                    + "' references unknown engine '"
                                    + override.engine() + "'"));
        } else if (engine == null) {
            throw new IllegalStateException(
                    "Configured chat engine '" + recipeLookup
                            + "' is not registered — known: "
                            + thinkEngineService.listEngines());
        }
        Optional<EngineBundledConfig> bundled = engine.bundledConfig(
                session.getTenantId(), session.getProjectId());
        if (!explicitRecipeOverride && bundled.isEmpty()) {
            // Auto-apply the engine-named recipe (e.g. "arthur") so
            // the chat-process inherits the engine's default prompt
            // and validator templates — same path the LLM takes when
            // it spawns a worker via process_create.
            try {
                applied = recipeResolver.applyDefaulting(
                        session.getTenantId(),
                        /*projectId*/ session.getProjectId(),
                        /*recipeName*/ recipeLookup,
                        session.getProfile(),
                        /*callerParams*/ null);
            } catch (RecipeResolver.UnknownRecipeException e) {
                // No engine-named recipe in the cascade — chat-process
                // starts engine-default (no recipe overrides).
                applied = null;
            }
            if (applied != null) {
                // The recipe may redirect to a different engine.
                final AppliedRecipe finalResolved = applied;
                engine = thinkEngineService.resolve(applied.engine())
                        .orElseThrow(() -> new IllegalStateException(
                                "Recipe '" + finalResolved.name()
                                        + "' references unknown engine '"
                                        + finalResolved.engine() + "'"));
            }
        }

        ThinkProcessDocument fresh;
        try {
            if (bundled.isPresent()) {
                EngineBundledConfig cfg = bundled.get();
                fresh = thinkProcessService.create(
                        session.getTenantId(),
                        session.getProjectId(),
                        session.getSessionId(),
                        CHAT_PROCESS_NAME,
                        engine.name(), engine.version(),
                        /*title*/ "Session Chat",
                        /*goal*/  null,
                        parentProcessId,
                        cfg.params(),
                        /*recipeName*/ null,
                        cfg.promptOverride(),
                        /*promptOverrideAppend*/ null,
                        cfg.promptMode(),
                        cfg.dataRelayCorrection(),
                        cfg.allowedTools(),
                        session.getProfile(),
                        /*defaultActiveSkills*/ null,
                        /*allowedSkillsOverride*/ null);
            } else if (applied != null) {
                fresh = thinkProcessService.create(
                        session.getTenantId(),
                        session.getProjectId(),
                        session.getSessionId(),
                        CHAT_PROCESS_NAME,
                        engine.name(), engine.version(),
                        /*title*/ "Session Chat",
                        /*goal*/  null,
                        parentProcessId,
                        applied.params(),
                        applied.name(),
                        applied.promptOverride(),
                        applied.promptOverrideAppend(),
                        applied.promptMode(),
                        applied.dataRelayCorrection(),
                        applied.effectiveAllowedTools(),
                        applied.connectionProfile(),
                        applied.defaultActiveSkills(),
                        applied.allowedSkills() == null
                                ? null : java.util.Set.copyOf(applied.allowedSkills()));
            } else {
                fresh = thinkProcessService.create(
                        session.getTenantId(),
                        session.getProjectId(),
                        session.getSessionId(),
                        CHAT_PROCESS_NAME,
                        engine.name(), engine.version(),
                        /*title*/ "Session Chat",
                        /*goal*/  null);
            }
        } catch (ThinkProcessService.ThinkProcessAlreadyExistsException race) {
            // Lost the race against a concurrent bootstrapper — adopt their result.
            ThinkProcessDocument adopted = thinkProcessService.findByName(
                    session.getTenantId(), session.getSessionId(), CHAT_PROCESS_NAME)
                    .orElseThrow(() -> new IllegalStateException(
                            "Concurrent chat-process create reported clash but the "
                                    + "process is gone — sessionId='" + session.getSessionId() + "'"));
            sessionService.setChatProcessId(session.getSessionId(), adopted.getId());
            return Optional.of(adopted);
        }

        // Link first so the chat-process is visible to other code paths
        // even if engine.start throws below.
        sessionService.setChatProcessId(session.getSessionId(), fresh.getId());

        // Apply the lifecycle config from the bootstrap-recipe's
        // profile.session block, before engines start. Worker spawns
        // ignore their session block (see specification/session-lifecycle.md §6).
        if (applied != null && applied.sessionLifecycleConfig() != null) {
            sessionService.applyLifecycleConfig(
                    session.getSessionId(), applied.sessionLifecycleConfig());
        }
        // Bootstrap is now considered complete; flip session INIT → IDLE
        // before the chat engine's first turn fires.
        sessionService.markBootstrapped(session.getSessionId());

        try {
            laneScheduler.submit(fresh.getId(), () -> {
                thinkEngineService.start(fresh);
                return null;
            }).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted starting chat engine for session '"
                            + session.getSessionId() + "'", ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new IllegalStateException(
                    "Chat engine start failed for session '"
                            + session.getSessionId() + "': " + cause.getMessage(), cause);
        }

        log.info("Bootstrapped chat-process id='{}' engine='{}' session='{}'",
                fresh.getId(), engine.name(), session.getSessionId());
        return thinkProcessService.findById(fresh.getId());
    }

    private boolean isHubProject(String tenantId, String projectName) {
        if (projectName == null || projectName.isBlank()) {
            return false;
        }
        return projectService.findByTenantAndName(tenantId, projectName)
                .map(p -> p.getKind() == ProjectKind.SYSTEM)
                .orElse(false);
    }
}
