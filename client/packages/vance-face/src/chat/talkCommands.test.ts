import { describe, expect, it } from 'vitest';
import {
  matchTalkCommand,
  normalizeForMatch,
  stripCommandTail,
} from './talkCommands';
import {
  defaultTalkCommands,
  type TalkCommandAction,
} from '../platform/speechSettings';

const ACTIVE: readonly TalkCommandAction[] = ['send', 'clear', 'pause', 'end'];
const PAUSED: readonly TalkCommandAction[] = ['resume', 'clear', 'end'];

describe('normalizeForMatch', () => {
  it('lowercases, strips punctuation and collapses whitespace', () => {
    expect(normalizeForMatch('  Computer,  SENDEN! ')).toBe('computer senden');
  });
});

describe('matchTalkCommand', () => {
  it('matches a trigger name plus command word standing alone', () => {
    const m = matchTalkCommand('Computer senden', defaultTalkCommands(), ACTIVE);
    expect(m).toEqual({ action: 'send', firstToken: 'computer' });
  });

  it('matches a command trailing dictated content in the same chunk', () => {
    const m = matchTalkCommand(
      'das war der plan, Computer senden', defaultTalkCommands(), ACTIVE);
    expect(m?.action).toBe('send');
  });

  it('ignores a bare command word when a trigger name is required', () => {
    expect(matchTalkCommand('senden', defaultTalkCommands(), ACTIVE)).toBeNull();
  });

  it('accepts a bare command word when trigger name is not required', () => {
    const cfg = { ...defaultTalkCommands(), requireTriggerName: false };
    const m = matchTalkCommand('bitte senden', cfg, ACTIVE);
    expect(m).toEqual({ action: 'send', firstToken: 'senden' });
  });

  it('does not fire an action outside the allowed set for the status', () => {
    // "resume" is not allowed while ACTIVE; "senden" not allowed while PAUSED.
    expect(matchTalkCommand('Computer weiter', defaultTalkCommands(), ACTIVE))
      .toBeNull();
    expect(matchTalkCommand('Computer senden', defaultTalkCommands(), PAUSED))
      .toBeNull();
  });

  it('resolves resume only while paused', () => {
    const m = matchTalkCommand('Computer weiter', defaultTalkCommands(), PAUSED);
    expect(m?.action).toBe('resume');
  });

  it('treats plain dictation without a command as no match', () => {
    expect(
      matchTalkCommand('analysiere die logs bitte', defaultTalkCommands(), ACTIVE),
    ).toBeNull();
  });

  it('recognizes English synonyms', () => {
    const m = matchTalkCommand('Computer send', defaultTalkCommands(), ACTIVE);
    expect(m?.action).toBe('send');
  });
});

describe('stripCommandTail', () => {
  it('keeps the content that preceded the command', () => {
    expect(stripCommandTail('das war der plan, Computer senden', 'computer'))
      .toBe('das war der plan');
  });

  it('returns empty when the phrase is only the command', () => {
    expect(stripCommandTail('Computer senden', 'computer')).toBe('');
  });

  it('returns the trimmed phrase when the token is absent', () => {
    expect(stripCommandTail('  hallo welt  ', 'computer')).toBe('hallo welt');
  });
});
