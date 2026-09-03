<script setup lang="ts">
import {
  computed,
  inject,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
  type Component,
  type Ref,
} from 'vue';
import {
  brainFetch,
  documentContentUrl,
  postComposeRun,
  pollComposeRun,
  cancelComposeRun,
} from '@vance/shared';
import { useDocumentPrefixReaction, useLinkPickerHost, VLinkPicker } from '@vance/components';
import { WorkPageEditor, type ComposeRunResult } from '@vance/block-editor';
import {
  scanGtd,
  getGtdAction,
  captureGtd,
  patchGtdAction,
  moveGtdAction,
  moveGtdActionToProject,
  deleteGtdAction,
  searchGtd,
  rebuildGtd,
  reorderGtdActions,
} from './api';
import type { GtdView } from './generated/gtd/GtdView';
import type { GtdActionView } from './generated/gtd/GtdActionView';
import type { GtdActionContentView } from './generated/gtd/GtdActionContentView';
import type { GtdHitView } from './generated/gtd/GtdHitView';
import { useT } from './i18n';

/**
 * GTD application view (Things-style). Left: buckets (derived) + projects +
 * contexts. Middle: the selected bucket's action list + a capture field.
 * Right: the selected action's detail — a when-picker (moving buckets = setting
 * `when`), deadline, contexts, done and a body-only editor. See
 * planning/app-gtd.md.
 */
const props = defineProps<{
  document: { id: string; path: string; projectId: string; title?: string | null };
}>();

const projectId = computed(() => props.document.projectId);
const folder = computed(() => props.document.path.replace(/\/_app\.yaml$/, ''));
const title = computed(() => view.value?.title ?? props.document.title ?? folder.value);

const t = useT();

const view = ref<GtdView | null>(null);
const error = ref<string | null>(null);
const loading = ref(false);
const rebuilding = ref(false);

const BUCKETS = ['inbox', 'today', 'upcoming', 'anytime', 'someday', 'trash'] as const;
type BucketId = (typeof BUCKETS)[number];
const selectedBucket = ref<BucketId>('today');
const selectedProject = ref<string | null>(null);
const selectedContext = ref<string | null>(null);

const selectedPath = ref<string | null>(null);
const detail = ref<GtdActionContentView | null>(null);
const detailLoading = ref(false);

// draft fields for the detail panel
const titleDraft = ref('');
const deadlineDraft = ref('');
const contextsDraft = ref('');
const projectDraft = ref('');
const currentBody = ref('');

// Sentinel for the "new project" option. Project names are folder slugs
// (`a-z0-9_-`), so a value with a colon in it can never collide with a real one.
const NEW_PROJECT = 'new:';

type SaveStatus = 'idle' | 'saving' | 'saved' | 'error';
const saveStatus = ref<SaveStatus>('idle');

const editorRef = ref<{
  save: () => void;
  flush: () => boolean;
  applyLink: (href: string, openInNewTab?: boolean) => void;
  clearLink: () => void;
  currentLinkHref: () => string | null;
} | null>(null);

// Link picker. The handshake (editor asks → dialog → apply to the current
// selection) is in `useLinkPickerHost`; the names are destructured so the
// template reads as before.
const {
  isOpen: linkPickerOpen,
  initialHref: linkPickerInitialHref,
  open: openLinkPicker,
  close: closeLinkPicker,
  onPicked: onLinkPicked,
  onClear: onLinkClear,
} = useLinkPickerHost(() => editorRef.value);

const embedComponent = inject<Component | null>('vance:embed-component', null);
const formComponent = inject<Component | null>('vance:form-component', null);
const composeOutputComponent = inject<Component | null>('vance:compose-output-component', null);
const sessionId = inject<Ref<string | null>>('vance:session-id', ref(null));

// self-write quiet window keyed by path
const SELF_WRITE_QUIET_MS = 3000;
const lastSelfWriteAt = ref<Map<string, number>>(new Map());
function withinSelfWrite(path: string): boolean {
  const t = lastSelfWriteAt.value.get(path);
  return t != null && Date.now() - t < SELF_WRITE_QUIET_MS;
}
function markSelfWrite(path: string): void {
  lastSelfWriteAt.value.set(path, Date.now());
}

// ── Derived lists ───────────────────────────────────────────────────
function bucketActions(bucket: BucketId): GtdActionView[] {
  const b = view.value?.buckets.find((x) => x.bucket === bucket);
  return b ? b.actions : [];
}
const allActions = computed<GtdActionView[]>(() =>
  (view.value?.buckets ?? []).flatMap((b) => b.actions),
);
const displayedActions = computed<GtdActionView[]>(() => {
  let list: GtdActionView[];
  if (selectedProject.value) {
    // Across all buckets — except the bin. A project view answers "what is
    // left on this project"; what somebody threw away is reachable under
    // Trash, and only there.
    list = allActions.value.filter(
      (a) => a.project === selectedProject.value && a.bucket !== 'trash',
    );
  } else {
    list = bucketActions(selectedBucket.value);
  }
  if (selectedContext.value) {
    list = list.filter((a) => a.contexts.includes(selectedContext.value!));
  }
  return list;
});
function bucketCount(bucket: BucketId): number {
  return view.value?.stats.bucketCounts?.[bucket] ?? 0;
}

// ── Load / scan ─────────────────────────────────────────────────────
async function loadScan(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    view.value = await scanGtd(projectId.value, folder.value);
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('gtd.app.error.scan');
    view.value = null;
  } finally {
    loading.value = false;
  }
}

function selectBucket(b: BucketId): void {
  selectedBucket.value = b;
  selectedProject.value = null;
  setTimeout(focusCapture, 0);
}
function selectProject(p: string): void {
  selectedProject.value = p;
  setTimeout(focusCapture, 0);
}
function toggleContext(c: string): void {
  selectedContext.value = selectedContext.value === c ? null : c;
  setTimeout(focusCapture, 0);
}

// ── Action detail ───────────────────────────────────────────────────
async function selectAction(path: string): Promise<void> {
  if (editorRef.value?.flush()) { /* flush previous */ }
  selectedPath.value = path;
  detailLoading.value = true;
  saveStatus.value = 'idle';
  try {
    const c = await getGtdAction(projectId.value, path);
    applyDetail(c);
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('gtd.app.error.loadAction');
    detail.value = null;
  } finally {
    detailLoading.value = false;
  }
}
function applyDetail(c: GtdActionContentView): void {
  detail.value = c;
  selectedPath.value = c.path;
  titleDraft.value = c.title;
  deadlineDraft.value = c.deadline ?? '';
  contextsDraft.value = (c.contexts ?? []).join(', ');
  projectDraft.value = c.project ?? '';
  currentBody.value = c.body ?? '';
}
function parseCtx(raw: string): string[] {
  return raw.split(',').map((t) => t.trim()).filter((t) => t.length > 0);
}

// ── Mutations ───────────────────────────────────────────────────────
async function patchField(fields: Record<string, unknown>): Promise<void> {
  const path = selectedPath.value;
  if (!path) return;
  saveStatus.value = 'saving';
  markSelfWrite(path);
  try {
    const c = await patchGtdAction(projectId.value, path, fields);
    markSelfWrite(c.path);
    applyDetail(c);
    saveStatus.value = 'saved';
    await loadScan();
  } catch (e) {
    saveStatus.value = 'error';
    error.value = e instanceof Error ? e.message : t('gtd.app.error.save');
  }
}

function onTitleChange(): void { void patchField({ title: titleDraft.value.trim() }); }
function onDeadlineChange(): void { void patchField({ deadline: deadlineDraft.value.trim() }); }
function onContextsChange(): void { void patchField({ contexts: parseCtx(contextsDraft.value) }); }
function onBodySave(body: string): void { currentBody.value = body; void patchField({ body }); }
function onBodyDirty(dirty: boolean): void { if (dirty) saveStatus.value = 'saving'; }

/**
 * Tick the box — and nothing else. The action keeps its bucket and its place
 * in the list, struck through; the list is cleared by Rebuild, which sweeps
 * every completed action into Trash. Making the row disappear here is what
 * made completing look like deleting.
 */
async function toggleDone(a: GtdActionView): Promise<void> {
  markSelfWrite(a.path);
  try {
    await patchGtdAction(projectId.value, a.path, { done: !a.done });
    if (selectedPath.value === a.path) {
      const c = await getGtdAction(projectId.value, a.path);
      applyDetail(c);
    }
    await loadScan();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Update failed.';
  } finally {
    // Back to the capture field — ticking a box is the end of a thought, and
    // the next one is usually typed. Also in the failure case: the focus does
    // not belong on a checkbox either way.
    await nextTick();
    focusCapture();
  }
}

/**
 * Move an action to a bucket — sets `when` (relocates for Inbox). Reached from
 * the detail panel's picker and from a drop on a bucket in the sidebar, so it
 * takes a path: the dragged action is usually not the selected one, and the
 * detail panel must only follow along when it actually is.
 */
async function moveActionTo(path: string, bucket: BucketId, datePrefill = ''): Promise<void> {
  let date: string | undefined;
  if (bucket === 'upcoming') {
    const input = window.prompt(t('gtd.app.promptUpcoming'), datePrefill);
    if (!input) return;
    date = input.trim();
  }
  saveStatus.value = 'saving';
  markSelfWrite(path);
  try {
    const c = await moveGtdAction(projectId.value, folder.value, path, { bucket, date });
    markSelfWrite(c.path);
    if (selectedPath.value === path) applyDetail(c);
    saveStatus.value = 'saved';
    await loadScan();
  } catch (e) {
    saveStatus.value = 'error';
    error.value = e instanceof Error ? e.message : t('gtd.app.error.move');
  }
}

/** Detail-panel bucket picker — moves the selected action and follows it. */
async function moveTo(bucket: BucketId): Promise<void> {
  const path = selectedPath.value;
  if (!path) return;
  await moveActionTo(path, bucket, deadlineDraft.value || '');
  // Following the action into its new bucket is the point — except for the two
  // "put it away" targets, where the person is filing something out of the way
  // and does not want the list to follow it there.
  if (bucket !== 'inbox' && bucket !== 'trash') selectBucket(bucket);
}

/**
 * Re-file an action into `projects/<name>/`; `null` files it back out into
 * `actions/`. Relocation only — the bucket is derived from `when` and stays put.
 */
async function refileAction(path: string, project: string | null): Promise<void> {
  saveStatus.value = 'saving';
  markSelfWrite(path);
  try {
    const c = await moveGtdActionToProject(projectId.value, folder.value, path, {
      project: project ?? '',
    });
    markSelfWrite(c.path);
    if (selectedPath.value === path) applyDetail(c);
    saveStatus.value = 'saved';
    await loadScan();
  } catch (e) {
    saveStatus.value = 'error';
    error.value = e instanceof Error ? e.message : t('gtd.app.error.refile');
  }
}

/** Add one context to an action, keeping the ones it already has. */
async function addContext(action: GtdActionView, context: string): Promise<void> {
  if (action.contexts.includes(context)) return;
  saveStatus.value = 'saving';
  markSelfWrite(action.path);
  try {
    const c = await patchGtdAction(projectId.value, action.path, {
      contexts: [...action.contexts, context],
    });
    markSelfWrite(c.path);
    if (selectedPath.value === action.path) applyDetail(c);
    saveStatus.value = 'saved';
    await loadScan();
  } catch (e) {
    saveStatus.value = 'error';
    error.value = e instanceof Error ? e.message : 'Update failed.';
  }
}

/** Project options for the detail select — the scanned ones plus the current one. */
const projectOptions = computed<string[]>(() => {
  const names = new Set((view.value?.projects ?? []).map((p) => p.name));
  if (detail.value?.project) names.add(detail.value.project);
  return [...names].sort();
});

async function onProjectChange(): Promise<void> {
  const path = selectedPath.value;
  if (!path) return;
  const current = detail.value?.project ?? '';
  let target = projectDraft.value;
  if (target === NEW_PROJECT) {
    const name = window.prompt(t('gtd.app.promptNewProject'), '');
    if (name == null || !name.trim()) { projectDraft.value = current; return; }
    target = name.trim();
  }
  if (target === current) return;
  await refileAction(path, target === '' ? null : target);
}

/**
 * Delete key, two meanings — and the one that applies is the one the person
 * can see: outside the bin it files the action into Trash, inside it the
 * action goes for good. Only the second asks, because only the second cannot
 * be undone by dragging the row back out.
 */
const selectedIsInTrash = computed(() => currentBucketOf() === 'trash');

async function removeAction(): Promise<void> {
  const path = selectedPath.value;
  if (!path) return;
  if (selectedIsInTrash.value
      && !window.confirm(t('gtd.app.confirmDelete'))) {
    return;
  }
  try {
    await deleteGtdAction(projectId.value, folder.value, path);
    detail.value = null;
    selectedPath.value = null;
    await loadScan();
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('gtd.app.error.delete');
  }
}

// ── Drag & drop onto the sidebar ─────────────────────────────────────
// The sidebar holds the three things an action can be filed under, and each
// one is a different operation: a bucket sets `when`, a project relocates the
// file, a context adds a tag. Dropping runs exactly the operation the detail
// panel runs — the drag only skips having to select the action first.

type DropTarget =
  | { kind: 'bucket'; id: BucketId }
  | { kind: 'project'; name: string }
  | { kind: 'context'; name: string };

const dragging = ref<GtdActionView | null>(null);
const dropHover = ref<string | null>(null);

function bucketTarget(id: BucketId): DropTarget { return { kind: 'bucket', id }; }
function projectTarget(name: string): DropTarget { return { kind: 'project', name }; }
function contextTarget(name: string): DropTarget { return { kind: 'context', name }; }

function targetKey(t: DropTarget): string {
  return t.kind === 'bucket' ? `bucket:${t.id}` : `${t.kind}:${t.name}`;
}

/** Whether dropping the dragged action here would change anything. */
function acceptsDrop(t: DropTarget): boolean {
  const a = dragging.value;
  if (!a) return false;
  switch (t.kind) {
    case 'bucket': return a.bucket !== t.id;
    case 'project': return (a.project ?? '') !== t.name;
    case 'context': return !a.contexts.includes(t.name);
  }
}

function isDropHover(t: DropTarget): boolean {
  return dropHover.value === targetKey(t);
}

/** An ISO `when` is a sensible default for the Upcoming prompt; `today` is not. */
function datePrefill(a: GtdActionView): string {
  if (a.when && /^\d{4}-\d{2}-\d{2}$/.test(a.when)) return a.when;
  return a.deadline ?? '';
}

function onActionDragStart(a: GtdActionView, ev: DragEvent): void {
  dragging.value = a;
  if (ev.dataTransfer) {
    ev.dataTransfer.effectAllowed = 'move';
    ev.dataTransfer.setData('application/x-vance-gtd-action', a.path);
  }
}

function onActionDragEnd(): void {
  dragging.value = null;
  dropHover.value = null;
  rowDropTarget.value = null;
}

function onTargetDragOver(t: DropTarget, ev: DragEvent): void {
  // No preventDefault on a target that would be a no-op — the browser then
  // shows "not allowed" instead of promising a move that does nothing.
  if (!acceptsDrop(t)) return;
  ev.preventDefault();
  if (ev.dataTransfer) ev.dataTransfer.dropEffect = 'move';
  dropHover.value = targetKey(t);
}

function onTargetDragLeave(t: DropTarget): void {
  if (isDropHover(t)) dropHover.value = null;
}

async function onTargetDrop(t: DropTarget, ev: DragEvent): Promise<void> {
  ev.preventDefault();
  const a = dragging.value;
  const accepted = acceptsDrop(t);
  dragging.value = null;
  dropHover.value = null;
  if (!a || !accepted) return;
  switch (t.kind) {
    case 'bucket': await moveActionTo(a.path, t.id, datePrefill(a)); break;
    case 'project': await refileAction(a.path, t.name); break;
    case 'context': await addContext(a, t.name); break;
  }
  // The action has landed in a new bucket/project/context — the list the
  // user now sees is the updated one, and the capture field is where fast
  // follow-up entry lives. Not done for the reorder drop below (sorting is
  // not capturing).
  void setTimeout(focusCapture, 0);
}

// ── Drag & drop within the action list (reorder, §8b) ────────────────
// Independent of the cross-cutting sidebar drag above: dropping onto a list
// row changes the order, never the bucket.
//
// Reordering is *within one bucket*, and only rows of that bucket are drop
// targets. The middle list is not always one bucket — the project view lists
// every bucket at once — and a drag across that boundary has no meaning: the
// order lives per bucket, so it could not be honoured. Refusing the drop
// (no insertion bar, no write) is the honest answer; accepting it would look
// like it worked and change nothing on screen.
//
// A project or context filter narrows the list, so what we send is regularly
// a *subset* of the bucket. The server splices it into the recorded order
// instead of replacing it, which leaves the hidden Actions where they were —
// see GtdService.resyncBucketOrder.
type RowDropPosition = 'before' | 'after';
const rowDropTarget = ref<{ actionId: string; position: RowDropPosition } | null>(null);

function canDropOnRow(a: GtdActionView): boolean {
  const dragged = dragging.value;
  return !!dragged && dragged.id !== a.id && dragged.bucket === a.bucket;
}

function onRowDragOver(a: GtdActionView, ev: DragEvent): void {
  if (!canDropOnRow(a)) return;
  ev.preventDefault();
  if (ev.dataTransfer) ev.dataTransfer.dropEffect = 'move';
  const rect = (ev.currentTarget as HTMLElement).getBoundingClientRect();
  const half = rect.top + rect.height / 2;
  rowDropTarget.value = {
    actionId: a.id,
    position: ev.clientY < half ? 'before' : 'after',
  };
}

function onRowDragLeave(ev: DragEvent): void {
  // dragleave also fires when the pointer crosses into a child of the row
  // (checkbox, title, badges) — clearing on those makes the bar flicker.
  const row = ev.currentTarget as HTMLElement;
  const to = ev.relatedTarget as Node | null;
  if (to && row.contains(to)) return;
  rowDropTarget.value = null;
}

async function onRowDrop(a: GtdActionView, ev: DragEvent): Promise<void> {
  const target = rowDropTarget.value;
  rowDropTarget.value = null;
  if (!canDropOnRow(a) || !target) return;
  ev.preventDefault();
  await applyRowReorder(dragging.value!, target.actionId, target.position);
}

async function applyRowReorder(
  source: GtdActionView,
  anchorId: string,
  position: RowDropPosition,
): Promise<void> {
  const bucket = source.bucket;
  if (!bucket) return;
  // Only the source bucket's visible rows: in the project view the list holds
  // several buckets, and ids of the others are not this bucket's order.
  const list = displayedActions.value.filter((x) => x.bucket === bucket);
  const sourceIdx = list.findIndex((x) => x.id === source.id);
  if (sourceIdx < 0) return;
  list.splice(sourceIdx, 1);
  let insertAt = list.length; // default: append
  const anchorIdx = list.findIndex((x) => x.id === anchorId);
  if (anchorIdx >= 0) insertAt = position === 'before' ? anchorIdx : anchorIdx + 1;
  list.splice(insertAt, 0, source);

  saveStatus.value = 'saving';
  try {
    // The answer is the whole view — the order it carries is the resynced one,
    // which is not necessarily the order we just sent.
    view.value = await reorderGtdActions(projectId.value, folder.value, {
      bucket,
      orderedIds: list.map((x) => x.id),
    });
    saveStatus.value = 'saved';
  } catch (e) {
    saveStatus.value = 'error';
    error.value = e instanceof Error ? e.message : t('gtd.app.error.reorder');
  }
}

// ── Capture ─────────────────────────────────────────────────────────
const captureText = ref('');
const capturing = ref(false);
// Auto-focus the capture field on mount and after a successful capture, so the
// user can immediately type the next item — the page's purpose is fast entry.
// Skipped while a body edit is in flight (the editor owns focus then).
const captureRef = ref<HTMLFormElement | null>(null);
const captureInputRef = ref<HTMLInputElement | null>(null);
function focusCapture(): void {
  // The WorkPageEditor in the right pane can hold the caret during a body
  // edit — don't snatch focus from it. A button click in the sidebar (bucket /
  // project / context) is not an edit, so it yields focus. Only a real edit
  // element outside the capture form blocks the refocus.
  const active = document.activeElement as HTMLElement | null;
  if (active && active !== document.body && !captureRef.value?.contains(active)) {
    if (holdsACaret(active)) return;
  }
  captureInputRef.value?.focus();
}

/**
 * Whether an element is somewhere text is being written — the only thing this
 * view refuses to take focus away from.
 *
 * <p>A checkbox is an `<input>` and holds no caret: the done-tick in a row is
 * the clearest case, and treating every `<input>` as an edit would leave the
 * focus sitting on the box the person just clicked.
 */
function holdsACaret(el: HTMLElement): boolean {
  if (el.isContentEditable) return true;
  const tag = el.tagName.toLowerCase();
  if (tag === 'textarea' || tag === 'select') return true;
  if (tag !== 'input') return false;
  const type = (el as HTMLInputElement).type;
  return type !== 'checkbox' && type !== 'radio' && type !== 'button'
    && type !== 'submit' && type !== 'reset';
}

async function submitCapture(): Promise<void> {
  const text = captureText.value.trim();
  if (!text) return;
  capturing.value = true;
  try {
    await captureGtd(projectId.value, folder.value, { title: text });
    captureText.value = '';
    await loadScan();
    selectBucket('inbox');
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('gtd.app.error.capture');
  } finally {
    capturing.value = false;
    await nextTick();
    focusCapture();
  }
}

// ── Search ──────────────────────────────────────────────────────────
const searchQuery = ref('');
const searchResults = ref<GtdHitView[]>([]);
const searchOpen = ref(false);
const searching = ref(false);
async function runSearch(): Promise<void> {
  const q = searchQuery.value.trim();
  if (!q) { searchResults.value = []; searchOpen.value = false; return; }
  searching.value = true;
  try {
    const resp = await searchGtd(projectId.value, folder.value, q);
    searchResults.value = resp.items ?? [];
    searchOpen.value = true;
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('gtd.app.error.search');
  } finally {
    searching.value = false;
  }
}
function pickSearchResult(hit: GtdHitView): void {
  searchOpen.value = false;
  searchQuery.value = '';
  void selectAction(hit.path);
}

// ── Rebuild ─────────────────────────────────────────────────────────
async function rebuild(): Promise<void> {
  rebuilding.value = true;
  try {
    await rebuildGtd(projectId.value, folder.value);
    await loadScan();
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('gtd.app.error.rebuild');
  } finally {
    rebuilding.value = false;
  }
}

// ── Image + vance: resolution + compose (from journal / workbook) ────
async function uploadImage(file: File): Promise<string | null> {
  const path = `${folder.value}/assets/${Date.now()}-${file.name.toLowerCase().replace(/[^a-z0-9._-]+/g, '_')}`;
  const form = new FormData();
  form.append('file', file);
  form.append('projectId', projectId.value);
  form.append('path', path);
  if (file.type) form.append('mimeType', file.type);
  try {
    await brainFetch<{ id: string }>('POST', 'documents/upload', { body: form });
    return `vance:/${encodeURI(path)}?kind=image`;
  } catch {
    return null;
  }
}
async function resolveVanceImageSrc(uri: string): Promise<string | null> {
  let parsed: URL;
  try { parsed = new URL(uri); } catch { return null; }
  if (parsed.protocol !== 'vance:') return null;
  const target = parsed.hostname ? decodeURIComponent(parsed.hostname) : projectId.value;
  const path = decodeURIComponent(parsed.pathname.replace(/^\//, ''));
  if (!target || !path) return null;
  try {
    const dto = await brainFetch<{ id: string }>(
      'GET',
      `documents/by-path?projectId=${encodeURIComponent(target)}&path=${encodeURIComponent(path)}`,
    );
    return documentContentUrl(dto.id);
  } catch {
    return null;
  }
}
function runCompose(yaml: string): Promise<ComposeRunResult> {
  return postComposeRun(projectId.value, {
    composeYaml: yaml, composeBasePath: folder.value, sessionId: sessionId.value, appKey: folder.value,
  });
}
function pollCompose(runId: string): Promise<ComposeRunResult> { return pollComposeRun(projectId.value, runId); }
function cancelCompose(runId: string): Promise<ComposeRunResult> { return cancelComposeRun(projectId.value, runId); }

// ── Live watch ──────────────────────────────────────────────────────
useDocumentPrefixReaction({
  prefix: computed(() => `${folder.value}/`),
  debounceMs: 250,
  onRemoteChange: async (paths) => {
    const path = selectedPath.value;
    if (path != null && withinSelfWrite(path)) return;
    await loadScan();
    if (path && paths.includes(path)) {
      try { applyDetail(await getGtdAction(projectId.value, path)); } catch { /* gone */ }
    }
  },
});

watch(folder, () => {
  selectedPath.value = null;
  detail.value = null;
  void loadScan();
});

onMounted(async () => {
  await loadScan();
  await nextTick();
  focusCapture();
});
onBeforeUnmount(() => { editorRef.value?.flush(); });

const saveStatusLabel = computed<string | null>(() => {
  switch (saveStatus.value) {
    case 'saving': return t('gtd.common.saving');
    case 'saved': return t('gtd.common.saved');
    case 'error': return t('gtd.common.saveFailed');
    default: return null;
  }
});
const editorKey = computed(() => selectedPath.value ?? 'none');
const currentWhen = computed(() => detail.value?.when ?? '');

/** The selected action's current derived bucket, read from the scan grouping. */
function currentBucketOf(): BucketId | null {
  const p = selectedPath.value;
  if (!p || !view.value) return null;
  for (const b of view.value.buckets) {
    if (b.actions.some((a) => a.path === p)) return b.bucket as BucketId;
  }
  return null;
}
function isCurrentBucket(b: BucketId): boolean {
  return currentBucketOf() === b;
}
</script>

<template>
  <div class="gtd">
    <header class="gtd__topbar">
      <div class="gtd__brand" :title="folder">{{ title }}</div>
      <div class="gtd__search">
        <input
          v-model="searchQuery"
          type="search"
          class="gtd__input"
          :placeholder="t('gtd.app.searchPlaceholder')"
          @keydown.enter.prevent="runSearch"
          @keydown.escape="searchOpen = false"
        />
        <button class="gtd__btn" :disabled="searching" @click="runSearch">🔍</button>
        <div v-if="searchOpen" class="gtd__search-results">
          <ul v-if="searchResults.length" class="gtd__search-list">
            <li v-for="r in searchResults" :key="r.id" class="gtd__search-row" @click="pickSearchResult(r)">
              <span class="gtd__search-title">{{ r.title || t('gtd.app.untitled') }}</span>
              <span class="gtd__search-snippet">{{ r.snippet }}</span>
            </li>
          </ul>
          <div v-else class="gtd__search-empty">{{ t('gtd.app.noMatch') }}</div>
        </div>
      </div>
      <span class="gtd__spacer" />
      <span v-if="saveStatusLabel" class="gtd__save" :class="`gtd__save--${saveStatus}`">{{ saveStatusLabel }}</span>
      <button
        class="gtd__btn"
        :disabled="rebuilding"
        :title="t('gtd.app.rebuildTip')"
        @click="rebuild"
      >
        {{ rebuilding ? '…' : '↻' }}
      </button>
    </header>

    <div v-if="error" class="gtd__error">{{ error }}</div>

    <div class="gtd__body">
      <!-- Left: buckets + projects + contexts -->
      <aside class="gtd__nav">
        <div class="gtd__nav-group">
          <button
            v-for="b in BUCKETS"
            :key="b"
            class="gtd__nav-item"
            :class="{
              'gtd__nav-item--active': !selectedProject && selectedBucket === b,
              'gtd__nav-item--drop': isDropHover(bucketTarget(b)),
            }"
            @click="selectBucket(b)"
            @mousedown.prevent
            @dragover="onTargetDragOver(bucketTarget(b), $event)"
            @dragleave="onTargetDragLeave(bucketTarget(b))"
            @drop="onTargetDrop(bucketTarget(b), $event)"
          >
            <span>{{ t(`gtd.bucket.${b}`) }}</span>
            <span class="gtd__badge">{{ bucketCount(b) }}</span>
          </button>
        </div>

        <div v-if="view && view.projects.length" class="gtd__nav-group">
          <div class="gtd__nav-head">{{ t('gtd.app.projects') }}</div>
          <button
            v-for="p in view.projects"
            :key="p.name"
            class="gtd__nav-item"
            :class="{
              'gtd__nav-item--active': selectedProject === p.name,
              'gtd__nav-item--drop': isDropHover(projectTarget(p.name)),
            }"
            @click="selectProject(p.name)"
            @mousedown.prevent
            @dragover="onTargetDragOver(projectTarget(p.name), $event)"
            @dragleave="onTargetDragLeave(projectTarget(p.name))"
            @drop="onTargetDrop(projectTarget(p.name), $event)"
          >
            <span>{{ p.name }}</span>
            <span class="gtd__badge">{{ p.openCount }}</span>
          </button>
        </div>

        <div v-if="view && view.contexts.length" class="gtd__nav-group">
          <div class="gtd__nav-head">{{ t('gtd.app.contexts') }}</div>
          <div class="gtd__chips">
            <button
              v-for="c in view.contexts"
              :key="c"
              class="gtd__chip"
              :class="{
                'gtd__chip--active': selectedContext === c,
                'gtd__chip--drop': isDropHover(contextTarget(c)),
              }"
              @click="toggleContext(c)"
              @mousedown.prevent
              @dragover="onTargetDragOver(contextTarget(c), $event)"
              @dragleave="onTargetDragLeave(contextTarget(c))"
              @drop="onTargetDrop(contextTarget(c), $event)"
            >{{ c }}</button>
          </div>
        </div>
      </aside>

      <!-- Middle: action list -->
      <main class="gtd__list">
        <form ref="captureRef" class="gtd__capture" @submit.prevent="submitCapture">
          <input
            ref="captureInputRef"
            v-model="captureText"
            type="text"
            class="gtd__input gtd__capture-input"
            :placeholder="t('gtd.app.capturePlaceholder')"
            :disabled="capturing"
          />
        </form>
        <div v-if="loading" class="gtd__hint">{{ t('gtd.common.loading') }}</div>
        <ul v-else-if="displayedActions.length" class="gtd__actions">
          <li
            v-for="a in displayedActions"
            :key="a.id"
            class="gtd__action"
            :class="{
              'gtd__action--sel': a.path === selectedPath,
              'gtd__action--done': a.done,
              'gtd__action--overdue': a.overdue && !a.done,
              'gtd__action--dragging': dragging?.path === a.path,
              'gtd__action--drop-before': rowDropTarget && rowDropTarget.actionId === a.id && rowDropTarget.position === 'before',
              'gtd__action--drop-after': rowDropTarget && rowDropTarget.actionId === a.id && rowDropTarget.position === 'after',
            }"
            draggable="true"
            @click="selectAction(a.path)"
            @dragstart="onActionDragStart(a, $event)"
            @dragend="onActionDragEnd"
            @dragover="onRowDragOver(a, $event)"
            @dragleave="onRowDragLeave($event)"
            @drop="onRowDrop(a, $event)"
          >
            <input
              type="checkbox"
              class="gtd__check"
              :checked="a.done"
              @click.stop="toggleDone(a)"
            />
            <span class="gtd__action-title">{{ a.title }}</span>
            <span v-if="a.when && a.when !== 'today' && a.when !== 'someday'" class="gtd__when">{{ a.when }}</span>
            <span v-if="a.deadline" class="gtd__deadline">⏱ {{ a.deadline }}</span>
            <span v-for="c in a.contexts" :key="c" class="gtd__ctx">{{ c }}</span>
          </li>
        </ul>
        <div v-else class="gtd__hint">{{ t('gtd.app.nothingHere') }}</div>
      </main>

      <!-- Right: detail -->
      <aside v-if="detail" class="gtd__detail">
        <div v-if="detailLoading" class="gtd__hint">{{ t('gtd.common.loading') }}</div>
        <template v-else>
          <input v-model="titleDraft" class="gtd__detail-title" @change="onTitleChange" />

          <div class="gtd__field">
            <label class="gtd__label">{{ t('gtd.app.bucketLabel') }}</label>
            <div class="gtd__when-picker">
              <button
                v-for="b in BUCKETS"
                :key="b"
                class="gtd__when-btn"
                :class="{ 'gtd__when-btn--active': isCurrentBucket(b) }"
                @click="moveTo(b)"
              >{{ t(`gtd.bucket.${b}`) }}</button>
            </div>
            <div class="gtd__when-hint">
              {{ t('gtd.app.whenHint') }}
              <code>{{ currentWhen || t('gtd.app.anytimeValue') }}</code>
            </div>
          </div>

          <div class="gtd__field">
            <label class="gtd__label">{{ t('gtd.app.project') }}</label>
            <select v-model="projectDraft" class="gtd__input" @change="onProjectChange">
              <option value="">{{ t('gtd.app.noProject') }}</option>
              <option v-for="p in projectOptions" :key="p" :value="p">{{ p }}</option>
              <option :value="NEW_PROJECT">{{ t('gtd.app.newProject') }}</option>
            </select>
          </div>

          <div class="gtd__field">
            <label class="gtd__label">{{ t('gtd.app.deadline') }}</label>
            <input v-model="deadlineDraft" type="date" class="gtd__input" @change="onDeadlineChange" />
          </div>

          <div class="gtd__field">
            <label class="gtd__label">{{ t('gtd.app.contexts') }}</label>
            <input v-model="contextsDraft" class="gtd__input" :placeholder="t('gtd.app.contextsPlaceholder')" @change="onContextsChange" />
          </div>

          <div class="gtd__field gtd__field--row">
            <label class="gtd__label">
              <input type="checkbox" :checked="detail.done" @change="patchField({ done: !detail.done })" />
              {{ t('gtd.app.done') }}
            </label>
            <button class="gtd__btn gtd__btn--danger" @click="removeAction">
              🗑 {{ selectedIsInTrash ? t('gtd.app.deleteForGood') : t('gtd.app.moveToTrash') }}
            </button>
          </div>

          <div class="gtd__field gtd__field--grow">
            <label class="gtd__label">{{ t('gtd.app.note') }}</label>
            <WorkPageEditor
              :key="editorKey"
              ref="editorRef"
              :document="{ id: detail.id, path: detail.path, projectId, title: detail.title }"
              :source="currentBody"
              :auto-save-ms="1500"
              body-only
              :current-project-id="projectId"
              :upload-image="uploadImage"
              :resolve-image-src="resolveVanceImageSrc"
              :open-link-picker="openLinkPicker"
              :suppress-floating="linkPickerOpen"
              :embed-component="embedComponent ?? undefined"
              :form-component="formComponent ?? undefined"
              :compose-output-component="composeOutputComponent ?? undefined"
              :run-compose="runCompose"
              :poll-compose="pollCompose"
              :cancel-compose="cancelCompose"
              @save="onBodySave"
              @dirty="onBodyDirty"
            />
            <VLinkPicker
              v-if="linkPickerOpen"
              :project-id="projectId"
              :initial-href="linkPickerInitialHref"
              @pick="onLinkPicked"
              @clear="onLinkClear"
              @close="closeLinkPicker"
            />
          </div>
        </template>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.gtd { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.gtd__topbar {
  display: flex; align-items: center; gap: 0.5rem; padding: 0.4rem 0.75rem;
  border-bottom: 1px solid color-mix(in oklab, var(--color-base-content) 15%, transparent); background: var(--color-base-100);
}
.gtd__brand { font-weight: 700; font-size: 0.95rem; max-width: 14rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.gtd__search { position: relative; display: flex; align-items: center; gap: 0.25rem; }
.gtd__input {
  border: 1px solid color-mix(in oklab, var(--color-base-content) 20%, transparent); border-radius: 6px; background: transparent;
  padding: 0.25rem 0.5rem; font-size: 0.82rem;
}
.gtd__search .gtd__input { width: 12rem; }
.gtd__search-results {
  position: absolute; top: 100%; left: 0; margin-top: 0.25rem; min-width: 22rem; max-height: 22rem;
  overflow-y: auto; padding: 0.25rem; background: var(--color-base-100); border: 1px solid color-mix(in oklab, var(--color-base-content) 20%, transparent);
  border-radius: 8px; box-shadow: 0 6px 20px rgba(0, 0, 0, 0.18); z-index: 50;
}
.gtd__search-list { list-style: none; margin: 0; padding: 0; }
.gtd__search-row { display: flex; flex-direction: column; padding: 0.35rem 0.5rem; border-radius: 6px; cursor: pointer; }
.gtd__search-row:hover { background: color-mix(in oklab, var(--color-base-content) 8%, transparent); }
.gtd__search-title { font-size: 0.85rem; font-weight: 600; }
.gtd__search-snippet { font-size: 0.72rem; opacity: 0.6; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.gtd__search-empty { padding: 0.6rem; font-size: 0.8rem; opacity: 0.6; }
.gtd__spacer { flex: 1; }
.gtd__save { font-size: 0.72rem; opacity: 0.7; }
.gtd__save--error { color: #d33; opacity: 1; }
.gtd__save--saved { color: #2a8; }
.gtd__btn {
  border: 1px solid color-mix(in oklab, var(--color-base-content) 20%, transparent); border-radius: 6px; background: transparent;
  padding: 0.2rem 0.55rem; font-size: 0.8rem; cursor: pointer; white-space: nowrap;
}
.gtd__btn:hover:not(:disabled) { background: color-mix(in oklab, var(--color-base-content) 8%, transparent); }
.gtd__btn:disabled { opacity: 0.5; cursor: default; }
.gtd__btn--danger:hover { background: rgba(221, 51, 51, 0.12); }
.gtd__error { padding: 0.5rem 0.75rem; color: #d33; font-size: 0.82rem; }
.gtd__hint { padding: 2rem; text-align: center; opacity: 0.6; font-size: 0.85rem; }
.gtd__body { flex: 1; display: flex; min-height: 0; }
.gtd__nav {
  width: 220px; flex-shrink: 0; padding: 0.5rem; overflow-y: auto;
  border-right: 1px solid color-mix(in oklab, var(--color-base-content) 15%, transparent);
}
.gtd__nav-group { margin-bottom: 1rem; }
.gtd__nav-head { font-size: 0.68rem; text-transform: uppercase; opacity: 0.5; padding: 0.25rem 0.5rem; }
.gtd__nav-item {
  display: flex; align-items: center; justify-content: space-between; width: 100%;
  border: none; background: transparent; border-radius: 6px; padding: 0.35rem 0.5rem;
  font-size: 0.85rem; cursor: pointer; text-align: left;
}
.gtd__nav-item:hover { background: color-mix(in oklab, var(--color-base-content) 8%, transparent); }
.gtd__nav-item--active { background: color-mix(in oklab, var(--color-primary) 16%, transparent); font-weight: 600; }
.gtd__nav-item--drop, .gtd__chip--drop {
  outline: 2px dashed color-mix(in oklab, var(--color-primary) 65%, transparent);
  outline-offset: -2px;
  background: color-mix(in oklab, var(--color-primary) 10%, transparent);
}
.gtd__badge { font-size: 0.68rem; opacity: 0.6; }
.gtd__chips { display: flex; flex-wrap: wrap; gap: 0.25rem; padding: 0 0.4rem; }
.gtd__chip {
  border: 1px solid color-mix(in oklab, var(--color-base-content) 20%, transparent); border-radius: 999px; background: transparent;
  padding: 0.1rem 0.5rem; font-size: 0.72rem; cursor: pointer;
}
.gtd__chip--active { background: color-mix(in oklab, var(--color-primary) 18%, transparent); }
.gtd__list { flex: 1; min-width: 0; display: flex; flex-direction: column; overflow-y: auto; }
.gtd__capture { padding: 0.5rem 0.75rem; border-bottom: 1px solid color-mix(in oklab, var(--color-base-content) 10%, transparent); }
.gtd__capture-input { width: 100%; }
.gtd__actions { list-style: none; margin: 0; padding: 0.25rem; }
.gtd__action {
  display: flex; align-items: center; gap: 0.5rem; padding: 0.4rem 0.5rem; border-radius: 6px; cursor: pointer;
  position: relative;
}
.gtd__action:hover { background: color-mix(in oklab, var(--color-base-content) 6%, transparent); }
.gtd__action--sel { background: color-mix(in oklab, var(--color-primary) 12%, transparent); }
.gtd__action--dragging { opacity: 0.45; }
.gtd__action--overdue .gtd__action-title { color: #d33; }
/* Completed stays on the list until Rebuild sweeps it into Trash — visibly
   handled, not visibly gone. */
.gtd__action--done .gtd__action-title { text-decoration: line-through; opacity: 0.5; }
/* Drop-insertion indicator for intra-list reorder (§8b) — same shape as the
   workbook page-row indicator: a 2px primary bar above/below the row. */
.gtd__action--drop-before::before,
.gtd__action--drop-after::after {
  content: '';
  position: absolute;
  left: 0; right: 0;
  height: 2px;
  background: var(--color-primary);
  pointer-events: none;
}
.gtd__action--drop-before::before { top: -1px; }
.gtd__action--drop-after::after { bottom: -1px; }
.gtd__action-title { flex: 1; font-size: 0.88rem; }
.gtd__when, .gtd__deadline, .gtd__ctx {
  font-size: 0.7rem; opacity: 0.7; padding: 0.05rem 0.35rem; border-radius: 4px; background: color-mix(in oklab, var(--color-base-content) 8%, transparent);
}
.gtd__detail {
  width: 340px; flex-shrink: 0; border-left: 1px solid color-mix(in oklab, var(--color-base-content) 15%, transparent);
  padding: 0.75rem; overflow-y: auto; background: var(--color-base-100); display: flex; flex-direction: column;
}
.gtd__detail-title { font-size: 1.1rem; font-weight: 700; border: none; background: transparent; width: 100%; margin-bottom: 0.5rem; }
.gtd__field { margin-bottom: 0.75rem; display: flex; flex-direction: column; gap: 0.3rem; }
.gtd__field--row { flex-direction: row; align-items: center; justify-content: space-between; }
.gtd__field--grow { flex: 1; min-height: 12rem; }
.gtd__label { font-size: 0.7rem; text-transform: uppercase; opacity: 0.55; }
.gtd__when-picker { display: flex; flex-wrap: wrap; gap: 0.25rem; }
.gtd__when-btn {
  border: 1px solid color-mix(in oklab, var(--color-base-content) 20%, transparent); border-radius: 6px; background: transparent;
  padding: 0.15rem 0.5rem; font-size: 0.75rem; cursor: pointer;
}
.gtd__when-btn--active { background: color-mix(in oklab, var(--color-primary) 18%, transparent); font-weight: 600; }
.gtd__when-hint { font-size: 0.7rem; opacity: 0.6; }
</style>
