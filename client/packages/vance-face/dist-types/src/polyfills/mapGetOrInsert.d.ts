/**
 * Polyfill for the TC39 Stage-3 proposal
 * <https://tc39.es/proposal-upsert/>: {@code Map.prototype.getOrInsert}
 * and {@code Map.prototype.getOrInsertComputed}. {@code pdfjs-dist} v5
 * uses {@code getOrInsertComputed} inside its message handler and
 * crashes on browsers that haven't shipped the proposal yet (notably
 * Safari < 18 and Firefox &lt; 132).
 *
 * Importing this module installs the methods if missing — idempotent,
 * skipped when the runtime already provides them.
 *
 * Must be imported <em>before</em> the first {@code pdfjs-dist} call,
 * <em>and</em> from the worker entry so the polyfill is also live in
 * the worker's global scope.
 */
interface MapWithUpsert<K, V> extends Map<K, V> {
    getOrInsert(key: K, value: V): V;
    getOrInsertComputed(key: K, callbackfn: (key: K) => V): V;
}
declare const proto: MapWithUpsert<unknown, unknown>;
//# sourceMappingURL=mapGetOrInsert.d.ts.map