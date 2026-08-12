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
    ]);

    expect(result).toEqual({
      anchorMessageId: '4',
      context:
        'Alice [USER]:\nDeploy tonight?\n\n' +
        'Bob [USER]:\nThat conflicts with migration.\n\n' +
        'ASSISTANT:\nWe can defer activation.\n\n' +
        'Carol [USER]:\nDid Ops approve it?',
    });
  });

  it('anchors duplicate text by message id', () => {
    const result = buildFollowUpContext([
      message('first', 'ASSISTANT', 'Same text'),
      message('second', 'ASSISTANT', 'Same text'),
    ]);

    expect(result?.anchorMessageId).toBe('second');
  });
});
