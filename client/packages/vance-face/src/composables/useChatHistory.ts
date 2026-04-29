import { ref, type Ref } from 'vue';
import type { ChatMessageDto } from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * REST loader for the persisted chat history of a session. Fires on
 * chat-editor mount and once again whenever the user resumes a
 * different session.
 *
 * Renders messages chronologically (oldest first); the live WS stream
 * appends to the same list as `chat-message-appended` frames arrive
 * (handled separately in {@code useChatSession}).
 */
export function useChatHistory(): {
  messages: Ref<ChatMessageDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: (sessionId: string) => Promise<void>;
  reset: () => void;
} {
  const messages = ref<ChatMessageDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(sessionId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const data = await brainFetch<ChatMessageDto[]>(
        'GET',
        `sessions/${encodeURIComponent(sessionId)}/messages`,
      );
      messages.value = data;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load chat history.';
      messages.value = [];
    } finally {
      loading.value = false;
    }
  }

  function reset(): void {
    messages.value = [];
    error.value = null;
    loading.value = false;
  }

  return { messages, loading, error, load, reset };
}
