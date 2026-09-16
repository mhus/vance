package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionListRequest;
import de.mhus.vance.api.ws.SessionListResponse;
import de.mhus.vance.api.ws.SessionSummary;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Lists the caller's sessions in the current tenant. Optional
 * {@code projectId} filter narrows the result to a single project. Allowed
 * with or without a bound session.
 */
@Component
@RequiredArgsConstructor
public class SessionListHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final SessionService sessionService;
    private final de.mhus.vance.shared.thinkprocess.ThinkProcessService thinkProcessService;
    private final de.mhus.vance.brain.chattheme.ChatThemeResolver chatThemeResolver;
    private final RequestAuthority authority;

    @Override
    public String type() {
        return MessageType.SESSION_LIST;
    }

    @Override
    public boolean canExecute(ConnectionContext ctx) {
        return true;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        String projectId = null;
        if (envelope.getData() != null) {
            try {
                SessionListRequest request = objectMapper.convertValue(envelope.getData(), SessionListRequest.class);
                if (request != null) {
                    projectId = request.getProjectId();
                }
            } catch (IllegalArgumentException e) {
                sender.sendError(wsSession, envelope, 400, "Invalid session.list payload: " + e.getMessage());
                return;
            }
        }

        authority.enforce(ctx, new Resource.Tenant(ctx.getTenantId()), Action.READ);

        List<SessionDocument> documents = isBlank(projectId)
                ? sessionService.listForUser(ctx.getTenantId(), ctx.getUserId())
                : sessionService.listForUserAndProject(ctx.getTenantId(), ctx.getUserId(), projectId);

        // Chat theme per session: chatProcessId → recipeName (one batched
        // repo call, same join as the REST session list), then recipeName →
        // webTheme (memoised per request — one recipe load per
        // (project, recipe), not per session).
        java.util.Map<String, String> recipeByProcessId = collectChatRecipes(documents);
        java.util.Map<String, String> themeMemo = new java.util.HashMap<>();

        List<SessionSummary> summaries = documents.stream()
                .map(doc -> toSummary(
                        doc,
                        doc.getChatProcessId() == null
                                ? null
                                : chatThemeFor(
                                        ctx.getTenantId(),
                                        doc.getProjectId(),
                                        recipeByProcessId.get(doc.getChatProcessId()),
                                        themeMemo)))
                .toList();
        SessionListResponse response =
                SessionListResponse.builder().sessions(summaries).build();
        sender.sendReply(wsSession, envelope, MessageType.SESSION_LIST, response);
    }

    private static SessionSummary toSummary(SessionDocument doc, @Nullable String chatTheme) {
        return SessionSummary.builder()
                .sessionId(doc.getSessionId())
                .projectId(doc.getProjectId())
                .status(doc.getStatus().name())
                .createdAt(toEpochMillis(doc.getCreatedAt()))
                .lastActivityAt(toEpochMillis(doc.getLastActivityAt()))
                .bound(doc.getBoundConnectionId() != null)
                .displayName(doc.getDisplayName())
                .title(doc.getTitle())
                .icon(doc.getIcon())
                .color(doc.getColor())
                .tags(doc.getTags() == null ? java.util.List.of() : new java.util.ArrayList<>(doc.getTags()))
                .pinned(doc.isPinned())
                .profile(doc.getProfile())
                .firstUserMessage(doc.getFirstUserMessage())
                .lastMessagePreview(doc.getLastMessagePreview())
                .chatTheme(chatTheme)
                .build();
    }

    /**
     * Batch-resolve {@code chatProcessId → recipeName} in one repo
     * call — the same join the REST session list runs. Processes
     * spawned without a recipe surface as absent, which
     * {@code ChatThemeResolver.effectiveThemeName} maps to the default
     * theme.
     */
    private java.util.Map<String, String> collectChatRecipes(List<SessionDocument> documents) {
        java.util.Set<String> chatProcessIds = new java.util.LinkedHashSet<>();
        for (SessionDocument s : documents) {
            String id = s.getChatProcessId();
            if (id != null && !id.isBlank()) chatProcessIds.add(id);
        }
        if (chatProcessIds.isEmpty()) return java.util.Map.of();
        java.util.Map<String, String> byProcessId = new java.util.HashMap<>(chatProcessIds.size());
        for (de.mhus.vance.shared.thinkprocess.ThinkProcessDocument p : thinkProcessService.findByIds(chatProcessIds)) {
            if (p.getRecipeName() != null && !p.getRecipeName().isBlank()) {
                byProcessId.put(p.getId(), p.getRecipeName());
            }
        }
        return byProcessId;
    }

    /** Memoised {@code recipe → webTheme} resolution, per request. */
    private String chatThemeFor(
            String tenant, String projectId, @Nullable String recipe, java.util.Map<String, String> memo) {
        if (recipe == null) return null;
        String key = projectId + "\n" + recipe;
        return memo.computeIfAbsent(key, k -> chatThemeResolver.effectiveThemeName(tenant, projectId, recipe));
    }

    private static long toEpochMillis(@Nullable Instant instant) {

        return instant == null ? 0L : instant.toEpochMilli();
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
