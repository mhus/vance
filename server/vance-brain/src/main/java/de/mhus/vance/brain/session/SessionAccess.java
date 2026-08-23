package de.mhus.vance.brain.session;

import de.mhus.vance.shared.session.SessionDocument;
import org.jspecify.annotations.Nullable;

/**
 * Who may look into somebody else's session — the multi-user rule, in one
 * place.
 *
 * <p>A session is owned by a person and its transcript is private to them
 * unless they opted in: {@code allowMultipleClients} is exactly that opt-in
 * ({@code specification/multi-user-sessions.md} §2.5). The project grant does
 * <b>not</b> imply it — {@code Resource.Session} resolves to the project role
 * (R3), so a plain project READER passes every {@code Resource.Session} check
 * in the tree. Both are needed: the project check says the caller belongs
 * here at all, this rule says the conversation is theirs to see.
 *
 * <p>Existed three times before, written out per call site
 * ({@code ChatHistoryController}, {@code SessionResumeHandler}, and missing
 * where it mattered in {@code SessionProcessController}). One helper so the
 * next copy cannot quietly differ.
 *
 * <p><b>System sessions need no clause of their own.</b> A scheduler or
 * agrajag session is owned either by {@code SessionService.SYSTEM_OWNER} —
 * nobody is that user — or by the {@code runAs} person whose session it
 * genuinely is, and neither is ever {@code allowMultipleClients}. Excluding
 * {@link SessionDocument#isSystem()} on top would only take a session away
 * from its own owner.
 */
public final class SessionAccess {

    private SessionAccess() {
    }

    /**
     * May {@code userId} read/use {@code session}?
     *
     * @param session the session in question
     * @param userId  the authenticated caller; {@code null} or blank is never
     *                an owner and never allowed into a private session
     */
    public static boolean mayAccess(SessionDocument session, @Nullable String userId) {
        if (userId != null && !userId.isBlank() && userId.equals(session.getUserId())) {
            return true;
        }
        return session.isAllowMultipleClients();
    }
}
