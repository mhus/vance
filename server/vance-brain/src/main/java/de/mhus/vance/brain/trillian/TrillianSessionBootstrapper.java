package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.session.DisconnectPolicy;
import de.mhus.vance.api.session.IdlePolicy;
import de.mhus.vance.api.session.SessionLifecycleConfig;
import de.mhus.vance.api.session.SuspendPolicy;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Pairs a Trillian-Control chat-process with its own
 * Trillian-User session (v2 architecture).
 *
 * <p>Called by {@link de.mhus.vance.brain.session.SessionChatBootstrapper}
 * right after the session chat-process is created. A no-op unless the
 * chat-process's recipe is {@value #CONTROL_RECIPE_NAME}.
 *
 * <p>On a Trillian-Control session this:
 * <ol>
 *   <li>Mints a fresh {@code _trillian-0XXXX} service account.</li>
 *   <li>Resolves the {@value #USER_RECIPE_NAME} recipe to get the
 *       configured user-engine + params.</li>
 *   <li>Creates a <b>separate session</b> owned by the new service-
 *       account, in the same project as Control, marked
 *       {@code system=true} and using a headless profile (no bound
 *       WS connection).</li>
 *   <li>Spawns the {@value #USER_PROCESS_NAME} primary process inside
 *       that user-session, with {@code parentProcessId} pointing at
 *       the control-process (cross-session parent — the standard
 *       {@code ParentNotificationListener} relays terminal events
 *       across the session boundary).</li>
 *   <li>Writes {@code peerProcessId} + {@code peerSessionId} +
 *       {@code trillianUserName} into both processes'
 *       {@code engineParams} so the control-tools and user-loop
 *       tools find each other directly.</li>
 *   <li>Starts the user-process on its own lane so it's ready to
 *       receive the first {@code task_request} event.</li>
 * </ol>
 *
 * <p>Idempotent: if the control-process already has
 * {@code peerSessionId} set, the bootstrap is skipped.
 *
 * <p>See {@code planning/trillian-engine.md} §2 + §6 + §10.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianSessionBootstrapper {

    /**
     * Engine name carried by all Trillian-Control processes regardless
     * of Nature. Detect on this rather than recipe name so the
     * {@code trillian} default-alias recipe + future Nature recipes
     * ({@code trillian-a} etc.) all trigger the same bootstrap.
     */
    public static final String CONTROL_ENGINE_NAME = "trillian-control";

    /**
     * Recipe-name prefix for the User-Loop recipe family. The
     * bootstrap resolves {@code USER_RECIPE_PREFIX + nature} as the
     * concrete recipe to spawn (e.g. {@code trillian-user-0} for
     * Nature-0).
     */
    public static final String USER_RECIPE_PREFIX = "trillian-user-";

    /** Default Nature when a control process doesn't pin one in engineParams. */
    public static final String DEFAULT_NATURE = "0";

    public static final String USER_PROCESS_NAME = "trillian-user-loop";

    /**
     * Recipe name used by the Arthur-bridge tools
     * ({@code trillian_session_create}) to spawn a fresh Trillian
     * session at the current default Nature. Equivalent to the
     * {@code --recipe trillian} foot invocation. Resolves through
     * the standard cascade — the bundled {@code trillian.yaml}
     * mirrors the current default Nature recipe.
     */
    public static final String DEFAULT_CONTROL_RECIPE = "trillian";

    public static final String PARAM_PEER_PROCESS_ID = "peerProcessId";
    public static final String PARAM_PEER_SESSION_ID = "peerSessionId";
    public static final String PARAM_TRILLIAN_USER_NAME = "trillianUserName";

    /** engineParams key for the Trillian Nature pinned by the recipe. */
    public static final String PARAM_NATURE = "nature";

    /** Profile slot used for the headless Trillian-User session. */
    public static final String HEADLESS_PROFILE = "headless";

    /** Nature-0 prefix: {@code _trillian-0} + 4 random digits. */
    private static final String NATURE_0_PREFIX = "_trillian-0";
    private static final int MAX_NAMING_ATTEMPTS = 16;
    private static final int NATURE_0_SUFFIX_BOUND = 10_000;

    private static final String CLIENT_NAME = "trillian-bootstrap";
    private static final String CLIENT_VERSION = "0.1.0";

    private final UserService userService;
    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final RecipeResolver recipeResolver;
    private final LaneScheduler laneScheduler;
    private final SecureRandom random = new SecureRandom();

    /**
     * No-op when {@code controlProcess} is not a Trillian-Control;
     * otherwise pairs it with a fresh user-session.
     *
     * <p>Recoverable: failure here is logged but doesn't throw —
     * the control-session is still alive for the human; a future
     * re-bootstrap or manual cleanup is the recovery path.
     */
    public void maybeBootstrap(
            SessionDocument controlSession,
            @Nullable ThinkProcessDocument controlProcess) {
        if (controlProcess == null) {
            return;
        }
        // Detect by engine, not recipe name — that way the trillian
        // default-alias recipe and any future Nature recipes
        // (trillian-a etc.) all funnel through this bootstrap.
        if (!CONTROL_ENGINE_NAME.equals(controlProcess.getThinkEngine())) {
            return;
        }

        // Idempotency: peerSessionId already wired?
        Object peerSessRaw = controlProcess.getEngineParams() == null
                ? null : controlProcess.getEngineParams().get(PARAM_PEER_SESSION_ID);
        if (peerSessRaw instanceof String peerSess && !peerSess.isBlank()) {
            log.debug("Trillian user-session '{}' already wired for control id='{}' — adopting",
                    peerSess, controlProcess.getId());
            return;
        }

        try {
            doBootstrap(controlSession, controlProcess);
        } catch (RuntimeException e) {
            log.error("Trillian bootstrap failed for control session '{}'; "
                            + "control-process stays but user-session is missing",
                    controlSession.getSessionId(), e);
        }
    }

    private void doBootstrap(
            SessionDocument controlSession,
            ThinkProcessDocument controlProcess) {
        // 1. Mint the service account.
        String trillianName = pickUniqueTrillianName(controlSession.getTenantId());
        String suffix = trillianName.substring("_trillian-".length());
        UserDocument trillian = userService.createServiceAccount(
                controlSession.getTenantId(),
                trillianName,
                /*passwordHash*/ null,
                /*title*/ "Trillian " + suffix,
                /*email*/ null);
        log.info("Minted Trillian service-account '{}' id='{}' for control session '{}'",
                trillian.getName(), trillian.getId(), controlSession.getSessionId());

        // 2. Resolve the user recipe — pick the Nature variant that
        //    matches what the control process was spawned with. The
        //    Nature lives in controlProcess.engineParams.nature; we
        //    fall back to DEFAULT_NATURE when the recipe didn't pin
        //    one (legacy / unannotated recipes).
        String nature = readNature(controlProcess);
        String userRecipeName = USER_RECIPE_PREFIX + nature;
        AppliedRecipe applied = recipeResolver.applyDefaulting(
                controlSession.getTenantId(),
                controlSession.getProjectId(),
                userRecipeName,
                HEADLESS_PROFILE,
                /*callerParams*/ null);
        final String userRecipeNameFinal = userRecipeName;
        ThinkEngine engine = thinkEngineService.resolve(applied.engine())
                .orElseThrow(() -> new IllegalStateException(
                        "Recipe '" + userRecipeNameFinal
                                + "' references unknown engine '" + applied.engine()
                                + "' — known: " + thinkEngineService.listEngines()));

        // 3. Create the headless user-session owned by the service-
        //    account. system=true marks it as auto-managed (UI may
        //    filter system sessions in the user's session list).
        SessionDocument userSession = sessionService.create(
                controlSession.getTenantId(),
                trillian.getId(),
                controlSession.getProjectId(),
                /*displayName*/ "Trillian-User " + suffix,
                /*profile*/ HEADLESS_PROFILE,
                CLIENT_VERSION,
                CLIENT_NAME,
                /*system*/ true);
        log.info("Trillian user-session created id='{}' owner='{}' project='{}'",
                userSession.getSessionId(), trillian.getId(), controlSession.getProjectId());

        // 4. Spawn the primary user-process in the user-session.
        //    parentProcessId = controlProcess.id makes terminal events
        //    flow back through the standard
        //    ParentNotificationListener path even across the session
        //    boundary.
        Map<String, Object> userParams = new LinkedHashMap<>();
        if (applied.params() != null) {
            userParams.putAll(applied.params());
        }
        userParams.put(PARAM_PEER_PROCESS_ID, controlProcess.getId());
        userParams.put(PARAM_PEER_SESSION_ID, controlSession.getSessionId());
        userParams.put(PARAM_TRILLIAN_USER_NAME, trillianName);

        ThinkProcessDocument userProc;
        try {
            userProc = thinkProcessService.create(
                    controlSession.getTenantId(),
                    controlSession.getProjectId(),
                    userSession.getSessionId(),
                    USER_PROCESS_NAME,
                    engine.name(),
                    engine.version(),
                    /*title*/ "Trillian User Loop " + suffix,
                    /*goal*/ null,
                    /*parentProcessId*/ controlProcess.getId(),
                    userParams,
                    applied.name(),
                    applied.promptOverride(),
                    applied.promptOverrideAppend(),
                    applied.promptMode(),
                    applied.dataRelayCorrection(),
                    applied.effectiveAllowedTools(),
                    applied.connectionProfile(),
                    applied.defaultActiveSkills(),
                    applied.allowedSkills() == null
                            ? null : Set.copyOf(applied.allowedSkills()));
        } catch (ThinkProcessService.ThinkProcessAlreadyExistsException race) {
            log.warn("Concurrent Trillian-User process create in session '{}'; aborting bootstrap",
                    userSession.getSessionId());
            return;
        }

        // Link the user-process as the user-session's chatProcessId so
        // session-close cascades reach it via the standard path.
        sessionService.setChatProcessId(userSession.getSessionId(), userProc.getId());

        // Pin daemon-style lifecycle on the user-session: never
        // auto-suspend, keep across disconnects (it has no
        // connection anyway), keep-on-suspend for the standard
        // 24h. Redundant with safeDefault today, but explicit —
        // protects against future changes to safeDefault and
        // documents intent at the spawn site.
        sessionService.applyLifecycleConfig(
                userSession.getSessionId(),
                SessionLifecycleConfig.builder()
                        .onDisconnect(DisconnectPolicy.KEEP_OPEN)
                        .onIdle(IdlePolicy.NONE)
                        .onSuspend(SuspendPolicy.KEEP)
                        .build());
        sessionService.markBootstrapped(userSession.getSessionId());

        // 5. Record cross-references on the control-process too.
        ThinkProcessDocument refreshedControl = thinkProcessService.findById(controlProcess.getId())
                .orElse(controlProcess);
        Map<String, Object> controlParams = new LinkedHashMap<>();
        if (refreshedControl.getEngineParams() != null) {
            controlParams.putAll(refreshedControl.getEngineParams());
        }
        controlParams.put(PARAM_PEER_PROCESS_ID, userProc.getId());
        controlParams.put(PARAM_PEER_SESSION_ID, userSession.getSessionId());
        controlParams.put(PARAM_TRILLIAN_USER_NAME, trillianName);
        thinkProcessService.replaceEngineParams(controlProcess.getId(), controlParams);

        // 6. Start the user-process on its own lane.
        try {
            laneScheduler.submit(userProc.getId(), () -> {
                thinkEngineService.start(userProc);
                return null;
            }).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted starting Trillian user-process id='"
                            + userProc.getId() + "'", ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new IllegalStateException(
                    "Trillian user-process start failed: " + cause.getMessage(), cause);
        }

        log.info("Bootstrapped Trillian pair: control id='{}' session='{}' / "
                        + "user id='{}' session='{}' trillianUser='{}'",
                controlProcess.getId(), controlSession.getSessionId(),
                userProc.getId(), userSession.getSessionId(), trillianName);
    }

    /**
     * Picks a fresh {@code _trillian-0XXXX} name that doesn't collide
     * in the tenant. Up to {@value #MAX_NAMING_ATTEMPTS} retries.
     */
    private String pickUniqueTrillianName(String tenantId) {
        for (int i = 0; i < MAX_NAMING_ATTEMPTS; i++) {
            String name = NATURE_0_PREFIX
                    + String.format("%04d", random.nextInt(NATURE_0_SUFFIX_BOUND));
            if (!userService.existsByTenantAndName(tenantId, name)) {
                return name;
            }
        }
        throw new IllegalStateException(
                "Could not find unique Trillian-Nature-0 name in tenant '"
                        + tenantId + "' after " + MAX_NAMING_ATTEMPTS + " attempts");
    }

    /**
     * Reads {@code engineParams.nature} off the control process,
     * falling back to {@link #DEFAULT_NATURE} when missing or empty.
     */
    public static String readNature(ThinkProcessDocument controlProcess) {
        if (controlProcess.getEngineParams() == null) {
            return DEFAULT_NATURE;
        }
        Object raw = controlProcess.getEngineParams().get(PARAM_NATURE);
        if (raw instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return DEFAULT_NATURE;
    }

    /** Lookup the user-session id wired to this control process. */
    public Optional<String> findUserSessionId(ThinkProcessDocument controlProcess) {
        if (controlProcess.getEngineParams() == null) {
            return Optional.empty();
        }
        Object v = controlProcess.getEngineParams().get(PARAM_PEER_SESSION_ID);
        return v instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
    }
}
