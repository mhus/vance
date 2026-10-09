package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.session.DisconnectPolicy;
import de.mhus.vance.api.session.IdlePolicy;
import de.mhus.vance.api.session.SessionLifecycleConfig;
import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.session.SuspendPolicy;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.permission.PermissionBootstrap;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.trillian.TrillianProcessKeys;
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
import org.springframework.beans.factory.ObjectProvider;
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
 *   <li>Checks the kill switch and the loop's model gate — before anything
 *       is minted, so a refusal leaves nothing behind.</li>
 *   <li>Mints (or adopts) the {@code _trillian-<nature>-<instance>} service
 *       account, ensures its hub {@code _user_<account>} and grants it
 *       project-ADMIN on the control session's project.</li>
 *   <li>Creates a <b>separate session</b> owned by the service account in
 *       its hub, marked {@code system=true} and using a headless profile
 *       (no bound WS connection).</li>
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
     * ({@code trillian-alpha} etc.) all trigger the same bootstrap.
     *
     * <p>Value owned by {@link TrillianProcessKeys}: it is persisted on the
     * process row and read by the project-maintenance handler, which runs in a
     * process without the brain on its classpath.
     */
    public static final String CONTROL_ENGINE_NAME = TrillianProcessKeys.CONTROL_ENGINE_NAME;

    /**
     * Recipe-name prefix for the User-Loop recipe family. The
     * bootstrap resolves {@code USER_RECIPE_PREFIX + nature} as the
     * concrete recipe to spawn (e.g. {@code trillian-user-void} for
     * Nature void).
     */
    public static final String USER_RECIPE_PREFIX = "trillian-user-";

    /**
     * Recipe-name prefix for the per-task worker family. Derived from the
     * Nature exactly like the user-loop recipe, so a new Nature brings its
     * own worker without anyone editing a prompt.
     */
    public static final String WORKER_RECIPE_PREFIX = "trillian-worker-";

    /**
     * engineParams key on the user-loop carrying the resolved worker
     * recipe name. The loop's prompt reads it as
     * {@code {{ params.workerRecipe }}} instead of naming a recipe in
     * prose — a literal in the prompt meant every Nature had to fork the
     * prompt just to change one word, and a model that mistyped it
     * spawned nothing.
     */
    public static final String PARAM_WORKER_RECIPE = "workerRecipe";

    /** Default Nature when a control process doesn't pin one in engineParams. */
    public static final String DEFAULT_NATURE = "void";

    public static final String USER_PROCESS_NAME = "trillian-user-loop";

    /**
     * Recipe the Arthur-bridge tool {@code trillian_session_create} spawns
     * when the caller names no recipe. Resolves through the standard
     * cascade, so a tenant can override the document.
     *
     * <p><b>It names a Nature, and that is the point.</b> This used to be
     * the {@code trillian} alias, which mirrored "whatever Nature is
     * current" — a reasonable idea while {@code void} was the only one.
     * With {@code adam} shipped the Natures differ in what they *are*
     * (adam's attributes outlive a restart), so a caller that got the
     * "current" one would silently get a different agent on the next
     * upgrade. Baseline is the honest default for a tool that was not
     * asked which Nature it wants; anything else is a choice, and a choice
     * belongs to whoever makes it.
     */
    public static final String DEFAULT_CONTROL_RECIPE = "trillian-void";

    public static final String PARAM_PEER_PROCESS_ID = "peerProcessId";
    public static final String PARAM_PEER_SESSION_ID = "peerSessionId";
    /** See {@link TrillianProcessKeys#PARAM_TRILLIAN_USER_NAME} — one authority. */
    public static final String PARAM_TRILLIAN_USER_NAME = TrillianProcessKeys.PARAM_TRILLIAN_USER_NAME;

    /**
     * engineParams key on a control process that parks the outgoing
     * worker's attributes across a reactivate. The attributes themselves
     * live on the worker process and would die with it; this is where
     * {@code TrillianSessionLifecycleHook} leaves them for the next
     * bootstrap to pick up.
     */
    public static final String PARAM_CARRIED_ATTRIBUTES = "carriedWorkerAttributes";
    /** Engine-param override: why the loop is currently suppressed (model gate). */
    public static final String PARAM_GATE_DENIED = "trillianGateDenied";

    /**
     * engineParams key on a control process overriding the derived
     * user-loop recipe name. Same shape as {@link #PARAM_WORKER_RECIPE}: a
     * recipe that wants a different working style (direct tools, a
     * collab-heavy loop) pins it here instead of forking the derivation.
     */
    public static final String PARAM_USER_RECIPE = "userRecipe";

    /** engineParams key for the Trillian Nature pinned by the recipe. */
    public static final String PARAM_NATURE = "nature";

    /** Profile slot used for the headless Trillian-User session. */
    public static final String HEADLESS_PROFILE = "headless";

    /**
     * Account naming: {@code _trillian-<nature>-<instance>}, e.g.
     * {@code _trillian-void-1535} or {@code _trillian-alpha-4711}.
     *
     * <p>Three parts, so a Nature id is not limited to one character and
     * can say what it is ({@code fast}, {@code alpha}) instead of needing
     * a legend. The separator also makes the name decomposable — with
     * {@code _trillian-a1535} one had to know that the first character is
     * the Nature and the rest the instance, a rule that lived only in a
     * comment.
     *
     * <p>The instance part is random rather than sequential: a counter
     * would need coordination across pods for a value nobody reads.
     */
    private static final String ACCOUNT_PREFIX = "_trillian-";

    private static final int MAX_NAMING_ATTEMPTS = 16;
    private static final int INSTANCE_BOUND = 10_000;

    /**
     * How long a bootstrap may hold its lease before another pod may take it
     * over — generously above a bootstrap's real length (a few writes and a
     * process start), so only a dead holder is ever overtaken.
     */
    private static final java.time.Duration BOOTSTRAP_LEASE_TTL = java.time.Duration.ofMinutes(2);

    private static final String CLIENT_NAME = "trillian-bootstrap";
    private static final String CLIENT_VERSION = "0.1.0";

    private final UserService userService;
    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final RecipeResolver recipeResolver;
    private final LaneScheduler laneScheduler;
    private final ChatMessageService chatMessageService;
    private final de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry natureRegistry;
    private final de.mhus.vance.shared.home.HomeBootstrapService homeBootstrapService;
    private final de.mhus.vance.shared.project.ProjectService projectService;

    /** Cross-pod lease: one bootstrap per control process at a time. */
    private final TrillianWakeupClaimService claimService;

    /** P2/D8: relaxed model gate for the unattended loop. */
    private final TrillianModelGate modelGate;

    /** P2/D8: the kill switch — no new user-loop while {@code trillian.enabled} is off. */
    private final TrillianActivationGate activationGate;

    /**
     * Present only when a grant-storing permission provider is loaded
     * (simple-auth); {@code ifAvailable} keeps the seed a no-op under an
     * external governor that manages rights elsewhere.
     */
    private final ObjectProvider<PermissionBootstrap> permissionBootstrapProvider;

    private final SecureRandom random = new SecureRandom();

    /**
     * No-op when {@code controlProcess} is not a Trillian-Control;
     * otherwise pairs it with a fresh user-session.
     *
     * <p>Recoverable: failure here is logged but doesn't throw —
     * the control-session is still alive for the human; a future
     * re-bootstrap or manual cleanup is the recovery path.
     *
     * <p>Serialised per control process <b>across pods</b>: the control
     * engine's first turn, the command handler and the session-create path
     * all bootstrap the pair, and the wiring params are check-then-act. A
     * caller that does not get the lease leaves the build to its holder.
     */
    public void maybeBootstrap(SessionDocument controlSession, @Nullable ThinkProcessDocument controlProcess) {
        if (controlProcess == null || controlProcess.getId() == null) {
            return;
        }
        // Detect by engine, not recipe name — that way the trillian
        // default-alias recipe and any future Nature recipes
        // (trillian-alpha etc.) all funnel through this bootstrap.
        if (!CONTROL_ENGINE_NAME.equals(controlProcess.getThinkEngine())) {
            return;
        }
        String lease = bootstrapLeaseKey(controlProcess);
        String leaseToken = claimService.acquireLease(lease, BOOTSTRAP_LEASE_TTL);
        if (leaseToken == null) {
            log.debug("Trillian bootstrap of control id='{}' is running elsewhere — skipping", controlProcess.getId());
            return;
        }
        try {
            bootstrapLeased(controlSession, controlProcess.getId());
        } finally {
            claimService.releaseLease(lease, leaseToken);
        }
    }

    /**
     * The bootstrap proper, under the lease. Decides on a freshly read
     * process: the document a caller holds may predate the wiring another
     * caller wrote while this one waited.
     */
    private void bootstrapLeased(SessionDocument controlSession, String controlProcessId) {
        ThinkProcessDocument controlProcess =
                thinkProcessService.findById(controlProcessId).orElse(null);
        if (controlProcess == null) {
            return;
        }
        // Idempotency: peerSessionId already wired?
        Object peerSessRaw = controlProcess.getEngineParams() == null
                ? null
                : controlProcess.getEngineParams().get(PARAM_PEER_SESSION_ID);
        if (peerSessRaw instanceof String peerSess && !peerSess.isBlank()) {
            // Adopt only a pair that is actually alive — a stale wire to a
            // closed loop session must rebuild, not adopt.
            if (sessionAlive(peerSess)) {
                log.debug(
                        "Trillian user-session '{}' already wired for control id='{}' — adopting",
                        peerSess,
                        controlProcess.getId());
                return;
            }
            log.debug(
                    "Trillian user-session '{}' wired on control id='{}' is gone — rebuilding",
                    peerSess,
                    controlProcess.getId());
            unwirePeer(controlProcess.getId(), /*carriedAttributes*/ null);
            controlProcess = thinkProcessService.findById(controlProcessId).orElse(controlProcess);
        }

        try {
            doBootstrap(controlSession, controlProcess);
        } catch (RuntimeException e) {
            log.error(
                    "Trillian bootstrap failed for control session '{}'; "
                            + "control-process stays but user-session is missing",
                    controlSession.getSessionId(),
                    e);
        }
    }

    private void doBootstrap(SessionDocument controlSession, ThinkProcessDocument controlProcess) {
        // 0a. Kill switch: while trillian.enabled is off, no new user-loop
        //     is built — checked before anything is minted so a disabled
        //     Trillian leaves no account, no home and no session behind.
        //     The hub layer of the cascade is consulted when a previous
        //     incarnation's account exists (reactivate path); a freshly
        //     picked name has no hub yet. A suppressed loop retries on the
        //     next control turn (suppressLoop dedups the announcement).
        String previousAccount = previousAccountOf(controlSession).orElse(null);
        if (!activationGate.loopsEnabled(
                controlSession.getTenantId(), previousAccount, controlSession.getProjectId())) {
            suppressLoop(controlSession, controlProcess, TrillianActivationGate.refusalMessage());
            return;
        }
        // 0. Placement: the user-loop lives in the Trillian's own hub
        //    (_user_<trillian>), which is podless by design. Self-waking no
        //    longer assumes a home pod — the heartbeat claims its wake slot
        //    cross-pod (see TrillianHeartbeatTick), so a podless home is as
        //    workable as a placed one. No refusal here (D2, 2026-10-04).

        // 1. Which Nature this pair runs. It lives in
        //    controlProcess.engineParams.nature; DEFAULT_NATURE covers a
        //    recipe that didn't pin one. Read first, because both the
        //    account name and the two follow-up recipes derive from it.
        String nature = readNature(controlProcess);

        // 2. Reuse the account of a previous incarnation, or pick a fresh
        //    name. After a reactivate (or a rebuild of the fluid loop) a
        //    process of this session remembers which account this
        //    session's Trillian was. Reusing it is what makes archiving
        //    reversible: same identity, same attributes, same grants —
        //    a Trillian that came back rather than a stranger wearing
        //    its session. Picking a name writes nothing: every refusal
        //    below happens before anything is minted.
        final boolean adopted = previousAccount != null;
        final String trillianName =
                adopted ? previousAccount : pickUniqueTrillianName(controlSession.getTenantId(), nature);
        final String homeProject = de.mhus.vance.shared.home.HomeBootstrapService.hubProjectName(trillianName);

        // 3. Resolve the user recipe — the Nature variant of the loop.
        String userRecipeName = userRecipeNameOf(controlProcess, nature);
        AppliedRecipe applied = recipeResolver.applyDefaulting(
                controlSession.getTenantId(),
                controlSession.getProjectId(),
                userRecipeName,
                HEADLESS_PROFILE,
                /*callerParams*/ null);
        final String userRecipeNameFinal = userRecipeName;
        // P2/D8: the cost gate for the unattended loop. Relaxed policy
        // (default '*'), and loop-only at that: a refused model must not
        // cost the operator their control session — the loop simply does
        // not start and the chat says why. Self-healing: fix the setting
        // and the next turn builds the loop. Before the mint, so a refusal
        // leaves no account behind (it would otherwise mint one per turn).
        try {
            modelGate.checkLoopModel(
                    controlSession.getTenantId(),
                    trillianName,
                    controlSession.getProjectId(),
                    homeProject,
                    de.mhus.vance.brain.ai.AiModelResolver.parseModelSpec(applied.params()));
        } catch (IllegalStateException denied) {
            suppressLoop(controlSession, controlProcess, denied.getMessage());
            return;
        }
        clearGateSuppression(controlProcess);

        ThinkEngine engine = thinkEngineService
                .resolve(applied.engine())
                .orElseThrow(() -> new IllegalStateException("Recipe '" + userRecipeNameFinal
                        + "' references unknown engine '" + applied.engine()
                        + "' — known: " + thinkEngineService.listEngines()));

        // 2b. Mint — only now that nothing can refuse any more. The name is
        //     recorded on the control process right away: anything that
        //     fails after this point adopts the account on the next attempt
        //     instead of minting another one.
        if (adopted) {
            log.info(
                    "Adopted Trillian service-account '{}' for control session '{}'",
                    trillianName,
                    controlSession.getSessionId());
        } else {
            // The title is a starting point, not an identity: it is what
            // the UI shows, and a human may rename it (//trillian name).
            // The account name never changes, so the two are independent
            // — which is the point of not deriving the display name from
            // the account on the fly.
            UserDocument trillian = userService.createServiceAccount(
                    controlSession.getTenantId(),
                    trillianName,
                    /*passwordHash*/ null,
                    /*title*/ "Trillian " + accountSuffix(trillianName),
                    /*email*/ null);
            recordAccount(controlProcess.getId(), trillianName);
            log.info(
                    "Minted Trillian service-account '{}' id='{}' for control session '{}'",
                    trillian.getName(),
                    trillian.getId(),
                    controlSession.getSessionId());
        }

        // 2c. The Trillian's own home. Every account has one
        //     (`_user_<login>`) — the hub is where the loop session lives,
        //     where its attributes and schedules are filed, and what outlives
        //     a merely archived control session. ensureHome is idempotent, so
        //     an adopted account adopts its hub along with it.
        homeBootstrapService.ensureHome(controlSession.getTenantId(), trillianName);

        // 2d. Seed the account's authority. Without a grant the account
        //     exists but may do nothing: every tool call goes through
        //     ToolDispatcher -> PermissionService.enforce(EXECUTE), which
        //     resolves to WRITER-on-project. Scope is deliberately the
        //     control session's project — Trillian stands in for the human
        //     in the project they started it in, and nowhere else. Spawning
        //     into other projects (cross_process_create) therefore stays
        //     denied until someone grants that explicitly. Every time, not
        //     only on mint: grants are idempotent, and an attempt that died
        //     between mint and grant must not leave an account without one.
        permissionBootstrapProvider.ifAvailable(
                pb -> pb.grantProjectAdmin(controlSession.getTenantId(), controlSession.getProjectId(), trillianName));

        // 4. Create the headless user-session owned by the service-
        //    account. system=true marks it as auto-managed (UI may
        //    filter system sessions in the user's session list).
        // SessionDocument.userId is the UserDocument *name*, not the Mongo id
        // — the whole authz chain (ToolDispatcher's SecurityContext, team
        // lookup, grant matching) keys on the name.
        SessionDocument userSession = sessionService.create(
                controlSession.getTenantId(),
                trillianName,
                homeProject,
                /*displayName*/ "Trillian-User " + accountSuffix(trillianName),
                /*profile*/ HEADLESS_PROFILE,
                CLIENT_VERSION,
                CLIENT_NAME,
                /*system*/ true);
        log.info(
                "Trillian user-session created id='{}' owner='{}' project='{}'",
                userSession.getSessionId(),
                trillianName,
                homeProject);

        // 5. Spawn the primary user-process in the user-session.
        //    parentProcessId = controlProcess.id makes terminal events
        //    flow back through the standard
        //    ParentNotificationListener path even across the session
        //    boundary.
        Map<String, Object> userParams = new LinkedHashMap<>();
        if (applied.params() != null) {
            userParams.putAll(applied.params());
        }
        // Attributes an earlier incarnation carried — persona, language,
        // whatever the human set. Adopting the account without them would
        // return the same name wearing nobody.
        Map<String, Object> carried = carriedAttributesOf(controlSession);
        if (carried.isEmpty()) {
            // Nothing parked by a reactivate. A persistent Nature keeps
            // its own copy that outlives the process rows entirely; an
            // ephemeral one returns nothing and the worker starts blank.
            carried = natureRegistry
                    .resolve(nature)
                    .initialAttributes(controlSession.getTenantId(), homeProject, trillianName);
        }
        if (!carried.isEmpty()) {
            userParams.put(TrillianInternalApi.PARAM_ATTRIBUTES, carried);
            log.info(
                    "Restored {} Trillian attribute(s) for control session '{}'",
                    carried.size(),
                    controlSession.getSessionId());
        }
        // A Nature that names its Trillians gets that name onto the
        // account, so the UI shows "Ada" rather than "Trillian adam-4711".
        // Only on mint: after that the title is the human's to change, and
        // rewriting it on every bootstrap would undo their rename.
        if (!adopted) {
            adoptCharacterName(controlSession.getTenantId(), trillianName, carried);
        }
        userParams.put(PARAM_WORKER_RECIPE, WORKER_RECIPE_PREFIX + nature);
        userParams.put(PARAM_PEER_PROCESS_ID, controlProcess.getId());
        userParams.put(PARAM_PEER_SESSION_ID, controlSession.getSessionId());
        userParams.put(PARAM_TRILLIAN_USER_NAME, trillianName);

        ThinkProcessDocument userProc;
        try {
            userProc = thinkProcessService.create(
                    controlSession.getTenantId(),
                    homeProject,
                    userSession.getSessionId(),
                    USER_PROCESS_NAME,
                    engine.name(),
                    engine.version(),
                    /*title*/ "Trillian User Loop " + accountSuffix(trillianName),
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
                    applied.allowedSkills() == null ? null : Set.copyOf(applied.allowedSkills()));
        } catch (ThinkProcessService.ThinkProcessAlreadyExistsException race) {
            log.warn(
                    "Concurrent Trillian-User process create in session '{}'; aborting bootstrap",
                    userSession.getSessionId());
            // The session was created a moment ago and holds nothing; left
            // behind it would be an empty loop session in the hub forever.
            sessionService.delete(userSession.getSessionId());
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

        // 6. Record cross-references on the control-process too.
        ThinkProcessDocument refreshedControl =
                thinkProcessService.findById(controlProcess.getId()).orElse(controlProcess);
        Map<String, Object> controlParams = new LinkedHashMap<>();
        if (refreshedControl.getEngineParams() != null) {
            controlParams.putAll(refreshedControl.getEngineParams());
        }
        controlParams.put(PARAM_PEER_PROCESS_ID, userProc.getId());
        controlParams.put(PARAM_PEER_SESSION_ID, userSession.getSessionId());
        controlParams.put(PARAM_TRILLIAN_USER_NAME, trillianName);
        thinkProcessService.replaceEngineParams(controlProcess.getId(), controlParams);

        // 6b. Announce the minted identity in the control chat.
        //     The name is generated per session and only ever appeared in
        //     the brain log — but it is what an operator has to name when
        //     granting the worker access to a further project, and with
        //     several Trillians alive there is otherwise no way to tell
        //     which '_trillian-*' belongs to which session. Persisted as a
        //     regular chat message on purpose: a transient notification
        //     would be gone by the time it is needed.
        announceIdentity(
                controlSession,
                controlProcess,
                trillianName,
                natureRegistry.resolve(nature).callName(carried));

        // 7. Start the user-process on its own lane.
        try {
            laneScheduler
                    .submit(userProc.getId(), () -> {
                        thinkEngineService.start(userProc);
                        return null;
                    })
                    .get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted starting Trillian user-process id='" + userProc.getId() + "'", ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new IllegalStateException("Trillian user-process start failed: " + cause.getMessage(), cause);
        }

        log.info(
                "Bootstrapped Trillian pair: control id='{}' session='{}' / "
                        + "user id='{}' session='{}' trillianUser='{}'",
                controlProcess.getId(),
                controlSession.getSessionId(),
                userProc.getId(),
                userSession.getSessionId(),
                trillianName);
    }

    /**
     * The model gate refused the loop: say so once in the control chat and
     * remember the reason, so the next turn can tell a new refusal from one
     * the operator has already seen. The control conversation stays fully
     * usable — the gate is about the unattended loop, not about the face.
     */
    private void suppressLoop(
            SessionDocument controlSession, ThinkProcessDocument controlProcess, @Nullable String reason) {
        String message = reason == null ? "user-loop model refused" : reason;
        Object last = controlProcess.getEngineParamOverrides() == null
                ? null
                : controlProcess.getEngineParamOverrides().get(PARAM_GATE_DENIED);
        if (message.equals(last)) {
            return;
        }
        thinkProcessService.setEngineParamOverride(controlProcess.getId(), PARAM_GATE_DENIED, message);
        try {
            chatMessageService.append(ChatMessageDocument.builder()
                    .tenantId(controlSession.getTenantId())
                    .sessionId(controlSession.getSessionId())
                    .thinkProcessId(controlProcess.getId())
                    .role(ChatRole.ASSISTANT)
                    .content("My working side is switched off: " + message)
                    .build());
        } catch (RuntimeException e) {
            log.warn(
                    "Trillian bootstrap: could not announce the model-gate refusal in session '{}': {}",
                    controlSession.getSessionId(),
                    e.toString());
        }
    }

    /** A gate that now passes retires the refusal along with it. */
    private void clearGateSuppression(ThinkProcessDocument controlProcess) {
        if (controlProcess.getEngineParamOverrides() != null
                && controlProcess.getEngineParamOverrides().containsKey(PARAM_GATE_DENIED)) {
            thinkProcessService.setEngineParamOverride(controlProcess.getId(), PARAM_GATE_DENIED, null);
        }
    }

    /**
     * Write the minted worker identity into the control chat as a persistent
     * assistant message. Best-effort — a failure here must not abort a
     * bootstrap that is otherwise complete, so it is logged and swallowed.
     */
    private void announceIdentity(
            SessionDocument controlSession, ThinkProcessDocument controlProcess, String trillianName, String callName) {
        try {
            chatMessageService.append(ChatMessageDocument.builder()
                    .tenantId(controlSession.getTenantId())
                    .sessionId(controlSession.getSessionId())
                    .thinkProcessId(controlProcess.getId())
                    .role(ChatRole.ASSISTANT)
                    .content("I am " + callName + ", ready to go. My working side runs "
                            + "as the service account `" + trillianName + "` from my home `"
                            + de.mhus.vance.shared.home.HomeBootstrapService.hubProjectName(trillianName)
                            + "`, and everything goes away when this session closes. To let"
                            + " me work in another project, that account needs access there.")
                    .build());
        } catch (RuntimeException e) {
            log.warn(
                    "Trillian bootstrap: could not announce identity '{}' in session '{}': {}",
                    trillianName,
                    controlSession.getSessionId(),
                    e.toString());
        }
    }

    /**
     * The service account a previous incarnation of this control session
     * used, if any. Read from the closed, renamed chat-process that
     * {@code reactivateFromArchive} leaves behind — it still carries the
     * {@code trillianUserName} in its {@code engineParams}.
     *
     * <p>Only accounts that still exist count: if cleanup already
     * removed it, this is a fresh start and minting is right.
     */
    private java.util.Optional<String> previousAccountOf(SessionDocument controlSession) {
        // Newest first: every cycle leaves another closed chat-process
        // behind, and an older one may name an account that has since
        // been deleted.
        for (ThinkProcessDocument p : newestFirst(controlSession)) {
            Object name =
                    p.getEngineParams() == null ? null : p.getEngineParams().get(PARAM_TRILLIAN_USER_NAME);
            if (name == null || name.toString().isBlank()) {
                continue;
            }
            String candidate = name.toString();
            if (userService.existsByTenantAndName(controlSession.getTenantId(), candidate)) {
                return java.util.Optional.of(candidate);
            }
            log.debug("Previous Trillian account '{}' is gone — minting a fresh one", candidate);
        }
        return java.util.Optional.empty();
    }

    /**
     * Attributes parked by the lifecycle hook on a closed process of this
     * session, and cleared once read so a later bootstrap does not
     * resurrect a stale persona.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> carriedAttributesOf(SessionDocument controlSession) {
        for (ThinkProcessDocument p : newestFirst(controlSession)) {
            Object raw =
                    p.getEngineParams() == null ? null : p.getEngineParams().get(PARAM_CARRIED_ATTRIBUTES);
            if (!(raw instanceof Map<?, ?> m) || m.isEmpty()) {
                continue;
            }
            Map<String, Object> params = new LinkedHashMap<>(p.getEngineParams());
            params.remove(PARAM_CARRIED_ATTRIBUTES);
            thinkProcessService.replaceEngineParams(p.getId(), params);
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return Map.of();
    }

    /** Processes of the session, most recently created first. */
    private java.util.List<ThinkProcessDocument> newestFirst(SessionDocument session) {
        java.util.List<ThinkProcessDocument> processes = new java.util.ArrayList<>(
                thinkProcessService.findBySession(session.getTenantId(), session.getSessionId()));
        processes.sort(java.util.Comparator.comparing(
                        ThinkProcessDocument::getCreatedAt,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                .reversed());
        return processes;
    }

    /**
     * Sets the account title from the Nature's {@code name} attribute,
     * when it has one. Cosmetic and best-effort — a Trillian without a
     * title is odd-looking, one that fails to bootstrap is broken.
     */
    private void adoptCharacterName(String tenantId, String trillianName, Map<String, Object> attributes) {
        Object given = attributes.get("name");
        if (!(given instanceof String name) || name.isBlank()) {
            return;
        }
        try {
            userService.update(
                    tenantId, trillianName, name.strip(), /*email*/ null, /*status*/ null, /*loginEnabled*/ null);
        } catch (RuntimeException e) {
            log.warn("Trillian: could not title account '{}' as '{}': {}", trillianName, name, e.toString());
        }
    }

    /**
     * The {@code <nature>-<instance>} tail of an account name, used to
     * label the session and seed the account title. Every account this
     * class ever hands out is minted by {@link #pickUniqueTrillianName},
     * so the prefix is always there.
     */
    private static String accountSuffix(String trillianName) {
        return trillianName.substring(ACCOUNT_PREFIX.length());
    }

    /**
     * Picks a fresh {@code _trillian-<nature>-<instance>} name that does
     * not collide in the tenant. Up to {@value #MAX_NAMING_ATTEMPTS}
     * retries — with 10.000 instances per Nature a collision is already
     * unlikely, and running out is a signal, not something to work
     * around silently.
     */
    private String pickUniqueTrillianName(String tenantId, String nature) {
        for (int i = 0; i < MAX_NAMING_ATTEMPTS; i++) {
            String name = ACCOUNT_PREFIX + nature + "-" + String.format("%04d", random.nextInt(INSTANCE_BOUND));
            // A name whose hub still exists is taken too: a hub sweep that
            // did not finish leaves rows the next holder would inherit.
            if (!userService.existsByTenantAndName(tenantId, name)
                    && !projectService.existsByTenantAndName(
                            tenantId, de.mhus.vance.shared.home.HomeBootstrapService.hubProjectName(name))) {
                return name;
            }
        }
        throw new IllegalStateException("Could not find a unique Trillian name for nature '" + nature
                + "' in tenant '" + tenantId + "' after "
                + MAX_NAMING_ATTEMPTS + " attempts");
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

    /**
     * Makes sure the user-loop pair exists and is alive. The loop session is
     * <b>fluid</b> (D1): closed or archived, it is simply rebuilt on the next
     * turn — same account (adoption), attributes carried over from the
     * departing loop when they can still be read.
     *
     * <p>Called from the control side before it dispatches anything at the
     * peer (turn head, command handler). Returns {@code true} when a live
     * loop exists afterwards.
     */
    public boolean ensureUserLoop(ThinkProcessDocument controlProcess) {
        if (controlProcess.getId() == null || !CONTROL_ENGINE_NAME.equals(controlProcess.getThinkEngine())) {
            return false;
        }
        Optional<SessionDocument> controlSession = sessionService.findBySessionId(controlProcess.getSessionId());
        if (controlSession.isEmpty()) {
            return false;
        }
        String lease = bootstrapLeaseKey(controlProcess);
        String leaseToken = claimService.acquireLease(lease, BOOTSTRAP_LEASE_TTL);
        if (leaseToken == null) {
            // Someone else is building (or checking) this pair right now;
            // answer from what is wired at this moment and do not rebuild.
            return thinkProcessService
                    .findById(controlProcess.getId())
                    .map(this::pairAlive)
                    .orElse(false);
        }
        try {
            // Decide on a fresh read: the document in hand may predate a
            // wiring another caller wrote while this one waited for the lease.
            ThinkProcessDocument fresh =
                    thinkProcessService.findById(controlProcess.getId()).orElse(null);
            if (fresh == null) {
                return false;
            }
            if (pairAlive(fresh)) {
                return true;
            }
            // Fluid, not fatal: carry whatever the departing loop still
            // holds, unwire — one write, so the carried attributes and the
            // unwiring cannot overwrite each other — and build a fresh one.
            Map<String, Object> carried = findPeerProcess(fresh)
                    .map(TrillianInternalApi::readAttributes)
                    .orElse(Map.of());
            unwirePeer(fresh.getId(), carried.isEmpty() ? null : carried);
            log.info("Trillian: user-loop of control '{}' is gone — rebuilding", fresh.getId());
            bootstrapLeased(controlSession.get(), fresh.getId());
            return thinkProcessService
                    .findById(fresh.getId())
                    .flatMap(this::findPeerProcess)
                    .filter(TrillianSessionBootstrapper::isAlive)
                    .isPresent();
        } finally {
            claimService.releaseLease(lease, leaseToken);
        }
    }

    /** Whether the wired loop process and its session are both alive. */
    private boolean pairAlive(ThinkProcessDocument controlProcess) {
        Optional<ThinkProcessDocument> loop = findPeerProcess(controlProcess);
        return loop.isPresent()
                && isAlive(loop.get())
                && findUserSessionId(controlProcess).map(this::sessionAlive).orElse(false);
    }

    /** The wired user-loop process, if the reference still resolves. */
    private Optional<ThinkProcessDocument> findPeerProcess(ThinkProcessDocument controlProcess) {
        if (controlProcess.getEngineParams() == null) {
            return Optional.empty();
        }
        Object raw = controlProcess.getEngineParams().get(PARAM_PEER_PROCESS_ID);
        return raw instanceof String s && !s.isBlank() ? thinkProcessService.findById(s) : Optional.empty();
    }

    private static boolean isAlive(ThinkProcessDocument process) {
        return process.getStatus() != null && process.getStatus() != ThinkProcessStatus.CLOSED;
    }

    private boolean sessionAlive(String sessionId) {
        Optional<SessionDocument> s = sessionService.findBySessionId(sessionId);
        return s.isPresent()
                && s.get().getStatus() != SessionStatus.CLOSED
                && s.get().getStatus() != SessionStatus.ARCHIVED;
    }

    /**
     * Drops the peer wiring, keeping {@code trillianUserName} for adoption.
     * Reads the params fresh and writes once; {@code carriedAttributes}, when
     * given, is parked in the same write for the rebuild to pick up through
     * {@link #PARAM_CARRIED_ATTRIBUTES}.
     */
    private void unwirePeer(String controlProcessId, @Nullable Map<String, Object> carriedAttributes) {
        ThinkProcessDocument fresh =
                thinkProcessService.findById(controlProcessId).orElse(null);
        if (fresh == null) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (fresh.getEngineParams() != null) {
            params.putAll(fresh.getEngineParams());
        }
        params.remove(PARAM_PEER_PROCESS_ID);
        params.remove(PARAM_PEER_SESSION_ID);
        if (carriedAttributes != null) {
            params.put(PARAM_CARRIED_ATTRIBUTES, carriedAttributes);
        }
        thinkProcessService.replaceEngineParams(controlProcessId, params);
    }

    /**
     * Records the freshly minted account on the control process at once, so
     * a bootstrap that fails after the mint adopts it next time instead of
     * minting another one.
     */
    private void recordAccount(String controlProcessId, String trillianName) {
        ThinkProcessDocument fresh =
                thinkProcessService.findById(controlProcessId).orElse(null);
        if (fresh == null) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (fresh.getEngineParams() != null) {
            params.putAll(fresh.getEngineParams());
        }
        params.put(PARAM_TRILLIAN_USER_NAME, trillianName);
        thinkProcessService.replaceEngineParams(controlProcessId, params);
    }

    private static String bootstrapLeaseKey(ThinkProcessDocument controlProcess) {
        return "bootstrap/" + controlProcess.getTenantId() + "/" + controlProcess.getId();
    }

    /**
     * The user-loop recipe: an explicit {@link #PARAM_USER_RECIPE} on the
     * control process wins, otherwise the Nature family default. Same shape
     * as {@link #PARAM_WORKER_RECIPE} — a recipe pins a different working
     * style instead of forking the derivation.
     */
    private static String userRecipeNameOf(ThinkProcessDocument controlProcess, String nature) {
        if (controlProcess.getEngineParams() != null) {
            Object raw = controlProcess.getEngineParams().get(PARAM_USER_RECIPE);
            if (raw instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }
        return USER_RECIPE_PREFIX + nature;
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
