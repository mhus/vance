import { describe, expect, it } from 'vitest';
import type { ChatMessageDto } from '@vance/generated';
import { buildFollowUpContext } from './followUpContext';

function message(
  messageId: string,
  role: ChatMessageDto['role'],
  content: string,
  senderDisplayName?: string,
): ChatMessageDto {
  return {
    messageId,
    thinkProcessId: `process-${messageId}`,
    role,
    content,
    senderDisplayName,
    addressedToAgent: true,
  };
}

describe('buildFollowUpContext', () => {
  it('keeps consecutive shared-chat users and their display names', () => {
    const result = buildFollowUpContext([
      message('1', 'USER', 'Deploy tonight?', 'Alice'),
      message('2', 'USER', 'That conflicts with migration.', 'Bob'),
      message('3', 'ASSISTANT', 'We can defer activation.'),
      message('4', 'USER', 'Did Ops approve it?', 'Carol'),
      message('5', 'ASSISTANT', 'Ops signed off an hour ago.'),
    ]);

    expect(result).toEqual({
      anchorMessageId: '5',
      context:
        'Alice [USER]:\nDeploy tonight?\n\n' +
        'Bob [USER]:\nThat conflicts with migration.\n\n' +
        'ASSISTANT:\nWe can defer activation.\n\n' +
        'Carol [USER]:\nDid Ops approve it?\n\n' +
        'ASSISTANT:\nOps signed off an hour ago.',
    });
  });

  it('anchors duplicate text by message id', () => {
    const result = buildFollowUpContext([
      message('first', 'ASSISTANT', 'Same text'),
      message('second', 'ASSISTANT', 'Same text'),
    ]);

    expect(result?.anchorMessageId).toBe('second');
  });

  it('has no anchor while the conversation tail is the user', () => {
    // The optimistic echo of the message the user just sent lands here.
    // Anchoring on it would ask the server for a reply to the user's own
    // sentence — and hang the ghost bubble under their own message.
    const result = buildFollowUpContext([
      message('1', 'ASSISTANT', 'Deploy is staged.'),
      message('tmp_1', 'USER', 'Ship it.', 'Alice'),
    ]);

    expect(result).toBeNull();
  });

  it('has no anchor when the user answers a worker-free assistant turn twice', () => {
    // Two consecutive USER messages (shared session) are still a USER tail.
    const result = buildFollowUpContext([
      message('1', 'ASSISTANT', 'Deploy is staged.'),
      message('2', 'USER', 'Ship it.', 'Alice'),
      message('3', 'USER', 'Actually, wait.', 'Bob'),
    ]);

    expect(result).toBeNull();
  });

  it('anchors again once the assistant has answered', () => {
    const result = buildFollowUpContext([
      message('1', 'ASSISTANT', 'Deploy is staged.'),
      message('2', 'USER', 'Ship it.', 'Alice'),
      message('3', 'ASSISTANT', 'Shipped.'),
    ]);

    expect(result?.anchorMessageId).toBe('3');
  });

  it('ignores a trailing empty user message when deciding the tail', () => {
    // Filtering runs first: an empty message is not usable, so it neither
    // becomes the anchor nor blocks the assistant turn before it.
    const result = buildFollowUpContext([
      message('1', 'ASSISTANT', 'Deploy is staged.'),
      message('2', 'USER', '   ', 'Alice'),
    ]);

    expect(result?.anchorMessageId).toBe('1');
  });
});
