import * as yaml from 'js-yaml';

/**
 * Codec for the `kind: vance-scheduler` document — a Ursa scheduler
 * definition (spec `specification/public/scheduler.md`).
 *
 * The parsed model is the **whole YAML map**, not a typed subset: the
 * form view owns only the fields it renders (description, trigger,
 * target, identity), and every other key — `$meta`, `params`, `tags`,
 * `lockMode`, … — survives each form round-trip byte-for-byte in value
 * terms. Serialising rebuilds the YAML from that map, so a form edit can
 * never silently drop configuration the form does not know about.
 */
export type SchedulerDoc = Record<string, unknown>;

/** Parse failure surfaced by {@link parseSchedulerDoc}. */
export class SchedulerFormParseError extends Error {}

/** How the schedule is expressed in the form. */
export type SchedulerMode = 'hourly' | 'daily' | 'weekly' | 'monthly' | 'once' | 'other';

/**
 * The form's view of the trigger. `cron` always carries the current raw
 * expression — the source of truth for mode `other` and the display of
 * every other mode.
 */
export interface SchedulerSchedule {
  mode: SchedulerMode;
  /** Minute (0-59) — used by hourly/daily/weekly/monthly. */
  minute: number;
  /** Hour (0-23) — used by daily/weekly/monthly. */
  hour: number;
  /** Interval in hours — used by hourly. */
  everyHours: number;
  /** Selected weekdays (subset of {@link WEEKDAYS}) — used by weekly. */
  weekdays: string[];
  /** Day of month (1-31) — used by monthly. */
  monthDay: number;
  /** ISO-8601 datetime — used by once. */
  at: string;
  /** Raw cron expression — authoritative for `other`, derived otherwise. */
  cron: string;
}

/** Quartz weekday names in canonical order. */
export const WEEKDAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'] as const;

/** Default form state for a fresh scheduler doc. */
export function emptySchedule(): SchedulerSchedule {
  return {
    mode: 'daily',
    minute: 0,
    hour: 8,
    everyHours: 2,
    weekdays: ['MON', 'TUE', 'WED', 'THU', 'FRI'],
    monthDay: 1,
    at: '',
    cron: '0 0 8 * * *',
  };
}

/**
 * Parse a scheduler body. Throws {@link SchedulerFormParseError} when the
 * YAML is malformed or not a top-level mapping — the shell then falls back
 * to the raw YAML editor. The reserved `$meta` header is part of the map
 * like any other key and survives round-trips.
 */
export function parseSchedulerDoc(body: string): SchedulerDoc {
  // js-yaml v5 throws on an empty document instead of returning
  // undefined — a blank body is an empty scheduler map, not a parse error.
  if (body.trim() === '') return {};
  let parsed: unknown;
  try {
    parsed = yaml.load(body, { schema: yaml.JSON_SCHEMA });
  } catch (e) {
    throw new SchedulerFormParseError(e instanceof Error ? e.message : String(e));
  }
  if (parsed === null || parsed === undefined) return {};
  if (typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new SchedulerFormParseError('a scheduler document must be a YAML mapping');
  }
  return parsed as SchedulerDoc;
}

/** Serialise the model back to YAML. */
export function serializeSchedulerDoc(doc: SchedulerDoc): string {
  return yaml.dump(doc, {
    indent: 2,
    lineWidth: 120,
    noRefs: true,
    sortKeys: false,
  });
}

/** Type guard for a string-typed map value. */
function str(v: unknown): string | null {
  return typeof v === 'string' && v.trim() !== '' ? v : null;
}

/**
 * Derive the form schedule from the doc's `cron:` / `at:` fields. Anything
 * the cron patterns below don't recognise becomes mode `other` with the raw
 * expression — the form never *mis*represents a schedule, it degrades to
 * the cron string itself (spec: unrecognised cron → free-form input).
 */
export function scheduleFromDoc(doc: SchedulerDoc): SchedulerSchedule {
  const schedule = emptySchedule();

  const at = str(doc.at);
  const cron = str(doc.cron);
  if (cron === null && at !== null) {
    schedule.mode = 'once';
    schedule.at = at;
    return schedule;
  }
  if (cron !== null) {
    schedule.cron = cron;
    const analysed = analyseCron(cron);
    if (analysed) {
      schedule.mode = analysed.mode;
      schedule.minute = analysed.minute;
      schedule.hour = analysed.hour;
      if (analysed.mode === 'hourly') schedule.everyHours = analysed.everyHours;
      if (analysed.mode === 'weekly') schedule.weekdays = analysed.weekdays;
      if (analysed.mode === 'monthly') schedule.monthDay = analysed.monthDay;
    } else {
      schedule.mode = 'other';
    }
    return schedule;
  }
  // No trigger at all — hand the user a daily skeleton to fill in.
  return schedule;
}

interface AnalysedCron {
  mode: 'hourly' | 'daily' | 'weekly' | 'monthly';
  minute: number;
  hour: number;
  everyHours: number;
  weekdays: string[];
  monthDay: number;
}

function isWildcard(field: string): boolean {
  return field === '*' || field === '?';
}

function toInt(field: string): number | null {
  if (!/^\d+$/.test(field)) return null;
  return Number(field);
}

/**
 * Recognise the cron shapes the form can express. Spring's 6-field
 * `<sec> <min> <hour> <dom> <mon> <dow>` is parsed strictly — Quartz
 * macros (@daily, …), `L`, `#` or any exotic field fall through to
 * `null` (= mode `other`).
 */
function analyseCron(cron: string): AnalysedCron | null {
  if (cron.startsWith('@')) return null;
  const parts = cron.trim().split(/\s+/);
  if (parts.length !== 6 || parts[0] !== '0') return null;
  const [, min, hour, dom, mon, dow] = parts;

  const minute = toInt(min);
  if (minute === null) return null;

  // hourly: 0 <m> */N * * *
  const stepMatch = /^\*\/(\d+)$/.exec(hour);
  if (stepMatch !== null && isWildcard(dom) && mon === '*' && isWildcard(dow)) {
    return { mode: 'hourly', minute, hour: 0, everyHours: Number(stepMatch[1]), weekdays: [], monthDay: 1 };
  }

  const hourNum = toInt(hour);
  if (hourNum === null || mon !== '*') return null;

  // daily: 0 <m> <h> * * *
  if (isWildcard(dom) && isWildcard(dow)) {
    return { mode: 'daily', minute, hour: hourNum, everyHours: 1, weekdays: [], monthDay: 1 };
  }

  // weekly: 0 <m> <h> ? * MON,WED
  if (isWildcard(dom)) {
    const weekdays = parseWeekdays(dow);
    if (weekdays !== null) {
      return { mode: 'weekly', minute, hour: hourNum, everyHours: 1, weekdays, monthDay: 1 };
    }
    return null;
  }

  // monthly: 0 <m> <h> <D> * ?
  if (isWildcard(dow)) {
    const day = toInt(dom);
    if (day !== null && day >= 1 && day <= 31) {
      return { mode: 'monthly', minute, hour: hourNum, everyHours: 1, weekdays: [], monthDay: day };
    }
  }
  return null;
}

/** Expand a Quartz DOW field (names, commas, ranges) into a weekday set. */
function parseWeekdays(field: string): string[] | null {
  if (isWildcard(field)) return null;
  const out = new Set<string>();
  for (const token of field.split(',')) {
    const range = /^([A-Za-z]{3})-([A-Za-z]{3})$/.exec(token.trim());
    if (range !== null) {
      const from = weekdayIndex(range[1]);
      const to = weekdayIndex(range[2]);
      if (from === null || to === null) return null;
      if (to >= from) {
        for (let i = from; i <= to; i++) out.add(WEEKDAYS[i]);
      } else {
        // Wrap-around range (FRI-MON) — valid in Quartz/Spring DOW.
        for (let i = from; i < WEEKDAYS.length; i++) out.add(WEEKDAYS[i]);
        for (let i = 0; i <= to; i++) out.add(WEEKDAYS[i]);
      }
      continue;
    }
    const single = weekdayIndex(token.trim());
    if (single === null) return null;
    out.add(WEEKDAYS[single]);
  }
  if (out.size === 0) return null;
  // Canonical order, regardless of how the expression listed them.
  return WEEKDAYS.filter(d => out.has(d));
}

function weekdayIndex(name: string): number | null {
  const idx = WEEKDAYS.findIndex(d => d === name.toUpperCase());
  return idx >= 0 ? idx : null;
}

/**
 * Write the schedule back into a **cloned** doc: `cron:` for the four cron
 * modes (and `other`), `at:` for once. The field not in use is removed —
 * the parser rejects a doc carrying both.
 */
export function applySchedule(doc: SchedulerDoc, schedule: SchedulerSchedule): SchedulerDoc {
  const out: SchedulerDoc = { ...doc };
  if (schedule.mode === 'once') {
    delete out.cron;
    out.at = schedule.at.trim();
    return out;
  }
  delete out.at;
  out.cron = cronFromSchedule(schedule);
  return out;
}

/** Build the 6-field cron expression for a form schedule. */
export function cronFromSchedule(schedule: SchedulerSchedule): string {
  const minute = clamp(Math.trunc(schedule.minute), 0, 59);
  switch (schedule.mode) {
    case 'hourly': {
      const step = clamp(Math.trunc(schedule.everyHours), 1, 24);
      return `0 ${minute} */${step} * * *`;
    }
    case 'weekly': {
      const days = schedule.weekdays.length > 0
        ? WEEKDAYS.filter(d => schedule.weekdays.includes(d)).join(',')
        : 'MON';
      return `0 ${minute} ${clamp(Math.trunc(schedule.hour), 0, 23)} ? * ${days}`;
    }
    case 'monthly': {
      const day = clamp(Math.trunc(schedule.monthDay), 1, 31);
      return `0 ${minute} ${clamp(Math.trunc(schedule.hour), 0, 23)} ${day} * ?`;
    }
    case 'daily':
      return `0 ${minute} ${clamp(Math.trunc(schedule.hour), 0, 23)} * * *`;
    default:
      // 'other': the raw expression is the source of truth.
      return schedule.cron.trim();
  }
}

function clamp(n: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, n));
}

// ─── Target (recipe / workflow / script) ────────────────────────────────

/** Which trigger target the doc uses. */
export type TargetKind = 'recipe' | 'workflow' | 'script' | 'none';

/** Form view of the trigger target. */
export interface SchedulerTarget {
  kind: TargetKind;
  /** Recipe or workflow name. */
  name: string;
  /** Script fields — only for kind 'script'. */
  scriptSource: string;
  scriptPath: string;
}

export function targetFromDoc(doc: SchedulerDoc): SchedulerTarget {
  const recipe = str(doc.recipe);
  const workflow = str(doc.workflow);
  const script = doc.script;
  if (recipe !== null) return { kind: 'recipe', name: recipe, scriptSource: '', scriptPath: '' };
  if (workflow !== null) return { kind: 'workflow', name: workflow, scriptSource: '', scriptPath: '' };
  if (typeof script === 'object' && script !== null) {
    const map = script as Record<string, unknown>;
    return {
      kind: 'script',
      name: '',
      scriptSource: str(map.source) ?? '',
      scriptPath: str(map.path) ?? '',
    };
  }
  return { kind: 'none', name: '', scriptSource: '', scriptPath: '' };
}

/**
 * Write the target back into a **cloned** doc. The three keys are mutually
 * exclusive in the parser — setting one removes the others.
 */
export function applyTarget(doc: SchedulerDoc, target: SchedulerTarget): SchedulerDoc {
  const out: SchedulerDoc = { ...doc };
  delete out.recipe;
  delete out.workflow;
  delete out.script;
  if (target.kind === 'recipe') {
    if (target.name.trim() !== '') out.recipe = target.name.trim();
  } else if (target.kind === 'workflow') {
    if (target.name.trim() !== '') out.workflow = target.name.trim();
  } else if (target.kind === 'script') {
    const script: Record<string, unknown> = {};
    if (target.scriptSource.trim() !== '') script.source = target.scriptSource.trim();
    if (target.scriptPath.trim() !== '') script.path = target.scriptPath.trim();
    if (Object.keys(script).length > 0) out.script = script;
  }
  return out;
}
