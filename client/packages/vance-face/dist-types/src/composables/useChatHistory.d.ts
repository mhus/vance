import { type Ref } from 'vue';
import type { ChatMessageDto } from '@vance/generated';
/**
 * REST loader for the persisted chat history of a session. Fires on
 * chat-editor mount and once again whenever the user resumes a
 * different session.
 *
 * Renders messages chronologically (oldest first); the live WS stream
 * appends to the same list as `chat-message-appended` frames arrive
 * (handled separately in {@code useChatSession}).
 */
export declare function useChatHistory(): {
    messages: Ref<ChatMessageDto[]>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: (sessionId: string) => Promise<void>;
    reset: () => void;
};
//# sourceMappingURL=useChatHistory.d.ts.map