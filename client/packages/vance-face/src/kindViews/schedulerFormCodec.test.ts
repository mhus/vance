import { describe, expect, it } from 'vitest';
import {
  SchedulerFormParseError,
  WEEKDAYS,
  applySchedule,
  applyTarget,
  cronFromSchedule,
  emptySchedule,
  parseSchedulerDoc,
  scheduleFromDoc,
  serializeSchedulerDoc,
  targetFromDoc,
} from './schedulerFormCodec';

// ── parse / serialize ───────────────────────────────────────────────────

describe('parseSchedulerDoc / serializeSchedulerDoc', () => {
  it('keeps the whole map — keys the form does not own survive a round-trip', () => {
    const body = [
      '$meta:',
      '  kind: vance-scheduler',
      'description: Daily briefing.',
      'cron: "0 0 8 * * MON-FRI"',
      'timezone: Europe/Berlin',
      'recipe: analyze',
      'params:',
      '  model: default:fast',
      'tags:',
      '  - daily',
      'lockMode: protected',
    ].join('\n');

    const doc = parseSchedulerDoc(body);
    expect(doc).toMatchObject({
      description: 'Daily briefing.',
      cron: '0 0 8 * * MON-FRI',
      timezone: 'Europe/Berlin',
      recipe: 'analyze',
      lockMode: 'protected',
    });
    expect((doc.params as Record<string, unknown>).model).toBe('default:fast');
    expect((doc.tags as unknown[])).toEqual(['daily']);
    expect(serializeSchedulerDoc(doc)).toContain('lockMode: protected');
  });

  it('keeps ISO datetimes as strings (no YAML timestamp coercion)', () => {
    const doc = parseSchedulerDoc('at: 2026-05-14T08:00:00\nrecipe: default\n');
    expect(doc.at).toBe('2026-05-14T08:00:00');
  });

  it('throws a parse error on malformed YAML', () => {
    expect(() => parseSchedulerDoc('description: [unterminated'))
      .toThrow(SchedulerFormParseError);
  });

  it('throws a parse error on a non-mapping body', () => {
    expect(() => parseSchedulerDoc('- just\n- a list\n'))
      .toThrow(SchedulerFormParseError);
  });

  it('parses an empty body into an empty map', () => {
    expect(parseSchedulerDoc('')).toEqual({});
  });
});

// ── cron → form ─────────────────────────────────────────────────────────

describe('scheduleFromDoc', () => {
  it('recognises a daily cron', () => {
    const s = scheduleFromDoc(parseSchedulerDoc('cron: "0 30 8 * * *"\nrecipe: r\n'));
    expect(s.mode).toBe('daily');
    expect(s.hour).toBe(8);
    expect(s.minute).toBe(30);
  });

  it('recognises an hourly cron with interval and minute', () => {
    const s = scheduleFromDoc(parseSchedulerDoc('cron: "0 30 */3 * * *"\nrecipe: r\n'));
    expect(s.mode).toBe('hourly');
    expect(s.everyHours).toBe(3);
    expect(s.minute).toBe(30);
  });

  it('recognises a weekly cron with a weekday list', () => {
    const s = scheduleFromDoc(parseSchedulerDoc('cron: "0 0 9 ? * MON,WED,FRI"\nrecipe: r\n'));
    expect(s.mode).toBe('weekly');
    expect(s.weekdays).toEqual(['MON', 'WED', 'FRI']);
  });

  it('expands a weekday range into canonical order', () => {
    const s = scheduleFromDoc(parseSchedulerDoc('cron: "0 0 9 ? * FRI-MON"\nrecipe: r\n'));
    expect(s.mode).toBe('weekly');
    // Canonical MON-first order, regardless of the wrap-around notation.
    expect(s.weekdays).toEqual(['MON', 'FRI', 'SAT', 'SUN']);
  });

  it('recognises a monthly cron with a day of month', () => {
    const s = scheduleFromDoc(parseSchedulerDoc('cron: "0 15 9 15 * ?"\nrecipe: r\n'));
    expect(s.mode).toBe('monthly');
    expect(s.monthDay).toBe(15);
    expect(s.minute).toBe(15);
    expect(s.hour).toBe(9);
  });

  it('recognises a one-shot at: trigger', () => {
    const s = scheduleFromDoc(parseSchedulerDoc('at: 2026-05-14T08:00:00\nrecipe: r\n'));
    expect(s.mode).toBe('once');
    expect(s.at).toBe('2026-05-14T08:00:00');
  });

  it('degrades exotic cron (nth weekday) to mode other with the raw expression', () => {
    const s = scheduleFromDoc(parseSchedulerDoc('cron: "0 0 9 ? * MON#2"\nrecipe: r\n'));
    expect(s.mode).toBe('other');
    expect(s.cron).toBe('0 0 9 ? * MON#2');
  });

  it('degrades Quartz macros to mode other', () => {
    const s = scheduleFromDoc(parseSchedulerDoc('cron: "@daily"\nrecipe: r\n'));
    expect(s.mode).toBe('other');
    expect(s.cron).toBe('@daily');
  });

  it('degrades a 5-field Unix cron to mode other (the server upgrades, the form does not rewrite)', () => {
    const s = scheduleFromDoc(parseSchedulerDoc('cron: "0 8 * * *"\nrecipe: r\n'));
    expect(s.mode).toBe('other');
  });

  it('hands back the daily skeleton for a trigger-less doc', () => {
    const s = scheduleFromDoc({ recipe: 'r' });
    expect(s.mode).toBe('daily');
    expect(s.hour).toBe(8);
  });
});

// ── form → cron ─────────────────────────────────────────────────────────

describe('applySchedule / cronFromSchedule', () => {
  it('builds an hourly expression', () => {
    const s = emptySchedule();
    s.mode = 'hourly';
    s.everyHours = 3;
    s.minute = 30;
    expect(cronFromSchedule(s)).toBe('0 30 */3 * * *');
  });

  it('builds a weekly expression in canonical weekday order', () => {
    const s = emptySchedule();
    s.mode = 'weekly';
    s.weekdays = ['FRI', 'MON'];
    s.hour = 9;
    s.minute = 0;
    expect(cronFromSchedule(s)).toBe('0 0 9 ? * MON,FRI');
  });

  it('builds a monthly expression', () => {
    const s = emptySchedule();
    s.mode = 'monthly';
    s.monthDay = 15;
    s.hour = 9;
    s.minute = 15;
    expect(cronFromSchedule(s)).toBe('0 15 9 15 * ?');
  });

  it('writes cron and removes at for cron modes', () => {
    const doc = parseSchedulerDoc('at: 2026-05-14T08:00:00\nrecipe: r\ntags:\n  - keep\n');
    const s = emptySchedule();
    s.mode = 'daily';
    s.hour = 7;
    s.minute = 45;

    const out = applySchedule(doc, s);

    expect(out.cron).toBe('0 45 7 * * *');
    expect(out.at).toBeUndefined();
    // Non-scheduler keys pass through.
    expect(out.tags).toEqual(['keep']);
  });

  it('writes at and removes cron for once', () => {
    const doc = parseSchedulerDoc('cron: "0 0 8 * * *"\nrecipe: r\n');
    const s = emptySchedule();
    s.mode = 'once';
    s.at = '2026-05-14T08:00:00';

    const out = applySchedule(doc, s);

    expect(out.at).toBe('2026-05-14T08:00:00');
    expect(out.cron).toBeUndefined();
  });

  it('round-trips a weekly cron semantically (a weekday range becomes a canonical list)', () => {
    const body = 'cron: "0 0 9 ? * MON-FRI"\nrecipe: r\n';
    const parsed = scheduleFromDoc(parseSchedulerDoc(body));
    const rebuilt = applySchedule(parseSchedulerDoc(body), parsed);
    expect(rebuilt.cron).toBe('0 0 9 ? * MON,TUE,WED,THU,FRI');
  });
});

// ── target ─────────────────────────────────────────────────────────────

describe('targetFromDoc / applyTarget', () => {
  it('reads a recipe target', () => {
    const t = targetFromDoc(parseSchedulerDoc('recipe: analyze\ncron: "0 0 8 * * *"\n'));
    expect(t.kind).toBe('recipe');
    expect(t.name).toBe('analyze');
  });

  it('reads a workflow target', () => {
    const t = targetFromDoc(parseSchedulerDoc('workflow: daily-audit\ncron: "0 0 8 * * *"\n'));
    expect(t.kind).toBe('workflow');
    expect(t.name).toBe('daily-audit');
  });

  it('reads a script target', () => {
    const t = targetFromDoc(parseSchedulerDoc('cron: "0 0 8 * * *"\nscript:\n  source: project\n  path: cleanups/daily.js\n'));
    expect(t.kind).toBe('script');
    expect(t.scriptSource).toBe('project');
    expect(t.scriptPath).toBe('cleanups/daily.js');
  });

  it('switching target removes the previous key (targets are exclusive)', () => {
    const doc = parseSchedulerDoc('recipe: analyze\ncron: "0 0 8 * * *"\nparams:\n  keep: true\n');
    const out = applyTarget(doc, { kind: 'workflow', name: 'audit', scriptSource: '', scriptPath: '' });
    expect(out.recipe).toBeUndefined();
    expect(out.workflow).toBe('audit');
    expect(out.params).toEqual({ keep: true });
  });
});

// ── weekday constants ───────────────────────────────────────────────────

describe('WEEKDAYS', () => {
  it('is Monday-first Quartz order', () => {
    expect(WEEKDAYS).toEqual(['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']);
  });
});
