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
 * <p><b>Both are `typeof`-guarded</b>, and that is not symmetry for its own
 * sake: naming an undeclared identifier throws, and a throw here happens at
 * module-load time — it takes down the whole page before a single line of the
 * importing module runs. The symptom is a page that renders and does nothing,
 * which is expensive to diagnose from the outside.
 *
 * <p>Outside an extension neither exists and `api` is undefined. Left that way
 * on purpose: the failure then lands at the call that needed it, which is a
 * place with a stack trace, instead of at an import.
 */
declare const browser: typeof chrome | undefined;

const resolved = typeof browser !== 'undefined'
  ? browser
  : typeof chrome !== 'undefined'
    ? chrome
    : undefined;

export const api: typeof chrome = resolved as typeof chrome;
