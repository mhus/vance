package de.mhus.vance.brain.trillian;

import de.mhus.vance.brain.session.SessionLifecycleHook;
import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.shared.permission.PermissionBootstrap;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.user.UserService;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
 * <p>Replaces a listener that hung on process status. That was the wrong
 * anchor twice over. Deleting a control session left the worker session
 * behind as a closed shell with its chat messages, processes and memories
 * intact — invisible, because the session is {@code system=true}, and one
 * more of them per Trillian start. And archiving could not be told apart
 * from closing at all: the archive cascade closes every process, so the
 * listener fired and destroyed the account. Guarding on
 * {@code CloseReason.ARCHIVED} did not help, because that reason is
 * written <em>after</em> the close event has already been dispatched —
 * verified in a live archive run. At session level the two transitions
 * are simply different methods and cannot be confused.
 *
 * <p><b>The account survives archiving.</b> Archived means put away, and
 * a Trillian that comes back with a different identity, no attributes
 * and no grants has not come back. It is deleted on close and on
 * delete — not on archive.
 *
 * <p><b>Only control sessions act here</b>, recognised by a process
 * running the {@code trillian-control} engine. The wiring alone is no
 * discriminator: <em>both</em> sides carry {@code peerSessionId},
 * pointing at each other. Keying on it made the cascade delete the
 * worker, whose hook then deleted the control, whose hook deleted the
 * worker — a StackOverflowError on the first reactivate, and on the way
 * there the worker's own {@code trillianUserName} made it delete the
 * shared service account.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianSessionLifecycleHook implements SessionLifecycleHook {

    private final ThinkProcessService thinkProcessService;
    private final SessionService sessionService;
    private final UserService userService;
    /** Breaks the cycle: the service owns this hook. */
    private final ObjectProvider<SessionLifecycleService> lifecycleProvider;

    /** Absent unless a grant-storing permission provider is loaded. */
    private final ObjectProvider<PermissionBootstrap> permissionBootstrapProvider;

    private final de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry natureRegistry;

    @Override
    public void onSessionClosed(SessionDocument session) {
        if (!isControlSession(session)) {
            return;
        }
        releaseAccount(session);
        peerSessionOf(session).ifPresent(peer -> {
            lifecycleProvider.getObject().closeWithCascade(peer);
            log.info("Trillian: closed worker session '{}' alongside control '{}'",
                    peer, session.getSessionId());
        });
    }

    /**
     * Releases everything that hangs off the service account, then the
     * account itself.
     *
     * <p>Called from both {@link #onSessionClosed} and
     * {@link #onSessionDeleted}, because neither alone covers every way a
     * Trillian ends. {@code SessionLifecycleService.deleteSession} drives
     * the close cascade only for sessions that are neither {@code CLOSED}
     * nor {@code ARCHIVED} — so deleting an <em>archived</em> Trillian
     * never fired the close hook, and its account, grants and attribute
     * document stayed behind. That leak was found by having to clear
     * orphaned accounts out of Mongo by hand.
     *
     * <p>Idempotent by construction: on the ordinary path both hooks run
     * and the second finds nothing left to do.
     */
    private void releaseAccount(SessionDocument session) {
        accountOf(session).ifPresent(account -> {
            // Everything keyed by the account name goes before the name
            // does. A Nature that stored attributes under it releases
            // them here — the file is named after an account that is
            // about to stop existing, and the next Trillian gets a new
            // name, so it would be unreadable and unreachable at once.
            try {
                natureRegistry.resolve(natureOf(session))
                        .attributesDiscarded(
                                session.getTenantId(), session.getProjectId(), account);
            } catch (RuntimeException e) {
                log.warn("Trillian: discarding stored attributes of '{}' failed: {}",
                        account, e.toString());
            }
            // Grants next: they key on the user *name* too, and one must
            // not outlive its subject. UserService.delete does not cascade
            // into grant storage.
            try {
                permissionBootstrapProvider.ifAvailable(
                        pb -> pb.revokeAll(session.getTenantId(), account));
            } catch (RuntimeException e) {
                log.warn("Trillian: revoking grants of '{}' failed: {}", account, e.toString());
            }
            // Presence check rather than catching UserNotFoundException:
            // on the ordinary path this runs twice, and the second pass
            // would otherwise log a failure for the success of the first.
            if (userService.findByTenantAndName(session.getTenantId(), account).isEmpty()) {
                return;
            }
            try {
                userService.delete(session.getTenantId(), account);
                log.info("Trillian: deleted service-account '{}' with control session '{}'",
                        account, session.getSessionId());
            } catch (RuntimeException e) {
                log.warn("Trillian: deleting account '{}' failed: {}", account, e.toString());
            }
        });
    }

    @Override
    public void onSessionArchived(SessionDocument session) {
        if (!isControlSession(session)) {
            return;
        }
        peerSessionOf(session).ifPresent(peer -> {
            lifecycleProvider.getObject().archiveWithCascade(peer);
            log.info("Trillian: archived worker session '{}' alongside control '{}'",
                    peer, session.getSessionId());
        });
    }

    @Override
    public void onSessionUnarchived(SessionDocument session) {
        if (!isControlSession(session)) {
            return;
        }
        // The old worker session cannot be revived: its processes are
        // CLOSED, and a closed process never returns. Removing it here
        // keeps the reactivate clean — TrillianSessionBootstrapper then
        // rebuilds a worker session around the *same* service account.
        peerSessionOf(session).ifPresent(peer -> {
            // Rescue the attributes first. They live in the worker
            // process's engineParams — Mongo-persistent, so they survive
            // restarts and the archive itself, but not the delete two
            // lines down. Parking them on the (closed, renamed) control
            // process is where the bootstrapper already looks when it
            // adopts the account.
            carryAttributesToControl(session, peer);
            lifecycleProvider.getObject().deleteSession(peer);
            log.info("Trillian: cleared archived worker session '{}' before reactivating "
                    + "control '{}'", peer, session.getSessionId());
        });
    }

    @Override
    public void onSessionDeleted(SessionDocument session) {
        if (!isControlSession(session)) {
            return;
        }
        // Also here, not only in onSessionClosed: an archived session is
        // deleted without ever passing through the close cascade.
        releaseAccount(session);
        peerSessionOf(session).ifPresent(peer -> {
            lifecycleProvider.getObject().deleteSession(peer);
            log.info("Trillian: deleted worker session '{}' alongside control '{}'",
                    peer, session.getSessionId());
        });
    }

    /**
     * Copies the worker's attributes onto the control session's closed
     * chat-process, so the fresh worker can inherit them.
     *
     * <p>Best-effort: losing a persona on reactivate is worth a warning,
     * not a failed transition.
     */
    private void carryAttributesToControl(SessionDocument control, String peerSessionId) {
        try {
            Map<String, Object> attributes = thinkProcessService
                    .findBySession(control.getTenantId(), peerSessionId).stream()
                    .map(TrillianInternalApi::readAttributes)
                    .filter(a -> !a.isEmpty())
                    .findFirst()
                    .orElse(Map.of());
            if (attributes.isEmpty()) {
                return;
            }
            for (ThinkProcessDocument p : thinkProcessService.findBySession(
                    control.getTenantId(), control.getSessionId())) {
                if (paramString(p, TrillianSessionBootstrapper.PARAM_PEER_SESSION_ID) == null) {
                    continue;
                }
                Map<String, Object> params = new LinkedHashMap<>(
                        p.getEngineParams() == null ? Map.of() : p.getEngineParams());
                params.put(TrillianSessionBootstrapper.PARAM_CARRIED_ATTRIBUTES, attributes);
                thinkProcessService.replaceEngineParams(p.getId(), params);
                log.info("Trillian: carried {} attribute(s) across the reactivate of '{}'",
                        attributes.size(), control.getSessionId());
                return;
            }
        } catch (RuntimeException e) {
            log.warn("Trillian: could not carry attributes across reactivate of '{}': {}",
                    control.getSessionId(), e.toString());
        }
    }

    /**
     * Whether this is the human-facing half of a Trillian pair.
     *
     * <p>The engine name is the only reliable discriminator — the
     * {@code peerSessionId} wiring exists on both sides and would send
     * the cascade back and forth forever.
     */
    private boolean isControlSession(SessionDocument session) {
        return thinkProcessService
                .findBySession(session.getTenantId(), session.getSessionId()).stream()
                .anyMatch(p -> TrillianSessionBootstrapper.CONTROL_ENGINE_NAME
                        .equals(p.getThinkEngine()));
    }

    /** The Nature this pair runs, or {@code null} to let the registry default. */
    private @Nullable String natureOf(SessionDocument session) {
        return paramOfAnyProcess(session, TrillianSessionBootstrapper.PARAM_NATURE)
                .orElse(null);
    }

    /** The service account this control session runs its worker as. */    /** The service account this control session runs its worker as. */
    private Optional<String> accountOf(SessionDocument session) {
        return paramOfAnyProcess(session, TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME);
    }

    /**
     * The worker session this control session currently owns.
     *
     * <p>Every archive/reactivate cycle leaves another closed chat-process
     * behind, each carrying the {@code peerSessionId} it had at the time.
     * Taking the first match therefore picked an arbitrary generation —
     * observed live: the hook went looking for a worker session deleted
     * two cycles earlier, found it gone, and did nothing, so attributes
     * were never carried and the live worker session was left orphaned.
     *
     * <p>Newest first, and skip any whose session no longer exists.
     */
    private Optional<String> peerSessionOf(SessionDocument session) {
        List<ThinkProcessDocument> processes = new ArrayList<>(
                thinkProcessService.findBySession(session.getTenantId(), session.getSessionId()));
        processes.sort(Comparator.comparing(
                ThinkProcessDocument::getCreatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        for (ThinkProcessDocument p : processes) {
            String peer = paramString(p, TrillianSessionBootstrapper.PARAM_PEER_SESSION_ID);
            if (StringUtils.isBlank(peer)) {
                continue;
            }
            if (sessionService.findBySessionId(peer).isEmpty()) {
                log.debug("Trillian: worker session '{}' from an earlier cycle of control '{}' "
                        + "is gone — looking further back", peer, session.getSessionId());
                continue;
            }
            return Optional.of(peer);
        }
        return Optional.empty();
    }

    /**
     * Reads a wiring value off any process of the session, not just the
     * current chat one: after a reactivate the link lives on the renamed,
     * closed predecessor.
     */

    private Optional<String> paramOfAnyProcess(SessionDocument session, String key) {
        List<ThinkProcessDocument> processes =
                thinkProcessService.findBySession(session.getTenantId(), session.getSessionId());
        for (ThinkProcessDocument p : processes) {
            String value = paramString(p, key);
            if (StringUtils.isNotBlank(value)) {
                return Optional.of(value);
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
