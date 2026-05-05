import { brainFetch } from '@vance/shared';
import { ChatRole, type ChatMessageDto } from '@vance/generated';
import { normalizeEnum } from '@/util/enum';

/**
 * REST snapshot of a session's chat history. Mobile loads this once
 * on entering the live screen, before subscribing to streaming
 * frames over the WebSocket — the order is important so the dedupe
 * logic in `useChatLive` can match incoming `chat-message-appended`
 * frames against history-loaded ids.
 */
export async function listChatHistory(sessionId: string, limit = 200): Promise<ChatMessageDto[]> {
  const raw = await brainFetch<ChatMessageDto[]>(
    'GET',
    `sessions/${encodeURIComponent(sessionId)}/messages?limit=${limit}`,
  );
  return raw.map((m) => ({
    ...m,
    role: normalizeEnum(ChatRole, m.role),
  }));
}
