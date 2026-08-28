package de.mhus.vance.brain.fook;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Full read-projection of a persisted {@code fook-ticket} YAML
 * document. Used by callers that need more than a candidate card —
 * Frankie, admin UIs, debugging. {@link FookTicketService#readTicket}
 * returns this.
 */
@Value
@Builder
public class TicketDocument {

    // ── $meta scalars ───────────────────────────────────────────────
    String id;
    String title;
    String type;
    String severity;

    /**
     * Local transport-lifecycle state. {@code new} — just triaged,
     * not yet transferred. {@code transferred} — successfully
     * pushed to the configured {@code TicketProvider}.
     * {@code failed} — provider-call gave up.
     */
    String status;

    /**
     * Permission flag for the {@code FookUpstreamService} sender-tick.
     * {@code auto} — created under {@code mode=automatic}, no
     * approval needed. {@code pending} — {@code mode=manual},
     * waiting for admin. {@code approved} — admin gave the go.
     * {@code none} — {@code mode=never}, ticket stays local.
     */
    @Nullable String transportApproval;

    Instant createdAt;
    @Nullable Instant triagedAt;
    @Nullable String triagedBy;

    /** Set when the ticket was successfully transferred to upstream. */
    @Nullable Instant transferredAt;

    /**
     * Pointer to the initial inbox-item written at triage-time.
     * The sender-tick re-uses this to update the same item with
     * the upstream URL once the transfer completes, instead of
     * spawning a second "transferred" item alongside.
     */
    @Nullable String inboxItemId;

    /** Provider key, e.g. {@code "github"}. */
    @Nullable String upstreamProvider;

    /**
     * External id assigned by the provider (e.g. GitHub issue number).
     * May be a secret — see {@code ProviderTicketRef}. Use
     * {@link #upstreamDisplayId} for anything a person or a log sees.
     */
    @Nullable String upstreamExternalId;

    /**
     * The showable half of the provider's identity. Null on tickets
     * transferred before this field existed; callers fall back to
     * {@link #upstreamExternalId}, which for the only provider that
     * existed then (GitHub) is the same value.
     */
    @Nullable String upstreamDisplayId;

    /** Full URL for the user to follow. */
    @Nullable String upstreamUrl;

    /** Last mirrored provider state ({@code open}/{@code closed} for GitHub). */
    @Nullable String upstreamState;

    /** Last poll-tick that touched this ticket. */
    @Nullable Instant upstreamLastSyncedAt;

    /**
     * External ids of upstream comments already fanned out to the
     * reporter's inbox. The poll-tick dedups against this so a stale
     * global {@code since} cannot re-deliver a comment as a fresh
     * FEEDBACK item. Never null (empty when nothing synced yet).
     */
    java.util.List<String> syncedCommentExternalIds;

    // ── body ────────────────────────────────────────────────────────
    String description;
    @Nullable String triageNote;
    @Nullable TicketContext context;
    TicketRelations relations;
    TicketReporter reporter;
}
