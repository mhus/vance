package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.brain.kit.KitException;
import de.mhus.vance.brain.kit.catalog.ProjectKitInstaller;
import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.brain.eddie.activity.EntityRef;
import de.mhus.vance.brain.eddie.activity.EddieActivityKind;
import de.mhus.vance.brain.eddie.activity.EddieActivityService;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                                    + "a substantive goal in one round-trip."),
                    "kitName", Map.of(
                            "type", "string",
                            "description", "Optional — catalog name OR a "
                                    + "free-text kit wish (e.g. "
                                    + "'school-essay', 'Schul-Aufsatz', "
                                    + "'essay kit'). The server matches "
                                    + "strict first; on miss a single-shot "
                                    + "LLM resolver maps the wish against "
                                    + "the tenant catalog. When matched, "
                                    + "the kit is installed into the new "
                                    + "project right after creation. If "
                                    + "the resolver returns no match the "
                                    + "call fails with the catalog listing "
                                    + "+ rationale, so you can retry with "
                                    + "a recognisable name. To install a "
                                    + "kit from a raw git/file URL that "
                                    + "isn't in the catalog, leave this "
                                    + "empty and call kit_install "
                                    + "afterwards.")),
            "required", List.of("name"));

    /**
     * {@code ObjectProvider} breaks the bean cycle:
     * ProjectCreateTool → ProjectLifecycleService → SessionChatBootstrapper →
     * ThinkEngineService → ToolDispatcher → BuiltInToolSource → ProjectCreateTool.
     * Resolution happens on first use, by which time all engine beans
     * exist.
     */
    private final ObjectProvider<ProjectLifecycleService> lifecycleServiceProvider;
    private final EddieActivityService activityService;
    private final ProjectKitInstaller projectKitInstaller;

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
    public java.util.Set<String> labels() {
        return java.util.Set.of("eddie", "executive");
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
        String kitName = optString(params, "kitName");

        ProjectLifecycleService lifecycle = lifecycleServiceProvider.getObject();

        // create(): ProjectService.create + bring() (claim + scratch + RUNNING)
        try {
            lifecycle.create(
                    ctx.tenantId(),
                    projectName,
                    title,
                    projectGroupId,
                    /*teamIds*/ null,
                    ProjectKind.NORMAL,
                    /*createdBy*/ ctx.userId());
        } catch (ProjectService.ProjectAlreadyExistsException
                | ProjectService.ReservedProjectNameException e) {
            throw new ToolException(e.getMessage(), e);
        }

        // Optional kit install — caller resolved a name from kit_list.
        String kitInstallError = null;
        if (kitName != null) {
            try {
                projectKitInstaller.installFromCatalog(
                        ctx.tenantId(), projectName, kitName, ctx.userId());
            } catch (KitException e) {
                // Project is created and RUNNING. Don't roll back —
                // Eddie surfaces the kit problem to the user in the
                // tool result so the conversation can recover (retry,
                // pick a different kit, continue without one).
                log.warn("project_create: kit install failed tenant='{}' project='{}' kit='{}': {}",
                        ctx.tenantId(), projectName, kitName, e.getMessage());
                kitInstallError = e.getMessage();
            }
        }

        // bootstrapChat(): Session + chat-process + optional initialPrompt
        // dispatch (cross-pod-aware via EngineMessageRouter). Synthetic session
        // — no real client connection; FOOT profile disables web-only
        // restrictions on the spawned chat-process.
        ProjectLifecycleService.BootstrapResult bootstrap = lifecycle.bootstrapChat(
                new ProjectLifecycleService.BootstrapChatRequest(
                        ctx.tenantId(),
                        projectName,
                        ctx.userId(),
                        SPAWNED_BY_EDDIE_LABEL,
                        Profiles.FOOT,
                        SPAWNED_CLIENT_VERSION,
                        /*clientName*/ null,
                        /*parentProcessId*/ ctx.processId(),
                        initialPrompt,
                        /*senderProcessId*/ ctx.processId()));

        log.info("project_create: tenant='{}' project='{}' session='{}' chat='{}' parent='{}'",
                ctx.tenantId(), bootstrap.project().getName(), bootstrap.session().getSessionId(),
                bootstrap.chatProcess().getId(), ctx.processId());

        // Activity-Log: peers see this on their next recap.
        activityService.append(
                ctx.tenantId(), ctx.userId(),
                ctx.sessionId(), ctx.processId(),
                EddieActivityKind.PROJECT_CREATED,
                "Projekt `" + bootstrap.project().getName() + "` angelegt"
                        + (initialPrompt != null ? " mit initialer Aufgabe" : ""),
                List.of(EntityRef.project(bootstrap.project().getName()),
                        EntityRef.process(bootstrap.chatProcess().getId(),
                                bootstrap.chatProcess().getName())));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", bootstrap.project().getName());
        if (bootstrap.project().getTitle() != null) out.put("title", bootstrap.project().getTitle());
        out.put("projectGroupId", bootstrap.project().getProjectGroupId());
        out.put("sessionId", bootstrap.session().getSessionId());
        out.put("chatProcessId", bootstrap.chatProcess().getId());
        out.put("chatEngine", bootstrap.chatProcess().getThinkEngine());
        out.put("initialPromptDispatched", initialPrompt != null);
        if (kitName != null) {
            out.put("kitName", kitName);
            out.put("kitInstalled", kitInstallError == null);
            if (kitInstallError != null) {
                out.put("kitInstallError", kitInstallError);
            }
        }
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
