package de.mhus.vance.brain.trillian;

import de.mhus.vance.brain.session.SessionLifecycleHook;
import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Makes the Trillian worker session follow its control session through
 * archive, reactivate and delete.
 *
 * <p>Before this, only a process-status listener reacted, and only to
 * CLOSED. Deleting a control session left the worker session behind as
 * a closed shell with its chat messages, processes and memories intact —
 * invisible, because the session is {@code system=true}, and one more of
 * them per Trillian start. Archiving was worse: the cascade closes every
 * process, the listener saw CLOSED and deleted the service account, so
 * "archive" quietly meant "destroy and let the next reactivate mint a
 * stranger".
 *
 * <p><b>The account survives archiving.</b> Archived means put away, and
 * a Trillian that comes back with a different identity, no attributes
 * and no grants has not come back. It is deleted only when the session
 * is — see {@link TrillianCleanupListener} for the close path.
 *
 * <p>Only control sessions act here. The worker session carries no
 * {@code peerSessionId}, which is what stops the cascade from recursing
 * when this hook deletes it through the same service that called us.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianSessionLifecycleHook implements SessionLifecycleHook {

    private final ThinkProcessService thinkProcessService;
    /** Breaks the cycle: the service owns this hook. */
    private final ObjectProvider<SessionLifecycleService> lifecycleProvider;

    @Override
    public void onSessionArchived(SessionDocument session) {
        peerSessionOf(session).ifPresent(peer -> {
            lifecycleProvider.getObject().archiveWithCascade(peer);
            log.info("Trillian: archived worker session '{}' alongside control '{}'",
                    peer, session.getSessionId());
        });
    }

    @Override
    public void onSessionUnarchived(SessionDocument session) {
        // The old worker session cannot be revived: its processes are
        // CLOSED, and a closed process never returns. Removing it here
        // keeps the reactivate clean — TrillianSessionBootstrapper then
        // rebuilds a worker session around the *same* service account,
        // so identity, attributes and grants carry over.
        peerSessionOf(session).ifPresent(peer -> {
            lifecycleProvider.getObject().deleteSession(peer);
            log.info("Trillian: cleared archived worker session '{}' before reactivating "
                    + "control '{}'", peer, session.getSessionId());
        });
    }

    @Override
    public void onSessionDeleted(SessionDocument session) {
        peerSessionOf(session).ifPresent(peer -> {
            lifecycleProvider.getObject().deleteSession(peer);
            log.info("Trillian: deleted worker session '{}' alongside control '{}'",
                    peer, session.getSessionId());
        });
    }

    /**
     * The worker session this control session owns, read from any of its
     * processes. Looks at every process rather than the current chat
     * one: after a reactivate the link lives on the renamed, closed
     * predecessor.
     */
    private Optional<String> peerSessionOf(SessionDocument session) {
        List<ThinkProcessDocument> processes =
                thinkProcessService.findBySession(session.getTenantId(), session.getSessionId());
        for (ThinkProcessDocument p : processes) {
            String peer = paramString(p, TrillianSessionBootstrapper.PARAM_PEER_SESSION_ID);
            if (StringUtils.isNotBlank(peer)) {
                return Optional.of(peer);
            }
        }
        return Optional.empty();
    }

    private static @Nullable String paramString(ThinkProcessDocument process, String key) {
        if (process.getEngineParams() == null) {
            return null;
        }
        Object v = process.getEngineParams().get(key);
        return v == null ? null : v.toString();
    }
}
