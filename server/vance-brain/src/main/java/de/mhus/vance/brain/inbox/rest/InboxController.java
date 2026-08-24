package de.mhus.vance.brain.inbox.rest;

import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.EffectDescription;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxAnswerRequest;
import de.mhus.vance.api.inbox.InboxCountResponse;
import de.mhus.vance.api.inbox.InboxMessagePostRequest;
import de.mhus.vance.api.inbox.InboxReadRequest;
import de.mhus.vance.api.inbox.InboxInviteRequest;
import de.mhus.vance.api.inbox.InboxParticipantRemoveRequest;
import de.mhus.vance.api.inbox.InboxFollowRequest;
import de.mhus.vance.api.inbox.InboxReactRequest;
import de.mhus.vance.shared.inbox.MaximegalonRuleException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import de.mhus.vance.api.inbox.InboxDelegateRequest;
import de.mhus.vance.api.inbox.MaximegalonDto;
import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.api.inbox.InboxListResponse;
import de.mhus.vance.api.inbox.InboxShareRequest;
import de.mhus.vance.api.inbox.InboxTagsResponse;
import de.mhus.vance.api.inbox.ResolvedBy;
import de.mhus.vance.brain.inbox.InboxMapper;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.inbox.InboxEffectRegistry;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
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

    private final MaximegalonService inboxItemService;
    private final InboxEffectRegistry effectRegistry;
    private final TeamService teamService;
    private final ProjectService projectService;
    private final RequestAuthority authority;
    private final de.mhus.vance.brain.inbox.InboxAuthz inboxAuthz;

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
            @RequestParam(value = "status", required = false) @Nullable MaximegalonStatus status,
            @RequestParam(value = "tag", required = false) @Nullable String tag,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.READ);
        String currentUser = currentUser(httpRequest);
        List<String> targetUsers = resolveTargetUsers(tenant, currentUser, assignedTo);
        List<MaximegalonDocument> docs = inboxItemService.listFiltered(
                tenant, targetUsers, status, tag);
        // The list query leaves the transcripts behind; the sizes come back in
        // a counting query, so a row can show that a thread has a discussion
        // without transferring it.
        List<MaximegalonDto> dtos = InboxMapper.toDtos(docs, inboxItemService.countMessages(
                tenant, docs.stream().map(MaximegalonDocument::getId).filter(Objects::nonNull).toList()));
        return InboxListResponse.builder().items(dtos).count(dtos.size()).build();
    }

    /**
     * Pending-item counts for the topbar badge. Same {@code assignedTo}
     * grammar (and therefore the same authorisation) as {@link #list} —
     * missing means the personal inbox.
     *
     * <p>Separate from {@link #list} on purpose: the badge renders on every
     * editor page and only needs two numbers, while the list transfers every
     * pending body and payload.
     */
    @GetMapping("/brain/{tenant}/inbox/count")
    public InboxCountResponse count(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "assignedTo", required = false) @Nullable String assignedTo,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.READ);
        String currentUser = currentUser(httpRequest);
        List<String> targetUsers = resolveTargetUsers(tenant, currentUser, assignedTo);
        MaximegalonService.PendingCounts counts =
                inboxItemService.countPending(tenant, targetUsers);
        // The badge counts unread, which is per-person by nature: there is no
        // such thing as "unread for a team". When a team view is requested the
        // alarm numbers stay at zero and only the stock is reported.
        MaximegalonService.BadgeCounts badge = assignedTo == null
                ? inboxItemService.countBadge(tenant, currentUser)
                : new MaximegalonService.BadgeCounts(0, 0, counts.total());
        return InboxCountResponse.builder()
                .unread(badge.unread())
                .unreadRequiresAction(badge.unreadRequiresAction())
                .pending(counts.total())
                .requiresAction(counts.requiresAction())
                .build();
    }

    /** Single item — same authorisation as list. */
    @GetMapping("/brain/{tenant}/inbox/{id}")
    public MaximegalonDto findOne(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {
        MaximegalonDocument doc = loadVisible(tenant, id, httpRequest);
        return InboxMapper.toDto(doc);
    }

    /**
     * Server-rendered facts about what answering this item will execute —
     * present only for items that carry an {@code InboxEffect}.
     *
     * <p>Separate from the item itself on purpose: these facts come from
     * the effect's own storage and stay current (a request may have
     * failed or lapsed since the item was written), while the item's body
     * carries only the requester's stated reason.
     *
     * @return 204 when the item declares no effect, or the effect has
     *         nothing to show
     */
    @GetMapping("/brain/{tenant}/inbox/{id}/effect")
    public ResponseEntity<EffectDescription> effect(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {
        MaximegalonDocument doc = loadVisible(tenant, id, httpRequest);
        return effectRegistry.describe(doc)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
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
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.READ);
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
    public ResponseEntity<MaximegalonDto> answer(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @Valid @RequestBody InboxAnswerRequest request,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        MaximegalonDocument doc = loadDecidable(tenant, id, httpRequest);
        authority.enforce(httpRequest, inboxResource(doc), Action.WRITE);
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
        MaximegalonDocument updated = inboxItemService.answer(
                        tenant, id, payload, ResolvedBy.USER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(InboxMapper.toDto(updated));
    }

    @PostMapping("/brain/{tenant}/inbox/{id}/archive")
    public ResponseEntity<MaximegalonDto> archive(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        MaximegalonDocument doc = loadDecidable(tenant, id, httpRequest);
        authority.enforce(httpRequest, inboxResource(doc), Action.WRITE);
        MaximegalonDocument updated = inboxItemService.archive(tenant, id, currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(InboxMapper.toDto(updated));
    }

    /**
     * Pulls an archived item back into the active inbox — restores
     * status to {@code ANSWERED} when an answer is on file, else
     * {@code PENDING}. No-op if the item isn't currently archived.
     */
    @PostMapping("/brain/{tenant}/inbox/{id}/unarchive")
    public ResponseEntity<MaximegalonDto> unarchive(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        MaximegalonDocument doc = loadDecidable(tenant, id, httpRequest);
        authority.enforce(httpRequest, inboxResource(doc), Action.WRITE);
        MaximegalonDocument updated = inboxItemService.unarchive(tenant, id, currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(InboxMapper.toDto(updated));
    }

    @PostMapping("/brain/{tenant}/inbox/{id}/dismiss")
    public ResponseEntity<MaximegalonDto> dismiss(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        MaximegalonDocument doc = loadDecidable(tenant, id, httpRequest);
        authority.enforce(httpRequest, inboxResource(doc), Action.WRITE);
        MaximegalonDocument updated = inboxItemService.dismiss(tenant, id, currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(InboxMapper.toDto(updated));
    }

    @PostMapping("/brain/{tenant}/inbox/{id}/delegate")
    public ResponseEntity<MaximegalonDto> delegate(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @Valid @RequestBody InboxDelegateRequest request,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        MaximegalonDocument doc = loadDecidable(tenant, id, httpRequest);
        authority.enforce(httpRequest, inboxResource(doc), Action.WRITE);
        if (request.getToUserId() == null || request.getToUserId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Delegate target userId is required");
        }
        // Delegating *delivers* into someone else's inbox, so it needs WRITE
        // there — the same rule invite() applies below, and for the same reason:
        // handing a matter to a person spends their attention. The check on the
        // item above only says the caller may settle this thread; it says
        // nothing about whose desk it may land on.
        authority.enforce(httpRequest,
                new Resource.InboxItem(tenant, "", request.getToUserId()), Action.WRITE);
        MaximegalonDocument updated = inboxItemService.delegate(
                        tenant, id, request.getToUserId(), currentUser, request.getNote())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(InboxMapper.toDto(updated));
    }

    // ────────────────── Thread ──────────────────
    //
    // All five gate on maySee, not mayDecide: contributing to a discussion is
    // not settling it. Being invited into a thread must never confer the right
    // to answer it — that would also confer the right to fire its effectType,
    // which grants permissions.

    /** Adds a contribution to the thread's clarification. */
    @PostMapping("/brain/{tenant}/inbox/{id}/messages")
    public ResponseEntity<MaximegalonDto> postMessage(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @Valid @RequestBody InboxMessagePostRequest request,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        loadVisible(tenant, id, httpRequest);
        MaximegalonDocument updated = inboxItemService.postMessage(
                        tenant, id, currentUser, request.getBody(), request.getParentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(InboxMapper.toDto(updated));
    }

    /**
     * Marks the thread — or named messages within it — read for the caller.
     *
     * <p>Never touches {@code status}: looking at a decision is not making it.
     */
    @PostMapping("/brain/{tenant}/inbox/{id}/read")
    public ResponseEntity<MaximegalonDto> markRead(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @Valid @RequestBody(required = false) @Nullable InboxReadRequest request,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        loadVisible(tenant, id, httpRequest);
        List<String> messageIds = request == null ? null : request.getMessageIds();
        Optional<MaximegalonDocument> updated = messageIds == null || messageIds.isEmpty()
                ? inboxItemService.markRead(tenant, id, currentUser)
                : inboxItemService.markMessagesRead(tenant, id, currentUser, messageIds);
        return ResponseEntity.ok(InboxMapper.toDto(
                updated.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))));
    }

    /**
     * Pulls someone into the thread.
     *
     * <p>Inviting <em>is</em> delivering, so it is checked like a delivery: the
     * caller needs {@code WRITE} on the <em>invitee's</em> inbox, the same gate
     * Milliways' inbox handler passes. Without that, anyone who can see a
     * thread could push it at anyone in the tenant.
     */
    @PostMapping("/brain/{tenant}/inbox/{id}/invite")
    public ResponseEntity<MaximegalonDto> invite(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @Valid @RequestBody InboxInviteRequest request,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        loadVisible(tenant, id, httpRequest);
        authority.enforce(httpRequest,
                new Resource.InboxItem(tenant, "", request.getUserId()), Action.WRITE);
        MaximegalonDocument updated = inboxItemService.invite(
                        tenant, id, request.getUserId(), currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(InboxMapper.toDto(updated));
    }

    /**
     * Takes someone back out of the thread.
     *
     * <p>The one thread endpoint gated on {@code mayDecide} rather than
     * {@code maySee}: deciding who is in the room belongs to running the
     * matter, and a participant removing another participant would make
     * membership self-governing. It is needed because participation is checked
     * before anything derived — joining turns a visibility that followed the
     * assignee into one that stays, and without this only the joiner could undo
     * it.
     */
    @PostMapping("/brain/{tenant}/inbox/{id}/participants/remove")
    public ResponseEntity<MaximegalonDto> removeParticipant(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @Valid @RequestBody InboxParticipantRemoveRequest request,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        MaximegalonDocument doc = loadDecidable(tenant, id, httpRequest);
        authority.enforce(httpRequest, inboxResource(doc), Action.WRITE);
        MaximegalonDocument updated = inboxItemService.removeParticipant(
                        tenant, id, request.getUserId(), currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(InboxMapper.toDto(updated));
    }

    /** Subscribes to or unsubscribes from the thread's updates. */
    @PostMapping("/brain/{tenant}/inbox/{id}/follow")
    public ResponseEntity<MaximegalonDto> follow(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @Valid @RequestBody InboxFollowRequest request,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        loadVisible(tenant, id, httpRequest);
        MaximegalonDocument updated = inboxItemService.setFollowing(
                        tenant, id, currentUser, request.isFollowing())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(InboxMapper.toDto(updated));
    }

    /** Toggles the caller's emoji reaction on the thread or on one message. */
    @PostMapping("/brain/{tenant}/inbox/{id}/react")
    public ResponseEntity<MaximegalonDto> react(
            @PathVariable("tenant") String tenant,
            @PathVariable("id") String id,
            @Valid @RequestBody InboxReactRequest request,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        loadVisible(tenant, id, httpRequest);
        MaximegalonDocument updated = inboxItemService.react(
                        tenant, id, request.getMessageId(), request.getKey(), currentUser,
                        request.isOn())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(InboxMapper.toDto(updated));
    }

    /**
     * A thread invariant refused the call — the assignee of an open ask trying
     * to unsubscribe, a full thread, a reply to a reply. 409 with the reason as
     * a stable code, so the client can say which rule it hit rather than "an
     * error occurred".
     */
    @ExceptionHandler(MaximegalonRuleException.class)
    public ResponseEntity<Map<String, String>> onRuleViolation(MaximegalonRuleException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("reason", e.getReason(), "message", e.getMessage()));
    }

    /**
     * Drop a shared item (text / URL / note) into the authenticated
     * user's inbox, scoped to the chosen project. Endpoint used by
     * the iOS Share-Extension in {@code @vance/facelift-bridge} —
     * the extension authenticates with a long-lived bearer token
     * minted at login time and stored in the App-Group keychain.
     *
     * <p>The item is created as an {@link MaximegalonType#OUTPUT_TEXT}
     * with {@code requiresAction=false} so the inbox treats it as a
     * delivered note the user can read, archive, move to Documents,
     * or attach to a chat later. The project association lives in
     * the payload (under {@code projectName}) and as a tag prefix
     * (so the existing tag-filter UI picks it up).
     */
    @PostMapping("/brain/{tenant}/share/inbox")
    public ResponseEntity<MaximegalonDto> shareInbox(
            @PathVariable("tenant") String tenant,
            @Valid @RequestBody InboxShareRequest request,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.READ);
        String currentUser = currentUser(httpRequest);

        ProjectDocument project = projectService
                .findByTenantAndName(tenant, request.getProjectName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Project not found: " + request.getProjectName()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectName", project.getName());
        payload.put("source", "share-extension");
        if (StringUtils.isNotBlank(request.getSharedUrl())) {
            payload.put("sharedUrl", request.getSharedUrl());
        }

        List<String> tags = new ArrayList<>();
        tags.add("share");
        tags.add("project:" + project.getName());

        String title = StringUtils.defaultIfBlank(request.getTitle(), "Shared item");

        MaximegalonDocument toCreate = MaximegalonDocument.builder()
                .tenantId(tenant)
                .originatorUserId(currentUser)
                .assignedToUserId(currentUser)
                .type(MaximegalonType.OUTPUT_TEXT)
                .criticality(Criticality.NORMAL)
                .status(MaximegalonStatus.PENDING)
                .requiresAction(false)
                .title(title)
                .body(request.getBody())
                .tags(tags)
                .payload(payload)
                .build();

        MaximegalonDocument created = inboxItemService.create(toCreate);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(InboxMapper.toDto(created));
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
     * userIds the {@link MaximegalonService} should filter on.
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
        if (!inboxAuthz.sharesTeam(tenant, currentUser, assignedTo)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        List<String> out = new ArrayList<>();
        out.add(assignedTo);
        return out;
    }

    /**
     * Loads the item, validates tenant, and checks the current
     * user is allowed to see/touch it (own inbox or shared team).
     */
    private static Resource.InboxItem inboxResource(MaximegalonDocument doc) {
        return new Resource.InboxItem(
                doc.getTenantId() == null ? "" : doc.getTenantId(),
                doc.getId() == null ? "" : doc.getId(),
                doc.getAssignedToUserId() == null ? "" : doc.getAssignedToUserId());
    }

    /**
     * Loads a thread the caller may <b>read</b> — assignee, their team, a
     * participant or the declared team. Used by the two read endpoints and by
     * everything that only contributes (messages, reads, reactions).
     */
    private MaximegalonDocument loadVisible(
            String tenant, String id, HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        MaximegalonDocument doc = loadInTenant(tenant, id);
        // The permission provider is asked first and stays the authority — it
        // is the replaceable part (simple-auth, EE governor, …). Participation
        // and the declared team are then checked as properties of the document,
        // which is why Resource.InboxItem needs no new field: an invitation was
        // authorized when it happened, and membership is the answer afterwards.
        // Order matters — checking only the provider would make participants
        // unreachable, checking only the document would bypass the provider.
        if (authority.check(httpRequest, inboxResource(doc), Action.READ)) {
            return doc;
        }
        if (inboxAuthz.maySee(tenant, currentUser, doc)) {
            return doc;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    /**
     * Loads a thread the caller may <b>decide</b> on — answer, dismiss,
     * delegate, archive. Same rule and same population as before the thread
     * work: being invited into a discussion does not confer the right to
     * settle it.
     */
    private MaximegalonDocument loadDecidable(
            String tenant, String id, HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        MaximegalonDocument doc = loadInTenant(tenant, id);
        if (!inboxAuthz.mayDecide(tenant, currentUser, doc.getAssignedToUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return doc;
    }

    /**
     * Existence and tenant check, shared by both gates. Not-found and
     * not-allowed both answer 404 on purpose: a 403 would confirm that an item
     * with that id exists in this tenant.
     */
    private MaximegalonDocument loadInTenant(String tenant, String id) {
        Optional<MaximegalonDocument> opt = inboxItemService.findById(tenant, id);
        if (opt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        MaximegalonDocument doc = opt.get();
        if (!tenant.equals(doc.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return doc;
    }
}
