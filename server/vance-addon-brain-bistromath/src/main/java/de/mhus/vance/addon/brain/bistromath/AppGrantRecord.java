package de.mhus.vance.addon.brain.bistromath;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One app's release request and its outcome — the **machine-written** half of
 * app governance.
 *
 * <p>Two documents, and the split is about write ownership, not tidiness:
 * {@code applications.yaml} is hand-written by an admin, with comments, and the
 * server never touches it; this record lives in
 * {@code applications-granted.yaml} and is rewritten whenever a request is
 * raised or answered. Rewriting the hand-written file would eat the comments an
 * admin put there — the same reason a kit's {@code installed/} record is
 * separate from its {@code config/}.
 *
 * <p>It also carries the **frozen proposal**. Re-deriving it from the app's
 * {@code _app.yaml} at approval time would mean approving whatever the app asks
 * for *now* — and an app that widened its ask between request and decision would
 * be approved for the wider thing. The `InboxEffect` contract says the same in
 * general terms: a description must come from the effect's own storage, never
 * from text the requester controls.
 */
public record AppGrantRecord(
        Status status,
        AppMode mode,
        @Nullable List<String> restFamilies,
        boolean surface,
        boolean documentsWritable,
        @Nullable String requestedBy,
        @Nullable String requestedAt,
        @Nullable String inboxItemId,
        @Nullable String decidedBy,
        @Nullable String decidedAt) {

    public enum Status {
        /** Asked, not answered. Grants nothing. */
        REQUESTED,
        /** Answered yes — this is the only status the resolver honours. */
        GRANTED,
        /** Answered no. Kept, so a second ask is not immediate. */
        DENIED
    }

    public AppGrantRecord {
        if (restFamilies != null) restFamilies = List.copyOf(restFamilies);
    }

    /** The policy this record grants, or {@code null} unless it was approved. */
    public @Nullable AppPolicy grantedPolicy() {
        return status == Status.GRANTED
                ? new AppPolicy(mode, restFamilies, surface, documentsWritable)
                : null;
    }

    public boolean open() {
        return status == Status.REQUESTED;
    }
}
