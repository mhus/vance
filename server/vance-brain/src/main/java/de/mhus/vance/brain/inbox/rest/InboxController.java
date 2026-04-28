package de.mhus.vance.brain.inbox.rest;

import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.InboxAnswerRequest;
import de.mhus.vance.api.inbox.InboxDelegateRequest;
import de.mhus.vance.api.inbox.InboxItemDto;
import de.mhus.vance.api.inbox.InboxItemStatus;
import de.mhus.vance.api.inbox.InboxListResponse;
import de.mhus.vance.api.inbox.InboxTagsResponse;
import de.mhus.vance.api.inbox.ResolvedBy;
import de.mhus.vance.brain.inbox.InboxMapper;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoints for the Web-UI inbox editor. WebSocket variants
 * (used by {@code vance-foot}) live under
 * {@code de.mhus.vance.brain.inbox.handlers.*} and stay untouched —
 * this controller is a parallel REST facade with the same semantics.
 *
 * <p>Tenant in the path is validated by {@code BrainAccessFilter}
 * against the JWT's {@code tid} claim. The {@code username} claim
 * drives the cross-user authorisation rule:
 *
 * <ul>
 *   <li>An item with {@code assignedToUserId == currentUser} is
 *       always reachable (personal inbox).</li>
 *   <li>An item with a different assignee is reachable iff that
 *       assignee shares a {@link TeamDocument} with the current
 *       user (team inbox).</li>
 *   <li>Anything else → 404 (we hide existence rather than 403).</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class InboxController {

    private final InboxItemService inboxItemService;
    private final TeamService teamService;

    // ──────────────────── Read ────────────────────

    /**
     * List inbox items. Filters:
     * <ul>
     *   <li>{@code assignedTo} — single userId. {@code "self"} (or
     *       missing) means current user. {@code "team:<teamName>"}
     *       expands to all members of that team <em>except</em> the
     *       current user (the team-inbox view).</li>
     *   <li>{@code status} — {@code PENDING / ANSWERED / DISMISSED /
     *       ARCHIVED}. Missing → all statuses.</li>
     *   <li>{@code tag} — single tag. Missing → no tag filter.</li>
     * </ul>
     */
    @GetMapping("/brain/{tenant}/inbox")
    public InboxListResponse list(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "assignedTo", required = false) @Nullable String assignedTo,
            @RequestParam(value = "status", required = false) @Nullable InboxItemStatus status,
            @RequestParam(value = "tag", required = false) @Nullable String tag,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        List<String> targetUsers = resolveTargetUsers(tenant, currentUser, assignedTo);
        List<InboxItemDocument> docs = inboxItemService.listFiltered(
                tenant, targetUsers, status, tag);
        List<InboxItemDto> dtos = InboxMapper.toDtos(docs);
        return InboxListResponse.builder().items(dtos).count(dtos.size()).build();
    }

    /** Single item — same authorisation as list. */
    @GetMapping("/brain/{tenant}/inbox/{id}")
    public InboxItemDto findOne(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {
        InboxItemDocument doc = loadAuthorized(tenant, id, httpRequest);
        return InboxMapper.toDto(doc);
    }

    /**
     * Distinct tags currently in use across the items the user
     * may see (own inbox + every team they're a member of). Used
     * by the sidebar to render the tag list.
     */
    @GetMapping("/brain/{tenant}/inbox/tags")
    public InboxTagsResponse tags(
            @PathVariable("tenant") String tenant,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        Set<String> userScope = new LinkedHashSet<>();
        userScope.add(currentUser);
        for (TeamDocument t : teamService.byMember(tenant, currentUser)) {
            if (t.getMembers() != null) userScope.addAll(t.getMembers());
        }
        List<String> tags = inboxItemService.distinctTags(tenant, new ArrayList<>(userScope));
        tags.sort(String::compareToIgnoreCase);
        return InboxTagsResponse.builder().tags(tags).build();
    }

    // ──────────────────── Mutations ────────────────────

    @PostMapping("/brain/{tenant}/inbox/{id}/answer")
    public ResponseEntity<InboxItemDto> answer(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @Valid @RequestBody InboxAnswerRequest request,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        loadAuthorized(tenant, id, httpRequest);
        // The wire-DTO is flat (outcome / value / reason). Build
        // the AnswerPayload here, stamping the resolver with the
        // JWT's username claim — never trust a client-side
        // {@code answeredBy}.
        AnswerPayload payload = AnswerPayload.builder()
                .outcome(request.getOutcome())
                .value(request.getValue())
                .reason(request.getReason())
                .answeredBy(currentUser)
                .build();
        InboxItemDocument updated = inboxItemService.answer(
                        tenant, id, payload, ResolvedBy.USER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(InboxMapper.toDto(updated));
    }

    @PostMapping("/brain/{tenant}/inbox/{id}/archive")
    public ResponseEntity<InboxItemDto> archive(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        loadAuthorized(tenant, id, httpRequest);
        InboxItemDocument updated = inboxItemService.archive(tenant, id, currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(InboxMapper.toDto(updated));
    }

    /**
     * Pulls an archived item back into the active inbox — restores
     * status to {@code ANSWERED} when an answer is on file, else
     * {@code PENDING}. No-op if the item isn't currently archived.
     */
    @PostMapping("/brain/{tenant}/inbox/{id}/unarchive")
    public ResponseEntity<InboxItemDto> unarchive(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        loadAuthorized(tenant, id, httpRequest);
        InboxItemDocument updated = inboxItemService.unarchive(tenant, id, currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(InboxMapper.toDto(updated));
    }

    @PostMapping("/brain/{tenant}/inbox/{id}/dismiss")
    public ResponseEntity<InboxItemDto> dismiss(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        loadAuthorized(tenant, id, httpRequest);
        InboxItemDocument updated = inboxItemService.dismiss(tenant, id, currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(InboxMapper.toDto(updated));
    }

    @PostMapping("/brain/{tenant}/inbox/{id}/delegate")
    public ResponseEntity<InboxItemDto> delegate(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @Valid @RequestBody InboxDelegateRequest request,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        loadAuthorized(tenant, id, httpRequest);
        if (request.getToUserId() == null || request.getToUserId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Delegate target userId is required");
        }
        InboxItemDocument updated = inboxItemService.delegate(
                        tenant, id, request.getToUserId(), currentUser, request.getNote())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(InboxMapper.toDto(updated));
    }

    // ──────────────────── Authorization helpers ────────────────────

    private String currentUser(HttpServletRequest httpRequest) {
        Object u = httpRequest.getAttribute(AccessFilterBase.ATTR_USERNAME);
        if (!(u instanceof String s) || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "No authenticated user");
        }
        return s;
    }

    /**
     * Map an {@code assignedTo}-query-param to the concrete list of
     * userIds the {@link InboxItemService} should filter on.
     *
     * <ul>
     *   <li>{@code null} or {@code "self"} → {@code [currentUser]}</li>
     *   <li>{@code "team:<teamName>"} → all members of that team
     *       <em>excluding</em> {@code currentUser}. The current
     *       user must be a member of the team; otherwise 404.</li>
     *   <li>any other string → {@code [that userId]} — but only if
     *       the user shares a team with that user, otherwise 404.</li>
     * </ul>
     */
    private List<String> resolveTargetUsers(
            String tenant, String currentUser, @Nullable String assignedTo) {
        if (assignedTo == null || assignedTo.isBlank() || "self".equalsIgnoreCase(assignedTo)) {
            List<String> out = new ArrayList<>();
            out.add(currentUser);
            return out;
        }
        if (assignedTo.startsWith("team:")) {
            String teamName = assignedTo.substring("team:".length()).trim();
            TeamDocument team = teamService.findByTenantAndName(tenant, teamName)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Unknown team '" + teamName + "'"));
            List<String> members = team.getMembers() == null
                    ? new ArrayList<>() : team.getMembers();
            if (!members.contains(currentUser)) {
                // Hide existence — same as 404 elsewhere.
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown team '" + teamName + "'");
            }
            // Team-inbox shows OTHER members' items only; the user's
            // own items live in the personal-inbox view.
            List<String> others = new ArrayList<>(members);
            others.remove(currentUser);
            return others;
        }
        // Specific userId — only allowed if shared team.
        if (!sharesTeam(tenant, currentUser, assignedTo)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        List<String> out = new ArrayList<>();
        out.add(assignedTo);
        return out;
    }

    private boolean sharesTeam(String tenant, String userA, String userB) {
        if (userA.equals(userB)) return true;
        for (TeamDocument t : teamService.byMember(tenant, userA)) {
            if (t.getMembers() != null && t.getMembers().contains(userB)) return true;
        }
        return false;
    }

    /**
     * Loads the item, validates tenant, and checks the current
     * user is allowed to see/touch it (own inbox or shared team).
     */
    private InboxItemDocument loadAuthorized(
            String tenant, String id, HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        Optional<InboxItemDocument> opt = inboxItemService.findById(tenant, id);
        if (opt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        InboxItemDocument doc = opt.get();
        if (!tenant.equals(doc.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String assignee = doc.getAssignedToUserId();
        if (assignee == null
                || (!assignee.equals(currentUser) && !sharesTeam(tenant, currentUser, assignee))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return doc;
    }
}
