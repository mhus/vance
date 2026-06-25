package de.mhus.vance.brain.chat;

import de.mhus.vance.brain.events.SessionConnectionRegistry;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Per-turn resolver for the multi-user collaboration variables that
 * end up in the prompt-render context. See
 * {@code planning/multi-user-sessions.md} §5 / §6.
 *
 * <p>Collab is "active" when the session permits multiple clients
 * <em>and</em> more than one is currently bound. The participant
 * list is taken from {@link SessionConnectionRegistry} so it always
 * reflects what's live on the wire — captured display names with
 * a fallback to user ids.
 *
 * <p>{@code mentionedBy} is supplied by the caller (usually
 * {@code SteerMessage.UserChatInput.fromUserDisplayName()} from the
 * last USER turn in the drain batch); the resolver just packages it
 * into the same record so engines have one structure to plumb into
 * the prompt context.
 */
@Component
@RequiredArgsConstructor
public class CollabContextResolver {

    private final SessionService sessionService;
    private final SessionConnectionRegistry connectionRegistry;

    public CollabContext resolve(@Nullable String sessionId, @Nullable String mentionedBy) {
        if (sessionId == null) return CollabContext.INACTIVE;
        SessionDocument session = sessionService.findBySessionId(sessionId).orElse(null);
        if (session == null || !session.isAllowMultipleClients()) {
            return CollabContext.INACTIVE;
        }
        if (connectionRegistry.connectionCount(sessionId) <= 1) {
            return CollabContext.INACTIVE;
        }
        List<String> names = connectionRegistry.participantDisplayNames(sessionId);
        return new CollabContext(true, names, normalise(mentionedBy));
    }

    private static @Nullable String normalise(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public record CollabContext(
            boolean active, List<String> participants, @Nullable String mentionedBy) {
        public static final CollabContext INACTIVE = new CollabContext(false, List.of(), null);
    }
}
