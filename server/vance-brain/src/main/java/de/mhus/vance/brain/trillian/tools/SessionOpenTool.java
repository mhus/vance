package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.brain.trillian.TrillianUserEngine;
import de.mhus.vance.brain.trillian.nature.CollabMode;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
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
 * owned by the calling account and carries
 * {@value #CLIENT_NAME_PREFIX}{@code <loopId>} — foreign sessions are
 * untouchable.
 *
 * <p><b>Always shared, never {@code system=true}</b> (A6 contract): a
 * human may look in and work along; {@code system=true} would hide the
 * session from exactly those humans. The collab strength comes from the
 * Nature ({@code sessionCollab}), never from the model.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionOpenTool implements Tool {

    /** Client-name marker: {@code trillian-session:<loopProcessId>}. */
    public static final String CLIENT_NAME_PREFIX = "trillian-session:";

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
        return Set.of("executive", "cross-project");
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
        String projectId = stringOrThrow(params, "projectId");
        String recipeName = stringOrThrow(params, "recipe");
        String purpose = stringOrThrow(params, "purpose");
        String firstMessage = stringOrNull(params, "firstMessage");

        // Starting work in the target project is the human's gate to give
        // (fail-closed) — a session is presence, not a licence.
        permissionService.enforce(
                contextFactory.forToolSubject(ctx.tenantId(), ctx.userId()),
                new de.mhus.vance.shared.permission.Resource.Project(ctx.tenantId(), projectId),
                de.mhus.vance.shared.permission.Action.START);

        ThinkProcessDocument loop = thinkProcessService
                .findById(ctx.processId())
                .orElseThrow(() -> new ToolException("session_open: calling process is gone"));
        CollabMode mode = collabOf(loop, projectId, purpose);
        if (mode == CollabMode.SOLO) {
            throw new ToolException(
                    "session_open: this Trillian works solo and does " + "not open shared sessions (Nature decision)");
        }

        SessionDocument session = sessionService.create(
                ctx.tenantId(),
                ctx.userId(),
                projectId,
                /*displayName*/ "Trillian: " + purpose,
                /*profile*/ TrillianSessionBootstrapper.HEADLESS_PROFILE,
                /*clientVersion*/ "trillian-session/1",
                /*clientName*/ CLIENT_NAME_PREFIX + loop.getId(),
                /*system*/ false);
        // Project-sessions default to shared; pin it explicitly so a
        // default change cannot silently make Trillian's work invisible.
        sessionService.patchMetadata(
                session.getSessionId(),
                de.mhus.vance.api.session.SessionMetadataPatchRequest.builder()
                        .allowMultipleClients(Boolean.TRUE)
                        .build());

        AppliedRecipe applied = recipeResolver.applyDefaulting(
                ctx.tenantId(), projectId, recipeName, session.getProfile(), /*callerParams*/ null);
        ThinkEngine engine = thinkEngineService
                .resolve(applied.engine())
                .orElseThrow(() -> new ToolException("session_open: recipe '" + recipeName
                        + "' references unknown engine '" + applied.engine() + "'"));
        ThinkProcessDocument chat;
        try {
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
                    /*engineParams*/ Map.of(),
                    applied.name(),
                    applied.promptOverride(),
                    applied.promptOverrideAppend(),
                    applied.promptMode(),
                    applied.dataRelayCorrection(),
                    applied.effectiveAllowedTools(),
                    applied.connectionProfile(),
                    applied.defaultActiveSkills(),
                    applied.allowedSkills() == null ? null : Set.copyOf(applied.allowedSkills()));
        } catch (ThinkProcessService.ThinkProcessAlreadyExistsException e) {
            throw new ToolException(
                    "session_open: chat process already exists in session '" + session.getSessionId() + "'", e);
        }
        sessionService.setChatProcessId(session.getSessionId(), chat.getId());
        sessionService.markBootstrapped(session.getSessionId());
        try {
            laneScheduler
                    .submit(chat.getId(), () -> {
                        thinkEngineService.start(chat);
                        return null;
                    })
                    .get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ToolException("session_open: interrupted starting the chat process", ie);
        } catch (ExecutionException ee) {
            throw new ToolException("session_open: chat process start failed: " + ee.getCause(), ee);
        }

        if (firstMessage != null) {
            // The opening message rides the normal chat path — the engine
            // sees a user turn, not a special case.
            laneScheduler.submit(chat.getId(), () -> {
                thinkEngineService.steer(
                        chat,
                        new de.mhus.vance.brain.thinkengine.SteerMessage.UserChatInput(
                                java.time.Instant.now(), null, ctx.userId(), firstMessage));
                return null;
            });
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", session.getSessionId());
        out.put("chatProcessId", chat.getId());
        out.put("projectId", projectId);
        out.put("collab", mode.name());
        out.put("status", "opened");
        return out;
    }

    private CollabMode collabOf(ThinkProcessDocument loop, String projectId, @Nullable String purpose) {
        try {
            TrillianNature nature = natureRegistry.resolve(TrillianSessionBootstrapper.readNature(loop));
            return nature.sessionCollab(loop, projectId, purpose);
        } catch (RuntimeException e) {
            log.warn("Trillian: sessionCollab failed, falling back to WATCH: {}", e.toString());
            return CollabMode.WATCH;
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
