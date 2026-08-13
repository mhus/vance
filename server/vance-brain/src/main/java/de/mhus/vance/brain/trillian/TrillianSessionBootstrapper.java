package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.session.DisconnectPolicy;
import de.mhus.vance.api.session.IdlePolicy;
import de.mhus.vance.api.session.SessionLifecycleConfig;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.session.SuspendPolicy;
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
 *   <li>Mints a fresh {@code _trillian-<nature>-<instance>} service account.</li>
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
     * ({@code trillian-alpha} etc.) all trigger the same bootstrap.
     */
    public static final String CONTROL_ENGINE_NAME = "trillian-control";

    /**
     * Recipe-name prefix for the User-Loop recipe family. The
     * bootstrap resolves {@code USER_RECIPE_PREFIX + nature} as the
     * concrete recipe to spawn (e.g. {@code trillian-user-0} for
     * Nature-0).
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

    /**
     * engineParams key on a control process that parks the outgoing
     * worker's attributes across a reactivate. The attributes themselves
     * live on the worker process and would die with it; this is where
     * {@code TrillianSessionLifecycleHook} leaves them for the next
     * bootstrap to pick up.
     */
    public static final String PARAM_CARRIED_ATTRIBUTES = "carriedWorkerAttributes";

    /** engineParams key for the Trillian Nature pinned by the recipe. */
    public static final String PARAM_NATURE = "nature";

    /** Profile slot used for the headless Trillian-User session. */
    public static final String HEADLESS_PROFILE = "headless";

    /**
     * Account naming: {@code _trillian-<nature>-<instance>}, e.g.
     * {@code _trillian-0-1535} or {@code _trillian-alpha-4711}.
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

    private static final String CLIENT_NAME = "trillian-bootstrap";
    private static final String CLIENT_VERSION = "0.1.0";

    private final UserService userService;
    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final RecipeResolver recipeResolver;
    private final LaneScheduler laneScheduler;
    private final ChatMessageService chatMessageService;

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
     */
    public void maybeBootstrap(
            SessionDocument controlSession,
            @Nullable ThinkProcessDocument controlProcess) {
        if (controlProcess == null) {
            return;
        }
        // Detect by engine, not recipe name — that way the trillian
        // default-alias recipe and any future Nature recipes
        // (trillian-alpha etc.) all funnel through this bootstrap.
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
        // 1. Which Nature this pair runs. It lives in
        //    controlProcess.engineParams.nature; DEFAULT_NATURE covers a
        //    recipe that didn't pin one. Read first, because both the
        //    account name and the two follow-up recipes derive from it.
        String nature = readNature(controlProcess);

        // 2. Reuse the account of a previous incarnation, or mint one.
        //    After a reactivate the old chat-process is still around,
        //    renamed and closed, and it remembers which account this
        //    session's Trillian was. Reusing it is what makes archiving
        //    reversible: same identity, same attributes, same grants —
        //    a Trillian that came back rather than a stranger wearing
        //    its session.
        String trillianName = previousAccountOf(controlSession).orElse(null);
        boolean adopted = trillianName != null;
        if (!adopted) {
            trillianName = pickUniqueTrillianName(controlSession.getTenantId(), nature);
        }
        if (adopted) {
            log.info("Adopted Trillian service-account '{}' for reactivated control session '{}'",
                    trillianName, controlSession.getSessionId());
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
            log.info("Minted Trillian service-account '{}' id='{}' for control session '{}'",
                    trillian.getName(), trillian.getId(), controlSession.getSessionId());
        }
        final String trillianNameFinal = trillianName;

        // 2b. Seed the account's authority. Without a grant the account
        //     exists but may do nothing: every tool call goes through
        //     ToolDispatcher -> PermissionService.enforce(EXECUTE), which
        //     resolves to WRITER-on-project. Scope is deliberately the
        //     control session's project — Trillian stands in for the human
        //     in the project they started it in, and nowhere else. Spawning
        //     into other projects (cross_process_create) therefore stays
        //     denied until someone grants that explicitly.
        if (!adopted) {
            permissionBootstrapProvider.ifAvailable(pb -> pb.grantProjectAdmin(
                    controlSession.getTenantId(), controlSession.getProjectId(),
                    trillianNameFinal));
        }

        // 3. Resolve the user recipe — the Nature variant of the loop.
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

        // 4. Create the headless user-session owned by the service-
        //    account. system=true marks it as auto-managed (UI may
        //    filter system sessions in the user's session list).
        // SessionDocument.userId is the UserDocument *name*, not the Mongo id
        // — the whole authz chain (ToolDispatcher's SecurityContext, team
        // lookup, grant matching) keys on the name.
        SessionDocument userSession = sessionService.create(
                controlSession.getTenantId(),
                trillianName,
                controlSession.getProjectId(),
                /*displayName*/ "Trillian-User " + accountSuffix(trillianName),
                /*profile*/ HEADLESS_PROFILE,
                CLIENT_VERSION,
                CLIENT_NAME,
                /*system*/ true);
        log.info("Trillian user-session created id='{}' owner='{}' project='{}'",
                userSession.getSessionId(), trillianName, controlSession.getProjectId());

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
        if (!carried.isEmpty()) {
            userParams.put(TrillianInternalApi.PARAM_ATTRIBUTES, carried);
            log.info("Restored {} Trillian attribute(s) for control session '{}'",
                    carried.size(), controlSession.getSessionId());
        }
        userParams.put(PARAM_WORKER_RECIPE, WORKER_RECIPE_PREFIX + nature);
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

        // 6. Record cross-references on the control-process too.
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

        // 6b. Announce the minted identity in the control chat.
        //     The name is generated per session and only ever appeared in
        //     the brain log — but it is what an operator has to name when
        //     granting the worker access to a further project, and with
        //     several Trillians alive there is otherwise no way to tell
        //     which '_trillian-*' belongs to which session. Persisted as a
        //     regular chat message on purpose: a transient notification
        //     would be gone by the time it is needed.
        announceIdentity(controlSession, controlProcess, trillianName);

        // 7. Start the user-process on its own lane.
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
     * Write the minted worker identity into the control chat as a persistent
     * assistant message. Best-effort — a failure here must not abort a
     * bootstrap that is otherwise complete, so it is logged and swallowed.
     */
    private void announceIdentity(
            SessionDocument controlSession,
            ThinkProcessDocument controlProcess,
            String trillianName) {
        try {
            chatMessageService.append(ChatMessageDocument.builder()
                    .tenantId(controlSession.getTenantId())
                    .sessionId(controlSession.getSessionId())
                    .thinkProcessId(controlProcess.getId())
                    .role(ChatRole.ASSISTANT)
                    .content("Trillian is ready. My background worker runs as the service"
                            + " account `" + trillianName + "` in project `"
                            + controlSession.getProjectId() + "`, and is removed when this"
                            + " session closes. To let it work in another project, that"
                            + " account needs access there.")
                    .build());
        } catch (RuntimeException e) {
            log.warn("Trillian bootstrap: could not announce identity '{}' in session '{}': {}",
                    trillianName, controlSession.getSessionId(), e.toString());
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
            Object name = p.getEngineParams() == null
                    ? null : p.getEngineParams().get(PARAM_TRILLIAN_USER_NAME);
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
            Object raw = p.getEngineParams() == null
                    ? null : p.getEngineParams().get(PARAM_CARRIED_ATTRIBUTES);
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
                thinkProcessService.findBySession(
                        session.getTenantId(), session.getSessionId()));
        processes.sort(java.util.Comparator.comparing(
                ThinkProcessDocument::getCreatedAt,
                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())).reversed());
        return processes;
    }

    /**
     * The {@code <nature>-<instance>} tail of an account name, used to
     * label the session and seed the account title.
     *
     * <p>Tolerates a name that predates the prefix: labels are cosmetic
     * and must not be the thing that breaks an adoption.
     */
    private static String accountSuffix(String trillianName) {
        return trillianName.startsWith(ACCOUNT_PREFIX)
                ? trillianName.substring(ACCOUNT_PREFIX.length())
                : trillianName;
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
            String name = ACCOUNT_PREFIX + nature + "-"
                    + String.format("%04d", random.nextInt(INSTANCE_BOUND));
            if (!userService.existsByTenantAndName(tenantId, name)) {
                return name;
            }
        }
        throw new IllegalStateException(
                "Could not find a unique Trillian name for nature '" + nature
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

    /** Lookup the user-session id wired to this control process. */
    public Optional<String> findUserSessionId(ThinkProcessDocument controlProcess) {
        if (controlProcess.getEngineParams() == null) {
            return Optional.empty();
        }
        Object v = controlProcess.getEngineParams().get(PARAM_PEER_SESSION_ID);
        return v instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
    }
}
