import './style.css';
import { listGroups } from './api';
import {
  clearConnection,
  hasHostAccess,
  loadConnection,
  loadGrabFolder,
  parseConnection,
  requestHostAccess,
  saveConnection,
  saveGrabFolder,
} from './connection';

/**
 * Settings: paste a connection string, let the browser be asked for access to
 * that host, prove the whole thing works, store it.
 *
 * <p>The order is the point. Storing first and finding out later that the host
 * was never granted, or that the token was already revoked, produces an
 * extension that looks configured and fails at the moment somebody wanted to
 * save something. So: parse → ask → call → store, and nothing is written
 * unless the round trip came back.
 */

const el = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

const status = el<HTMLDivElement>('status');
const blobInput = el<HTMLTextAreaElement>('blob');

function say(text: string, kind: 'plain' | 'error' | 'ok' = 'plain'): void {
  status.textContent = text;
  status.className = `note${kind === 'plain' ? '' : ` ${kind}`}`;
}

void main();

async function main(): Promise<void> {
  el('save').addEventListener('click', () => void onSave());
  el('test').addEventListener('click', () => void onTest());
  el('forget').addEventListener('click', () => void onForget());
  el('save-folder').addEventListener('click', () => void onSaveFolder());
  el<HTMLInputElement>('grab-folder').value = await loadGrabFolder();
  await renderCurrent();
}

async function renderCurrent(): Promise<void> {
  const conn = await loadConnection();
  const section = el('current');
  if (!conn) {
    section.classList.add('hidden');
    return;
  }
  section.classList.remove('hidden');
  el('cur-brain').textContent = conn.brainUrl;
  el('cur-tenant').textContent = conn.tenant;
  el('cur-project').textContent = conn.projectId;
  el('cur-folder').textContent = conn.target || '(project root)';
  el('cur-expires').textContent = conn.expiresAt
    ? new Date(conn.expiresAt).toISOString().slice(0, 10)
    : 'never';

  if (!(await hasHostAccess(conn))) {
    say('Stored, but the browser has not granted access to that host. '
      + 'Paste the string again and save to be asked.', 'error');
  }
}

async function onSave(): Promise<void> {
  const text = blobInput.value.trim();
  if (!text) return;

  const conn = parseConnection(text);
  if (!conn) {
    // One message for every way it can fail. The only useful reaction is to
    // copy it again, and naming which byte went missing would not change that.
    say('That is not a usable connection string — it may have been cut off in '
      + 'copying. Copy it again from the link list.', 'error');
    return;
  }

  // Must run in this click's gesture: Chrome refuses a permission request that
  // is not user-initiated, and awaiting anything first can lose the gesture.
  if (!(await requestHostAccess(conn))) {
    say(`Access to ${conn.brainUrl} was not granted — without it the extension `
      + 'cannot reach the brain.', 'error');
    return;
  }

  try {
    const groups = await listGroups(conn);
    await saveConnection(conn);
    blobInput.value = '';
    say(`Connected to “${groups.title ?? conn.target}” — `
      + `${groups.groups.length} group${groups.groups.length === 1 ? '' : 's'}.`, 'ok');
    await renderCurrent();
  } catch (e) {
    // Deliberately not stored: a credential that already fails is not worth
    // keeping, and keeping it would hide the failure behind a popup later.
    say(message(e), 'error');
  }
}

async function onSaveFolder(): Promise<void> {
  await saveGrabFolder(el<HTMLInputElement>('grab-folder').value);
  // No round trip to confirm it: the folder is not checked until something is
  // saved into it, and the server creates it on the way. Claiming it was
  // "verified" would be a claim we did not make.
  say('Folder saved.', 'ok');
}

async function onTest(): Promise<void> {
  const conn = await loadConnection();
  if (!conn) return;
  try {
    const groups = await listGroups(conn);
    say(`Working — ${groups.groups.length} group${groups.groups.length === 1 ? '' : 's'} `
      + `in “${groups.title ?? conn.target}”.`, 'ok');
  } catch (e) {
    say(message(e), 'error');
  }
}

async function onForget(): Promise<void> {
  if (!window.confirm('Forget this connection? The token itself stays valid until '
    + 'you revoke it in Vancetope.')) return;
  await clearConnection();
  say('Forgotten. The token is still live — revoke it under the ⚙ of the link list '
    + 'if it should stop working.', 'plain');
  await renderCurrent();
}

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
