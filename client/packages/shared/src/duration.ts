/**
 * Working-time durations as a number of hours — the unit estimates are kept in.
 *
 * <p>A day is {@link HOURS_PER_UNIT}`.d` hours and a week `.w` hours, so
 * "2d" means two working days. `m` is minutes: months have no abbreviation
 * here, they do not occur in estimates.
 */

export type DurationUnit = 'min' | 'h' | 'd';

/** Hours per unit; `w` is only accepted as typed input, it is never a display unit. */
export const HOURS_PER_UNIT = {
  min: 1 / 60,
  h: 1,
  d: 8,
  w: 40,
} as const;

/** The units a field offers for display and selection. */
export const DURATION_UNITS: readonly DurationUnit[] = ['min', 'h', 'd'];

type TypedUnit = keyof typeof HOURS_PER_UNIT;

const SEGMENT = /^\s*(\d+(?:[.,]\d+)?|[.,]\d+)\s*(min|m|h|d|w)?/i;

const UNIT_ALIASES: Record<string, TypedUnit> = {
  min: 'min',
  m: 'min',
  h: 'h',
  d: 'd',
  w: 'w',
};

export interface ParsedDuration {
  hours: number;
  /** The unit the text carried, `null` when it was a bare number. */
  typedUnit: TypedUnit | null;
}

/**
 * Parses `"1,5h"`, `"2d"`, `"30min"`, `"1d 4h"` or a bare number (read in
 * {@link defaultUnit}). Decimal point and comma are both accepted. Returns
 * `null` for anything else — including blank text, which is the caller's to
 * treat as "no value".
 */
export function parseDuration(text: string, defaultUnit: DurationUnit = 'h'): ParsedDuration | null {
  let rest = text;
  let hours = 0;
  let segments = 0;
  let typedUnit: TypedUnit | null = null;
  let bare = false;
  while (rest.trim() !== '') {
    const m = SEGMENT.exec(rest);
    if (!m) return null;
    const amount = Number(m[1].replace(',', '.'));
    const unit = m[2] ? UNIT_ALIASES[m[2].toLowerCase()] : null;
    segments++;
    if (unit === null) bare = true;
    typedUnit = unit;
    hours += amount * HOURS_PER_UNIT[unit ?? defaultUnit];
    rest = rest.slice(m[0].length);
  }
  // "1 2h" is not a duration: a bare number only stands alone.
  if (segments === 0 || (bare && segments > 1) || !Number.isFinite(hours)) return null;
  return { hours, typedUnit };
}

/** The unit a stored value reads best in: whole working days, else hours, else minutes. */
export function bestUnit(hours: number): DurationUnit {
  if (hours > 0 && hours < 1) return 'min';
  if (hours >= HOURS_PER_UNIT.d && Number.isInteger(hours / HOURS_PER_UNIT.d)) return 'd';
  return 'h';
}

/** Hours expressed in {@link unit}, rounded to two decimals, without trailing zeros. */
export function formatDurationAmount(hours: number, unit: DurationUnit): string {
  const amount = hours / HOURS_PER_UNIT[unit];
  return String(Math.round(amount * 100) / 100);
}

/** A stored value as short text in its best unit: `1.5h`, `2d`, `30min`. */
export function formatDuration(hours: number): string {
  const unit = bestUnit(hours);
  return `${formatDurationAmount(hours, unit)}${unit}`;
}
