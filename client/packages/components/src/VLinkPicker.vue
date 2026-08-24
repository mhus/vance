<script setup lang="ts">
/**
 * Link picker — two tabs:
 *
 *   1. **Project document** — server-side search across every kind of
 *      document in the current project; emits a {@code vance:/<path>}
 *      URI with the document's kind as a hint.
 *   2. **Direct URL** — paste box for an external URL (or a manual
 *      vance: URI). "Open in new tab" checkbox, defaults by scheme.
 *
 * The picker emits the resolved {@code href} + {@code openInNewTab}
 * back to the host; the host calls {@code editorRef.applyLink(...)}.
 *
 * Lives here rather than in {@code @vance/block-editor}: the editor has
 * no Vance dependency (Tiptap + Vue only) and talks to no server, which
 * is why its {@code openLinkPicker} is a host callback in the first
 * place. It is shared by every host that mounts the block editor —
 * workbook, wiki, kanban, gtd, issues, journal.
 */
import { computed, onMounted, ref, watch } from 'vue';
import { brainFetch } from '@vance/shared';
import type {
  ApplicationEntryDto,
  ApplicationListResponse,
  ApplicationTargetDto,
  ApplicationTargetsResponse,
  DocumentSearchItem,
  DocumentSearchResponse,
} from '@vance/generated';
import { vanceRef } from './vanceUri';

/** One place inside the surrounding app, as the host describes it. */
export interface AppLinkTarget {
  handle: string;
  label: string;
  group?: string | null;
}

/**
 * The app this editor runs inside. Supplied by the host — it is the only one
 * that knows; the picker never derives it from the document path, and without
 * it the "this app" tab simply does not appear.
 */
export interface AppLinkContext {
  /** Path of the app manifest (`<folder>/_app.yaml`). */
  appPath: string;
  /** Display name of the app. */
  appLabel: string;
  targets: AppLinkTarget[];
}

const props = defineProps<{
  projectId: string;
  /** Currently selected link href, when editing an existing link. */
  initialHref?: string | null;
  /** The surrounding app, for linking to a sibling page. */
  appTargets?: AppLinkContext | null;
}>();

const emit = defineEmits<{
  (e: 'pick', href: string, openInNewTab: boolean): void;
  (e: 'clear'): void;
  (e: 'close'): void;
}>();

type TabId = 'project' | 'url' | 'own' | 'starred' | 'apps';
const tab = ref<TabId>('project');

// ── Tab 1: Project document search ─────────────────────────────────
const docQuery = ref('');
const docResults = ref<DocumentSearchItem[]>([]);
const docLoading = ref(false);
const docError = ref<string | null>(null);
const docTotal = ref(0);
let docTimer: ReturnType<typeof setTimeout> | null = null;

async function searchDocs(query: string) {
  docLoading.value = true;
  docError.value = null;
  try {
    // Recursive project-wide search. /documents/folder?search would only
    // hit the root-level files (one folder layer deep), which makes it
    // useless for "find the yaml file somewhere in the project".
    const params = new URLSearchParams();
    params.set('projectId', props.projectId);
    if (query) params.set('query', query);
    params.set('size', '40');
    const resp = await brainFetch<DocumentSearchResponse>(
      'GET',
      `documents/search?${params}`,
    );
    docResults.value = resp.items ?? [];
    docTotal.value = resp.total ?? docResults.value.length;
  } catch (e) {
    docError.value = e instanceof Error ? e.message : 'Search failed';
    docResults.value = [];
    docTotal.value = 0;
  } finally {
    docLoading.value = false;
  }
}

function scheduleSearch() {
  if (docTimer != null) clearTimeout(docTimer);
  docTimer = setTimeout(() => {
    docTimer = null;
    void searchDocs(docQuery.value.trim());
  }, 200);
}

function pickDoc(doc: DocumentSearchItem) {
  const params: string[] = [];
  if (doc.kind) params.push(`kind=${encodeURIComponent(doc.kind)}`);
  const href = `vance:/${encodeURI(doc.path)}${params.length ? '?' + params.join('&') : ''}`;
  emit('pick', href, urlOpensInNewTab.value);
}

// ── Tabs 3–5: link into an application ─────────────────────────────
// Three tabs, not one with sections: "this app" comes from the host and needs
// no request, "Starred" crosses projects and is the user's own shortcut,
// "Applications" is this project. They differ in where the data comes from and
// in what a row means, so merging them would need a label per row anyway.

/** Own app: the host knows it, we never derive it from the document path. */
const ownTargets = computed<AppLinkTarget[]>(() => props.appTargets?.targets ?? []);
const ownQuery = ref('');
const filteredOwnTargets = computed(() => filterTargets(ownTargets.value, ownQuery.value));

const appsLoading = ref(false);
const appsError = ref<string | null>(null);
const starredApps = ref<ApplicationEntryDto[]>([]);
const projectApps = ref<ApplicationEntryDto[]>([]);
let appsLoaded = false;

/** The app whose places are being shown, or null while picking an app. */
const openApp = ref<ApplicationEntryDto | null>(null);
const appTargetList = ref<ApplicationTargetDto[]>([]);
const appTargetsLoading = ref(false);
const appTargetsError = ref<string | null>(null);

async function loadApps() {
  if (appsLoaded) return;
  appsLoading.value = true;
  appsError.value = null;
  try {
    const params = new URLSearchParams({ projectId: props.projectId });
    const resp = await brainFetch<ApplicationListResponse>('GET', `applications?${params}`);
    starredApps.value = resp.starred ?? [];
    projectApps.value = resp.project ?? [];
    appsLoaded = true;
  } catch (e) {
    appsError.value = e instanceof Error ? e.message : 'Could not load applications';
  } finally {
    appsLoading.value = false;
  }
}

/**
 * Step two: the places inside a chosen app. An empty result is a normal answer
 * (most apps have none) — the app itself stays pickable either way, so there is
 * always something to do on this screen.
 */
async function openAppTargets(app: ApplicationEntryDto) {
  openApp.value = app;
  appTargetList.value = [];
  appTargetsError.value = null;
  appTargetsLoading.value = true;
  try {
    const params = new URLSearchParams({ projectId: app.project, path: app.path });
    const resp = await brainFetch<ApplicationTargetsResponse>(
      'GET',
      `applications/targets?${params}`,
    );
    appTargetList.value = resp.targets ?? [];
  } catch (e) {
    appTargetsError.value = e instanceof Error ? e.message : 'Could not load places';
  } finally {
    appTargetsLoading.value = false;
  }
}

function pickOwnTarget(handle: string | null) {
  const app = props.appTargets;
  if (!app) return;
  // No project: the own app is by definition in the project being edited, and a
  // relative reference survives the folder being copied elsewhere.
  emit('pick', vanceRef({ path: app.appPath, kind: 'application', entry: handle }), false);
}

function pickApp(app: ApplicationEntryDto, handle: string | null) {
  emit('pick', vanceRef({
    path: app.path,
    project: app.project === props.projectId ? null : app.project,
    kind: 'application',
    entry: handle,
  }), false);
}

function filterTargets<T extends { label: string }>(list: T[], query: string): T[] {
  const q = query.trim().toLowerCase();
  if (!q) return list;
  return list.filter((t) => t.label.toLowerCase().includes(q));
}

/** Rows grouped by their `group`, ungrouped first, groups in first-seen order. */
function grouped<T extends { group?: string | null }>(list: T[]): { name: string | null; items: T[] }[] {
  const out: { name: string | null; items: T[] }[] = [];
  const byName = new Map<string | null, { name: string | null; items: T[] }>();
  for (const item of list) {
    const name = item.group || null;
    let bucket = byName.get(name);
    if (!bucket) { bucket = { name, items: [] }; byName.set(name, bucket); out.push(bucket); }
    bucket.items.push(item);
  }
  return out;
}

// ── Tab 2: Direct URL ──────────────────────────────────────────────
const urlInput = ref('');
const urlOpensInNewTab = ref(true);
const urlInputRef = ref<HTMLInputElement | null>(null);

const urlIsVance = computed(() => urlInput.value.startsWith('vance:'));
// Re-default the "open in new tab" toggle when the URL changes between
// vance: and external — saves the user a click.
watch(urlIsVance, (isVance) => {
  urlOpensInNewTab.value = !isVance;
});

function submitUrl() {
  const trimmed = urlInput.value.trim();
  if (!trimmed) return;
  emit('pick', trimmed, urlOpensInNewTab.value);
}

// ── Lifecycle ─────────────────────────────────────────────────────
function close() { emit('close'); }
function clearLink() { emit('clear'); }
function onBackdrop(e: MouseEvent) {
  if (e.target === e.currentTarget) close();
}

onMounted(() => {
  // If editing an existing link, jump straight to the URL tab with
  // the current href pre-filled — that's the typical "I want to
  // change this link's target" flow.
  if (props.initialHref) {
    urlInput.value = props.initialHref;
    urlOpensInNewTab.value = !props.initialHref.startsWith('vance:');
    tab.value = 'url';
  }
  void searchDocs('');
});

watch(tab, async (next) => {
  if (next === 'url') {
    await Promise.resolve();
    urlInputRef.value?.focus();
  }
  // Lazily, and only once: the listing is one request, but it is pointless for
  // the many links that are a document or a plain URL.
  if (next === 'starred' || next === 'apps') {
    openApp.value = null;
    void loadApps();
  }
});
</script>

<template>
  <div class="link-picker" @click="onBackdrop">
    <div class="link-picker__panel">
      <header class="link-picker__header">
        <span>Insert link</span>
        <button class="link-picker__close" type="button" @click="close">×</button>
      </header>

      <nav class="link-picker__tabs">
        <button
          type="button"
          class="link-picker__tab"
          :class="{ 'link-picker__tab--active': tab === 'project' }"
          @click="tab = 'project'"
        >Project document</button>
        <button
          v-if="appTargets"
          type="button"
          class="link-picker__tab"
          :class="{ 'link-picker__tab--active': tab === 'own' }"
          @click="tab = 'own'"
        >This app</button>
        <button
          type="button"
          class="link-picker__tab"
          :class="{ 'link-picker__tab--active': tab === 'starred' }"
          @click="tab = 'starred'"
        >Starred</button>
        <button
          type="button"
          class="link-picker__tab"
          :class="{ 'link-picker__tab--active': tab === 'apps' }"
          @click="tab = 'apps'"
        >Applications</button>
        <button
          type="button"
          class="link-picker__tab"
          :class="{ 'link-picker__tab--active': tab === 'url' }"
          @click="tab = 'url'"
        >Direct URL</button>
      </nav>

      <!-- ── Tab: Project document search ────────────────────────── -->
      <template v-if="tab === 'project'">
        <div class="link-picker__actions">
          <input
            v-model="docQuery"
            type="search"
            class="link-picker__search-input"
            placeholder="Search documents in this project…"
            autofocus
            @input="scheduleSearch"
          />
        </div>
        <div v-if="docError" class="link-picker__error">{{ docError }}</div>
        <div v-if="docLoading" class="link-picker__loading">Searching…</div>
        <div v-else-if="docResults.length === 0" class="link-picker__empty">
          No documents found.
        </div>
        <div v-else class="link-picker__list">
          <button
            v-for="d in docResults"
            :key="d.id"
            type="button"
            class="link-picker__list-item"
            @click="pickDoc(d)"
          >
            <span class="link-picker__list-title">{{ d.title || d.path }}</span>
            <span class="link-picker__list-meta">
              <span v-if="d.kind" class="link-picker__list-kind">{{ d.kind }}</span>
              <span class="link-picker__list-path">{{ d.path }}</span>
            </span>
          </button>
        </div>
        <div
          v-if="docResults.length > 0 && docTotal > docResults.length"
          class="link-picker__truncated"
        >
          Showing {{ docResults.length }} of {{ docTotal }} — refine the search.
        </div>
      </template>

      <!-- ── Tab: this app (sibling pages) ───────────────────────── -->
      <template v-else-if="tab === 'own' && appTargets">
        <div class="link-picker__actions">
          <input
            v-model="ownQuery"
            type="search"
            class="link-picker__search-input"
            :placeholder="`Filter in ${appTargets.appLabel}…`"
          />
        </div>
        <div class="link-picker__list">
          <button
            type="button"
            class="link-picker__list-item"
            @click="pickOwnTarget(null)"
          >
            <span class="link-picker__list-title">{{ appTargets.appLabel }}</span>
            <span class="link-picker__list-meta">
              <span class="link-picker__list-path">The app itself, no particular page</span>
            </span>
          </button>
          <template v-for="g in grouped(filteredOwnTargets)" :key="g.name ?? ''">
            <div v-if="g.name" class="link-picker__group">{{ g.name }}</div>
            <button
              v-for="t in g.items"
              :key="t.handle"
              type="button"
              class="link-picker__list-item"
              @click="pickOwnTarget(t.handle)"
            >
              <span class="link-picker__list-title">{{ t.label }}</span>
            </button>
          </template>
        </div>
        <div v-if="filteredOwnTargets.length === 0" class="link-picker__empty">
          No page matches the filter.
        </div>
      </template>

      <!-- ── Tabs: starred apps / apps in this project ───────────── -->
      <template v-else-if="tab === 'starred' || tab === 'apps'">
        <div v-if="appsError" class="link-picker__error">{{ appsError }}</div>
        <div v-else-if="appsLoading" class="link-picker__loading">Loading…</div>

        <!-- step 1: pick an app -->
        <template v-else-if="!openApp">
          <div
            v-if="(tab === 'starred' ? starredApps : projectApps).length === 0"
            class="link-picker__empty"
          >
            {{ tab === 'starred'
              ? 'No starred application.'
              : 'No application in this project.' }}
          </div>
          <div v-else class="link-picker__list">
            <button
              v-for="a in (tab === 'starred' ? starredApps : projectApps)"
              :key="a.project + a.path"
              type="button"
              class="link-picker__list-item"
              @click="openAppTargets(a)"
            >
              <span class="link-picker__list-title">
                <span v-if="a.icon">{{ a.icon }} </span>{{ a.title || a.path }}
              </span>
              <span class="link-picker__list-meta">
                <span class="link-picker__list-kind">{{ a.app }}</span>
                <span class="link-picker__list-path">
                  {{ a.project === projectId ? a.path : a.project + ' · ' + a.path }}
                </span>
              </span>
            </button>
          </div>
        </template>

        <!-- step 2: pick a place inside it, or the app itself -->
        <template v-else>
          <div class="link-picker__actions">
            <button type="button" class="link-picker__btn" @click="openApp = null">← Back</button>
            <span class="link-picker__crumb">{{ openApp.title || openApp.path }}</span>
          </div>
          <div v-if="appTargetsError" class="link-picker__error">{{ appTargetsError }}</div>
          <div v-else-if="appTargetsLoading" class="link-picker__loading">Loading…</div>
          <div v-else class="link-picker__list">
            <button
              type="button"
              class="link-picker__list-item"
              @click="pickApp(openApp, null)"
            >
              <span class="link-picker__list-title">{{ openApp.title || openApp.path }}</span>
              <span class="link-picker__list-meta">
                <span class="link-picker__list-path">The app itself, no particular place</span>
              </span>
            </button>
            <template v-for="g in grouped(appTargetList)" :key="g.name ?? ''">
              <div v-if="g.name" class="link-picker__group">{{ g.name }}</div>
              <button
                v-for="t in g.items"
                :key="t.handle"
                type="button"
                class="link-picker__list-item"
                @click="pickApp(openApp, t.handle)"
              >
                <span class="link-picker__list-title">{{ t.label }}</span>
              </button>
            </template>
          </div>
        </template>
      </template>

      <!-- ── Tab: Direct URL ─────────────────────────────────────── -->
      <template v-else-if="tab === 'url'">
        <form class="link-picker__url-form" @submit.prevent="submitUrl">
          <input
            ref="urlInputRef"
            v-model="urlInput"
            type="url"
            class="link-picker__url-input"
            placeholder="https://example.com or vance:/path/document"
            @keydown.escape="close"
          />
          <label class="link-picker__url-checkbox">
            <input v-model="urlOpensInNewTab" type="checkbox" />
            <span>Open in new tab</span>
          </label>
          <div class="link-picker__url-actions">
            <button
              v-if="initialHref"
              type="button"
              class="link-picker__btn link-picker__btn--danger"
              @click="clearLink"
            >Remove link</button>
            <span class="link-picker__url-spacer" />
            <button
              type="button"
              class="link-picker__btn"
              @click="close"
            >Cancel</button>
            <button
              type="submit"
              class="link-picker__btn link-picker__btn--primary"
              :disabled="!urlInput.trim()"
            >Apply</button>
          </div>
        </form>
      </template>
    </div>
  </div>
</template>

<style scoped>
.link-picker {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 2rem;
}
.link-picker__panel {
  background: var(--color-base-100);
  border-radius: 0.5rem;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.15);
  width: 100%;
  max-width: 36rem;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.link-picker__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem 1rem;
  font-weight: 600;
  border-bottom: 1px solid color-mix(in oklab, var(--color-base-content) 18%, transparent);
}
.link-picker__close {
  background: none;
  border: none;
  font-size: 1.4rem;
  line-height: 1;
  cursor: pointer;
  color: color-mix(in oklab, var(--color-base-content) 65%, transparent);
  padding: 0 0.25rem;
}
.link-picker__tabs {
  display: flex;
  gap: 0.25rem;
  padding: 0.25rem 0.5rem;
  border-bottom: 1px solid color-mix(in oklab, var(--color-base-content) 18%, transparent);
  background: color-mix(in oklab, var(--color-base-content) 6%, transparent);
}
.link-picker__tab {
  background: none;
  border: 0;
  padding: 0.4rem 0.8rem;
  border-radius: 0.25rem;
  cursor: pointer;
  font-size: 0.85rem;
  color: color-mix(in oklab, var(--color-base-content) 65%, transparent);
}
.link-picker__tab:hover { color: var(--color-base-content); }
.link-picker__tab--active {
  background: var(--color-base-100);
  color: var(--color-base-content);
  font-weight: 600;
}
.link-picker__actions {
  padding: 0.5rem 1rem;
  border-bottom: 1px solid color-mix(in oklab, var(--color-base-content) 18%, transparent);
}
.link-picker__search-input,
.link-picker__url-input {
  width: 100%;
  padding: 0.4rem 0.6rem;
  font-size: 0.9rem;
  border: 1px solid color-mix(in oklab, var(--color-base-content) 18%, transparent);
  border-radius: 0.25rem;
  background: var(--color-base-100);
  box-sizing: border-box;
}
.link-picker__error {
  background: color-mix(in oklab, var(--color-error) 12%, transparent);
  color: var(--color-error);
  font-size: 0.85rem;
  padding: 0.5rem 1rem;
}
.link-picker__loading,
.link-picker__empty {
  padding: 2rem;
  color: color-mix(in oklab, var(--color-base-content) 65%, transparent);
  text-align: center;
  font-size: 0.9rem;
}
.link-picker__truncated {
  padding: 0.5rem 1rem;
  font-size: 0.75rem;
  color: color-mix(in oklab, var(--color-base-content) 65%, transparent);
  text-align: center;
  border-top: 1px solid color-mix(in oklab, var(--color-base-content) 18%, transparent);
}
.link-picker__list {
  flex: 1;
  overflow-y: auto;
  padding: 0.5rem;
}
.link-picker__list-item {
  width: 100%;
  text-align: left;
  background: none;
  border: 0;
  padding: 0.5rem 0.75rem;
  border-radius: 0.25rem;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  color: var(--color-base-content);
}
.link-picker__list-item:hover {
  background: color-mix(in oklab, var(--color-base-content) 6%, transparent);
}
.link-picker__list-title {
  font-size: 0.9rem;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.link-picker__list-meta {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  font-size: 0.7rem;
  color: color-mix(in oklab, var(--color-base-content) 65%, transparent);
}
.link-picker__list-kind {
  background: color-mix(in oklab, var(--color-base-content) 18%, transparent);
  color: var(--color-base-content);
  border-radius: 999px;
  padding: 0 0.4rem;
  font-family: monospace;
}
.link-picker__list-path {
  font-family: monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* Section heading inside a target list (a workbook section, a wiki space). */
.link-picker__group {
  padding: 0.5rem 0.75rem 0.25rem;
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: color-mix(in oklab, var(--color-base-content) 55%, transparent);
}
/* Which app the second step is showing, next to the back button. */
.link-picker__crumb {
  font-size: 0.8rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: color-mix(in oklab, var(--color-base-content) 75%, transparent);
}
.link-picker__url-form {
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.link-picker__url-checkbox {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.85rem;
  color: var(--color-base-content);
  cursor: pointer;
}
.link-picker__url-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.link-picker__url-spacer { flex: 1; }
.link-picker__btn {
  padding: 0.4rem 0.9rem;
  border: 1px solid color-mix(in oklab, var(--color-base-content) 18%, transparent);
  border-radius: 0.25rem;
  background: color-mix(in oklab, var(--color-base-content) 6%, transparent);
  cursor: pointer;
  font-size: 0.85rem;
  color: var(--color-base-content);
}
.link-picker__btn:hover:not(:disabled) {
  background: var(--color-base-100);
}
.link-picker__btn:disabled { opacity: 0.5; cursor: not-allowed; }
.link-picker__btn--primary {
  background: var(--color-primary);
  color: var(--color-primary-content);
  border-color: var(--color-primary);
}
.link-picker__btn--primary:hover:not(:disabled) {
  background: color-mix(in oklab, var(--color-primary) 85%, transparent);
}
.link-picker__btn--danger {
  background: transparent;
  color: var(--color-error);
  border-color: var(--color-error);
}
.link-picker__btn--danger:hover {
  background: color-mix(in oklab, var(--color-error) 12%, transparent);
}
</style>
