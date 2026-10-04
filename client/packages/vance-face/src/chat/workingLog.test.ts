import { describe, expect, it } from 'vitest';
import type { ChatMessageDto, ChatRole } from '@vance/generated';
import {
  freezeDraftToWorkingLog,
  isInterimNote,
  isWorkingLogEntry,
  supersededWorkingLog,
  WORKING_LOG_PREFIX,
  type StreamingDraft,
} from './workingLog';

function draft(overrides: Partial<StreamingDraft> = {}): StreamingDraft {
  return {
    role: 'ASSISTANT' as ChatRole,
    content: 'I will inspect the project first.',
    thinking: '',
    processName: 'chat',
    ...overrides,
  };
}

function message(overrides: Partial<ChatMessageDto>): ChatMessageDto {
  return {
    messageId: 'srv-1',
    thinkProcessId: 'p1',
    processName: 'chat',
    role: 'ASSISTANT' as ChatRole,
    content: 'canonical reply',
    createdAt: new Date(),
    addressedToAgent: false,
    ...overrides,
  };
}

describe('isInterimNote', () => {
  it('recognises the interim working-log marker', () => {
    expect(isInterimNote({ kind: 'interim' })).toBe(true);
  });

  it('rejects absent meta and other kinds', () => {
    expect(isInterimNote(undefined)).toBe(false);
    expect(isInterimNote(null)).toBe(false);
    expect(isInterimNote({})).toBe(false);
    expect(isInterimNote({ kind: 'removed' })).toBe(false);
  });
});

describe('freezeDraftToWorkingLog', () => {
  it('freezes content, thinking, role and process into a live-only entry', () => {
    const frozen = freezeDraftToWorkingLog(
      draft({ content: 'round 10 text', thinking: 'reasoning trail', processName: 'chat' }),
    );
    expect(frozen).not.toBeNull();
    expect(frozen!.messageId.startsWith(WORKING_LOG_PREFIX)).toBe(true);
    expect(frozen!.processName).toBe('chat');
    expect(frozen!.content).toBe('round 10 text');
    expect(frozen!.thinking).toBe('reasoning trail');
    expect(isWorkingLogEntry(frozen!)).toBe(true);
  });

  it('returns null for a draft with nothing to keep', () => {
    expect(freezeDraftToWorkingLog(draft({ content: '', thinking: '' }))).toBeNull();
    expect(freezeDraftToWorkingLog(undefined)).toBeNull();
  });

  it('mints distinct ids so consecutive rounds never collide', () => {
    const first = freezeDraftToWorkingLog(draft());
    const second = freezeDraftToWorkingLog(draft());
    expect(first!.messageId).not.toBe(second!.messageId);
  });
});

describe('supersededWorkingLog', () => {
  it('drops the live-only preview a canonical commit replays', () => {
    const preview = message({
      messageId: `${WORKING_LOG_PREFIX}chat-1`,
      content: 'the answer, verbatim',
    });
    const unrelated = message({ messageId: `${WORKING_LOG_PREFIX}chat-2`, content: 'earlier round' });
    const canonical = message({ messageId: 'srv-9', content: 'the answer, verbatim' });
    const keep = supersededWorkingLog([unrelated, preview, canonical], 'chat', 'the answer, verbatim');
    expect(keep).toEqual([unrelated, canonical]);
  });

  it('never drops canonical messages even with matching content', () => {
    const canonical = message({ messageId: 'srv-1', content: 'same text' });
    expect(supersededWorkingLog([canonical], 'chat', 'same text')).toEqual([canonical]);
  });

  it('keeps other processes working on the same text', () => {
    const other = message({
      messageId: `${WORKING_LOG_PREFIX}worker-1`,
      processName: 'worker-a',
      content: 'same text',
    });
    expect(supersededWorkingLog([other], 'chat', 'same text')).toEqual([other]);
  });
});
