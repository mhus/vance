package de.mhus.vance.brain.agrajag;

import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.brain.agrajag.engine.AgrajagEngine;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService.ThinkProcessAlreadyExistsException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Lazy-bootstraps the per-project {@code _agrajag} system session and
 * spawns a {@link AgrajagEngine}-driven think-process on it for each
 * UNCLEAR triage decision coming out of {@code AgrajagChecker}.
 *
 * <p>The system session is owned by the {@code _system} pseudo user
 * (created on demand), flagged {@code system=true}, and never shows up
 * in user-facing listings. Diagnostic processes run as direct children
 * of the session — they live, do their work in a single turn, and
 * close with {@code DONE}.
 *
 * <p>Failure to spawn is logged and swallowed; the original tool error
 * always reaches the LLM regardless of whether Agrajag could be launched.
 *
 * <p>Like every other spawn path, the process is configured through
 * {@link RecipeResolver#applyDefaulting} against the {@code agrajag}
 * recipe: the failure context assembled here is passed as caller-params
 * and merged <em>over</em> the recipe defaults, so the recipe stays the
 * source of truth for {@code model}, {@code maxProbes} and
 * {@code aiScope}. Without that step the recipe name would be a label
 * only and the engine would silently fall back to
 * {@code ai.default.provider}/{@code ai.default.model}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgrajagSpawnerService {

    public static final String AGRAJAG_SESSION_NAME = "_agrajag";
    public static final String AGRAJAG_SYSTEM_USER = "_system";

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final LaneScheduler laneScheduler;
    /** Lazy provider — ThinkEngineService transitively reaches us via the dispatcher cycle. */
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    /**
     * Lazy for the same reason: RecipeResolver reaches ThinkEngineService,
     * which reaches the ToolDispatcher, which reaches AgrajagChecker → us.
     */
    private final ObjectProvider<RecipeResolver> recipeResolverProvider;

    /**
     * Spawn a Agrajag diagnostic process for the given tool error. Idempotent
     * at the session-level (creates {@code _agrajag} lazily); processes are
     * one-shot per call.
     */
    public void spawnDiagnosis(
            String tenantId,
            @Nullable String projectId,
            String toolName,
            ToolHealthScope scope,
            String scopeId,
            String errorSignature,
            @Nullable String originatingUserId,
            @Nullable String note) {

        try {
            SessionDocument session = ensureAgrajagSession(tenantId,
                    projectId == null ? "" : projectId);

            Map<String, Object> callerParams = new LinkedHashMap<>();
            callerParams.put("toolName", toolName);
            callerParams.put("scope", scope.name());
            callerParams.put("scopeId", scopeId);
            callerParams.put("errorSignature", errorSignature);
            callerParams.put("originatingUserId", originatingUserId);
            if (note != null) callerParams.put("note", note);

            // Recipe first — a broken project-level override of the
            // agrajag recipe aborts the spawn (caught below) instead of
            // silently degrading to raw caller-params, which would put us
            // back on ai.default.* with no model of our own.
            AppliedRecipe applied = applyRecipe(tenantId, projectId, callerParams);

            String processName = "diagnose-" + toolName + "-"
                    + UUID.randomUUID().toString().substring(0, 8);

            ThinkProcessDocument process;
            try {
                process = thinkProcessService.create(
                        tenantId,
                        projectId,
                        session.getSessionId(),
                        processName,
                        AgrajagEngine.NAME,
                        AgrajagEngine.VERSION,
                        /*title*/ "Agrajag: " + toolName,
                        /*goal*/ "Diagnose tool-error signature='" + errorSignature + "'",
                        /*parentProcessId*/ null,
                        applied.params(),
                        /*recipeName*/ applied.name(),
                        // No promptOverride: Agrajag's system prompt is
                        // engine-owned (fixed probe/diagnose loop with a
                        // strict output schema), so the recipe carries none.
                        /*promptOverride*/ null,
                        /*promptMode*/ null,
                        applied.effectiveAllowedTools());
            } catch (ThinkProcessAlreadyExistsException dup) {
                // Should not happen with the UUID suffix, but be defensive.
                log.debug("Agrajag spawn name clash — skipping: {}", dup.getMessage());
                return;
            }

            ThinkEngineService engines = thinkEngineServiceProvider.getObject();
            laneScheduler.submit(process.getId(), () -> {
                try {
                    engines.start(process);
                } catch (RuntimeException e) {
                    log.warn("Agrajag engine.start failed id='{}': {}",
                            process.getId(), e.toString());
                }
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Agrajag spawnDiagnosis failed tool='{}' tenant='{}' project='{}': {}",
                    toolName, tenantId, projectId, e.toString());
        }
    }

    /**
     * Merge the failure context over the {@code agrajag} recipe defaults.
     * Caller-params win per key (they carry the incident, the recipe
     * carries the configuration) — none of the two sets overlap today.
     *
     * <p>The recipe cascade stays project-aware on purpose: an operator
     * may tune probe budgets per project. Only the resulting AI
     * configuration is tenant-pinned, and that happens later via
     * {@code params.aiScope} when the chat is built.
     *
     * <p>The engine is <b>not</b> taken from the recipe. This spawner
     * <em>is</em> the tool-health service; a project-level override that
     * repoints the recipe at another engine would silently turn a
     * diagnosis into something else. We log it and stay on
     * {@link AgrajagEngine}.
     */
    private AppliedRecipe applyRecipe(
            String tenantId,
            @Nullable String projectId,
            Map<String, Object> callerParams) {
        AppliedRecipe applied = recipeResolverProvider.getObject().applyDefaulting(
                tenantId, projectId,
                /*recipeName*/ AgrajagEngine.NAME,
                // No connection profile: nothing is connected to a
                // diagnostic process, so the recipe's "default" block
                // applies. Open-string semantics make this a no-op for the
                // bundled recipe, which carries no profiles at all.
                /*connectionProfile*/ null,
                callerParams);
        if (!AgrajagEngine.NAME.equals(applied.engine())) {
            log.warn("Agrajag recipe '{}' (source={}) declares engine '{}' — ignoring, "
                            + "diagnostics always run on '{}'",
                    applied.name(), applied.source(), applied.engine(), AgrajagEngine.NAME);
        }
        return applied;
    }

    /** Lazy-create the per-project _agrajag system session. Thread-safe via Mongo upsert semantics. */
    private SessionDocument ensureAgrajagSession(String tenantId, String projectId) {
        return sessionService.findSystemSession(tenantId, projectId, AGRAJAG_SESSION_NAME)
                .orElseGet(() -> {
                    SessionDocument fresh = sessionService.create(
                            tenantId, AGRAJAG_SYSTEM_USER, projectId,
                            AGRAJAG_SESSION_NAME,
                            Profiles.WEB,            // profile is informational here
                            /*clientVersion*/ "agrajag/" + AgrajagEngine.VERSION,
                            /*clientName*/ "agrajag-spawner",
                            /*system*/ true);
                    sessionService.markBootstrapped(fresh.getSessionId());
                    log.info("Bootstrapped _agrajag system session for tenant='{}' project='{}' sessionId='{}'",
                            tenantId, projectId, fresh.getSessionId());
                    // markBootstrapped flips INIT → IDLE so the next find sees the active state.
                    return sessionService.findSystemSession(tenantId, projectId, AGRAJAG_SESSION_NAME)
                            .orElse(fresh);
                });
    }
}
