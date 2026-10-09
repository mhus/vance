/**
 * The project the reader last worked in — remembered per browser tab.
 *
 * <p>Every editor has its own project picker, and until now each one
 * started from its own idea of "nothing selected": the documents explorer
 * took the alphabetically first project, tools and setting-forms opened on
 * `_tenant`, the run view and the chat picker opened on nothing at all.
 * Switching editor therefore meant re-picking the same project, every time.
 *
 * <h3>Why browser-tab storage and not the server</h3>
 *
 * <p>Sidebar collapse state lives on the server (`me/ui-state/sidebar`)
 * because it is a lasting preference. This is the opposite kind of thing: it
 * is where the reader happens to be *right now*, and two browser tabs are
 * routinely in two different projects — that is what tabs are for. A shared
 * store would make the second tab overwrite the first one's context, which is
 * exactly the annoyance this is meant to remove. Tab-scoped storage survives
 * the full page loads between the standalone editors, which is the whole
 * span that needs covering — and inside the desktop app it moves to
 * `localStorage`, see {@link backingStore}.
 *
 * <p>Keyed by tenant and user, so signing into a different account in the
 * same tab does not inherit the previous one's project. That is belt to the
 * braces of {@link recallProject}'s membership check, not a substitute for it:
 * a project can be deleted or renamed while the name sits in storage.
 *
 * <h3>The one rule for callers</h3>
 *
 * <p>An explicit project always wins. Recall answers the question "what should
 * be selected when nothing said otherwise" — a `?project=` in the URL, a
 * session that carries its own project, a deep link: all of those say
 * otherwise. Remember, by contrast, is fed by the *effective* selection
 * however it arose, so following a deep link into a project also makes that
 * project the one the next editor opens in.
 */


import { getDesktopTools } from '@vance/shared';
import { getSessionData } from './webUiSession';

const KEY_PREFIX = 'vance.lastProject';

/**
 * Where the memory physically lives.
 *
 * <p><b>Browser: `sessionStorage`</b> — the per-tab design below.
 *
 * <p><b>Desktop app: `localStorage`</b> — the app has no tabs. One
 * WebContentsView per account IS the tab, and it dies with the app:
 * quitting and relaunching Vancetope started every editor on a blank
 * project while everything `localStorage`-backed (the Cortex
 * auto-reveal flag, …) survived in the persistent partition — the
 * memory became the *only* thing the app forgot. In a browser the same
 * user keeps a tab open for days (or restores the session, which
 * restores `sessionStorage` too), so the asymmetry never shows there.
 * There is no cross-tab bleed inside the app because the partition is
 * per account — same isolation the per-tab design buys in the browser.
 *
 * <p>The bridge is the gate, not the UA string: `getDesktopTools()`
 * answers "am I inside the app" the same way the client-tools code
 * does, so a desktop webview silently running an old build without the
 * preload keeps the browser behavior.
 */
function backingStore(): Storage {
  return getDesktopTools() ? window.localStorage : window.sessionStorage;
}

/**
 * Storage key for the signed-in account. Falls back to a shared key when
 * there is no session yet — a page that reads this before login has no
 * account to attribute the value to, and the membership check on read makes
 * a mismatch harmless.
 */
function storageKey(): string {
  const s = getSessionData();
  return s ? `${KEY_PREFIX}.${s.tenantId}.${s.username}` : KEY_PREFIX;
}

/**
 * Record `name` as the project this tab is working in.
 *
 * <p>Safe to call on every selection change, including the programmatic ones
 * a host makes while hydrating from the URL: writing the same value twice
 * costs nothing.
 *
 * <p><b>A blank or absent name is ignored, not a clear.</b> Editors pass
 * through "no project selected" constantly — the chat picker drops its
 * selection when a session binds, a popstate to a URL without `?project=`
 * nulls it — and none of those mean the reader stopped working in that
 * project. Clearing there would empty the memory precisely when it is about
 * to be useful. Nothing needs the opposite: the key carries the account, so
 * signing out does not leave a value the next account can see.
 */
export function rememberProject(name: string | null | undefined): void {
  if (!name) return;
  try {
    backingStore().setItem(storageKey(), name);
  } catch {
    // Private-mode browsers and storage-partitioned WebViews can throw on
    // write. The memory is a convenience; losing it must never break the page.
  }
}

/**
 * The remembered project, or `null` when there is none.
 *
 * <p>Pass `selectable` — the project names this host can actually show — and
 * the answer is `null` unless the remembered one is among them. It takes names
 * rather than the project list because "selectable here" is not always "exists
 * in the tenant": the setting-forms picker hides the per-user hub projects, and
 * selecting a project its dropdown does not list would leave the control blank.
 *
 * <p>Without the argument the raw name comes back, which is only right for
 * callers that can survive a stale one — a project can be deleted or renamed
 * while its name sits in this tab's storage, and the brain answers 404.
 */
export function recallProject(selectable?: readonly string[]): string | null {
  let stored: string | null;
  try {
    stored = backingStore().getItem(storageKey());
  } catch {
    return null;
  }
  if (!stored) return null;
  if (selectable && !selectable.includes(stored)) return null;
  return stored;
}
