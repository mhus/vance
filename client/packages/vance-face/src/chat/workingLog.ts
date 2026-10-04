import type { ChatMessageDto, ChatRole } from '@vance/generated';

/**
 * Live-only working log for streaming transcripts.
 *
 * Engines with long tool loops (Nutrimat's natures, Frankie) stream every
 * LLM round into a per-process draft bubble and narrate progress as
 * interim notes ({@code meta.kind === 'interim'}). The canonical
 * {@code chat-message-appended} only ever commits the final reply — the
 * streamed text of intermediate rounds is never persisted.
 *
 * An interim note arriving used to wipe the streaming draft (every
 * appended frame cleared it), so the text the user was reading vanished
 * with every round note (live finding 2026-10-04: "[redbull] round 11/12
 * kommt und vorher ein text da war, dann ist der text der vorher da war
 * weg"). Instead, the finished round's draft is frozen into a live-only
 * transcript entry: the text stays visible, the next round starts a fresh
 * draft, and the entries vanish on reload together with the interim
 * notes — history carries only the canonical turns. When a canonical
 * commit later arrives with the same content, it supersedes its
 * live-only preview (the janx ACCEPT case replays the final round's
 * text as the reply).
 */

/** Prefix for live-only working-log message ids. Never collides with
 *  server-side Mongo ids or the optimistic-echo {@code tmp_} prefix. */
export const WORKING_LOG_PREFIX = 'working-';

/** Shape of the per-process streaming draft (see ChatView). */
export interface StreamingDraft {
  role: ChatRole;
  content: string;
  thinking: string;
  processName: string;
}

/** Whether a message's metadata marks it as an interim working-log note
 *  ({@code meta.kind === 'interim'}, ChatMessageDocument.KIND_INTERIM —
 *  engine loop narration). */
export function isInterimNote(meta: Record<string, unknown> | null | undefined): boolean {
  return meta?.['kind'] === 'interim';
}

/** Whether a transcript entry is a frozen streaming draft (live-only). */
export function isWorkingLogEntry(message: Pick<ChatMessageDto, 'messageId'>): boolean {
  return message.messageId.startsWith(WORKING_LOG_PREFIX);
}

let workingLogSeq = 0;

/** Freezes a finished streaming draft into a live-only transcript entry,
 *  so the model's text of the just-ended round stays visible. Returns
 *  {@code null} when there is no draft or it carries nothing worth
 *  keeping. */
export function freezeDraftToWorkingLog(
  draft: StreamingDraft | null | undefined,
): ChatMessageDto | null {
  if (!draft || (!draft.content && !draft.thinking)) return null;
  return {
    messageId: `${WORKING_LOG_PREFIX}${draft.processName}-${++workingLogSeq}`,
    thinkProcessId: '',
    processName: draft.processName,
    role: draft.role,
    content: draft.content,
    thinking: draft.thinking,
    createdAt: new Date(),
    addressedToAgent: false,
  };
}

/** Live-only entries a canonical commit supersedes: same process, same
 *  content — the commit IS the persisted form of that preview. */
export function supersededWorkingLog(
  messages: ChatMessageDto[],
  processName: string | undefined,
  content: string,
): ChatMessageDto[] {
  return messages.filter(
    (m) => !isWorkingLogEntry(m) || m.processName !== processName || m.content !== content,
  );
}
