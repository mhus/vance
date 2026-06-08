import { type Ref } from 'vue';
/**
 * Reactive follow-up suggestion for the chat editor's ghost bubble.
 *
 * <p>Watches the most-recent assistant message, the composer's input
 * text, and a {@code requestActive} gate (typically the composer's
 * focus state). When the input is empty, a fresh assistant message is
 * available, and {@code requestActive} flips to {@code true} (or a
 * new assistant message arrives while {@code requestActive} stays
 * {@code true}), the composable fetches a single follow-up reply
 * suggestion from {@code POST /brain/{tenant}/follow-up/{project}}
 * (reply mode — no cursor) and exposes it as {@link activeSuggestion}.
 *
 * <p>Gating on {@code requestActive} keeps us from firing LLM calls
 * for users who never interact with the composer (e.g. read-only
 * viewers, background tabs, mid-scroll). Once a value is cached for
 * a given assistant message, follow-up focus events for the same
 * message reuse the cached value without an extra call.
 *
 * <p>Cache: keyed on {@code projectId + lastAssistantContent}. The
 * suggestion is acceptable only while the input stays empty. The
 * first non-space keystroke hides it; clearing the input brings it
 * back from cache (no refetch).
 *
 * <p>Errors are swallowed: the ghost bubble is a UX nicety, not a
 * blocking feature. On REST failure the suggestion stays {@code null}.
 */
export declare function useFollowUpSuggestion(params: {
    /** Most-recent assistant message content. {@code null} = none yet. */
    lastAssistantContent: Ref<string | null>;
    /** Current composer input text. */
    composerText: Ref<string>;
    /** Project for the REST scope. Pass {@code '_tenant'} for the
     *  default hub session. */
    projectId: Ref<string | null>;
    /** Set {@code false} to disable the feature entirely (e.g. while the
     *  composer is sending, or the chat is in plan mode). */
    enabled?: Ref<boolean>;
    /** Gate for when the suggestion is actually wanted — typically the
     *  composer's focus state. The fetch fires only while this is
     *  {@code true}; cached values for previously-fetched assistant
     *  messages remain available regardless. */
    requestActive: Ref<boolean>;
}): {
    activeSuggestion: Ref<string | null>;
    loading: Ref<boolean>;
    /** Call after the user has accepted the current suggestion via
     *  space/tab/click — drops it from the active state so it doesn't
     *  re-appear when the composer goes empty again on the same
     *  assistant message. */
    acceptCurrent: () => void;
};
//# sourceMappingURL=useFollowUpSuggestion.d.ts.map