package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.chat.ChatMentionParser;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SteerMessageCodec;
import de.mhus.vance.brain.trillian.nature.CollabMode;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The sessions a Trillian opened in other projects (A6 / D6) — one place for
 * what makes a session "its own" and how it is addressed.
 *
 * <p><b>The marker.</b> {@code session_open} names the session's client
 * {@value #CLIENT_NAME_PREFIX}{@code <mode>:<account>}: the collab mode the
 * Nature chose at open time, and the account that opened it. Bound to the
 * account, not to a loop process id: the loop session is fluid (D1) and a
 * rebuilt loop has a new id, while the account stays — replies must reach
 * whichever loop is alive now.
 *
 * <p><b>The marker is a claim, the owner is the proof.</b> A client name is
 * whatever a WS client sends at handshake, so every reader checks that the
 * session's owner ({@code userId}) <em>is</em> the account named in the
 * marker. A human naming their session after a Trillian gets nothing.
 *
 * <p><b>Only sessions opened with {@code session_open}.</b> The loop's own
 * home session is owned by the same account, but it carries no marker — so
 * the steering tools cannot reach (and close) the Trillian's own loop.
 */
@Component
@RequiredArgsConstructor
public class TrillianOwnSessions {

    /** Client-name prefix of every session opened by {@code session_open}. */
    public static final String CLIENT_NAME_PREFIX = "trillian-session:";

    private static final int LOOP_LOOKUP_LIMIT = 8;

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;
    private final EngineMessageRouter messageRouter;
    private final ChatMessageService chatMessageService;

    /** What the client name of an own session says. */
    public record Marker(CollabMode mode, String account) {}

    /** The client name {@code session_open} gives a session. */
    public static String clientName(CollabMode mode, String account) {
        return CLIENT_NAME_PREFIX + mode.name().toLowerCase(Locale.ROOT) + ":" + account;
    }

    /** Parses a client name; empty when it is not an own-session marker. */
    public static Optional<Marker> parse(@Nullable String clientName) {
        if (clientName == null || !clientName.startsWith(CLIENT_NAME_PREFIX)) {
            return Optional.empty();
        }
        String rest = clientName.substring(CLIENT_NAME_PREFIX.length());
        int colon = rest.indexOf(':');
        if (colon <= 0 || colon == rest.length() - 1) {
            return Optional.empty();
        }
        try {
            CollabMode mode = CollabMode.valueOf(rest.substring(0, colon).toUpperCase(Locale.ROOT));
            return Optional.of(new Marker(mode, rest.substring(colon + 1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The marker of {@code session}, only if the session really is owned by
     * the account the marker names.
     */
    public static Optional<Marker> verifiedMarker(SessionDocument session) {
        return parse(session.getClientName()).filter(m -> m.account().equals(session.getUserId()));
    }

    /** The collab mode recorded on an own session; {@link CollabMode#JOIN} if unreadable. */
    public static CollabMode modeOf(SessionDocument session) {
        return verifiedMarker(session).map(Marker::mode).orElse(CollabMode.JOIN);
    }

    /**
     * Resolves {@code sessionId} as one of the caller's own sessions and
     * re-checks {@code action} on it — a grant revoked after the open must
     * stop the steering too.
     *
     * @throws ToolException when the session is missing, foreign, not opened
     *     with {@code session_open}, or the caller lacks the right
     */
    public SessionDocument requireOwn(@Nullable String sessionId, ToolInvocationContext ctx, Action action) {
        if (ctx.userId() == null) {
            throw new ToolException("requires a user scope");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new ToolException("'sessionId' is required and must be non-empty");
        }
        SessionDocument session = sessionService
                .findBySessionId(sessionId.trim())
                .orElseThrow(() -> new ToolException("Session '" + sessionId + "' not found"));
        if (!session.getTenantId().equals(ctx.tenantId())) {
            throw new ToolException("Session '" + sessionId + "' is in another tenant");
        }
        Optional<Marker> marker = verifiedMarker(session);
        if (marker.isEmpty() || !marker.get().account().equals(ctx.userId())) {
            throw new ToolException("Session '" + sessionId + "' is not one you opened with session_open —"
                    + " only those can be used here");
        }
        permissionService.enforce(
                contextFactory.forToolSubject(ctx.tenantId(), ctx.userId()),
                new Resource.Session(ctx.tenantId(), session.getProjectId(), session.getSessionId()),
                action);
        return session;
    }

    /** The session's chat process. */
    public ThinkProcessDocument chatOf(SessionDocument session) {
        if (session.getChatProcessId() == null) {
            throw new ToolException("Session '" + session.getSessionId() + "' has no chat process");
        }
        return thinkProcessService
                .findById(session.getChatProcessId())
                .orElseThrow(() -> new ToolException("Session '" + session.getSessionId() + "' chat process is gone"));
    }

    /**
     * Says {@code message} in the session as {@code account}, with the same
     * addressing rule a human client gets ({@code multi-user-sessions.md}
     * §2): addressed to the agent — which a bare message is, after the
     * {@code @ai} prefix — it goes into the chat process's inbox through the
     * engine-message router (cross-pod when the project lives elsewhere);
     * addressed to a person ({@code @mara …}) it is only written into the
     * chat, the engine does not run.
     *
     * @return whether the engine was addressed
     * @throws ToolException when the router does not accept the message
     */
    public boolean deliver(
            SessionDocument session,
            ThinkProcessDocument chat,
            String account,
            @Nullable String senderProcessId,
            String message) {
        String content = addressed(message);
        if (!ChatMentionParser.isAddressedToAgent(content)) {
            chatMessageService.append(ChatMessageDocument.builder()
                    .tenantId(session.getTenantId())
                    .sessionId(session.getSessionId())
                    .thinkProcessId(chat.getId())
                    .role(ChatRole.USER)
                    .content(content)
                    .senderUserId(account)
                    .addressedToAgent(false)
                    .build());
            return false;
        }
        // A paused chat would hold the message without draining it; user
        // input is an implicit "continue" (same as the WS steer path).
        if (chat.getStatus() == ThinkProcessStatus.PAUSED) {
            thinkProcessService.updateStatus(chat.getId(), ThinkProcessStatus.IDLE);
            thinkProcessService.clearHalt(chat.getId());
        }
        SteerMessage.UserChatInput input =
                new SteerMessage.UserChatInput(Instant.now(), /*messageId*/ null, account, content);
        if (!messageRouter.dispatch(senderProcessId, chat.getId(), SteerMessageCodec.toDocument(input))) {
            throw new ToolException("Message to session '" + session.getSessionId() + "' was not accepted");
        }
        return true;
    }

    /**
     * The mention the engine needs to hear a message at all. Prepended when
     * the message does not open with a mention of its own — harmless while
     * nobody else is in the session, essential once a human joined. A
     * message that opens with {@code @someone} is left as it is: that is the
     * Trillian talking to a person.
     */
    static String addressed(String message) {
        String trimmed = message.stripLeading();
        return trimmed.startsWith("@") ? message : "@ai " + message;
    }

    /** Own sessions of {@code account} that are still open, newest first. */
    public List<SessionDocument> openSessionsOf(String tenantId, String account) {
        return sessionService.listForUser(tenantId, account).stream()
                .filter(s -> s.getStatus() != SessionStatus.CLOSED && s.getStatus() != SessionStatus.ARCHIVED)
                .filter(s -> verifiedMarker(s).isPresent())
                .sorted(java.util.Comparator.comparing(
                                SessionDocument::getCreatedAt,
                                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                        .reversed())
                .toList();
    }

    /**
     * The live user-loop of {@code account} — in its hub, not {@code CLOSED},
     * newest first. Empty while the loop is being rebuilt.
     */
    public Optional<ThinkProcessDocument> liveLoopOf(String tenantId, String account) {
        return thinkProcessService
                .findByProjectAndEngines(
                        tenantId,
                        HomeBootstrapService.hubProjectName(account),
                        List.of(TrillianUserEngine.NAME),
                        LOOP_LOOKUP_LIMIT)
                .stream()
                .filter(p -> p.getStatus() != ThinkProcessStatus.CLOSED)
                .findFirst();
    }
}
