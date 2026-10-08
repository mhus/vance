package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.api.session.IdlePolicy;
import de.mhus.vance.api.session.SessionLifecycleConfig;
import de.mhus.vance.api.session.SuspendPolicy;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.trillian.TrillianOwnSessions;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.brain.trillian.TrillianUserEngine;
import de.mhus.vance.brain.trillian.nature.CollabMode;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Opens a session in another project <b>as the Trillian itself</b> and
 * starts a chat process there with the project's recipe — the outgoing
 * twin of {@code trillian_session_create}. The loop then talks to that
 * project's engine like a user would (A6 / Geschmacksrichtung 2).
 *
 * <p><b>Own sessions only</b> (D6): everything this tool set touches is
 * owned by the calling account and carries the
 * {@link TrillianOwnSessions} marker — foreign sessions are untouchable.
 *
 * <p><b>One stay per project and recipe</b> (A6.2): a second
 * {@code session_open} for the same project and recipe reuses the open
 * session instead of starting another — the model says "go there", Java
 * keeps the bookkeeping. An idle stay suspends after a few hours and closes.
 *
 * <p><b>Shared unless the Nature says solo, never {@code system=true}</b>
 * (A6 contract): a human may look in and work along; {@code system=true}
 * would hide the session from exactly those humans. The collab strength
 * comes from the Nature ({@code sessionCollab}), never from the model —
 * {@link CollabMode#SOLO} is a private (single-client) session.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionOpenTool implements Tool {

    /** Idle stays end on their own: suspend after this long without work, then close. */
    static final long IDLE_TIMEOUT_MS = 4L * 60 * 60 * 1000;

    /** Cap on the purpose line — it becomes the session title humans see. */
    static final int PURPOSE_LIMIT = 120;

    private static final Map<String, Object> SCHEMA;

    static {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "projectId",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "The project to open the session in. Must exist in "
                                + "this tenant and not be a SYSTEM project."));
        properties.put(
                "recipe",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Recipe of the engine you want to talk to there "
                                + "(the project's usual one, e.g. 'arthur')."));
        properties.put(
                "purpose",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "One line on why you are there — becomes part of " + "the session title humans see."));
        properties.put(
                "firstMessage",
                Map.of(
                        "type", "string",
                        "description", "Optional opening message, delivered on start."));
        SCHEMA = Map.of(
                "type", "object", "properties", properties, "required", List.of("projectId", "recipe", "purpose"));
    }

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final RecipeResolver recipeResolver;
    private final LaneScheduler laneScheduler;
    private final TrillianNatureRegistry natureRegistry;
    private final de.mhus.vance.shared.permission.PermissionService permissionService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;
    private final ProjectService projectService;
    private final TrillianOwnSessions ownSessions;

    @Override
    public String name() {
        return "session_open";
    }

    @Override
    public String description() {
        return "Open a session in another project as yourself and start "
                + "talking to that project's engine there — like a user would. "
                + "The session is shared (humans may look in and work along) "
                + "and named after your purpose. You may only steer sessions "
                + "you opened yourself.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.INTERNAL, "executive", "cross-project");
    }

    @Override
    public Set<String> requiresEngineRoles() {
        return Set.of(TrillianUserEngine.ROLE_TRILLIAN_USER);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null || ctx.userId() == null) {
            throw new ToolException("session_open requires a process and user scope");
        }
        String account = ctx.userId();
        String projectId = stringOrThrow(params, "projectId");
        String recipeName = stringOrThrow(params, "recipe");
        String purpose = cap(stringOrThrow(params, "purpose"));
        String firstMessage = stringOrNull(params, "firstMessage");

        ProjectDocument project = projectService
                .findByTenantAndName(ctx.tenantId(), projectId)
                .orElseThrow(() -> new ToolException(
                        "session_open: project '" + projectId + "' not found — project_list shows the available ones"));
        if (project.getKind() == ProjectKind.SYSTEM) {
            throw new ToolException("session_open: '" + projectId + "' is a SYSTEM project — pick a regular project");
        }
        // Starting work in the target project is the human's gate to give
        // (fail-closed) — a session is presence, not a licence.
        permissionService.enforce(
                contextFactory.forToolSubject(ctx.tenantId(), account),
                new de.mhus.vance.shared.permission.Resource.Project(ctx.tenantId(), projectId),
                de.mhus.vance.shared.permission.Action.START);

        // One stay per (project, recipe): reuse it rather than opening a
        // second session next to it.
        for (SessionDocument open : ownSessions.openSessionsOf(ctx.tenantId(), account)) {
            if (!projectId.equals(open.getProjectId())) {
                continue;
            }
            ThinkProcessDocument chat = open.getChatProcessId() == null
                    ? null
                    : thinkProcessService.findById(open.getChatProcessId()).orElse(null);
            if (chat == null || !recipeName.equals(chat.getRecipeName())) {
                continue;
            }
            if (firstMessage != null) {
                ownSessions.deliver(open, chat, account, ctx.processId(), firstMessage);
            }
            return result(open, chat, projectId, TrillianOwnSessions.modeOf(open), "reused");
        }

        ThinkProcessDocument loop = thinkProcessService
                .findById(ctx.processId())
                .orElseThrow(() -> new ToolException("session_open: calling process is gone"));
        CollabMode mode = collabOf(loop, projectId, purpose);

        // Resolve everything that can refuse before anything is written: a
        // failure past this point would leave an empty session behind.
        AppliedRecipe applied = recipeResolver.applyDefaulting(
                ctx.tenantId(),
                projectId,
                recipeName,
                TrillianSessionBootstrapper.HEADLESS_PROFILE,
                /*callerParams*/ null);
        ThinkEngine engine = thinkEngineService
                .resolve(applied.engine())
                .orElseThrow(() -> new ToolException("session_open: recipe '" + recipeName
                        + "' references unknown engine '" + applied.engine() + "'"));

        SessionDocument session = sessionService.create(
                ctx.tenantId(),
                account,
                projectId,
                /*displayName*/ "Trillian: " + purpose,
                /*profile*/ TrillianSessionBootstrapper.HEADLESS_PROFILE,
                /*clientVersion*/ "trillian-session/2",
                /*clientName*/ TrillianOwnSessions.clientName(mode, account),
                /*system*/ false);
        ThinkProcessDocument chat;
        try {
            // Shared unless solo — pinned explicitly so a default change
            // cannot silently make the Trillian's work invisible.
            sessionService.patchMetadata(
                    session.getSessionId(),
                    de.mhus.vance.api.session.SessionMetadataPatchRequest.builder()
                            .allowMultipleClients(mode != CollabMode.SOLO)
                            .build());
            sessionService.applyLifecycleConfig(
                    session.getSessionId(),
                    SessionLifecycleConfig.builder()
                            .onIdle(IdlePolicy.SUSPEND)
                            .idleTimeoutMs(IDLE_TIMEOUT_MS)
                            .onSuspend(SuspendPolicy.CLOSE)
                            .build());
            chat = thinkProcessService.create(
                    ctx.tenantId(),
                    projectId,
                    session.getSessionId(),
                    /*name*/ "chat",
                    engine.name(),
                    engine.version(),
                    /*title*/ "Trillian: " + purpose,
                    /*goal*/ null,
                    /*parentProcessId*/ null,
                    /*engineParams*/ applied.params() == null ? Map.of() : applied.params(),
                    applied.name(),
                    applied.promptOverride(),
                    applied.promptOverrideAppend(),
                    applied.promptMode(),
                    applied.dataRelayCorrection(),
                    applied.effectiveAllowedTools(),
                    applied.connectionProfile(),
                    applied.defaultActiveSkills(),
                    applied.allowedSkills() == null ? null : Set.copyOf(applied.allowedSkills()));
            sessionService.setChatProcessId(session.getSessionId(), chat.getId());
            sessionService.markBootstrapped(session.getSessionId());
            laneScheduler
                    .submit(chat.getId(), () -> {
                        thinkEngineService.start(chat);
                        return null;
                    })
                    .get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            discard(session);
            throw new ToolException("session_open: interrupted starting the chat process", ie);
        } catch (ExecutionException ee) {
            discard(session);
            throw new ToolException("session_open: chat process start failed: " + ee.getCause(), ee);
        } catch (RuntimeException e) {
            discard(session);
            throw new ToolException("session_open: could not set up the session — " + e.getMessage(), e);
        }

        if (firstMessage != null) {
            // The opening message rides the normal chat path — the engine
            // sees a user turn, not a special case.
            ownSessions.deliver(session, chat, account, ctx.processId(), firstMessage);
        }
        return result(session, chat, projectId, mode, "opened");
    }

    private static Map<String, Object> result(
            SessionDocument session, ThinkProcessDocument chat, String projectId, CollabMode mode, String status) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", session.getSessionId());
        out.put("chatProcessId", chat.getId());
        out.put("projectId", projectId);
        out.put("collab", mode.name());
        out.put("status", status);
        out.put(
                "next",
                mode == CollabMode.WATCH
                        ? "Answers are not pushed to you here — read them with session_read."
                        : "Answers arrive later as session-reply events; session_read shows the conversation.");
        return out;
    }

    /** Removes a session whose set-up failed — it holds nothing yet. */
    private void discard(SessionDocument session) {
        try {
            sessionService.delete(session.getSessionId());
        } catch (RuntimeException e) {
            log.warn(
                    "session_open: could not remove half-built session '{}': {}", session.getSessionId(), e.toString());
        }
    }

    private static String cap(String purpose) {
        String flat = purpose.replaceAll("\\s+", " ").strip();
        return flat.length() <= PURPOSE_LIMIT ? flat : flat.substring(0, PURPOSE_LIMIT) + "…";
    }

    private CollabMode collabOf(ThinkProcessDocument loop, String projectId, @Nullable String purpose) {
        try {
            TrillianNature nature = natureRegistry.resolve(TrillianSessionBootstrapper.readNature(loop));
            return nature.sessionCollab(loop, projectId, purpose);
        } catch (RuntimeException e) {
            log.warn("Trillian: sessionCollab failed, falling back to JOIN: {}", e.toString());
            return CollabMode.JOIN;
        }
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required");
        }
        return s.trim();
    }

    private static @Nullable String stringOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
