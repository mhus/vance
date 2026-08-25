/**
 * How two cell values compare, in one place.
 *
 * <p>Its own file because the rule has a subtlety that got it wrong once, in
 * shipped code: **the empty-last rule must sit outside the direction.** Written
 * as `factor * compare(a, b)`, reversing the sort also reverses "empty last",
 * so a descending column put every blank at the top — under a claim that it did
 * not. The direction may only flip the comparison of two *present* values.
 *
 * <p>The same rule exists a second time, in JavaScript, inside the bundled
 * `core@1` library: an app author sorting a list by hand should get the same
 * answer as the `table` widget. Two implementations of one rule is a real cost;
 * the alternative was for the library to import from a bundle it cannot reach,
 * so what keeps them together is a test that exercises both.
 */
export function compareCells(a: unknown, b: unknown): number {
  const emptyA = a === undefined || a === null || a === '';
  const emptyB = b === undefined || b === null || b === '';
  // An empty value is the absence of one, not the smallest — and a column of
  // blanks at the top hides the data whichever way the reader sorted.
  if (emptyA || emptyB) return emptyA && emptyB ? 0 : emptyA ? 1 : -1;

  const na = Number(a);
  const nb = Number(b);
  // Numeric when both are numbers, so an amount column sorts 9 before 77.
  // Mixed columns fall back to text, which is at least stable.
  if (!Number.isNaN(na) && !Number.isNaN(nb)) return na - nb;
  return String(a).localeCompare(String(b));
}

/**
 * The comparison a sorted column uses: empties last regardless of direction,
 * present values in the direction asked for.
 */
export function compareInDirection(a: unknown, b: unknown, descending: boolean): number {
  const emptyA = a === undefined || a === null || a === '';
  const emptyB = b === undefined || b === null || b === '';
  if (emptyA || emptyB) return emptyA && emptyB ? 0 : emptyA ? 1 : -1;
  return (descending ? -1 : 1) * compareCells(a, b);
}
