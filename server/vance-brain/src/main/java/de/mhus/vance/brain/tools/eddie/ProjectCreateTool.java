package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.session.SessionChatBootstrapper;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.brain.eddie.activity.EntityRef;
import de.mhus.vance.brain.eddie.activity.EddieActivityKind;
import de.mhus.vance.brain.eddie.activity.EddieActivityService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Creates a new {@link ProjectKind#NORMAL} project, opens a Session
 * inside it, bootstraps the chat-process (Arthur by default), and —
 * optionally — drops an initial {@code UserChatInput} into Arthur's
 * pending queue so the worker starts immediately.
 *
 * <p>The chat-process's {@code parentProcessId} is set to the calling
 * Eddie process so {@code ProcessEvent}s (DONE / BLOCKED / FAILED)
 * route back across the project boundary into Eddie's pending queue.
 *
 * <p>Service-direct (no REST loopback) — when Multi-Pod arrives the
 * routing layer wraps these calls; the tool body stays the same.
 *
 * <p>Auto-archive policy: nothing archived here. Eddie never
 * auto-archives; the user decides.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectCreateTool implements Tool {

    private static final String SPAWNED_BY_EDDIE_LABEL = "Eddie hub";
    private static final String SPAWNED_CLIENT_VERSION = "vance-bot/0.1.0";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "Unique project name (kebab-case, "
                                    + "no leading underscore)."),
                    "title", Map.of(
                            "type", "string",
                            "description", "Optional human title (defaults to name)."),
                    "projectGroupId", Map.of(
                            "type", "string",
                            "description", "Optional project group name to "
                                    + "place the new project under."),
                    "initialPrompt", Map.of(
                            "type", "string",
                            "description", "Optional first message that goes "
                                    + "straight into the new chat-process's "
                                    + "pending queue. Use this to hand the worker "
                                    + "a substantive goal in one round-trip.")),
            "required", List.of("name"));

    private final ProjectService projectService;
    private final SessionService sessionService;
    /**
     * {@code ObjectProvider} breaks the bean cycle:
     * ProjectCreateTool → SessionChatBootstrapper → ThinkEngineService →
     * ToolDispatcher → BuiltInToolSource → ProjectCreateTool.
     * Resolution happens on first use, by which time all engine beans
     * exist.
     */
    private final ObjectProvider<SessionChatBootstrapper> chatBootstrapperProvider;
    private final de.mhus.vance.shared.thinkprocess.ThinkProcessService thinkProcessService;
    private final ProcessEventEmitter eventEmitter;
    private final LaneScheduler laneScheduler;
    private final EddieActivityService activityService;

    @Override
    public String name() {
        return "project_create";
    }

    @Override
    public String description() {
        return "Create a new user project, start a session in it, "
                + "and bootstrap the project's Arthur chat-process. "
                + "Optionally hand Arthur an initial prompt so he starts "
                + "the work immediately. The new chat-process reports "
                + "DONE / BLOCKED back to me across the project boundary.";
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
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null) {
            throw new ToolException("project_create requires an Eddie process scope");
        }
        if (ctx.userId() == null) {
            throw new ToolException("project_create requires a user identity");
        }
        String projectName = stringOrThrow(params, "name");
        String title = optString(params, "title");
        String projectGroupId = optString(params, "projectGroupId");
        String initialPrompt = optString(params, "initialPrompt");

        // 1) Project — uniqueness + reserved-prefix enforcement live in ProjectService.
        ProjectDocument project;
        try {
            project = projectService.create(
                    ctx.tenantId(),
                    projectName,
                    title,
                    projectGroupId,
                    /*teamIds*/ null,
                    ProjectKind.NORMAL);
        } catch (ProjectService.ProjectAlreadyExistsException
                | ProjectService.ReservedProjectNameException e) {
            throw new ToolException(e.getMessage(), e);
        }

        // 2) Session — owned by the user; Eddie acts on the user's behalf.
        // Synthetic session has no real client connection; FOOT is a stand-in
        // value that disables web-only restrictions on the spawned chat process.
        SessionDocument session = sessionService.create(
                ctx.tenantId(),
                ctx.userId(),
                project.getName(),
                SPAWNED_BY_EDDIE_LABEL,
                Profiles.FOOT,
                SPAWNED_CLIENT_VERSION,
                /* clientName */ null);

        // 3) Chat-process — Arthur (or whatever the tenant default is)
        //    with cross-project parent = this Eddie process. Engine.start()
        //    runs synchronously so the greeting is already in chat-history
        //    when this returns.
        SessionChatBootstrapper chatBootstrapper = chatBootstrapperProvider.getObject();
        Optional<ThinkProcessDocument> chatOpt = chatBootstrapper.ensureChatProcess(
                session, /*parentProcessId*/ ctx.processId());
        ThinkProcessDocument chat = chatOpt.orElseThrow(() ->
                new ToolException(
                        "Chat-process bootstrap failed for session '"
                                + session.getSessionId() + "'"));

        // 4) Optional: kick off the worker with the user's substantive ask.
        if (initialPrompt != null) {
            PendingMessageDocument msg = PendingMessageDocument.builder()
                    .type(PendingMessageType.USER_CHAT_INPUT)
                    .at(Instant.now())
                    .fromUser("eddie:" + ctx.processId())
                    .content(initialPrompt)
                    .build();
            if (!thinkProcessService.appendPending(chat.getId(), msg, ctx.processId())) {
                throw new ToolException(
                        "Failed to enqueue initial prompt — chat-process "
                                + chat.getId() + " disappeared");
            }
            eventEmitter.scheduleTurn(chat.getId());
            log.info("project_create: handed initial prompt to chat='{}' chars={}",
                    chat.getId(), initialPrompt.length());
        }

        log.info("project_create: tenant='{}' project='{}' session='{}' chat='{}' parent='{}'",
                ctx.tenantId(), project.getName(), session.getSessionId(),
                chat.getId(), ctx.processId());

        // Activity-Log: peers see this on their next recap.
        activityService.append(
                ctx.tenantId(), ctx.userId(),
                ctx.sessionId(), ctx.processId(),
                EddieActivityKind.PROJECT_CREATED,
                "Projekt `" + project.getName() + "` angelegt"
                        + (initialPrompt != null ? " mit initialer Aufgabe" : ""),
                List.of(EntityRef.project(project.getName()),
                        EntityRef.process(chat.getId(), chat.getName())));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getName());
        if (project.getTitle() != null) out.put("title", project.getTitle());
        out.put("projectGroupId", project.getProjectGroupId());
        out.put("sessionId", session.getSessionId());
        out.put("chatProcessId", chat.getId());
        out.put("chatEngine", chat.getThinkEngine());
        out.put("initialPromptDispatched", initialPrompt != null);
        return out;
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s.trim();
    }

    private static @org.jspecify.annotations.Nullable String optString(
            Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
