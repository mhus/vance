"use strict";
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
const proto = Map.prototype;
if (typeof proto.getOrInsert !== 'function') {
    Object.defineProperty(proto, 'getOrInsert', {
        value: function getOrInsert(key, value) {
            if (this.has(key))
                return this.get(key);
            this.set(key, value);
            return value;
        },
        writable: true,
        configurable: true,
    });
}
if (typeof proto.getOrInsertComputed !== 'function') {
    Object.defineProperty(proto, 'getOrInsertComputed', {
        value: function getOrInsertComputed(key, callbackfn) {
            if (this.has(key))
                return this.get(key);
            const value = callbackfn(key);
            this.set(key, value);
            return value;
        },
        writable: true,
        configurable: true,
    });
}
//# sourceMappingURL=mapGetOrInsert.js.map