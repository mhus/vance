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
} from '@vance/shared';
import { useDocumentPrefixReaction } from '@vance/components';
import { WorkPageEditor, type ComposeRunResult } from '@vance/block-editor';
import {
  scanJournal,
  journalMonth,
  getJournalEntry,
  putJournalEntry,
  deleteJournalEntry,
  journalOnThisDay,
  rebuildJournal,
  searchJournal,
} from './api';
import type { JournalView } from './generated/journal/JournalView';
import type { JournalEntryView } from './generated/journal/JournalEntryView';
import type { JournalHitView } from './generated/journal/JournalHitView';

/**
 * Journal application view — a diary of `kind: journal-entry` pages, one
 * per day. Left: a month calendar (days with an entry marked). Middle: the
 * selected day's body-only WorkPageEditor with a mood picker + tags. Right:
 * "on this day" from earlier years. Top: free-text search. See
 * planning/app-journal.md.
 */
const props = defineProps<{
  document: {
    id: string;
    path: string;
    projectId: string;
    title?: string | null;
  };
}>();

const projectId = computed(() => props.document.projectId);
const folder = computed(() => props.document.path.replace(/\/_app\.yaml$/, ''));
const title = computed(() => view.value?.title ?? props.document.title ?? folder.value);

const view = ref<JournalView | null>(null);
const error = ref<string | null>(null);
const loading = ref(false);
const rebuilding = ref(false);

const MONTH_NAMES = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];
const WEEKDAYS = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'];

const todayIso = todayString();
const calYear = ref(Number(todayIso.slice(0, 4)));
const calMonth = ref(Number(todayIso.slice(5, 7))); // 1-12
const monthDays = ref<Set<number>>(new Set());

const selectedDate = ref<string | null>(null);
const entryExists = ref(false);
const moodDraft = ref<string>('');
const tagsDraft = ref<string>('');
const currentBody = ref<string>('');
const pageLoading = ref(false);

type SaveStatus = 'idle' | 'dirty' | 'saving' | 'saved' | 'error';
const saveStatus = ref<SaveStatus>('idle');
const lastSaveError = ref<string | null>(null);

const editorRef = ref<{ save: () => void; flush: () => boolean } | null>(null);

const embedComponent = inject<Component | null>('vance:embed-component', null);
const formComponent = inject<Component | null>('vance:form-component', null);
const composeOutputComponent = inject<Component | null>('vance:compose-output-component', null);
const sessionId = inject<Ref<string | null>>('vance:session-id', ref(null));

const moodPresets = computed<string[]>(() => view.value?.moodPresets ?? []);

// ── Self-write quiet window (keyed by date) ─────────────────────────
const SELF_WRITE_QUIET_MS = 3000;
const lastSelfWriteAt = ref<Map<string, number>>(new Map());
function withinSelfWriteWindow(date: string): boolean {
  const t = lastSelfWriteAt.value.get(date);
  return t != null && Date.now() - t < SELF_WRITE_QUIET_MS;
}

// ── Calendar ────────────────────────────────────────────────────────
type Cell = { day: number; date: string; hasEntry: boolean } | null;
const calendarCells = computed<Cell[]>(() => {
  const y = calYear.value;
  const m = calMonth.value;
  const firstDow = (new Date(y, m - 1, 1).getDay() + 6) % 7; // Mon=0
  const daysInMonth = new Date(y, m, 0).getDate();
  const cells: Cell[] = [];
  for (let i = 0; i < firstDow; i++) cells.push(null);
  for (let d = 1; d <= daysInMonth; d++) {
    const date = `${y}-${pad(m)}-${pad(d)}`;
    cells.push({ day: d, date, hasEntry: monthDays.value.has(d) });
  }
  return cells;
});
const calLabel = computed(() => `${MONTH_NAMES[calMonth.value - 1]} ${calYear.value}`);

async function loadMonth(): Promise<void> {
  try {
    const m = await journalMonth(projectId.value, folder.value, calYear.value, calMonth.value);
    monthDays.value = new Set(m.days ?? []);
  } catch {
    monthDays.value = new Set();
  }
}

function prevMonth(): void {
  if (calMonth.value === 1) { calMonth.value = 12; calYear.value -= 1; }
  else calMonth.value -= 1;
  void loadMonth();
}
function nextMonth(): void {
  if (calMonth.value === 12) { calMonth.value = 1; calYear.value += 1; }
  else calMonth.value += 1;
  void loadMonth();
}
function goToday(): void {
  calYear.value = Number(todayIso.slice(0, 4));
  calMonth.value = Number(todayIso.slice(5, 7));
  void loadMonth();
  void selectDate(todayIso);
}

// ── Load / scan ─────────────────────────────────────────────────────
async function loadScan(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    view.value = await scanJournal(projectId.value, folder.value);
    await loadMonth();
    if (selectedDate.value == null) {
      // Open today by default (creates a blank entry to write into).
      await selectDate(todayIso);
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not scan journal.';
    view.value = null;
  } finally {
    loading.value = false;
  }
}

// ── Day selection + entry load ──────────────────────────────────────
async function selectDate(date: string, options: { force?: boolean } = {}): Promise<void> {
  if (editorRef.value?.flush()) { /* flush pending edits of the previous day */ }
  selectedDate.value = date;
  // keep the calendar month in sync with the selected day
  calYear.value = Number(date.slice(0, 4));
  calMonth.value = Number(date.slice(5, 7));
  saveStatus.value = 'idle';
  lastSaveError.value = null;
  await loadEntry(options);
  void loadOnThisDay(date);
  if (calYear.value !== undefined) void loadMonth();
}

async function loadEntry(options: { force?: boolean } = {}): Promise<void> {
  const date = selectedDate.value;
  if (!date) return;
  if (!options.force && withinSelfWriteWindow(date)) return;
  pageLoading.value = true;
  try {
    const entry = await getJournalEntry(projectId.value, folder.value, date);
    entryExists.value = true;
    moodDraft.value = entry.mood ?? '';
    tagsDraft.value = (entry.tags ?? []).join(', ');
    currentBody.value = entry.body ?? '';
  } catch {
    // 404 → a fresh, not-yet-created entry for this day.
    entryExists.value = false;
    moodDraft.value = '';
    tagsDraft.value = '';
    currentBody.value = '';
  } finally {
    pageLoading.value = false;
  }
}

// ── Save ────────────────────────────────────────────────────────────
function parseTags(raw: string): string[] {
  return raw.split(',').map((t) => t.trim()).filter((t) => t.length > 0);
}

async function persist(): Promise<void> {
  const date = selectedDate.value;
  if (!date) return;
  saveStatus.value = 'saving';
  lastSelfWriteAt.value.set(date, Date.now());
  try {
    await putJournalEntry(projectId.value, folder.value, {
      date,
      body: currentBody.value,
      mood: moodDraft.value.trim() || undefined,
      tags: parseTags(tagsDraft.value),
    });
    lastSelfWriteAt.value.set(date, Date.now());
    entryExists.value = true;
    saveStatus.value = 'saved';
    lastSaveError.value = null;
    // A new day may now carry an entry — refresh the calendar mark.
    if (Number(date.slice(0, 4)) === calYear.value
        && Number(date.slice(5, 7)) === calMonth.value) {
      monthDays.value = new Set(monthDays.value).add(Number(date.slice(8, 10)));
    }
  } catch (e) {
    saveStatus.value = 'error';
    lastSaveError.value = e instanceof Error ? e.message : 'Save failed.';
  }
}

function onEditorSave(body: string): void {
  currentBody.value = body;
  void persist();
}
function onEditorDirty(dirty: boolean): void {
  if (dirty) {
    saveStatus.value = 'dirty';
    if (selectedDate.value) lastSelfWriteAt.value.set(selectedDate.value, Date.now());
  }
}
function onMetaChange(): void {
  // Mood / tags edits — flush the editor body first so the PUT is complete.
  if (editorRef.value) editorRef.value.save();
  else void persist();
}

async function deleteEntry(): Promise<void> {
  const date = selectedDate.value;
  if (!date || !entryExists.value) return;
  if (!window.confirm(`Delete the entry for ${date}?`)) return;
  try {
    await deleteJournalEntry(projectId.value, folder.value, date);
    entryExists.value = false;
    currentBody.value = '';
    moodDraft.value = '';
    tagsDraft.value = '';
    await loadMonth();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Delete failed.';
  }
}

// ── On this day ─────────────────────────────────────────────────────
const onThisDay = ref<JournalEntryView[]>([]);
async function loadOnThisDay(date: string): Promise<void> {
  try {
    const resp = await journalOnThisDay(projectId.value, folder.value, date);
    onThisDay.value = resp.entries ?? [];
  } catch {
    onThisDay.value = [];
  }
}

// ── Search ──────────────────────────────────────────────────────────
const searchQuery = ref('');
const searchResults = ref<JournalHitView[]>([]);
const searchOpen = ref(false);
const searching = ref(false);
async function runSearch(): Promise<void> {
  const q = searchQuery.value.trim();
  if (!q) { searchResults.value = []; searchOpen.value = false; return; }
  searching.value = true;
  try {
    const resp = await searchJournal(projectId.value, folder.value, q);
    searchResults.value = resp.items ?? [];
    searchOpen.value = true;
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Search failed.';
  } finally {
    searching.value = false;
  }
}
function pickSearchResult(hit: JournalHitView): void {
  searchOpen.value = false;
  searchQuery.value = '';
  if (hit.date) void selectDate(hit.date);
}

// ── Rebuild ─────────────────────────────────────────────────────────
async function rebuild(): Promise<void> {
  rebuilding.value = true;
  try {
    if (editorRef.value?.flush()) { /* flush */ }
    await rebuildJournal(projectId.value, folder.value);
    await loadScan();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Rebuild failed.';
  } finally {
    rebuilding.value = false;
  }
}

// ── Image upload + vance: resolution (from WorkbookAppKind / wiki) ───
async function uploadImage(file: File): Promise<string | null> {
  const assetsFolder = `${folder.value}/assets`;
  const ts = Date.now();
  const safe = file.name.toLowerCase().replace(/[^a-z0-9._-]+/g, '_').replace(/^_+|_+$/g, '');
  const path = `${assetsFolder}/${ts}-${safe || 'image'}`;
  const form = new FormData();
  form.append('file', file);
  form.append('projectId', projectId.value);
  form.append('path', path);
  if (file.type) form.append('mimeType', file.type);
  try {
    await brainFetch<{ id: string }>('POST', 'documents/upload', { body: form });
    return `vance:/${encodeURI(path)}?kind=image`;
  } catch (e) {
    console.error('[Journal] image upload failed', e);
    return null;
  }
}
async function resolveVanceImageSrc(uri: string): Promise<string | null> {
  let parsed: URL;
  try { parsed = new URL(uri); } catch { return null; }
  if (parsed.protocol !== 'vance:') return null;
  const targetProject = parsed.hostname ? decodeURIComponent(parsed.hostname) : projectId.value;
  const path = decodeURIComponent(parsed.pathname.replace(/^\//, ''));
  if (!targetProject || !path) return null;
  try {
    const dto = await brainFetch<{ id: string }>(
      'GET',
      `documents/by-path?projectId=${encodeURIComponent(targetProject)}&path=${encodeURIComponent(path)}`,
    );
    return documentContentUrl(dto.id);
  } catch {
    return null;
  }
}
async function resolveEmbedDoc(uri: string): Promise<{
  id: string; path: string; title: string | null; kind: string | null; mimeType: string | null;
} | null> {
  let parsed: URL;
  try { parsed = new URL(uri); } catch { return null; }
  if (parsed.protocol !== 'vance:') return null;
  const target = parsed.hostname ? decodeURIComponent(parsed.hostname) : projectId.value;
  const path = decodeURIComponent(parsed.pathname.replace(/^\//, ''));
  if (!target || !path) return null;
  try {
    const dto = await brainFetch<{
      id: string; path: string; title?: string | null; kind?: string | null; mimeType?: string | null;
    }>(
      'GET',
      `documents/by-path?projectId=${encodeURIComponent(target)}&path=${encodeURIComponent(path)}`,
    );
    return {
      id: dto.id, path: dto.path,
      title: dto.title ?? null, kind: dto.kind ?? null, mimeType: dto.mimeType ?? null,
    };
  } catch {
    return null;
  }
}

// ── Compose (generic Damogran runner) ───────────────────────────────
function runCompose(yaml: string): Promise<ComposeRunResult> {
  return postComposeRun(projectId.value, {
    composeYaml: yaml,
    composeBasePath: folder.value,
    sessionId: sessionId.value,
    appKey: folder.value,
  });
}
function pollCompose(runId: string): Promise<ComposeRunResult> {
  return pollComposeRun(projectId.value, runId);
}
function cancelCompose(runId: string): Promise<ComposeRunResult> {
  return cancelComposeRun(projectId.value, runId);
}

// ── Live watch ──────────────────────────────────────────────────────
useDocumentPrefixReaction({
  prefix: computed(() => `${folder.value}/`),
  debounceMs: 250,
  onRemoteChange: async (paths) => {
    const date = selectedDate.value;
    if (date != null && withinSelfWriteWindow(date)) return;
    await loadMonth();
    const activePath = entryExists.value && date
      ? `${folder.value}/entries/${date.slice(0, 4)}/${date}.md`
      : null;
    if (activePath && paths.includes(activePath)) await loadEntry();
  },
});

watch(folder, () => {
  selectedDate.value = null;
  currentBody.value = '';
  void loadScan();
});

onMounted(() => { void loadScan(); });
onBeforeUnmount(() => { editorRef.value?.flush(); });

const saveStatusLabel = computed<string | null>(() => {
  switch (saveStatus.value) {
    case 'dirty': return 'Edited';
    case 'saving': return 'Saving…';
    case 'saved': return 'Saved';
    case 'error': return lastSaveError.value ?? 'Save failed';
    default: return null;
  }
});

const editorKey = computed(() => selectedDate.value ?? 'none');
const selectedLabel = computed(() => (selectedDate.value ? humanDate(selectedDate.value) : ''));

// ── Helpers ─────────────────────────────────────────────────────────
function pad(n: number): string { return String(n).padStart(2, '0'); }
function todayString(): string {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
function humanDate(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number);
  if (!y || !m || !d) return iso;
  return `${MONTH_NAMES[m - 1]} ${d}, ${y}`;
}
</script>

<template>
  <div class="jr">
    <header class="jr__topbar">
      <div class="jr__brand" :title="folder">{{ title }}</div>
      <button class="jr__btn" @click="goToday">Today</button>

      <div class="jr__search">
        <input
          v-model="searchQuery"
          type="search"
          class="jr__search-input"
          placeholder="Search entries…"
          @keydown.enter.prevent="runSearch"
          @keydown.escape="searchOpen = false"
        />
        <button class="jr__btn" :disabled="searching" @click="runSearch">🔍</button>
        <div v-if="searchOpen" class="jr__search-results">
          <ul v-if="searchResults.length" class="jr__search-list">
            <li
              v-for="r in searchResults"
              :key="r.id"
              class="jr__search-row"
              @click="pickSearchResult(r)"
            >
              <span class="jr__search-title">{{ r.date }} — {{ r.title || '(untitled)' }}</span>
              <span class="jr__search-snippet">{{ r.snippet }}</span>
            </li>
          </ul>
          <div v-else class="jr__search-empty">No matching entry.</div>
        </div>
      </div>

      <span class="jr__spacer" />
      <span v-if="saveStatusLabel" class="jr__save" :class="`jr__save--${saveStatus}`">
        {{ saveStatusLabel }}
      </span>
      <button
        class="jr__btn"
        :disabled="rebuilding"
        :title="rebuilding ? 'Rebuilding…' : 'Rebuild index + stats'"
        @click="rebuild"
      >{{ rebuilding ? '…' : '↻' }}</button>
    </header>

    <div v-if="error" class="jr__error">{{ error }}</div>

    <div class="jr__body">
      <!-- Left: calendar + stats -->
      <aside class="jr__cal">
        <div class="jr__cal-head">
          <button class="jr__btn jr__btn--sm" @click="prevMonth">‹</button>
          <span class="jr__cal-label">{{ calLabel }}</span>
          <button class="jr__btn jr__btn--sm" @click="nextMonth">›</button>
        </div>
        <div class="jr__cal-grid">
          <div v-for="w in WEEKDAYS" :key="w" class="jr__cal-dow">{{ w }}</div>
          <template v-for="(c, i) in calendarCells" :key="i">
            <div v-if="!c" class="jr__cal-cell jr__cal-cell--empty" />
            <button
              v-else
              class="jr__cal-cell"
              :class="{
                'jr__cal-cell--has': c.hasEntry,
                'jr__cal-cell--sel': c.date === selectedDate,
                'jr__cal-cell--today': c.date === todayIso,
              }"
              @click="selectDate(c.date)"
            >
              <span>{{ c.day }}</span>
              <span v-if="c.hasEntry" class="jr__cal-dot" />
            </button>
          </template>
        </div>

        <div v-if="view" class="jr__stats">
          <div class="jr__stat"><b>{{ view.stats.totalEntries }}</b><span>entries</span></div>
          <div class="jr__stat"><b>{{ view.stats.currentStreak }}</b><span>day streak</span></div>
          <div class="jr__stat"><b>{{ view.stats.longestStreak }}</b><span>longest</span></div>
        </div>
      </aside>

      <!-- Middle: the day's editor -->
      <main class="jr__main">
        <div v-if="loading" class="jr__hint">Loading journal…</div>
        <template v-else-if="selectedDate">
          <div class="jr__entryhead">
            <span class="jr__entrytitle">{{ selectedLabel }}</span>
            <span v-if="!entryExists" class="jr__badge">new</span>
            <span class="jr__spacer" />
            <label class="jr__mood">
              <select v-model="moodDraft" class="jr__mood-select" @change="onMetaChange">
                <option value="">mood…</option>
                <option v-for="m in moodPresets" :key="m" :value="m">{{ m }}</option>
              </select>
            </label>
            <button
              v-if="entryExists"
              class="jr__btn jr__btn--danger"
              title="Delete entry"
              @click="deleteEntry"
            >🗑</button>
          </div>
          <div class="jr__tags">
            <input
              v-model="tagsDraft"
              type="text"
              class="jr__tags-input"
              placeholder="tags, comma, separated"
              @change="onMetaChange"
            />
          </div>
          <div v-if="pageLoading" class="jr__hint">Loading entry…</div>
          <WorkPageEditor
            v-else
            :key="editorKey"
            ref="editorRef"
            :document="{ id: props.document.id, path: `${folder}/entries/${selectedDate.slice(0,4)}/${selectedDate}.md`, projectId, title: selectedLabel }"
            :source="currentBody"
            :auto-save-ms="1500"
            body-only
            :current-project-id="projectId"
            :upload-image="uploadImage"
            :resolve-image-src="resolveVanceImageSrc"
            :resolve-embed-doc="resolveEmbedDoc"
            :embed-component="embedComponent ?? undefined"
            :form-component="formComponent ?? undefined"
            :compose-output-component="composeOutputComponent ?? undefined"
            :run-compose="runCompose"
            :poll-compose="pollCompose"
            :cancel-compose="cancelCompose"
            @save="onEditorSave"
            @dirty="onEditorDirty"
          />
        </template>
        <div v-else class="jr__hint">Pick a day in the calendar.</div>
      </main>

      <!-- Right: on this day -->
      <aside class="jr__right">
        <div class="jr__right-head">On this day</div>
        <ul v-if="onThisDay.length" class="jr__otd-list">
          <li
            v-for="e in onThisDay"
            :key="e.id"
            class="jr__otd-row"
            @click="selectDate(e.date)"
          >
            <span class="jr__otd-date">{{ e.date }}</span>
            <span class="jr__otd-title">{{ e.title }}</span>
          </li>
        </ul>
        <div v-else class="jr__right-empty">Nothing from earlier years.</div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.jr { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.jr__topbar {
  display: flex; align-items: center; gap: 0.5rem;
  padding: 0.4rem 0.75rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.15);
  background: hsl(var(--b1));
}
.jr__brand { font-weight: 700; font-size: 0.95rem; max-width: 14rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.jr__search { position: relative; display: flex; align-items: center; gap: 0.25rem; }
.jr__search-input {
  border: 1px solid hsl(var(--bc) / 0.2); border-radius: 6px; background: transparent;
  padding: 0.2rem 0.5rem; font-size: 0.8rem; width: 12rem;
}
.jr__search-results {
  position: absolute; top: 100%; left: 0; margin-top: 0.25rem; min-width: 22rem; max-height: 22rem;
  overflow-y: auto; padding: 0.25rem; background: hsl(var(--b1));
  border: 1px solid hsl(var(--bc) / 0.2); border-radius: 8px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.18); z-index: 50;
}
.jr__search-list { list-style: none; margin: 0; padding: 0; }
.jr__search-empty { padding: 0.6rem; font-size: 0.8rem; opacity: 0.6; }
.jr__search-row { display: flex; flex-direction: column; padding: 0.35rem 0.5rem; border-radius: 6px; cursor: pointer; }
.jr__search-row:hover { background: hsl(var(--bc) / 0.08); }
.jr__search-title { font-size: 0.85rem; font-weight: 600; }
.jr__search-snippet { font-size: 0.72rem; opacity: 0.6; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.jr__spacer { flex: 1; }
.jr__save { font-size: 0.72rem; opacity: 0.7; }
.jr__save--error { color: #d33; opacity: 1; }
.jr__save--saved { color: #2a8; }
.jr__btn {
  border: 1px solid hsl(var(--bc) / 0.2); border-radius: 6px; background: transparent;
  padding: 0.2rem 0.55rem; font-size: 0.8rem; cursor: pointer; white-space: nowrap;
}
.jr__btn:hover:not(:disabled) { background: hsl(var(--bc) / 0.08); }
.jr__btn:disabled { opacity: 0.5; cursor: default; }
.jr__btn--sm { padding: 0.1rem 0.45rem; }
.jr__btn--danger:hover:not(:disabled) { background: rgba(221, 51, 51, 0.12); }
.jr__error { padding: 0.5rem 0.75rem; color: #d33; font-size: 0.82rem; }
.jr__hint { padding: 2rem; text-align: center; opacity: 0.6; font-size: 0.85rem; }
.jr__body { flex: 1; display: flex; min-height: 0; }
.jr__cal {
  width: 260px; flex-shrink: 0; padding: 0.75rem;
  border-right: 1px solid hsl(var(--bc) / 0.15); overflow-y: auto;
}
.jr__cal-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 0.5rem; }
.jr__cal-label { font-weight: 600; font-size: 0.85rem; }
.jr__cal-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 2px; }
.jr__cal-dow { text-align: center; font-size: 0.62rem; opacity: 0.5; padding-bottom: 0.2rem; }
.jr__cal-cell {
  position: relative; aspect-ratio: 1; border: none; background: transparent; border-radius: 6px;
  font-size: 0.78rem; cursor: pointer; display: flex; align-items: center; justify-content: center;
}
.jr__cal-cell--empty { cursor: default; }
.jr__cal-cell:hover:not(.jr__cal-cell--empty) { background: hsl(var(--bc) / 0.08); }
.jr__cal-cell--today { outline: 1px solid hsl(var(--p) / 0.5); }
.jr__cal-cell--sel { background: hsl(var(--p) / 0.18); font-weight: 700; }
.jr__cal-dot {
  position: absolute; bottom: 3px; left: 50%; transform: translateX(-50%);
  width: 4px; height: 4px; border-radius: 50%; background: hsl(var(--p));
}
.jr__stats { display: flex; gap: 0.5rem; margin-top: 1rem; }
.jr__stat { flex: 1; text-align: center; padding: 0.4rem; border: 1px solid hsl(var(--bc) / 0.12); border-radius: 8px; }
.jr__stat b { display: block; font-size: 1.1rem; }
.jr__stat span { font-size: 0.62rem; opacity: 0.6; }
.jr__main { flex: 1; min-width: 0; display: flex; flex-direction: column; overflow-y: auto; }
.jr__entryhead { display: flex; align-items: center; gap: 0.6rem; padding: 0.75rem 1.5rem 0.25rem; }
.jr__entrytitle { font-size: 1.3rem; font-weight: 700; }
.jr__badge { font-size: 0.62rem; padding: 0.1rem 0.4rem; border-radius: 4px; background: hsl(var(--bc) / 0.1); opacity: 0.8; }
.jr__mood-select {
  border: 1px solid hsl(var(--bc) / 0.2); border-radius: 6px; background: transparent;
  padding: 0.2rem 0.5rem; font-size: 0.8rem;
}
.jr__tags { padding: 0 1.5rem 0.4rem; }
.jr__tags-input {
  border: 1px solid hsl(var(--bc) / 0.2); border-radius: 6px; background: transparent;
  padding: 0.2rem 0.5rem; font-size: 0.78rem; width: 100%; max-width: 28rem;
}
.jr__right {
  width: 260px; flex-shrink: 0; border-left: 1px solid hsl(var(--bc) / 0.15);
  padding: 0.75rem; overflow-y: auto; background: hsl(var(--b1));
}
.jr__right-head { font-weight: 600; font-size: 0.8rem; margin-bottom: 0.5rem; }
.jr__right-empty { font-size: 0.78rem; opacity: 0.55; }
.jr__otd-list { list-style: none; margin: 0; padding: 0; }
.jr__otd-row { display: flex; flex-direction: column; padding: 0.4rem 0.3rem; border-radius: 6px; cursor: pointer; }
.jr__otd-row:hover { background: hsl(var(--bc) / 0.08); }
.jr__otd-date { font-size: 0.72rem; opacity: 0.6; }
.jr__otd-title { font-size: 0.85rem; }
</style>
