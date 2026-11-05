/**
 * Helpers for the "Simulate" dialog: what the reader typed becomes the result the
 * run is given. Pure, so the rules are tested without a dialog.
 */

/**
 * The output the reader typed. Empty means none. Text that parses as JSON is that
 * JSON — a later state reads its fields (`${state.x.field}`), so structure matters —
 * and anything else is plain text, kept as written.
 */
export function parseSimulatedOutput(text: string): unknown | undefined {
  const trimmed = text.trim();
  if (trimmed === '') return undefined;
  try {
    return JSON.parse(trimmed);
  } catch {
    return text;
  }
}

/** The outcome to preselect: `success` when the state has it, otherwise the first one. */
export function defaultOutcome(outcomes: readonly string[]): string {
  return outcomes.includes('success') ? 'success' : (outcomes[0] ?? '');
}
