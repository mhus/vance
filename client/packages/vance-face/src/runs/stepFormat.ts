/**
 * Display helpers for one step of a run. Pure functions, so the panel stays a
 * template and the formatting can be tested without mounting it.
 */

/** "850 ms", "1.2 s", "3 min 5 s" — the unit symbols are not translated. */
export function formatDuration(ms: number): string {
  if (ms < 1000) return `${Math.max(0, Math.round(ms))} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  const totalSeconds = Math.round(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return seconds === 0 ? `${minutes} min` : `${minutes} min ${seconds} s`;
}

/**
 * Time of day in the user's locale. The brain sends instants as ISO strings,
 * although the generated type says {@code Date}, so both are accepted.
 */
export function formatStepTime(at: Date | string, locale: string, timeZone?: string): string {
  const date = at instanceof Date ? at : new Date(at);
  if (Number.isNaN(date.getTime())) return '';
  return new Intl.DateTimeFormat(locale, { timeStyle: 'medium', timeZone }).format(date);
}
