package de.mhus.vance.brain.session;

import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.session.SessionSummaryRichDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST list endpoint for the Web/Mobile session pickers. Returns the
 * caller's own sessions only — admin cross-user listing lives on the
 * insights inspector path.
 *
 * <p>Default filter excludes {@code CLOSED} (terminal, eligible for
 * hard-delete) and {@code ARCHIVED} (long-term storage, hidden from
 * the active view). Set {@code includeArchived=true} to surface the
 * archive; pass an explicit {@code status} list to override entirely.
 *
 * <p>See {@code specification/session-lifecycle.md} §15.1.
 */
@RestController
@RequestMapping("/brain/{tenant}/sessions")
@RequiredArgsConstructor
@Slf4j
public class SessionListController {

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final RequestAuthority authority;

    @GetMapping
    public List<SessionSummaryRichDto> list(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            @RequestParam(value = "status", required = false) @Nullable List<String> statusCsv,
            @RequestParam(value = "includeArchived", required = false, defaultValue = "false")
                    boolean includeArchived,
            @RequestParam(value = "tag", required = false) @Nullable String tag,
            HttpServletRequest request) {
        String currentUser = currentUser(request);

        Set<SessionStatus> statuses = resolveStatuses(statusCsv, includeArchived);

        List<SessionDocument> sessions = sessionService.listWithFilters(
                tenant, currentUser, projectId, statuses, tag);

        // Per-record authority enforcement is overkill for an
        // owner-scoped list — the listWithFilters query already binds
        // userId. We only enforce when projectId is given (project-
        // level access can be revoked independently of session ownership).
        if (projectId != null && !projectId.isBlank()) {
            authority.enforce(request,
                    new Resource.Project(tenant, projectId), Action.READ);
        }

        Map<String, String> recipeByProcessId = collectChatRecipes(sessions);
        List<SessionSummaryRichDto> out = new ArrayList<>(sessions.size());
        for (SessionDocument s : sessions) {
            String recipe = s.getChatProcessId() == null
                    ? null
                    : recipeByProcessId.get(s.getChatProcessId());
            out.add(toDto(s, recipe));
        }
        return out;
    }

    /**
     * Batch-resolve {@code chatProcessId → recipeName} for the listed
     * sessions in one repo call. Sessions without a linked chat process
     * (newly created, pre-bootstrapper) and processes spawned without
     * a recipe (engine-default) both surface as {@code null} on the DTO.
     */
    private Map<String, String> collectChatRecipes(List<SessionDocument> sessions) {
        Set<String> chatProcessIds = new LinkedHashSet<>();
        for (SessionDocument s : sessions) {
            String id = s.getChatProcessId();
            if (id != null && !id.isBlank()) {
                chatProcessIds.add(id);
            }
        }
        if (chatProcessIds.isEmpty()) return Map.of();
        Map<String, String> byProcessId = new HashMap<>(chatProcessIds.size());
        for (ThinkProcessDocument p : thinkProcessService.findByIds(chatProcessIds)) {
            if (p.getRecipeName() != null && !p.getRecipeName().isBlank()) {
                byProcessId.put(p.getId(), p.getRecipeName());
            }
        }
        return byProcessId;
    }

    private static Set<SessionStatus> resolveStatuses(
            @Nullable List<String> raw, boolean includeArchived) {
        if (raw == null || raw.isEmpty()) {
            EnumSet<SessionStatus> defaults = EnumSet.of(
                    SessionStatus.INIT,
                    SessionStatus.RUNNING,
                    SessionStatus.IDLE,
                    SessionStatus.SUSPENDED);
            if (includeArchived) {
                defaults.add(SessionStatus.ARCHIVED);
            }
            return defaults;
        }
        EnumSet<SessionStatus> parsed = EnumSet.noneOf(SessionStatus.class);
        for (String r : raw) {
            if (r == null || r.isBlank()) continue;
            for (String v : r.split(",")) {
                try {
                    parsed.add(SessionStatus.valueOf(v.trim().toUpperCase(java.util.Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Unknown session status: " + v);
                }
            }
        }
        return parsed;
    }

    /**
     * Search hits don't carry the chat-recipe today — the search service
     * builds DTOs from {@link SessionDocument} alone and {@link
     * de.mhus.vance.shared.thinkprocess.ThinkProcessService} isn't on its
     * dependency surface. The list endpoint above does the join.
     */
    static SessionSummaryRichDto toDto(SessionDocument s) {
        return toDto(s, null);
    }

    static SessionSummaryRichDto toDto(SessionDocument s, @Nullable String chatRecipe) {
        List<String> tags = s.getTags() == null
                ? List.of()
                : new ArrayList<>(s.getTags());
        return SessionSummaryRichDto.builder()
                .sessionId(s.getSessionId())
                .projectId(s.getProjectId())
                .status(s.getStatus())
                .profile(s.getProfile())
                .createdAt(s.getCreatedAt())
                .lastActivityAt(s.getLastActivityAt())
                .suspendedAt(s.getSuspendedAt())
                .archivedAt(s.getArchivedAt())
                .bound(s.getBoundConnectionId() != null)
                .title(s.getTitle())
                .titleAutoGenerated(s.isTitleAutoGenerated())
                .icon(s.getIcon())
                .color(s.getColor())
                .tags(tags)
                .pinned(s.isPinned())
                .chatRecipe(chatRecipe)
                .firstUserMessage(s.getFirstUserMessage())
                .lastMessagePreview(s.getLastMessagePreview())
                .lastMessageRole(s.getLastMessageRole())
                .lastMessageAt(s.getLastMessageAt())
                .openDocumentIds(s.getOpenDocumentIds() == null
                        ? List.of()
                        : List.copyOf(s.getOpenDocumentIds()))
                .chatBoundDocumentId(s.getChatBoundDocumentId())
                .build();
    }

    private static String currentUser(HttpServletRequest request) {
        Object u = request.getAttribute(AccessFilterBase.ATTR_USERNAME);
        if (!(u instanceof String s) || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "No authenticated user");
        }
        return s;
    }
}
