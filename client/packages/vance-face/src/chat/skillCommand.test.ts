import { describe, expect, it } from 'vitest';
import { ProcessSkillCommand } from '@vance/generated';
import { parseSkillCommand, renderSkillReplyForTest } from './skillCommand';

/**
 * The parse/branching of `/skill` lines — the piece that decides what the
 * brain gets asked to do. Shared by every composer host (chat page and the
 * Cortex side panel), so a branch missed here is a branch missed everywhere.
 */
describe('parseSkillCommand', () => {
  it('parses a plain activation', () => {
    const p = parseSkillCommand('/skill design-blueprint');
    expect(p.command).toBe(ProcessSkillCommand.ACTIVATE);
    expect(p.skillName).toBe('design-blueprint');
    expect(p.oneShot).toBe(false);
    expect(p.args).toBeUndefined();
  });

  it('parses activation with --once and trailing args', () => {
    const p = parseSkillCommand('/skill design-blueprint --once landing page');
    expect(p.command).toBe(ProcessSkillCommand.ACTIVATE);
    expect(p.oneShot).toBe(true);
    expect(p.args).toBe('landing page');
  });

  it('keeps args that merely contain once', () => {
    const p = parseSkillCommand('/skill review once more');
    expect(p.oneShot).toBe(false);
    expect(p.args).toBe('once more');
  });

  it('parses list and clear variants', () => {
    expect(parseSkillCommand('/skill list').command).toBe(ProcessSkillCommand.LIST);
    expect(parseSkillCommand('/skill-list').command).toBe(ProcessSkillCommand.LIST);
    expect(parseSkillCommand('/skill clear').command).toBe(ProcessSkillCommand.CLEAR_ALL);
    const clear = parseSkillCommand('/skill clear design-blueprint');
    expect(clear.command).toBe(ProcessSkillCommand.CLEAR);
    expect(clear.skillName).toBe('design-blueprint');
    expect(parseSkillCommand('/skill-clear foo').skillName).toBe('foo');
  });

  it('flags a bare /skill as usage', () => {
    expect(parseSkillCommand('/skill').command).toBeNull();
    expect(parseSkillCommand('/skill   ').command).toBeNull();
  });
});

describe('renderSkillReply (activate branches)', () => {
  it('names the fresh activation with its effect', () => {
    const line = renderSkillReplyForTest(ProcessSkillCommand.ACTIVATE, 'demo', {
      processName: 'chat',
      activeSkills: [],
      availableSkills: [],
      newlyActivated: true,
      lifecycle: 'sticky',
    });
    expect(line).toContain('activated demo');
    expect(line).toContain('next turn');
  });

  it('says already-active rather than activated', () => {
    const line = renderSkillReplyForTest(ProcessSkillCommand.ACTIVATE, 'demo', {
      processName: 'chat',
      activeSkills: [],
      availableSkills: [],
      newlyActivated: false,
      lifecycle: 'sticky',
    });
    expect(line).toContain('already active');
  });

  it('calls a shot skill what it is', () => {
    const line = renderSkillReplyForTest(ProcessSkillCommand.ACTIVATE, 'demo', {
      processName: 'chat',
      activeSkills: [],
      availableSkills: [],
      newlyActivated: true,
      lifecycle: 'shot',
    });
    expect(line).toContain('fired demo');
  });
});
