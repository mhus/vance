package de.mhus.vance.brain.ws.live;

import de.mhus.vance.api.thinkprocess.SessionBootstrapRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionCreateRequest;
import de.mhus.vance.api.ws.SessionResumeRequest;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.project.ProjectManagerService;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.handlers.SessionBootstrapTargets;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves the home-pod endpoint for a session-channel frame arriving on a
 * Face-Pod's {@code /brain/{tenant}/ws} connection.
 *
 * <p>The lookup chain depends on the frame's message type:
 * <ul>
 *   <li>{@link MessageType#SESSION_CREATE} — the project lives in the
 *       payload ({@code projectId}); resolve via
 *       {@link ProjectManagerService#findProjectEndpoint}.</li>
 *   <li>{@link MessageType#SESSION_RESUME} — look up the session by id
 *       (payload {@code sessionId}), then its project's endpoint.</li>
 *   <li>{@link MessageType#SESSION_BOOTSTRAP} — the project the bootstrap
 *       will run in, decided by {@link SessionBootstrapTargets} exactly as
 *       the handler on the home pod will decide it.</li>
 *   <li>Any other frame — assumed to run inside an already-bound session;
 *       use the bound {@code sessionId} from {@link ConnectionContext} (or
 *       the LiveEnvelope's {@code sessionId} if the connection state was
 *       not yet propagated to ctx).</li>
 * </ul>
 *
 * <p>When the lookup yields no endpoint (project missing, not placed yet,
 * homeless, …) the result is {@link HomePodTarget#local()} — falling back to
 * local dispatch is safe: the local handler either places the project here
 * (no live lease anywhere) or rejects with a clear error, which is better
 * than refusing to route at all.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HomePodLookupService {

    private final ProjectManagerService projectManager;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;
    private final SessionBootstrapTargets bootstrapTargets;

    /**
     * Returns the routing target for a session-channel frame.
     *
     * @param ctx the Face-Pod's per-connection context (carries tenantId and,
     *     once a session is bound here, sessionId)
     * @param envelopeSessionId sessionId from the outer {@code LiveEnvelope}
     *     (may be {@code null} for {@code session-create})
     * @param envelope the unwrapped inner {@link WebSocketEnvelope}
     */
    public HomePodTarget resolve(
            ConnectionContext ctx, @Nullable String envelopeSessionId, WebSocketEnvelope envelope) {

        Optional<String> endpoint =
                switch (nullToEmpty(envelope.getType())) {
                    case MessageType.SESSION_CREATE -> resolveFromSessionCreate(ctx, envelope);
                    case MessageType.SESSION_RESUME -> resolveFromSessionResume(envelope);
                    case MessageType.SESSION_BOOTSTRAP -> resolveFromSessionBootstrap(ctx, envelope);
                    default -> resolveFromBoundSession(ctx, envelopeSessionId);
                };

        return endpoint.map(ep -> projectManager.isLocalPod(ep) ? HomePodTarget.LOCAL : HomePodTarget.remote(ep))
                .orElse(HomePodTarget.LOCAL);
    }

    private Optional<String> resolveFromSessionCreate(ConnectionContext ctx, WebSocketEnvelope envelope) {
        SessionCreateRequest req = convert(envelope.getData(), SessionCreateRequest.class);
        if (req == null || isBlank(req.getProjectId())) return Optional.empty();
        return projectManager.findProjectEndpoint(ctx.getTenantId(), req.getProjectId());
    }

    private Optional<String> resolveFromSessionResume(WebSocketEnvelope envelope) {
        SessionResumeRequest req = convert(envelope.getData(), SessionResumeRequest.class);
        if (req == null || isBlank(req.getSessionId())) return Optional.empty();
        return sessionService.findBySessionId(req.getSessionId()).flatMap(this::endpointOfSession);
    }

    private Optional<String> resolveFromSessionBootstrap(ConnectionContext ctx, WebSocketEnvelope envelope) {
        SessionBootstrapRequest req = convert(envelope.getData(), SessionBootstrapRequest.class);
        if (req == null) return Optional.empty();
        return bootstrapTargets
                .projectOf(ctx, req)
                .flatMap(projectId -> projectManager.findProjectEndpoint(ctx.getTenantId(), projectId));
    }

    private Optional<String> resolveFromBoundSession(ConnectionContext ctx, @Nullable String envelopeSessionId) {
        String sessionId = ctx.hasSession() ? ctx.getSessionId() : envelopeSessionId;
        if (isBlank(sessionId)) return Optional.empty();
        return sessionService.findBySessionId(sessionId).flatMap(this::endpointOfSession);
    }

    private Optional<String> endpointOfSession(SessionDocument session) {
        if (isBlank(session.getProjectId())) return Optional.empty();
        return projectManager.findProjectEndpoint(session.getTenantId(), session.getProjectId());
    }

    private <T> @Nullable T convert(@Nullable Object raw, Class<T> type) {
        if (raw == null) return null;
        try {
            return objectMapper.convertValue(raw, type);
        } catch (RuntimeException e) {
            log.debug("HomePodLookup: cannot decode payload as {}: {}", type.getSimpleName(), e.toString());
            return null;
        }
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
