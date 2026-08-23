import type { ChatMessageDto } from '@vance/generated';

export interface FollowUpContext {
  context: string;
  anchorMessageId: string;
}

const MAX_MESSAGES = 12;
const MAX_CHARACTERS = 12_000;

function speakerLabel(message: ChatMessageDto): string {
  const role = String(message.role || 'UNKNOWN');
  if (role === 'USER') {
    const displayName = message.senderDisplayName?.trim();
    if (displayName) return `${displayName} [USER]`;
  }
  return role;
}

/**
 * Builds the bounded, speaker-aware transcript sent to reply-mode follow-up.
 * Worker messages are excluded by the caller because they are not part of the
 * visible main conversation.
 *
 * <p>A conversation whose tail is a USER message has no anchor: the other side
 * has not answered yet, so there is nothing to suggest a reply to. Without this
 * rule the optimistic echo of the user's own message would trigger a follow-up
 * request (and a ghost bubble suggesting a reply to their own sentence).
 */
export function buildFollowUpContext(messages: ChatMessageDto[]): FollowUpContext | null {
  const usable = messages.filter((message) => {
    return Boolean(message.messageId && message.content?.trim());
  });
  const anchor = usable.at(-1);
  if (!anchor?.messageId) return null;
  if (String(anchor.role) === 'USER') return null;

  const blocks = usable.slice(-MAX_MESSAGES).map((message) => {
    return `${speakerLabel(message)}:\n${message.content.trim()}`;
  });

  while (blocks.length > 1 && blocks.join('\n\n').length > MAX_CHARACTERS) {
    blocks.shift();
  }
  let context = blocks.join('\n\n');
  if (context.length > MAX_CHARACTERS) {
    context = context.slice(context.length - MAX_CHARACTERS);
  }

  return { context, anchorMessageId: anchor.messageId };
}
