package de.mhus.vance.brain.session;

import de.mhus.vance.api.common.AccentColor;
import de.mhus.vance.api.session.SessionCompactResponse;
import de.mhus.vance.api.session.SessionDuplicateRequest;
import de.mhus.vance.api.session.SessionDuplicateResponse;
import de.mhus.vance.api.session.SessionMetadataDto;
import de.mhus.vance.api.session.SessionMetadataPatchRequest;
import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.brain.memory.CompactionResult;
import de.mhus.vance.brain.memory.MemoryCompactionService;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Session-level user actions: archive, reactivate, hard-delete, and the
 * metadata patch. WebSocket-style chat flow is unaffected — these are
 * out-of-band REST operations that the Web/Mobile UIs reach for.
 *
 * <p>See {@code specification/session-lifecycle.md} §11, §12.2, §14.2.
 *
 * <p>Tenant in the path is validated by
 * {@link de.mhus.vance.brain.access.BrainAccessFilter} against the JWT's
 * {@code tid} claim before the request reaches this controller; per-
 * record this controller additionally enforces session ownership.
 */
@RestController
@RequestMapping("/brain/{tenant}/sessions")
@RequiredArgsConstructor
@Slf4j
public class SessionLifecycleController {

    private final SessionService sessionService;
    private final SessionLifecycleService lifecycleService;
    private final SessionDuplicationService duplicationService;
    private final ThinkProcessService thinkProcessService;
    private final MemoryCompactionService compactionService;
    private final LaneScheduler laneScheduler;
    private final RequestAuthority authority;
    private final ProcessEventEmitter processEventEmitter;

    /**
     * How long the HTTP call waits for the lane-scheduled compaction
     * before returning a {@code deferred} response. The compaction keeps
     * running on the lane; the client just doesn't learn the concrete
     * result within this window (a turn ahead of it may be long).
     */
    private static final long COMPACT_TIMEOUT_MS = 60_000L;

    @PostMapping("/{sessionId}/archive")
    public ResponseEntity<Void> archive(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            HttpServletRequest request) {
        SessionDocument session = requireOwnedSession(tenant, sessionId, request);
        authority.enforce(request,
                new Resource.Session(tenant, session.getProjectId(), session.getSessionId()),
                Action.EXECUTE);
        if (session.getStatus() == SessionStatus.ARCHIVED
                || session.getStatus() == SessionStatus.CLOSED) {
            // No-op for terminal/archived sessions — 204 keeps the call idempotent
            return ResponseEntity.noContent().build();
        }
        lifecycleService.archiveWithCascade(sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Resume a SUSPENDED session — generic over all suspend causes
     * (IDLE, DISCONNECT, FORCED). Engines transition back to
     * {@code IDLE}, runtime-suspend fields are cleared, pending
     * messages get drained. See {@code specification/session-lifecycle.md}
     * §10.2.
     *
     * <p>Idempotent: 204 for an already-active session too. Use
     * {@code /reactivate} (separate endpoint) for {@code ARCHIVED}
     * sessions — those need their engines re-spawned, not resumed.
     */
    @PostMapping("/{sessionId}/resume")
    public ResponseEntity<Void> resume(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            HttpServletRequest request) {
        SessionDocument session = requireOwnedSession(tenant, sessionId, request);
        authority.enforce(request,
                new Resource.Session(tenant, session.getProjectId(), session.getSessionId()),
                Action.START);
        if (session.getStatus() == SessionStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Session is CLOSED and cannot be resumed");
        }
        if (session.getStatus() == SessionStatus.ARCHIVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Session is ARCHIVED — use /reactivate instead");
        }
        lifecycleService.resumeSessionCascade(sessionId, processEventEmitter);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{sessionId}/reactivate")
    public ResponseEntity<Void> reactivate(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            HttpServletRequest request) {
        SessionDocument session = requireOwnedSession(tenant, sessionId, request);
        authority.enforce(request,
                new Resource.Session(tenant, session.getProjectId(), session.getSessionId()),
                Action.START);
        if (session.getStatus() == SessionStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Session is CLOSED and cannot be reactivated");
        }
        if (session.getStatus() != SessionStatus.ARCHIVED) {
            // Already in an active state — symmetric with archive(),
            // which no-ops on already-archived sessions. Two reactivate
            // calls in a row both succeed with 204.
            return ResponseEntity.noContent().build();
        }
        lifecycleService.reactivateFromArchive(sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Duplicate a session together with its chat memory — the copy is a
     * fresh, resumable session in the same project. Returns the new
     * session's business id + resolved title. Owner-only. See
     * {@code specification/public/session-duplicate.md}.
     */
    @PostMapping("/{sessionId}/duplicate")
    public SessionDuplicateResponse duplicate(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            @RequestBody(required = false) @Nullable SessionDuplicateRequest body,
            HttpServletRequest request) {
        SessionDocument session = requireOwnedSession(tenant, sessionId, request);
        authority.enforce(request,
                new Resource.Session(tenant, session.getProjectId(), session.getSessionId()),
                Action.EXECUTE);
        String newTitle = body == null ? null : body.getTitle();
        SessionDuplicationService.DuplicateResult result =
                duplicationService.duplicate(sessionId, newTitle);
        return SessionDuplicateResponse.builder()
                .sessionId(result.newSessionId())
                .title(result.title())
                .build();
    }

    /**
     * Manually compact the session's chat memory now — the same
     * sliding-window compaction the engine runs on its automatic
     * threshold, folding older turns into an ARCHIVED_CHAT summary. Runs
     * on the chat process's lane so it serializes with turns and can never
     * corrupt an in-flight prompt. A no-op (nothing left to compact) is a
     * normal outcome carried in the response {@code reason}, not an error.
     * Owner-only. See {@code specification/public/session-compact.md}.
     */
    @PostMapping("/{sessionId}/compact")
    public SessionCompactResponse compact(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            HttpServletRequest request) {
        SessionDocument session = requireOwnedSession(tenant, sessionId, request);
        authority.enforce(request,
                new Resource.Session(tenant, session.getProjectId(), session.getSessionId()),
                Action.EXECUTE);

        String chatProcessId = session.getChatProcessId();
        if (chatProcessId == null || chatProcessId.isBlank()) {
            return SessionCompactResponse.builder()
                    .compacted(false).reason("no chat process").build();
        }
        ThinkProcessDocument process = thinkProcessService.findById(chatProcessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Chat process not found for session '" + sessionId + "'"));

        try {
            CompactionResult r = laneScheduler
                    .submit(chatProcessId, () -> compactionService.compact(process))
                    .get(COMPACT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return SessionCompactResponse.builder()
                    .compacted(r.compacted())
                    .messagesCompacted(r.messagesCompacted())
                    .summaryChars(r.summaryChars())
                    .memoryId(r.memoryId())
                    .reason(r.reason())
                    .deferred(false)
                    .build();
        } catch (TimeoutException e) {
            // Still queued on the lane (a turn was running); it will complete
            // between turns. The client just doesn't get the result now.
            return SessionCompactResponse.builder()
                    .compacted(false).deferred(true).reason("deferred").build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Interrupted while compacting");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Manual compaction failed session='{}'", sessionId, cause);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Compaction failed: " + cause.getMessage());
        }
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> delete(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            HttpServletRequest request) {
        SessionDocument session = requireOwnedSession(tenant, sessionId, request);
        authority.enforce(request,
                new Resource.Session(tenant, session.getProjectId(), session.getSessionId()),
                Action.DELETE);
        lifecycleService.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{sessionId}/metadata")
    public SessionMetadataDto patchMetadata(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            @RequestBody SessionMetadataPatchRequest patch,
            HttpServletRequest request) {
        SessionDocument session = requireOwnedSession(tenant, sessionId, request);
        authority.enforce(request,
                new Resource.Session(tenant, session.getProjectId(), session.getSessionId()),
                Action.WRITE);
        if (session.getStatus() == SessionStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot patch metadata on a CLOSED session");
        }
        sessionService.patchMetadata(sessionId, patch);
        SessionDocument refreshed = sessionService.findBySessionId(sessionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Session disappeared mid-patch"));
        return toDto(refreshed);
    }

    // -------------------------------------------------------------- helpers

    private SessionDocument requireOwnedSession(
            String tenant, String sessionId, HttpServletRequest request) {
        String currentUser = currentUser(request);
        SessionDocument session = sessionService.findBySessionId(sessionId)
                .filter(s -> tenant.equals(s.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Session '" + sessionId + "' not found"));
        if (!currentUser.equals(session.getUserId())) {
            log.debug("Session access denied: session='{}' owner='{}' caller='{}'",
                    sessionId, session.getUserId(), currentUser);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Session '" + sessionId + "' belongs to another user");
        }
        return session;
    }

    private static SessionMetadataDto toDto(SessionDocument session) {
        AccentColor color = session.getColor();
        List<String> tags = session.getTags() == null
                ? List.of()
                : new ArrayList<>(session.getTags());
        return SessionMetadataDto.builder()
                .title(session.getTitle())
                .titleAutoGenerated(session.isTitleAutoGenerated())
                .icon(session.getIcon())
                .color(color)
                .tags(tags)
                .pinned(session.isPinned())
                .allowMultipleClients(session.isAllowMultipleClients())
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
