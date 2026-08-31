import './style.css';
import { api } from './browserApi';
import { CaptureError, capture, listGroups, lookup } from './api';
import { type ConnectionBlob, daysLeft, hasHostAccess, loadConnection } from './connection';

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
const titleInput = el<HTMLInputElement>('title');
const groupSelect = el<HTMLSelectElement>('group');
const noteInput = el<HTMLInputElement>('note');
const saveButton = el<HTMLButtonElement>('save');
const urlLine = el<HTMLParagraphElement>('url');

let connection: ConnectionBlob | null = null;
let pageUrl = '';

function show(id: string): void {
  el(id).classList.remove('hidden');
}

function say(text: string, kind: 'plain' | 'error' | 'ok' = 'plain'): void {
  status.textContent = text;
  status.className = `note${kind === 'plain' ? '' : ` ${kind}`}`;
}

function clearStatus(): void {
  status.className = 'note hidden';
}

void main();

async function main(): Promise<void> {
  el('open-options').addEventListener('click', () => api.runtime.openOptionsPage());
  saveButton.addEventListener('click', () => void onSave());
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
  titleInput.value = tab.title ?? '';
  urlLine.textContent = pageUrl;
  urlLine.title = pageUrl;

  const left = daysLeft(connection);
  if (left !== null && left <= 14) {
    say(left <= 0
      ? 'This token has expired — create a new one in the link list.'
      : `This token expires in ${left} day${left === 1 ? '' : 's'}.`, 'error');
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
    say(describe(e), 'error');
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
    say(describe(e), 'error');
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
    say(describe(e), 'error');
    saveButton.disabled = false;
  }
}

function describe(e: unknown): string {
  if (e instanceof CaptureError) return e.message;
  return e instanceof Error ? e.message : String(e);
}
