/**
 * Convert an ISO date / `Date` / millis to a relative phrase
 * ("just now", "5m ago", "2h ago", "Yesterday", "12 Apr"). For
 * stamps older than ~7 days we drop to a short calendar date.
 *
 * Intentionally locale-light — RN's Intl.RelativeTimeFormat support
 * is patchy on Android, and our spec doesn't yet promise locale
 * coverage. Localise via i18n later when the app grows that.
 */
export function relativeTime(input: Date | string | number | undefined): string {
  if (input === undefined) return '';
  const d = typeof input === 'string' || typeof input === 'number' ? new Date(input) : input;
  const ms = Date.now() - d.getTime();
  const sec = Math.floor(ms / 1000);
  if (sec < 30) return 'just now';
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const day = Math.floor(hr / 24);
  if (day < 2) return 'Yesterday';
  if (day < 7) return `${day}d ago`;
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}
