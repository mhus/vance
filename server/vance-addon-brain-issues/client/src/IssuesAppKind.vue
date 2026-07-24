<script setup lang="ts">
import {
  computed,
  inject,
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
  useDocumentPrefixReaction,
} from '@vance/shared';
import { WorkPageEditor, type ComposeRunResult } from '@vance/block-editor';
import {
  scanIssues,
  getIssue,
  createIssue,
  patchIssue,
  archiveIssue,
  unarchiveIssue,
  addComment,
  deleteComment,
  deleteIssue,
  searchIssues,
  rebuildIssues,
} from './api';
import type { IssuesView } from './generated/issues/IssuesView';
import type { IssueView } from './generated/issues/IssueView';
import type { IssueContentView } from './generated/issues/IssueContentView';
import type { IssueHitView } from './generated/issues/IssueHitView';

/**
 * Issues application view (GitHub-Issues-style). Open / Closed / Archived tabs
 * + label filter + list; right-hand detail with state toggle, archive, labels,
 * assignee, body editor and a comment thread (document notes). See
 * planning/app-issues.md.
 */
const props = defineProps<{
  document: { id: string; path: string; projectId: string; title?: string | null };
}>();

const projectId = computed(() => props.document.projectId);
const folder = computed(() => props.document.path.replace(/\/_app\.yaml$/, ''));
const title = computed(() => view.value?.title ?? props.document.title ?? folder.value);

const view = ref<IssuesView | null>(null);
const error = ref<string | null>(null);
const loading = ref(false);
const rebuilding = ref(false);

type Tab = 'open' | 'closed' | 'archived';
const tab = ref<Tab>('open');
const labelFilter = ref<string | null>(null);

const selectedPath = ref<string | null>(null);
const detail = ref<IssueContentView | null>(null);
const detailLoading = ref(false);

const titleDraft = ref('');
const labelsDraft = ref('');
const assigneeDraft = ref('');
const priorityDraft = ref('');
const currentBody = ref('');
const commentDraft = ref('');

type SaveStatus = 'idle' | 'saving' | 'saved' | 'error';
const saveStatus = ref<SaveStatus>('idle');

const editorRef = ref<{ save: () => void; flush: () => boolean } | null>(null);

const embedComponent = inject<Component | null>('vance:embed-component', null);
const formComponent = inject<Component | null>('vance:form-component', null);
const composeOutputComponent = inject<Component | null>('vance:compose-output-component', null);
const sessionId = inject<Ref<string | null>>('vance:session-id', ref(null));

const SELF_WRITE_QUIET_MS = 3000;
const lastSelfWriteAt = ref<Map<string, number>>(new Map());
function withinSelfWrite(path: string): boolean {
  const t = lastSelfWriteAt.value.get(path);
  return t != null && Date.now() - t < SELF_WRITE_QUIET_MS;
}
function markSelfWrite(path: string): void {
  lastSelfWriteAt.value.set(path, Date.now());
}

const displayedIssues = computed<IssueView[]>(() => {
  const list = view.value?.issues ?? [];
  return labelFilter.value ? list.filter((i) => i.labels.includes(labelFilter.value!)) : list;
});
function bucketCount(t: Tab): number {
  const s = view.value?.stats;
  if (!s) return 0;
  if (t === 'open') return s.open;
  if (t === 'closed') return s.closed;
  return 0; // archived count not tracked in active stats
}

// ── Load / scan ─────────────────────────────────────────────────────
async function loadScan(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    view.value = tab.value === 'archived'
      ? await scanIssues(projectId.value, folder.value, undefined, true)
      : await scanIssues(projectId.value, folder.value, tab.value, false);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not scan issues.';
    view.value = null;
  } finally {
    loading.value = false;
  }
}
function selectTab(t: Tab): void {
  tab.value = t;
  void loadScan();
}

// ── Detail ──────────────────────────────────────────────────────────
async function selectIssue(path: string): Promise<void> {
  if (editorRef.value?.flush()) { /* flush previous */ }
  selectedPath.value = path;
  detailLoading.value = true;
  saveStatus.value = 'idle';
  try {
    applyDetail(await getIssue(projectId.value, path));
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load issue.';
    detail.value = null;
  } finally {
    detailLoading.value = false;
  }
}
function applyDetail(c: IssueContentView): void {
  detail.value = c;
  selectedPath.value = c.path;
  titleDraft.value = c.title;
  labelsDraft.value = (c.labels ?? []).join(', ');
  assigneeDraft.value = c.assignee ?? '';
  priorityDraft.value = c.priority ?? '';
  currentBody.value = c.body ?? '';
}
function parseList(raw: string): string[] {
  return raw.split(',').map((t) => t.trim()).filter((t) => t.length > 0);
}

async function patchField(fields: Record<string, unknown>): Promise<void> {
  const path = selectedPath.value;
  if (!path) return;
  saveStatus.value = 'saving';
  markSelfWrite(path);
  try {
    const c = await patchIssue(projectId.value, path, fields);
    markSelfWrite(c.path);
    applyDetail(c);
    saveStatus.value = 'saved';
    await loadScan();
  } catch (e) {
    saveStatus.value = 'error';
    error.value = e instanceof Error ? e.message : 'Save failed.';
  }
}

function onTitleChange(): void { void patchField({ title: titleDraft.value.trim() }); }
function onLabelsChange(): void { void patchField({ labels: parseList(labelsDraft.value) }); }
function onAssigneeChange(): void { void patchField({ assignee: assigneeDraft.value.trim() }); }
function onPriorityChange(): void { void patchField({ priority: priorityDraft.value.trim() }); }
function onBodySave(body: string): void { currentBody.value = body; void patchField({ body }); }
function onBodyDirty(dirty: boolean): void { if (dirty) saveStatus.value = 'saving'; }

async function toggleState(): Promise<void> {
  if (!detail.value) return;
  const next = detail.value.state === 'open' ? 'closed' : 'open';
  await patchField({ state: next });
}

async function toggleArchive(): Promise<void> {
  const path = selectedPath.value;
  if (!path || !detail.value) return;
  saveStatus.value = 'saving';
  markSelfWrite(path);
  try {
    const c = detail.value.archived
      ? await unarchiveIssue(projectId.value, folder.value, path)
      : await archiveIssue(projectId.value, folder.value, path);
    markSelfWrite(c.path);
    applyDetail(c);
    saveStatus.value = 'saved';
    await loadScan();
  } catch (e) {
    saveStatus.value = 'error';
    error.value = e instanceof Error ? e.message : 'Archive failed.';
  }
}

async function removeIssue(): Promise<void> {
  const path = selectedPath.value;
  if (!path) return;
  if (!window.confirm('Delete this issue?')) return;
  try {
    await deleteIssue(projectId.value, path);
    detail.value = null;
    selectedPath.value = null;
    await loadScan();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Delete failed.';
  }
}

// ── Comments ────────────────────────────────────────────────────────
async function submitComment(): Promise<void> {
  const path = selectedPath.value;
  const text = commentDraft.value.trim();
  if (!path || !text) return;
  markSelfWrite(path);
  try {
    applyDetail(await addComment(projectId.value, path, { text }));
    commentDraft.value = '';
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Comment failed.';
  }
}
async function removeComment(commentId: string): Promise<void> {
  const path = selectedPath.value;
  if (!path) return;
  markSelfWrite(path);
  try {
    applyDetail(await deleteComment(projectId.value, path, commentId));
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Delete comment failed.';
  }
}

// ── New issue ───────────────────────────────────────────────────────
const newTitle = ref('');
const creating = ref(false);
async function submitNew(): Promise<void> {
  const t = newTitle.value.trim();
  if (!t) return;
  creating.value = true;
  try {
    const c = await createIssue(projectId.value, folder.value, { title: t });
    newTitle.value = '';
    tab.value = 'open';
    await loadScan();
    applyDetail(c);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Create failed.';
  } finally {
    creating.value = false;
  }
}

// ── Search ──────────────────────────────────────────────────────────
const searchQuery = ref('');
const searchResults = ref<IssueHitView[]>([]);
const searchOpen = ref(false);
const searching = ref(false);
async function runSearch(): Promise<void> {
  const q = searchQuery.value.trim();
  if (!q) { searchResults.value = []; searchOpen.value = false; return; }
  searching.value = true;
  try {
    const resp = await searchIssues(projectId.value, folder.value, q);
    searchResults.value = resp.items ?? [];
    searchOpen.value = true;
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Search failed.';
  } finally {
    searching.value = false;
  }
}
function pickSearchResult(hit: IssueHitView): void {
  searchOpen.value = false;
  searchQuery.value = '';
  void selectIssue(hit.path);
}

// ── Rebuild ─────────────────────────────────────────────────────────
async function rebuild(): Promise<void> {
  rebuilding.value = true;
  try {
    await rebuildIssues(projectId.value, folder.value);
    await loadScan();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Rebuild failed.';
  } finally {
    rebuilding.value = false;
  }
}

// ── Image + compose (shared helpers) ────────────────────────────────
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
  const p = decodeURIComponent(parsed.pathname.replace(/^\//, ''));
  if (!target || !p) return null;
  try {
    const dto = await brainFetch<{ id: string }>(
      'GET', `documents/by-path?projectId=${encodeURIComponent(target)}&path=${encodeURIComponent(p)}`);
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
      try { applyDetail(await getIssue(projectId.value, path)); } catch { /* gone */ }
    }
  },
});

watch(folder, () => {
  selectedPath.value = null;
  detail.value = null;
  void loadScan();
});
onMounted(() => { void loadScan(); });
onBeforeUnmount(() => { editorRef.value?.flush(); });

const saveStatusLabel = computed<string | null>(() => {
  switch (saveStatus.value) {
    case 'saving': return 'Saving…';
    case 'saved': return 'Saved';
    case 'error': return 'Save failed';
    default: return null;
  }
});
const editorKey = computed(() => selectedPath.value ?? 'none');

function commentDate(c: { createdAt?: string | null }): string {
  if (!c.createdAt) return '';
  const d = new Date(c.createdAt);
  return isNaN(d.getTime()) ? '' : d.toLocaleString();
}
</script>

<template>
  <div class="iss">
    <header class="iss__topbar">
      <div class="iss__brand" :title="folder">{{ title }}</div>
      <div class="iss__tabs">
        <button class="iss__tab" :class="{ 'iss__tab--active': tab === 'open' }" @click="selectTab('open')">
          Open <span class="iss__badge">{{ bucketCount('open') }}</span>
        </button>
        <button class="iss__tab" :class="{ 'iss__tab--active': tab === 'closed' }" @click="selectTab('closed')">
          Closed <span class="iss__badge">{{ bucketCount('closed') }}</span>
        </button>
        <button class="iss__tab" :class="{ 'iss__tab--active': tab === 'archived' }" @click="selectTab('archived')">
          Archived
        </button>
      </div>
      <div class="iss__search">
        <input
          v-model="searchQuery"
          type="search"
          class="iss__input"
          placeholder="Search issues…"
          @keydown.enter.prevent="runSearch"
          @keydown.escape="searchOpen = false"
        />
        <button class="iss__btn" :disabled="searching" @click="runSearch">🔍</button>
        <div v-if="searchOpen" class="iss__search-results">
          <ul v-if="searchResults.length" class="iss__search-list">
            <li v-for="r in searchResults" :key="r.id" class="iss__search-row" @click="pickSearchResult(r)">
              <span class="iss__search-title">{{ r.title || '(untitled)' }}</span>
              <span class="iss__search-snippet">{{ r.snippet }}</span>
            </li>
          </ul>
          <div v-else class="iss__search-empty">No matching issue.</div>
        </div>
      </div>
      <span class="iss__spacer" />
      <span v-if="saveStatusLabel" class="iss__save" :class="`iss__save--${saveStatus}`">{{ saveStatusLabel }}</span>
      <button class="iss__btn" :disabled="rebuilding" title="Rebuild index + stats" @click="rebuild">
        {{ rebuilding ? '…' : '↻' }}
      </button>
    </header>

    <div v-if="error" class="iss__error">{{ error }}</div>

    <div class="iss__body">
      <!-- List -->
      <main class="iss__list">
        <form class="iss__new" @submit.prevent="submitNew">
          <input
            v-model="newTitle"
            type="text"
            class="iss__input iss__new-input"
            placeholder="＋ New issue title…"
            :disabled="creating"
          />
        </form>
        <div v-if="view && view.labels.length" class="iss__labelfilter">
          <button
            class="iss__chip"
            :class="{ 'iss__chip--active': labelFilter === null }"
            @click="labelFilter = null"
          >all</button>
          <button
            v-for="l in view.labels"
            :key="l"
            class="iss__chip"
            :class="{ 'iss__chip--active': labelFilter === l }"
            @click="labelFilter = labelFilter === l ? null : l"
          >{{ l }}</button>
        </div>
        <div v-if="loading" class="iss__hint">Loading…</div>
        <ul v-else-if="displayedIssues.length" class="iss__items">
          <li
            v-for="i in displayedIssues"
            :key="i.id"
            class="iss__item"
            :class="{ 'iss__item--sel': i.path === selectedPath }"
            @click="selectIssue(i.path)"
          >
            <span class="iss__dot" :class="i.state === 'open' ? 'iss__dot--open' : 'iss__dot--closed'" />
            <span class="iss__num">#{{ i.number }}</span>
            <span class="iss__item-title">{{ i.title }}</span>
            <span v-if="i.assignee" class="iss__assignee">@{{ i.assignee }}</span>
            <span v-for="l in i.labels" :key="l" class="iss__lab">{{ l }}</span>
          </li>
        </ul>
        <div v-else class="iss__hint">Nothing here.</div>
      </main>

      <!-- Detail -->
      <aside v-if="detail" class="iss__detail">
        <div v-if="detailLoading" class="iss__hint">Loading…</div>
        <template v-else>
          <div class="iss__detail-head">
            <span class="iss__num">#{{ detail.number }}</span>
            <span class="iss__state" :class="`iss__state--${detail.state}`">{{ detail.state }}</span>
            <span v-if="detail.archived" class="iss__archived">archived</span>
          </div>
          <input v-model="titleDraft" class="iss__detail-title" @change="onTitleChange" />

          <div class="iss__actions">
            <button class="iss__btn" @click="toggleState">
              {{ detail.state === 'open' ? 'Close' : 'Reopen' }}
            </button>
            <button class="iss__btn" @click="toggleArchive">
              {{ detail.archived ? 'Unarchive' : 'Archive' }}
            </button>
            <button class="iss__btn iss__btn--danger" @click="removeIssue">🗑</button>
          </div>

          <div class="iss__field">
            <label class="iss__label">Labels</label>
            <input v-model="labelsDraft" class="iss__input" placeholder="bug, auth" @change="onLabelsChange" />
          </div>
          <div class="iss__field iss__field--row">
            <div class="iss__field">
              <label class="iss__label">Assignee</label>
              <input v-model="assigneeDraft" class="iss__input" @change="onAssigneeChange" />
            </div>
            <div class="iss__field">
              <label class="iss__label">Priority</label>
              <input v-model="priorityDraft" class="iss__input" @change="onPriorityChange" />
            </div>
          </div>

          <div class="iss__field">
            <label class="iss__label">Description</label>
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
              :embed-component="embedComponent ?? undefined"
              :form-component="formComponent ?? undefined"
              :compose-output-component="composeOutputComponent ?? undefined"
              :run-compose="runCompose"
              :poll-compose="pollCompose"
              :cancel-compose="cancelCompose"
              @save="onBodySave"
              @dirty="onBodyDirty"
            />
          </div>

          <div class="iss__comments">
            <label class="iss__label">Discussion ({{ detail.comments.length }})</label>
            <div v-for="c in detail.comments" :key="c.id" class="iss__comment">
              <div class="iss__comment-head">
                <span class="iss__comment-user">{{ c.userId }}</span>
                <span class="iss__comment-date">{{ commentDate(c) }}</span>
                <button class="iss__comment-del" title="Delete" @click="removeComment(c.id)">×</button>
              </div>
              <div class="iss__comment-text">{{ c.text }}</div>
            </div>
            <form class="iss__comment-form" @submit.prevent="submitComment">
              <textarea
                v-model="commentDraft"
                class="iss__input iss__comment-input"
                rows="2"
                placeholder="Add a comment…"
              />
              <button type="submit" class="iss__btn iss__btn--primary" :disabled="!commentDraft.trim()">Comment</button>
            </form>
          </div>
        </template>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.iss { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.iss__topbar {
  display: flex; align-items: center; gap: 0.5rem; padding: 0.4rem 0.75rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.15); background: hsl(var(--b1));
}
.iss__brand { font-weight: 700; font-size: 0.95rem; max-width: 12rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.iss__tabs { display: flex; gap: 0.25rem; }
.iss__tab {
  border: 1px solid hsl(var(--bc) / 0.2); border-radius: 6px; background: transparent;
  padding: 0.2rem 0.6rem; font-size: 0.8rem; cursor: pointer;
}
.iss__tab--active { background: hsl(var(--p) / 0.16); font-weight: 600; }
.iss__badge { font-size: 0.68rem; opacity: 0.6; }
.iss__search { position: relative; display: flex; align-items: center; gap: 0.25rem; }
.iss__input {
  border: 1px solid hsl(var(--bc) / 0.2); border-radius: 6px; background: transparent;
  padding: 0.25rem 0.5rem; font-size: 0.82rem;
}
.iss__search .iss__input { width: 11rem; }
.iss__search-results {
  position: absolute; top: 100%; left: 0; margin-top: 0.25rem; min-width: 22rem; max-height: 22rem;
  overflow-y: auto; padding: 0.25rem; background: hsl(var(--b1)); border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 8px; box-shadow: 0 6px 20px rgba(0, 0, 0, 0.18); z-index: 50;
}
.iss__search-list { list-style: none; margin: 0; padding: 0; }
.iss__search-row { display: flex; flex-direction: column; padding: 0.35rem 0.5rem; border-radius: 6px; cursor: pointer; }
.iss__search-row:hover { background: hsl(var(--bc) / 0.08); }
.iss__search-title { font-size: 0.85rem; font-weight: 600; }
.iss__search-snippet { font-size: 0.72rem; opacity: 0.6; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.iss__search-empty { padding: 0.6rem; font-size: 0.8rem; opacity: 0.6; }
.iss__spacer { flex: 1; }
.iss__save { font-size: 0.72rem; opacity: 0.7; }
.iss__save--error { color: #d33; opacity: 1; }
.iss__save--saved { color: #2a8; }
.iss__btn {
  border: 1px solid hsl(var(--bc) / 0.2); border-radius: 6px; background: transparent;
  padding: 0.2rem 0.55rem; font-size: 0.8rem; cursor: pointer; white-space: nowrap;
}
.iss__btn:hover:not(:disabled) { background: hsl(var(--bc) / 0.08); }
.iss__btn:disabled { opacity: 0.5; cursor: default; }
.iss__btn--primary { border-color: hsl(var(--p) / 0.6); }
.iss__btn--danger:hover { background: rgba(221, 51, 51, 0.12); }
.iss__error { padding: 0.5rem 0.75rem; color: #d33; font-size: 0.82rem; }
.iss__hint { padding: 2rem; text-align: center; opacity: 0.6; font-size: 0.85rem; }
.iss__body { flex: 1; display: flex; min-height: 0; }
.iss__list { flex: 1; min-width: 0; display: flex; flex-direction: column; overflow-y: auto; }
.iss__new { padding: 0.5rem 0.75rem; border-bottom: 1px solid hsl(var(--bc) / 0.1); }
.iss__new-input { width: 100%; }
.iss__labelfilter { display: flex; flex-wrap: wrap; gap: 0.25rem; padding: 0.4rem 0.75rem; }
.iss__chip {
  border: 1px solid hsl(var(--bc) / 0.2); border-radius: 999px; background: transparent;
  padding: 0.1rem 0.5rem; font-size: 0.72rem; cursor: pointer;
}
.iss__chip--active { background: hsl(var(--p) / 0.18); }
.iss__items { list-style: none; margin: 0; padding: 0.25rem; }
.iss__item { display: flex; align-items: center; gap: 0.5rem; padding: 0.4rem 0.5rem; border-radius: 6px; cursor: pointer; }
.iss__item:hover { background: hsl(var(--bc) / 0.06); }
.iss__item--sel { background: hsl(var(--p) / 0.12); }
.iss__dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.iss__dot--open { background: #2a8; }
.iss__dot--closed { background: hsl(var(--bc) / 0.35); }
.iss__num { font-size: 0.75rem; opacity: 0.55; font-variant-numeric: tabular-nums; }
.iss__item-title { flex: 1; font-size: 0.88rem; }
.iss__assignee, .iss__lab {
  font-size: 0.68rem; opacity: 0.7; padding: 0.05rem 0.35rem; border-radius: 4px; background: hsl(var(--bc) / 0.08);
}
.iss__detail {
  width: 360px; flex-shrink: 0; border-left: 1px solid hsl(var(--bc) / 0.15);
  padding: 0.75rem; overflow-y: auto; background: hsl(var(--b1)); display: flex; flex-direction: column;
}
.iss__detail-head { display: flex; align-items: center; gap: 0.5rem; }
.iss__state { font-size: 0.68rem; padding: 0.1rem 0.45rem; border-radius: 999px; text-transform: uppercase; }
.iss__state--open { background: rgba(42, 136, 85, 0.18); color: #2a8; }
.iss__state--closed { background: hsl(var(--bc) / 0.12); }
.iss__archived { font-size: 0.68rem; padding: 0.1rem 0.4rem; border-radius: 4px; background: hsl(var(--bc) / 0.12); }
.iss__detail-title { font-size: 1.1rem; font-weight: 700; border: none; background: transparent; width: 100%; margin: 0.4rem 0; }
.iss__actions { display: flex; gap: 0.4rem; margin-bottom: 0.75rem; }
.iss__field { margin-bottom: 0.6rem; display: flex; flex-direction: column; gap: 0.25rem; }
.iss__field--row { flex-direction: row; gap: 0.5rem; }
.iss__field--row .iss__field { flex: 1; }
.iss__label { font-size: 0.7rem; text-transform: uppercase; opacity: 0.55; }
.iss__comments { margin-top: 0.75rem; border-top: 1px solid hsl(var(--bc) / 0.12); padding-top: 0.6rem; }
.iss__comment { border: 1px solid hsl(var(--bc) / 0.12); border-radius: 8px; padding: 0.4rem 0.55rem; margin-bottom: 0.5rem; }
.iss__comment-head { display: flex; align-items: center; gap: 0.5rem; font-size: 0.7rem; opacity: 0.65; margin-bottom: 0.2rem; }
.iss__comment-user { font-weight: 600; }
.iss__comment-date { flex: 1; }
.iss__comment-del { border: none; background: transparent; cursor: pointer; opacity: 0.5; font-size: 1rem; line-height: 1; }
.iss__comment-del:hover { opacity: 1; color: #d33; }
.iss__comment-text { font-size: 0.85rem; white-space: pre-wrap; }
.iss__comment-form { display: flex; flex-direction: column; gap: 0.35rem; margin-top: 0.4rem; }
.iss__comment-input { width: 100%; resize: vertical; font-family: inherit; }
</style>
