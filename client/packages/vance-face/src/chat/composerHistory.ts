/**
 * Pure logic for the chat composer's input history — the ArrowUp overlay
 * that mirrors terminal history (foot, shells). Everything here is free of
 * DOM/Vue so it can be unit-tested in isolation; the composer component owns
 * the persistence keys and the overlay rendering.
 *
 * Array order is oldest first: the newest entry sits at the end of the array
 * and therefore at the *bottom* of the overlay, nearest to the input.
 */
export const MAX_HISTORY_ENTRIES = 100;

/**
 * Appends one entry to the history. Terminal semantics:
 * <ul>
 *   <li>Blank text is ignored (attachment-only sends never enter history).</li>
 *   <li>A repeat of the *immediately preceding* entry is not duplicated —
 *       the old occurrence is dropped and the text re-appended, so the
 *       newest position always reflects reality.</li>
 *   <li>Non-consecutive repeats stay: they carry meaning ("asked this again
 *       after a while") exactly like they do in shell history.</li>
 *   <li>Beyond {@link MAX_HISTORY_ENTRIES} the oldest entries fall off.</li>
 * </ul>
 */
export function pushHistoryEntry(entries: readonly string[], text: string): string[] {
  const trimmed = text.trim();
  if (!trimmed) return entries.slice();
  const withoutRepeat = entries.length > 0 && entries[entries.length - 1] === trimmed
    ? entries.slice(0, -1)
    : entries.slice();
  const next = [...withoutRepeat, trimmed];
  return next.length > MAX_HISTORY_ENTRIES
    ? next.slice(next.length - MAX_HISTORY_ENTRIES)
    : next;
}

/**
 * Whether the caret sits in the first text line of the composer — the gate
 * for opening the history overlay with ArrowUp. Anywhere below line one,
 * ArrowUp must keep moving the caret instead of stealing the key.
 */
export function caretInFirstLine(text: string, selectionStart: number): boolean {
  const from = Math.max(0, Math.min(selectionStart, text.length));
  return text.slice(0, from).indexOf('\n') === -1;
}
