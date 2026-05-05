import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
/**
 * REST loader for the persisted chat history of a session. Fires on
 * chat-editor mount (chat.html) and once again whenever the user
 * resumes a different session.
 *
 * Renders messages chronologically (oldest first); the live WS stream
 * appends to the same list as `chat-message-appended` frames arrive
 * (handled separately in {@code useChatSession}).
 */
export function useChatHistory() {
    const messages = ref([]);
    const loading = ref(false);
    const error = ref(null);
    async function load(sessionId) {
        loading.value = true;
        error.value = null;
        try {
            const data = await brainFetch('GET', `sessions/${encodeURIComponent(sessionId)}/messages`);
            messages.value = data;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load chat history.';
            messages.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    function reset() {
        messages.value = [];
        error.value = null;
        loading.value = false;
    }
    return { messages, loading, error, load, reset };
}
//# sourceMappingURL=useChatHistory.js.map