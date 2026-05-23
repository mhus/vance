package de.mhus.vance.brain.fook;

import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.brain.fook.engine.FookEngine;
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
 * Lazy-bootstraps the per-project {@code _fook} system session and
 * spawns a {@link FookEngine}-driven think-process on it for each
 * UNCLEAR triage decision coming out of {@code FookChecker}.
 *
 * <p>The system session is owned by the {@code _system} pseudo user
 * (created on demand), flagged {@code system=true}, and never shows up
 * in user-facing listings. Diagnostic processes run as direct children
 * of the session — they live, do their work in a single turn, and
 * close with {@code DONE}.
 *
 * <p>Failure to spawn is logged and swallowed; the original tool error
 * always reaches the LLM regardless of whether Fook could be launched.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FookSpawnerService {

    public static final String FOOK_SESSION_NAME = "_fook";
    public static final String FOOK_SYSTEM_USER = "_system";

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final LaneScheduler laneScheduler;
    /** Lazy provider — ThinkEngineService transitively reaches us via the dispatcher cycle. */
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;

    /**
     * Spawn a Fook diagnostic process for the given tool error. Idempotent
     * at the session-level (creates {@code _fook} lazily); processes are
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
            SessionDocument session = ensureFookSession(tenantId,
                    projectId == null ? "" : projectId);

            Map<String, Object> engineParams = new LinkedHashMap<>();
            engineParams.put("toolName", toolName);
            engineParams.put("scope", scope.name());
            engineParams.put("scopeId", scopeId);
            engineParams.put("errorSignature", errorSignature);
            engineParams.put("originatingUserId", originatingUserId);
            if (note != null) engineParams.put("note", note);

            String processName = "diagnose-" + toolName + "-"
                    + UUID.randomUUID().toString().substring(0, 8);

            ThinkProcessDocument process;
            try {
                process = thinkProcessService.create(
                        tenantId,
                        projectId,
                        session.getSessionId(),
                        processName,
                        FookEngine.NAME,
                        FookEngine.VERSION,
                        /*title*/ "Fook: " + toolName,
                        /*goal*/ "Diagnose tool-error signature='" + errorSignature + "'",
                        /*parentProcessId*/ null,
                        engineParams,
                        /*recipeName*/ FookEngine.NAME,
                        /*promptOverride*/ null,
                        /*promptMode*/ null,
                        /*allowedToolsOverride*/ null);
            } catch (ThinkProcessAlreadyExistsException dup) {
                // Should not happen with the UUID suffix, but be defensive.
                log.debug("Fook spawn name clash — skipping: {}", dup.getMessage());
                return;
            }

            ThinkEngineService engines = thinkEngineServiceProvider.getObject();
            laneScheduler.submit(process.getId(), () -> {
                try {
                    engines.start(process);
                } catch (RuntimeException e) {
                    log.warn("Fook engine.start failed id='{}': {}",
                            process.getId(), e.toString());
                }
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Fook spawnDiagnosis failed tool='{}' tenant='{}' project='{}': {}",
                    toolName, tenantId, projectId, e.toString());
        }
    }

    /** Lazy-create the per-project _fook system session. Thread-safe via Mongo upsert semantics. */
    private SessionDocument ensureFookSession(String tenantId, String projectId) {
        return sessionService.findSystemSession(tenantId, projectId, FOOK_SESSION_NAME)
                .orElseGet(() -> {
                    SessionDocument fresh = sessionService.create(
                            tenantId, FOOK_SYSTEM_USER, projectId,
                            FOOK_SESSION_NAME,
                            Profiles.WEB,            // profile is informational here
                            /*clientVersion*/ "fook/" + FookEngine.VERSION,
                            /*clientName*/ "fook-spawner",
                            /*system*/ true);
                    sessionService.markBootstrapped(fresh.getSessionId());
                    log.info("Bootstrapped _fook system session for tenant='{}' project='{}' sessionId='{}'",
                            tenantId, projectId, fresh.getSessionId());
                    // markBootstrapped flips INIT → IDLE so the next find sees the active state.
                    return sessionService.findSystemSession(tenantId, projectId, FOOK_SESSION_NAME)
                            .orElse(fresh);
                });
    }
}
