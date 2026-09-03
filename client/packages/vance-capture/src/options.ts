import './style.css';
import { listGroups } from './api';
import {
  type StoredConnection,
  connectionKey,
  connectionLabel,
  daysLeft,
  hasHostAccess,
  loadActive,
  loadConnections,
  normalizeFolder,
  parseConnection,
  releaseHostAccess,
  removeConnection,
  requestHostAccess,
  setActive,
  updateConnection,
  upsertConnection,
} from './connection';

/**
 * Settings: the list of destinations, and a box to add one.
 *
 * <p>The add order is the point. Storing first and finding out later that the
 * host was never granted, or that the token was already revoked, produces an
 * extension that looks configured and fails at the moment somebody wanted to
 * save something. So: parse → ask → call → store, and nothing is written
 * unless the round trip came back.
 *
 * <p><b>Every row shows its expiry, so a dead token is visible without
 * switching to it.</b> The popup can only warn about the destination it is
 * pointed at; with several configured, that is the one place where a token
 * quietly running out would go unseen until somebody tried to use it.
 */

const el = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

/** Non-null querySelector. A row template that lost a class is a bug, not a case. */
function pick<T extends HTMLElement>(root: ParentNode, selector: string): T {
  const found = root.querySelector<T>(selector);
  if (!found) throw new Error(`The row template is missing ${selector}`);
  return found;
}

const status = el<HTMLDivElement>('status');
const blobInput = el<HTMLTextAreaElement>('blob');
const listBox = el<HTMLDivElement>('list');
const rowTemplate = el<HTMLTemplateElement>('conn-row');

function say(text: string, kind: 'plain' | 'error' | 'ok' = 'plain'): void {
  status.textContent = text;
  status.className = `note${kind === 'plain' ? '' : ` ${kind}`}`;
}

void main();

async function main(): Promise<void> {
  el('save').addEventListener('click', () => void onAdd());
  await render();
}

async function render(): Promise<void> {
  const list = await loadConnections();
  const active = await loadActive();
  const activeKey = active ? connectionKey(active.blob) : null;
  listBox.innerHTML = '';
  el('none').classList.toggle('hidden', list.length > 0);
  for (const record of list) {
    listBox.append(await renderRow(record, activeKey));
  }
}

async function renderRow(
  record: StoredConnection,
  activeKey: string | null,
): Promise<HTMLElement> {
  const key = connectionKey(record.blob);
  const row = pick<HTMLElement>(
    rowTemplate.content.cloneNode(true) as DocumentFragment, 'article');

  const labelInput = pick<HTMLInputElement>(row, '.label');
  labelInput.value = record.label;
  // The derived name as a placeholder, not as a value: written into the field
  // it would be saved on the next Save and stop tracking the folder it came
  // from.
  labelInput.placeholder = connectionLabel(record);

  pick(row, '.brain').textContent = record.blob.brainUrl;
  pick(row, '.tenant').textContent = record.blob.tenant;
  pick(row, '.project').textContent = record.blob.projectId;
  pick(row, '.folder').textContent = normalizeFolder(record.blob.target) || '(project root)';
  pick(row, '.expires').textContent = expiryText(record);

  const isActive = key === activeKey;
  row.classList.toggle('active', isActive);
  pick(row, '.use').classList.toggle('hidden', isActive);
  pick(row, '.badge').classList.toggle('hidden', !isActive);

  const folderInput = pick<HTMLInputElement>(row, '.grab-folder');
  folderInput.value = record.grabFolder;

  if (!(await hasHostAccess(record.blob))) {
    pick(row, '.grant').classList.remove('hidden');
    pick(row, '.grant-text').textContent =
      `The browser has not granted access to ${record.blob.brainUrl} — without it `
      + 'the extension cannot reach this brain.';
  }

  pick(row, '.use').addEventListener('click', () => void onUse(key, record));
  pick(row, '.apply').addEventListener('click', () => void onApply(
    key, labelInput.value, folderInput.value));
  pick(row, '.test').addEventListener('click', () => void onTest(record));
  pick(row, '.forget').addEventListener('click', () => void onForget(key, record));
  pick(row, '.grant-button').addEventListener('click', () => void onGrant(record));

  return row;
}

/**
 * The date, and how long it has left when that is close enough to matter.
 *
 * <p>A date alone makes the reader do the arithmetic; the number of days alone
 * hides which token it was. Both, and only when the answer is interesting.
 */
function expiryText(record: StoredConnection): string {
  if (!record.blob.expiresAt) return 'never';
  const date = new Date(record.blob.expiresAt).toISOString().slice(0, 10);
  const left = daysLeft(record.blob);
  if (left === null || left > 14) return date;
  if (left <= 0) return `${date} — expired`;
  return `${date} — ${left} day${left === 1 ? '' : 's'} left`;
}

async function onUse(key: string, record: StoredConnection): Promise<void> {
  await setActive(key);
  say(`The popup now saves to “${connectionLabel(record)}”.`, 'ok');
  await render();
}

async function onApply(key: string, label: string, grabFolder: string): Promise<void> {
  await updateConnection(key, { label, grabFolder });
  // No round trip to confirm the folder: it is not checked until something is
  // saved into it, and the server creates it on the way. Claiming it was
  // "verified" would be a claim we did not make.
  say('Saved.', 'ok');
  await render();
}

async function onTest(record: StoredConnection): Promise<void> {
  try {
    const groups = await listGroups(record.blob);
    say(`“${connectionLabel(record)}” is working — ${groups.groups.length} `
      + `group${groups.groups.length === 1 ? '' : 's'} in `
      + `“${groups.title ?? record.blob.target}”.`, 'ok');
  } catch (e) {
    say(message(e), 'error');
  }
}

async function onForget(key: string, record: StoredConnection): Promise<void> {
  if (!window.confirm(`Forget “${connectionLabel(record)}”? The token itself stays `
    + 'valid until you revoke it in Vancetope.')) return;
  const remaining = await removeConnection(key);
  // Only if nothing left needs that host — two destinations on one brain are
  // routine, and revoking on the first Forget would break the other one.
  await releaseHostAccess(record.blob, remaining);
  say('Forgotten. The token is still live — revoke it under the ⚙ of the link list '
    + 'if it should stop working.', 'plain');
  await render();
}

/**
 * Ask the browser for access to one brain.
 *
 * <p>Its own button because the request has to come out of a user gesture. The
 * alternative, and what this replaces, was pasting the connection string
 * again — which with several destinations means finding the right string for
 * the row that lost its grant.
 */
async function onGrant(record: StoredConnection): Promise<void> {
  try {
    if (!(await requestHostAccess(record.blob))) {
      say(`Access to ${record.blob.brainUrl} was not granted.`, 'error');
      return;
    }
    say(`Access to ${record.blob.brainUrl} granted.`, 'ok');
    await render();
  } catch (e) {
    say(message(e), 'error');
  }
}

async function onAdd(): Promise<void> {
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
  //
  // Wrapped, because this can throw as well as answer false — and an exception
  // escaping a click handler is invisible: no message, no state change, a
  // button that looks broken. That is exactly how a malformed match pattern
  // presented itself.
  try {
    if (!(await requestHostAccess(conn))) {
      say(`Access to ${conn.brainUrl} was not granted — without it the extension `
        + 'cannot reach the brain.', 'error');
      return;
    }
  } catch (e) {
    say(message(e), 'error');
    return;
  }

  try {
    const groups = await listGroups(conn);
    // The list's own title as the name in the picker. It comes back from the
    // call that verifies the token, so it costs nothing — and a destination
    // that arrives already named is one fewer field to fill in. Ignored when
    // this destination is already listed: the name there is the person's.
    const record = await upsertConnection(conn, { label: groups.title ?? '' });
    blobInput.value = '';
    say(`Connected to “${connectionLabel(record)}” — ${groups.groups.length} `
      + `group${groups.groups.length === 1 ? '' : 's'}. The popup now saves here.`, 'ok');
    await render();
  } catch (e) {
    // Deliberately not stored: a credential that already fails is not worth
    // keeping, and keeping it would hide the failure behind a popup later.
    say(message(e), 'error');
  }
}

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
