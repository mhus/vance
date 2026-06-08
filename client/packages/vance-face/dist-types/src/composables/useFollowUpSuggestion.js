import { computed, ref, watch } from 'vue';
import { brainFetch } from '@vance/shared';
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
export function useFollowUpSuggestion(params) {
    const { lastAssistantContent, composerText, projectId, enabled, requestActive } = params;
    /** Cache keyed by {@code `${projectId}::${assistantContent}`}. */
    const cache = new Map();
    /** Cache keys for which the user already accepted the suggestion —
     *  don't re-offer the same string after the user took it. */
    const accepted = new Set();
    const fetchedSuggestion = ref(null);
    const loading = ref(false);
    function cacheKey(project, assistant) {
        if (!project || !assistant)
            return null;
        return `${project}::${assistant}`;
    }
    const featureEnabled = computed(() => enabled?.value !== false);
    /** Trimmed composer text — pure whitespace counts as empty. */
    const composerEmpty = computed(() => composerText.value.trim().length === 0);
    /** Suggestion exposed to consumers: gated on composer emptiness +
     *  acceptance status. The fetch itself is kicked off by the watcher
     *  below; this computed only filters what to show. */
    const activeSuggestion = computed(() => {
        if (!featureEnabled.value)
            return null;
        if (!composerEmpty.value)
            return null;
        const key = cacheKey(projectId.value, lastAssistantContent.value);
        if (key === null)
            return null;
        if (accepted.has(key))
            return null;
        return fetchedSuggestion.value;
    });
    /** Fetch token used to drop responses that arrive after a newer
     *  assistant message has already taken over. */
    let fetchSeq = 0;
    async function fetchFor(project, assistant) {
        const key = `${project}::${assistant}`;
        if (cache.has(key)) {
            fetchedSuggestion.value = cache.get(key) ?? null;
            return;
        }
        const seq = ++fetchSeq;
        loading.value = true;
        try {
            const body = {
                text: assistant,
                count: 1,
                mode: 'chat-reply',
            };
            const resp = await brainFetch('POST', `follow-up/${encodeURIComponent(project)}`, { body });
            // Stale response — newer fetch has superseded us.
            if (seq !== fetchSeq)
                return;
            const first = resp.suggestions?.[0]?.text?.trim() ?? null;
            const value = first && first.length > 0 ? first : null;
            cache.set(key, value);
            fetchedSuggestion.value = value;
        }
        catch {
            // Suggestion is a nice-to-have — swallow and stay quiet.
            if (seq === fetchSeq) {
                cache.set(key, null);
                fetchedSuggestion.value = null;
            }
        }
        finally {
            if (seq === fetchSeq)
                loading.value = false;
        }
    }
    watch([lastAssistantContent, projectId, featureEnabled, requestActive], ([assistant, project, on, active], prev) => {
        const prevAssistant = prev?.[0] ?? null;
        // Clear stale state whenever the assistant message changes.
        // {@code requestActive} flipping to false on its own should NOT
        // wipe the cached suggestion — the user might just blur briefly
        // and come back; we want the bubble to stay visible.
        if (prevAssistant !== assistant) {
            fetchedSuggestion.value = null;
        }
        if (!on)
            return;
        if (!project || !assistant)
            return;
        if (!active)
            return;
        void fetchFor(project, assistant);
    }, { immediate: true });
    function acceptCurrent() {
        const key = cacheKey(projectId.value, lastAssistantContent.value);
        if (key !== null)
            accepted.add(key);
        fetchedSuggestion.value = null;
    }
    return { activeSuggestion, loading, acceptCurrent };
}
//# sourceMappingURL=useFollowUpSuggestion.js.map