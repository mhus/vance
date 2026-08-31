/**
 * The one namespace difference between the browsers we target.
 *
 * <p>Firefox exposes `browser.*` with promises; Chrome exposes `chrome.*`,
 * which in MV3 also returns promises for the four APIs used here (storage,
 * permissions, tabs, runtime). Firefox additionally provides a callback-shaped
 * `chrome` alias for compatibility — so preferring `browser` where it exists is
 * what keeps every call promise-shaped on both.
 *
 * <p><b>No `webextension-polyfill`.</b> It exists to turn callbacks into
 * promises across a whole API surface; ours is four calls that are already
 * promises on both engines. 20 kB of shim to replace one line would be the
 * larger of the two liabilities.
 *
 * <p>`typeof browser === 'undefined'` rather than a truthiness check: in Chrome
 * the identifier does not exist at all, and only `typeof` may be applied to an
 * undeclared name without throwing.
 */
declare const browser: typeof chrome | undefined;

export const api: typeof chrome = typeof browser === 'undefined' ? chrome : browser;
