import { type Ref } from 'vue';
import type { DocumentChangedNotification } from '@vance/generated';
/**
 * Awareness signal: who just wrote this document. Set by the composable
 * after a silent apply (clean reload / merge); cleared by the editor's
 * timer after {@link RECENT_EDITOR_TTL_MS}.
 */
export interface RecentEditor {
    /** Stable identity, preferred for the badge label. */
    displayName: string;
    /** Wall-clock when the signal was raised — drives the auto-fade timer. */
    setAt: number;
}
/**
 * Generic "react to a remote document change" composable, shared by
 * every editor surface (DocumentApp, Cortex tabs, future surfaces). It
 * owns three things:
 *
 * <ol>
 *   <li>Subscribing to the {@code documents.changed} WS frame for the
 *       given path and swapping the subscription when the path
 *       changes.</li>
 *   <li>Dispatching the change through the editor's {@link Options#tryApply}
 *       hook. The editor decides whether it can absorb the change
 *       silently (clean buffer, non-AV-binary, …) or needs the user to
 *       decide.</li>
 *   <li>Exposing a reactive {@link Reaction#pendingChange} flag plus
 *       {@code keepLocal}/{@code acceptRemote} actions so the editor's
 *       template can render a banner.</li>
 * </ol>
 *
 * <p>The editor's {@code tryApply(kind)} returns:
 * <ul>
 *   <li>{@code true} — applied the change silently; the composable
 *       leaves {@code pendingChange} as {@code null}.</li>
 *   <li>{@code false} — refused to apply (dirty buffer, audio in
 *       playback, …); the composable raises {@code pendingChange = kind}
 *       so the editor surfaces a banner.</li>
 * </ul>
 *
 * <p>{@code forceApply(kind)} runs the same effect as {@code tryApply}'s
 * happy path but unconditionally — driven by the user clicking
 * "Remote übernehmen". The editor must persist whatever state it needs
 * to make the apply succeed regardless of dirty/playback flags.
 *
 * <p>See {@code planning/document-presence.md} §"Phase A".
 */
export interface DocumentChangeReactionOptions {
    /** Path to watch. Reactive: switching the value swaps the subscription. */
    path: Ref<string | null>;
    /**
     * Editor's silent-apply attempt. Receives the full notification so
     * the editor can branch on {@code kind} and surface the writer's
     * identity in any post-merge UI. Return {@code true} when the change
     * was applied without user interaction, {@code false} to raise the
     * conflict banner.
     */
    tryApply: (notification: DocumentChangedNotification) => Promise<boolean>;
    /**
     * Unconditional apply — invoked when the user clicks "Remote
     * übernehmen" on the banner. Overrides any dirty/playback guard the
     * editor enforces in {@link tryApply}.
     */
    forceApply: (kind: string) => Promise<void>;
}
export interface DocumentChangeReaction {
    /**
     * The kind of the pending change ({@code "upserted"} /
     * {@code "deleted"}) when a banner should be shown. {@code null} when
     * no banner is needed (no change pending, or the change was applied
     * silently).
     */
    pendingChange: Ref<string | null>;
    /**
     * Awareness signal: who silently merged into this editor's buffer.
     * Editor renders {@code ⏺ {displayName}} when non-null; the composable
     * auto-clears the slot {@link RECENT_EDITOR_TTL_MS} after it was set.
     */
    recentEditor: Ref<RecentEditor | null>;
    /** Banner action: dismiss without applying — user keeps local edits. */
    keepLocal: () => void;
    /**
     * Banner action: apply the remote state, discarding any local edits.
     * Calls {@link DocumentChangeReactionOptions.forceApply}.
     */
    acceptRemote: () => Promise<void>;
}
export declare function useDocumentChangeReaction(options: DocumentChangeReactionOptions): DocumentChangeReaction;
/** True for {@code audio/*} or {@code video/*}. */
export declare function isAudioVideoMime(mime: string | null | undefined): boolean;
/** Outcome of a {@link tryThreeWayMerge} call. */
export type MergeOutcome = {
    ok: true;
    merged: string;
    remote: string;
} | {
    ok: false;
    remote: string;
};
/**
 * Three-way merge of a text document's {@code local} buffer against a
 * freshly-fetched {@code remote} body, using the {@code baseline}
 * (the version both sides started from) as the common ancestor.
 *
 * <p>Algorithm: {@code patch_make(baseline, local)} extracts the user's
 * edits as a patch list; {@code patch_apply(patches, remote)} replays
 * them on top of the remote snapshot. When every patch hunk applies
 * exactly (we keep the fuzzy-match threshold at 0 so context shifts
 * are treated as conflicts, not "close enough"), we have a clean
 * merge and return it; otherwise the caller falls back to a conflict
 * banner.
 *
 * <p>Trivial cases are short-circuited:
 * <ul>
 *   <li>{@code baseline === remote} — no remote change, return {@code local}.</li>
 *   <li>{@code baseline === local} — no local edit, return {@code remote}.</li>
 * </ul>
 *
 * <p>Cursor preservation is intentionally not handled here. The
 * non-conflict case typically only inserts/removes content well away
 * from the user's caret (otherwise it would have been flagged as a
 * conflict), so a fresh content set on the editor is acceptable for
 * Phase B; finer cursor adjustment can come later via the patch
 * offsets.
 */
export declare function tryThreeWayMerge(baseline: string, local: string, remote: string): MergeOutcome;
//# sourceMappingURL=useDocumentChangeReaction.d.ts.map