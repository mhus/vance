package de.mhus.vance.brain.session;

import de.mhus.vance.api.session.SessionMetadataPatchRequest;
import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sharing toggle for the insights inspector: flips
 * {@code allowMultipleClients} — the flag that makes a session joinable by
 * every connection in the tenant ("shared", see
 * {@code specification/multi-user-sessions.md} §1). This is the mutation
 * half of the inspector's share button; the read half stays on the
 * read-only {@code InsightsAdminController}
 * ({@code planning/session-control.md} §2.2).
 *
 * <p>Admin-gated ({@code Action.ADMIN} on the session), not owner-gated —
 * the same contract as {@link EngineStopAdminController}: this surface
 * exists to administer <em>other</em> people's sessions. The owner's own
 * toggle stays on the owner-only paths ({@code session-metadata-patch} on
 * the WS, {@code PATCH /sessions/{id}/metadata} on REST).
 *
 * <p>Answers 204 like the stop endpoints — the caller reads the new state
 * back from the read endpoints. The flip goes through
 * {@link SessionService#patchMetadata}, so its system-session guard holds:
 * a hub session stays private. CLOSED sessions are refused outright — a
 * dead session has no participants left to admit.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin")
@RequiredArgsConstructor
@Slf4j
public class SessionSharingAdminController {

    private final SessionService sessionService;
    private final RequestAuthority authority;

    /** Target state — explicit, so the endpoint is idempotent. */
    public record SharingRequest(Boolean shared) {}

    @PostMapping("/sessions/{sessionId}/sharing")
    public ResponseEntity<Void> setSharing(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            @RequestBody SharingRequest body,
            HttpServletRequest httpRequest) {
        if (body == null || body.shared() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "shared is required");
        }
        SessionDocument session = requireSession(tenant, sessionId, httpRequest);
        if (session.getStatus() == SessionStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot change sharing on a CLOSED session");
        }
        if (session.isSystem()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "System sessions stay private");
        }
        boolean shared = body.shared();
        log.info("insights sharing: session='{}' shared={}", session.getSessionId(), shared);
        sessionService.patchMetadata(
                session.getSessionId(),
                SessionMetadataPatchRequest.builder()
                        .allowMultipleClients(shared)
                        .build());
        return ResponseEntity.noContent().build();
    }

    private SessionDocument requireSession(String tenant, String sessionId, HttpServletRequest httpRequest) {
        SessionDocument session = sessionService
                .findBySessionId(sessionId)
                .filter(s -> tenant.equals(s.getTenantId()))
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Session '" + sessionId + "' not found"));
        authority.enforce(
                httpRequest,
                new Resource.Session(tenant, session.getProjectId(), session.getSessionId()),
                Action.ADMIN);
        return session;
    }
}
