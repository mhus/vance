import { describe, expect, it } from 'vitest';
import { defaultTalkCommands, parseTalkCommands } from './speechSettings';

describe('parseTalkCommands', () => {
  it('defaults auto-pause off', () => {
    expect(defaultTalkCommands().autoPause).toBe(false);
    expect(parseTalkCommands(null).autoPause).toBe(false);
  });

  it('reads a stored auto-pause flag', () => {
    expect(parseTalkCommands('{"autoPause":true}').autoPause).toBe(true);
  });

  // Settings written before auto-pause existed carry no flag at all —
  // they must keep the default rather than yield `undefined`.
  it('keeps the default when the stored config predates the flag', () => {
    const cfg = parseTalkCommands('{"requireTriggerName":false}');
    expect(cfg.autoPause).toBe(false);
    expect(cfg.requireTriggerName).toBe(false);
  });

  it('ignores a non-boolean auto-pause value', () => {
    expect(parseTalkCommands('{"autoPause":"yes"}').autoPause).toBe(false);
  });
});
