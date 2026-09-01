import './style.css';
import { api } from './browserApi';
import { CaptureError, capture, grab, listGroups, lookup } from './api';
import {
  type ConnectionBlob,
  daysLeft,
  hasHostAccess,
  knownToLack,
  loadConnection,
  loadGrabFolder,
} from './connection';
import { readTab } from './page';

/**
 * The popup: what page am I on, is it already saved, save it.
 *
 * <p><b>The lookup happens here, on open — not on every tab change.</b> A badge
 * that marks each page as saved or not would have to ask the brain about every
 * URL the person visits, which hands their whole browsing history to the
 * server for the sake of an icon. Asking only when the popup is opened means
 * the brain hears about pages somebody deliberately asked about.
 */

const el = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

const status = el<HTMLDivElement>('status');
const statusText = el<HTMLSpanElement>('status-text');
const statusAction = el<HTMLButtonElement>('status-action');
const titleInput = el<HTMLInputElement>('title');
const groupSelect = el<HTMLSelectElement>('group');
const noteInput = el<HTMLInputElement>('note');
const saveButton = el<HTMLButtonElement>('save');
const grabButton = el<HTMLButtonElement>('grab');
const urlLine = el<HTMLParagraphElement>('url');

let connection: ConnectionBlob | null = null;
let pageUrl = '';
let pageTabId: number | null = null;

function show(id: string): void {
  el(id).classList.remove('hidden');
}

/**
 * @param actionable true when the way out is the settings page — a rejected or
 *                   expired token. The message alone would name the fix
 *                   ("create a new one and paste it again") without offering a
 *                   route to it, which is the shape of advice nobody follows.
 */
function say(text: string, kind: 'plain' | 'error' | 'ok' = 'plain',
             actionable = false): void {
  statusText.textContent = text;
  status.className = `note${kind === 'plain' ? '' : ` ${kind}`}`;
  statusAction.classList.toggle('hidden', !actionable);
}

function clearStatus(): void {
  status.className = 'note hidden';
  statusAction.classList.add('hidden');
}

/**
 * Open the settings page.
 *
 * <p>Not just `openOptionsPage()`. Safari does not support the manifest's
 * `options_ui.open_in_tab` — the converter says so out loud — and what it does
 * with the call instead is version-dependent. The settings page is where the
 * connection string is pasted, so "the button did nothing" would mean the
 * extension cannot be set up at all on that browser.
 *
 * <p>The fallback opens the same page as an ordinary tab by its extension URL,
 * which every engine supports because it is just a URL.
 */
function openSettings(): void {
  try {
    if (typeof api.runtime.openOptionsPage === 'function') {
      api.runtime.openOptionsPage();
      return;
    }
  } catch {
    // Fall through — a throwing openOptionsPage is the same problem as a
    // missing one.
  }
  void api.tabs.create({ url: api.runtime.getURL('options.html') });
}

/** Whether the failure is one the settings page can fix. */
function fixable(e: unknown): boolean {
  return e instanceof CaptureError && e.isCredential;
}

void main();

async function main(): Promise<void> {
  el('open-options').addEventListener('click', openSettings);
  el('settings').addEventListener('click', openSettings);
  statusAction.addEventListener('click', openSettings);
  saveButton.addEventListener('click', () => void onSave());
  grabButton.addEventListener('click', () => void onGrab());
  // A note is the last thing typed; Enter there should mean "done".
  noteInput.addEventListener('keyup', (e) => { if (e.key === 'Enter') void onSave(); });

  connection = await loadConnection();
  if (!connection) {
    show('unconfigured');
    return;
  }
  if (!(await hasHostAccess(connection))) {
    say('The browser has not granted access to this brain. Re-save the connection '
      + 'string in the settings to grant it.', 'error');
    show('unconfigured');
    return;
  }

  const tab = await currentTab();
  if (!tab?.url || !/^https?:/i.test(tab.url)) {
    show('unsupported');
    return;
  }
  pageUrl = tab.url;
  pageTabId = tab.id ?? null;
  titleInput.value = tab.title ?? '';
  urlLine.textContent = pageUrl;
  urlLine.title = pageUrl;

  const left = daysLeft(connection);
  if (left !== null && left <= 14) {
    say(left <= 0
      ? 'This token has expired — create a new one in the link list.'
      : `This token expires in ${left} day${left === 1 ? '' : 's'}.`,
      'error', left <= 0);
  }

  show('form');
  await Promise.all([fillGroups(connection), checkExisting(connection)]);
}

async function currentTab(): Promise<chrome.tabs.Tab | undefined> {
  const [tab] = await api.tabs.query({ active: true, currentWindow: true });
  return tab;
}

/**
 * The lead "no group" choice is first and empty-valued — the same shape the
 * app renders it in, where it is the absence of a heading rather than a
 * heading called "none".
 */
async function fillGroups(conn: ConnectionBlob): Promise<void> {
  groupSelect.innerHTML = '<option value="">— no group —</option>';
  try {
    const view = await listGroups(conn);
    for (const group of view.groups) {
      const option = document.createElement('option');
      option.value = group;
      option.textContent = group;
      groupSelect.append(option);
    }
  } catch (e) {
    // A missing dropdown is not a reason to block saving — the entry just
    // lands ungrouped, and that is recoverable in the app.
    say(describe(e), 'error', fixable(e));
  }
}

async function checkExisting(conn: ConnectionBlob): Promise<void> {
  try {
    const found = await lookup(conn, pageUrl);
    if (!found.found) return;
    say(found.viewedAt
      ? `Already saved${where(found.group)} — and marked seen.`
      : `Already saved${where(found.group)}.`, 'ok');
    // Not disabled: saving again is a no-op on the server, and the fields are
    // still worth showing so the person can see what is stored.
    saveButton.textContent = 'Save anyway';
    if (found.group) groupSelect.value = found.group;
  } catch (e) {
    say(describe(e), 'error', fixable(e));
  }
}

function where(group?: string | null): string {
  return group ? ` in “${group}”` : '';
}

async function onSave(): Promise<void> {
  if (!connection || !pageUrl) return;
  saveButton.disabled = true;
  clearStatus();
  try {
    const result = await capture(connection, {
      url: pageUrl,
      title: titleInput.value.trim() || undefined,
      group: groupSelect.value || undefined,
      note: noteInput.value.trim() || undefined,
    });
    say(result.added
      ? `Saved${where(result.group)}.`
      : `Already in the list${where(result.group)} — nothing changed.`, 'ok');
    // Long enough to read the line, short enough not to be in the way.
    setTimeout(() => window.close(), 1200);
  } catch (e) {
    say(describe(e), 'error', fixable(e));
    saveButton.disabled = false;
  }
}

/**
 * Import the page itself.
 *
 * <p>Deliberately a second action rather than a side effect of Save: keeping a
 * reference and keeping a copy are different decisions, and a link list that
 * silently filled a project with documents would be a surprise nobody asked
 * for. The two share nothing but this popup.
 */
async function onGrab(): Promise<void> {
  if (!connection || !pageUrl || pageTabId === null) return;
  // Said here rather than left to the server's 401: a token minted before this
  // capability existed cannot grow one — the claims are signed — so the answer
  // is "create a new one", not "try again".
  if (knownToLack(connection, 'web-grab')) {
    say('This token cannot save pages — it was created without that capability. '
      + 'Create a new one in the link list and tick “Save pages as documents”.',
      'error', true);
    return;
  }
  grabButton.disabled = true;
  saveButton.disabled = true;
  say('Reading the page…');
  try {
    const page = await readTab(pageTabId, pageUrl);
    const result = await grab(connection, {
      url: pageUrl,
      content: page.blob,
      folder: (await loadGrabFolder()) || undefined,
      title: titleInput.value.trim() || undefined,
    });
    // Which of the two happened is worth saying: "we turned your page into
    // Markdown" and "we stored your PDF" are different outcomes.
    say(result.converted
      ? `Saved as Markdown — ${result.path}`
      : `Saved as ${result.mimeType ?? 'a file'} — ${result.path}`, 'ok');
  } catch (e) {
    say(describe(e), 'error', fixable(e));
  } finally {
    grabButton.disabled = false;
    saveButton.disabled = false;
  }
}

function describe(e: unknown): string {
  if (e instanceof CaptureError) return e.message;
  return e instanceof Error ? e.message : String(e);
}
