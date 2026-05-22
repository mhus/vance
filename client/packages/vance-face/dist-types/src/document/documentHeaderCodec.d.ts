/**
 * Lift a JSON object out of the {@code $meta} wrapper. If the
 * caller's object has a {@code $meta} key whose value is an object,
 * its scalar entries are merged on top of the body keys.
 *
 * Non-scalar {@code $meta} values are dropped — they wouldn't survive
 * a round-trip through the server's {@code JsonHeaderStrategy} either.
 */
export declare function unwrapJsonMeta(obj: Record<string, unknown>): Record<string, unknown>;
/**
 * Build a JSON object with {@code $meta} wrapping the given
 * {@code kind}. Body keys live at the top level alongside
 * {@code $meta}.
 */
export declare function wrapJsonMeta(kind: string, body: Record<string, unknown>): Record<string, unknown>;
/**
 * Parse a single-document YAML body and unwrap its {@code $meta}
 * mapping. Returns a flattened object that the kind-specific
 * {@code promoteTo…Document} can consume directly — scalar
 * {@code $meta} entries (kind, schema, …) land at the top level next
 * to the body keys.
 *
 * The top-level YAML value must be a mapping; anything else (sequence,
 * scalar) raises {@link Error}. The caller wraps with the codec's own
 * error type.
 */
export declare function parseYamlBody(body: string): Record<string, unknown>;
/**
 * Emit a single-document YAML body with {@code $meta} as the first
 * top-level key carrying {@code kind} (plus any optional scalar
 * extras). Shape mirrors {@code wrapJsonMeta} exactly.
 */
export declare function dumpYamlBody(kind: string, body: Record<string, unknown>, headerExtra?: Record<string, unknown>): string;
//# sourceMappingURL=documentHeaderCodec.d.ts.map