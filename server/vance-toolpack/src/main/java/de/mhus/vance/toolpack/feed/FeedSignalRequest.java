package de.mhus.vance.toolpack.feed;

import org.jspecify.annotations.Nullable;

/**
 * One back-channel message about one entry, addressed to the source.
 *
 * <p>The argument that belongs to the signal is mandatory and checked here
 * rather than at three call sites: a {@link FeedSignal#REPORT} without a
 * reason and a {@link FeedSignal#REQUEST} without a kind are not
 * meaningful messages.
 *
 * <p>{@code note} is free text written by a person and it leaves Vancetope
 * towards a foreign organisation. The length cap lives here so no surface
 * can forget it; the fact that it travels is stated at the input field
 * itself, not in a help text nobody opens.
 */
public record FeedSignalRequest(
        String itemId,
        FeedSignal signal,
        @Nullable FeedReportReason reason,
        @Nullable FeedRequestKind requestKind,
        @Nullable String note,
        @Nullable FeedActor actor) {

    /** Upper bound for the operator-visible note. */
    public static final int MAX_NOTE_LENGTH = 2000;

    public FeedSignalRequest {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId is required");
        }
        if (signal == null) {
            throw new IllegalArgumentException("signal is required");
        }
        if (signal == FeedSignal.REPORT && reason == null) {
            throw new IllegalArgumentException("REPORT requires a reason");
        }
        if (signal == FeedSignal.REQUEST && requestKind == null) {
            throw new IllegalArgumentException("REQUEST requires a kind");
        }
        if (note != null) {
            note = note.isBlank() ? null : note.trim();
            if (note != null && note.length() > MAX_NOTE_LENGTH) {
                throw new IllegalArgumentException(
                        "note exceeds " + MAX_NOTE_LENGTH + " characters");
            }
        }
    }

    public static FeedSignalRequest report(
            String itemId, FeedReportReason reason, @Nullable String note,
            @Nullable FeedActor actor) {
        return new FeedSignalRequest(itemId, FeedSignal.REPORT, reason, null, note, actor);
    }

    public static FeedSignalRequest request(
            String itemId, FeedRequestKind kind, @Nullable FeedActor actor) {
        return new FeedSignalRequest(itemId, FeedSignal.REQUEST, null, kind, null, actor);
    }
}
