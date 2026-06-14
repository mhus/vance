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

const proto = Map.prototype as unknown as MapWithUpsert<unknown, unknown>;

if (typeof proto.getOrInsert !== 'function') {
  Object.defineProperty(proto, 'getOrInsert', {
    value: function getOrInsert<K, V>(this: Map<K, V>, key: K, value: V): V {
      if (this.has(key)) return this.get(key) as V;
      this.set(key, value);
      return value;
    },
    writable: true,
    configurable: true,
  });
}

if (typeof proto.getOrInsertComputed !== 'function') {
  Object.defineProperty(proto, 'getOrInsertComputed', {
    value: function getOrInsertComputed<K, V>(
      this: Map<K, V>,
      key: K,
      callbackfn: (key: K) => V,
    ): V {
      if (this.has(key)) return this.get(key) as V;
      const value = callbackfn(key);
      this.set(key, value);
      return value;
    },
    writable: true,
    configurable: true,
  });
}
