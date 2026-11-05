/**
 * Display helpers for one step of a run. Pure functions, so the panel stays a
 * template and the formatting can be tested without mounting it.
 */

/**
 * Time of day in the user's locale. The brain sends instants as ISO strings,
 * although the generated type says {@code Date}, so both are accepted.
 */
export function formatStepTime(at: Date | string, locale: string, timeZone?: string): string {
  const date = at instanceof Date ? at : new Date(at);
  if (Number.isNaN(date.getTime())) return '';
  return new Intl.DateTimeFormat(locale, { timeStyle: 'medium', timeZone }).format(date);
}
