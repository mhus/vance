/**
 * Wire-vs-runtime enum bridging. The Brain's JSON serialises Java
 * enums by their NAME (Jackson default), e.g. `"status": "PENDING"`.
 * The Maven `generate-java-to-ts-maven-plugin` emits TypeScript
 * numeric enums (`InboxItemStatus.PENDING === 0`). At runtime that
 * leaves us holding a string while TypeScript's static types say
 * we have a number, which silently breaks every `===` comparison.
 *
 * Normalising at the API boundary collapses both representations
 * onto the numeric form so all downstream code can compare with
 * the enum value as written in TypeScript:
 *
 *   if (item.status === InboxItemStatus.PENDING) { ... }
 *
 * Outgoing (URL params, JSON body) goes the other way — back to the
 * name string, which Spring's `StringToEnumConverterFactory` and
 * Jackson both accept.
 */

/**
 * Convert whatever the wire delivered into the TS numeric enum.
 *
 * - Number passes through (already normalised).
 * - String resolves via the enum object's name → ordinal map.
 * - Anything else falls back to `0` so a malformed payload does not
 *   blow up the consumer; callers that care about the bad input
 *   should validate elsewhere.
 */
export function normalizeEnum<E extends Record<string, string | number>>(
  enumObj: E,
  value: unknown,
): E[keyof E] {
  if (typeof value === 'number') return value as E[keyof E];
  if (typeof value === 'string') {
    const mapped = (enumObj as unknown as Record<string, number>)[value];
    if (typeof mapped === 'number') return mapped as E[keyof E];
  }
  return 0 as E[keyof E];
}

/**
 * Convert the TS numeric enum value into the name string the Brain
 * expects on the wire (URL params, JSON bodies). The reverse
 * lookup is built into TypeScript's numeric enums:
 * `InboxItemStatus[InboxItemStatus.PENDING] === "PENDING"`.
 */
export function enumName<E extends Record<string, string | number>>(
  enumObj: E,
  value: E[keyof E],
): string {
  return (enumObj as unknown as Record<number, string>)[value as unknown as number];
}
