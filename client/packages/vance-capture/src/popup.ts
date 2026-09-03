import './style.css';
import { api } from './browserApi';
import { CaptureError, capture, grab, listGroups, lookup } from './api';
import {
  type StoredConnection,
  connectionKey,
  connectionLabel,
  cortexUrlFor,
  daysLeft,
  hasHostAccess,
  knownToLack,
  linksAppUrl,
  loadActive,
  loadConnections,
  setActive,
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
 *
 * <p><b>And only the active destination is asked, never all of them.</b> The
 * same reasoning: with several brains configured, answering "is this page in
 * any of my lists" would tell every one of them what page this is, on every
 * open. So the answer is scoped to the chosen list — and the wording has to
 * say so, because "Already saved" reads as a claim about all of them.
 */

const el = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

const status = el<HTMLDivElement>('status');
const statusText = el<HTMLSpanElement>('status-text');
const statusAction = el<HTMLButtonElement>('status-action');
const statusOpen = el<HTMLButtonElement>('status-open');
const targetRow = el<HTMLLabelElement>('target-row');
const targetSelect = el<HTMLSelectElement>('target');
const titleInput = el<HTMLInputElement>('title');
const groupSelect = el<HTMLSelectElement>('group');
const noteInput = el<HTMLInputElement>('note');
const saveButton = el<HTMLButtonElement>('save');
const grabButton = el<HTMLButtonElement>('grab');
const openListButton = el<HTMLButtonElement>('open-list');
const urlLine = el<HTMLParagraphElement>('url');

let connections: StoredConnection[] = [];
let active: StoredConnection | null = null;
let pageUrl = '';
let pageTabId: number | null = null;
/** Where the ↗ goes for the destination currently selected. */
let listUrl: string | null = null;
/** The group the page is already filed under, once both round trips are in. */
let preselectGroup: string | null = null;
/**
 * Which activation is the current one.
 *
 * <p>Switching destinations twice in a row leaves two round trips in flight
 * against two different brains, and the slower one answering last would write
 * its "Already saved in …" over a popup that now points somewhere else. The
 * counter lets a superseded run drop its answer instead of showing it.
 */
let generation = 0;

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
  // Any new message replaces whatever the last one offered.
  statusOpen.classList.add('hidden');
}

function clearStatus(): void {
  status.className = 'note hidden';
  statusAction.classList.add('hidden');
  statusOpen.classList.add('hidden');
}

/** Open a web-UI URL in a new tab and step out of the way. */
function openInVance(url: string): void {
  void api.tabs.create({ url });
  window.close();
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
  targetSelect.addEventListener('change', () => void onSwitch());
  // One handler reading a variable, rather than a fresh listener per
  // destination: re-adding one on every switch stacks them, and the third
  // switch opens three tabs.
  openListButton.addEventListener('click', () => { if (listUrl) openInVance(listUrl); });
  // A note is the last thing typed; Enter there should mean "done".
  noteInput.addEventListener('keyup', (e) => { if (e.key === 'Enter') void onSave(); });

  connections = await loadConnections();
  active = await loadActive();
  if (!active) {
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

  fillTargets();
  show('form');
  await activate();
}

async function currentTab(): Promise<chrome.tabs.Tab | undefined> {
  const [tab] = await api.tabs.query({ active: true, currentWindow: true });
  return tab;
}

function fillTargets(): void {
  targetRow.classList.toggle('hidden', connections.length < 2);
  if (connections.length < 2) return;
  targetSelect.innerHTML = '';
  for (const record of connections) {
    const option = document.createElement('option');
    option.value = connectionKey(record.blob);
    option.textContent = connectionLabel(record);
    targetSelect.append(option);
  }
  if (active) targetSelect.value = connectionKey(active.blob);
}

async function onSwitch(): Promise<void> {
  const chosen = connections.find((c) => connectionKey(c.blob) === targetSelect.value);
  if (!chosen) return;
  active = chosen;
  // Persisted immediately rather than on the next save: the choice is the
  // person's, and losing it because they closed the popup to read something
  // first would mean choosing again every time.
  await setActive(targetSelect.value);
  await activate();
}

/**
 * Everything that depends on *which* destination is chosen.
 *
 * <p>Split from {@link main} so a switch re-runs exactly this and nothing else
 * — the tab was read once and does not change.
 */
async function activate(): Promise<void> {
  const record = active;
  if (!record) return;
  const mine = ++generation;
  clearStatus();
  // A fresh destination has its own answer to "is this already here"; carrying
  // the previous one's button label over would say "Save anyway" about a list
  // that has never seen the page.
  saveButton.textContent = 'Save link';
  groupSelect.innerHTML = '<option value="">— no group —</option>';
  preselectGroup = null;
  listUrl = linksAppUrl(record.blob);
  // A connection without a folder points at no list; a button that cannot go
  // anywhere is worse than none.
  openListButton.classList.toggle('hidden', !listUrl);

  if (!(await hasHostAccess(record.blob))) {
    // Not the "unconfigured" section: with several destinations the others may
    // be perfectly reachable, and hiding the picker would strand the person on
    // the one that is not.
    say(`The browser has not granted access to ${record.blob.brainUrl}. `
      + 'Grant it in the settings.', 'error', true);
    // Writing is off, but the picker stays live: stranding somebody on the one
    // destination that is unreachable, with two working ones in the list, is
    // the opposite of what this check is for.
    setWritable(false);
    return;
  }
  setWritable(true);

  const left = daysLeft(record.blob);
  if (left !== null && left <= 14) {
    say(left <= 0
      ? 'This token has expired — create a new one in the link list.'
      : `This token expires in ${left} day${left === 1 ? '' : 's'}.`,
      'error', left <= 0);
  }

  await Promise.all([fillGroups(record, mine), checkExisting(record, mine)]);
  if (mine === generation && preselectGroup) {
    // Applied after both round trips rather than where it was learned: the
    // lookup routinely answers before the group list does, and assigning a
    // value to a select that has no such option yet does nothing at all.
    groupSelect.value = preselectGroup;
  }
}

/** Whether saving is possible at all — off while the brain is unreachable. */
function setWritable(ok: boolean): void {
  saveButton.disabled = !ok;
  grabButton.disabled = !ok;
}

/**
 * Save and grab both write; neither may run while the other does, and the
 * destination must not move under a request that is already out.
 */
function setBusy(busy: boolean): void {
  setWritable(!busy);
  targetSelect.disabled = busy;
}

/**
 * The lead "no group" choice is first and empty-valued — the same shape the
 * app renders it in, where it is the absence of a heading rather than a
 * heading called "none".
 */
async function fillGroups(record: StoredConnection, mine: number): Promise<void> {
  try {
    const view = await listGroups(record.blob);
    if (mine !== generation) return;
    for (const group of view.groups) {
      const option = document.createElement('option');
      option.value = group;
      option.textContent = group;
      groupSelect.append(option);
    }
  } catch (e) {
    if (mine !== generation) return;
    // A missing dropdown is not a reason to block saving — the entry just
    // lands ungrouped, and that is recoverable in the app.
    say(describe(e), 'error', fixable(e));
  }
}

async function checkExisting(record: StoredConnection, mine: number): Promise<void> {
  try {
    const found = await lookup(record.blob, pageUrl);
    if (mine !== generation || !found.found) return;
    const seen = found.viewedAt ? ' — and marked seen.' : '.';
    say(`${alreadyAt(record, found.group)}${seen}`, 'ok');
    // Not disabled: saving again is a no-op on the server, and the fields are
    // still worth showing so the person can see what is stored.
    saveButton.textContent = 'Save anyway';
    preselectGroup = found.group ?? null;
  } catch (e) {
    if (mine !== generation) return;
    say(describe(e), 'error', fixable(e));
  }
}

/**
 * The group, phrased to fit whichever sentence it lands in.
 *
 * <p>Parenthesised once the list is also named: "in “Work links” in “Reading”"
 * is grammatical and unreadable, and which of the two nouns is the list is
 * exactly the thing the sentence exists to say.
 */
function groupSuffix(group?: string | null): string {
  if (!group) return '';
  return connections.length < 2 ? ` in “${group}”` : ` (group “${group}”)`;
}

/**
 * Where the page already is.
 *
 * <p>The list is named only when there is more than one, and that is not
 * cosmetic: the lookup covers the chosen destination alone, so a bare "already
 * saved" would answer a question about all of them that nobody asked. With a
 * single destination there is nothing to disambiguate and the name is noise.
 */
function alreadyAt(record: StoredConnection, group?: string | null): string {
  return connections.length < 2
    ? `Already saved${groupSuffix(group)}`
    : `Already in “${connectionLabel(record)}”${groupSuffix(group)}`;
}

/** Where it just went. Same rule, other tense. */
function savedAt(record: StoredConnection, group?: string | null): string {
  return connections.length < 2
    ? `Saved${groupSuffix(group)}`
    : `Saved to “${connectionLabel(record)}”${groupSuffix(group)}`;
}

async function onSave(): Promise<void> {
  // Captured, not read from the module: a switch mid-flight must not redirect
  // a save that was already sent, nor report it against the wrong list.
  const record = active;
  if (!record || !pageUrl) return;
  setBusy(true);
  clearStatus();
  try {
    const result = await capture(record.blob, {
      url: pageUrl,
      title: titleInput.value.trim() || undefined,
      group: groupSelect.value || undefined,
      note: noteInput.value.trim() || undefined,
    });
    say(result.added
      ? `${savedAt(record, result.group)}.`
      : `${alreadyAt(record, result.group)} — nothing changed.`, 'ok');
    // Long enough to read the line, short enough not to be in the way.
    setTimeout(() => window.close(), 1200);
  } catch (e) {
    say(describe(e), 'error', fixable(e));
    setBusy(false);
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
  const record = active;
  if (!record || !pageUrl || pageTabId === null) return;
  // Said here rather than left to the server's 401: a token minted before this
  // capability existed cannot grow one — the claims are signed — so the answer
  // is "create a new one", not "try again".
  if (knownToLack(record.blob, 'web-grab')) {
    say('This token cannot save pages — it was created without that capability. '
      + 'Create a new one in the link list and tick “Save pages as documents”.',
      'error', true);
    return;
  }
  setBusy(true);
  say('Reading the page…');
  try {
    const page = await readTab(pageTabId, pageUrl);
    const result = await grab(record.blob, {
      url: pageUrl,
      content: page.blob,
      // This destination's folder, not one setting for the extension: a grab
      // into another project's path would land somewhere that means nothing
      // there.
      folder: record.grabFolder || undefined,
      title: titleInput.value.trim() || undefined,
    });
    // Which of the two happened is worth saying: "we turned your page into
    // Markdown" and "we stored your PDF" are different outcomes.
    say(result.converted
      ? `Saved as Markdown — ${result.path}`
      : `Saved as ${result.mimeType ?? 'a file'} — ${result.path}`, 'ok');
    // Offered, not opened. Saving three pages in a row should not leave three
    // tabs open behind you; whoever wants to look now says so.
    const docUrl = cortexUrlFor(record.blob, result.path);
    statusOpen.onclick = () => openInVance(docUrl);
    statusOpen.classList.remove('hidden');
  } catch (e) {
    say(describe(e), 'error', fixable(e));
  } finally {
    setBusy(false);
  }
}

function describe(e: unknown): string {
  if (e instanceof CaptureError) return e.message;
  return e instanceof Error ? e.message : String(e);
}
