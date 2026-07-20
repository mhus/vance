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
  brainFetchText,
  brainSendRaw,
  documentContentUrl,
  postComposeRun,
  pollComposeRun,
  cancelComposeRun,
  useDocumentPrefixReaction,
} from '@vance/shared';
import {
  WorkPageEditor,
  parseDocument,
  type ComposeRunResult,
} from '@vance/block-editor';
import {
  scanWiki,
  rebuildWiki,
  createWikiPage,
  deleteWikiPage,
  resolveWikiLink as resolveWikiLinkApi,
  searchWikiDocuments,
} from './api';
import { slugify, targetName } from './slug';
import WikiNotesPanel from './WikiNotesPanel.vue';
import WikiVersionsPanel from './WikiVersionsPanel.vue';
import WikiBacklinksPanel from './WikiBacklinksPanel.vue';
import type { WikiView } from './generated/wiki/WikiView';
import type { WikiPageView } from './generated/wiki/WikiPageView';
import type { WikiSpaceView } from './generated/wiki/WikiSpaceView';
import type { WikiDocumentItem } from './generated/wiki/WikiDocumentItem';

/**
 * Wiki application view — a name-addressed link graph over `kind: workpage`
 * pages. Unlike the workbook (curated tree, left sidebar), the wiki uses a
 * TOP-NAV + full-width layout:
 *
 *  ┌──────────────────────────────────────────────────────────────┐
 *  │ Title │ Space ▾ │ 🔍 search │ ＋ New │ 📄 Index │ ↻ │ ▸ Panel │
 *  ├──────────────────────────────────────────────────────────────┤
 *  │ full-width WorkPageEditor (page content)          │ Notes /   │
 *  │                                                    │ Versions  │
 *  └──────────────────────────────────────────────────────────────┘
 *
 * `[[Wikilinks]]` resolve space-aware via the server; a red (missing) link
 * creates the page in the current space on click. See planning/app-wiki.md.
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
const title = computed(
  () => view.value?.title ?? props.document.title ?? folder.value,
);

const view = ref<WikiView | null>(null);
const error = ref<string | null>(null);
const loading = ref(false);
const rebuilding = ref(false);

const activePageId = ref<string | null>(null);
const activePageView = ref<WikiPageView | null>(null);
const activeMarkdown = ref<string | null>(null);
const pageLoading = ref(false);
const pageError = ref<string | null>(null);

/** The space the user is currently browsing — drives resolve/create. */
const currentSpace = ref<string>('');

// ── Host-injected renderers (pass-through so embeds/forms/compose render).
const embedComponent = inject<Component | null>('vance:embed-component', null);
const formComponent = inject<Component | null>('vance:form-component', null);
const composeOutputComponent = inject<Component | null>('vance:compose-output-component', null);
const sessionId = inject<Ref<string | null>>('vance:session-id', ref(null));

type SaveStatus = 'idle' | 'dirty' | 'saving' | 'saved' | 'error';
const saveStatus = ref<SaveStatus>('idle');
const lastSaveError = ref<string | null>(null);

const editorRef = ref<{
  save: () => void;
  flush: () => boolean;
} | null>(null);

// ── Self-write quiet window — mirrors WorkbookAppKind. Suppresses the WS
// echo of our own save so the ProseMirror doc (and cursor) isn't rebuilt.
const lastSavedBodies = ref<Map<string, string>>(new Map());
const SELF_WRITE_QUIET_MS = 3000;
const lastSelfWriteAt = ref<Map<string, number>>(new Map());
function withinSelfWriteWindow(id: string): boolean {
  const t = lastSelfWriteAt.value.get(id);
  return t != null && Date.now() - t < SELF_WRITE_QUIET_MS;
}

// ── Derived indexes ────────────────────────────────────────────────
// Known page slugs — the synchronous red-link check. A `[[Target]]`
// resolves (non-red) when its leaf slug is a known page slug.
const knownSlugs = computed<Set<string>>(() => {
  const s = new Set<string>();
  for (const p of view.value?.pages ?? []) s.add(p.slug);
  return s;
});

// All generated-index document ids (root + each space). Editing them is
// pointless — a rebuild rewrites them — so the editor is read-only there.
const indexIds = computed<Set<string>>(() => {
  const s = new Set<string>();
  const v = view.value;
  if (!v) return s;
  if (v.indexPageId) s.add(v.indexPageId);
  for (const sp of v.spaces) if (sp.indexId) s.add(sp.indexId);
  return s;
});
const activeIsIndex = computed(
  () => activePageId.value != null && indexIds.value.has(activePageId.value),
);
const editorEditable = computed(() => !activeIsIndex.value);

const spaces = computed<WikiSpaceView[]>(() => view.value?.spaces ?? []);
const currentSpaceView = computed<WikiSpaceView | null>(
  () => spaces.value.find((s) => s.name === currentSpace.value) ?? null,
);

// Title shown above the editor — front-matter title of the loaded body,
// else the page view's title.
const parsedTitle = computed(() =>
  activeMarkdown.value == null ? null : parseDocument(activeMarkdown.value).title,
);
const pageDisplayTitle = computed(
  () => parsedTitle.value ?? activePageView.value?.title ?? '',
);

// ── Load / scan ────────────────────────────────────────────────────
async function loadWiki(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    view.value = await scanWiki(projectId.value, folder.value);
    if (activePageId.value == null) {
      pickInitialPage();
    } else {
      activePageView.value =
        findPageById(activePageId.value)
        ?? syntheticIndexView(activePageId.value)
        ?? null;
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not scan wiki.';
    view.value = null;
  } finally {
    loading.value = false;
  }
}

function pickInitialPage(): void {
  const v = view.value;
  if (!v) return;
  const urlRef = pageRefFromUrl();
  if (urlRef) {
    const hit = findByRef(urlRef);
    if (hit) {
      void selectPage(hit.id, hit.page, 'replace');
      return;
    }
  }
  if (v.mainPageId) {
    void selectPage(v.mainPageId, findPageById(v.mainPageId), 'replace');
    return;
  }
  // No curated home yet — don't silently open some other page. Leave the view
  // unselected so the "create the main page" prompt shows (see template).
  // Users still reach the index / other pages via the top-nav.
}

function findPageById(id: string): WikiPageView | null {
  return view.value?.pages.find((p) => p.id === id) ?? null;
}

/**
 * The generated `_index.md` files are filtered out of {@code view.pages}
 * (underscore prefix). Synthesise a minimal {@link WikiPageView} so an
 * index opens like any other page.
 */
function syntheticIndexView(id: string): WikiPageView | null {
  const v = view.value;
  if (!v) return null;
  if (v.indexPageId === id && v.indexPagePath) {
    return indexView(id, v.indexPagePath, '');
  }
  for (const sp of v.spaces) {
    if (sp.indexId === id && sp.indexPath) {
      return indexView(id, sp.indexPath, sp.name);
    }
  }
  return null;
}
function indexView(id: string, path: string, space: string): WikiPageView {
  const rel = path.startsWith(folder.value + '/')
    ? path.substring(folder.value.length + 1)
    : path;
  return { id, path, relativePath: rel, space, slug: '_index', title: 'Index', main: false };
}

// ── Page selection + URL sync ──────────────────────────────────────
// The URL carries the page by its space-qualified SLUG (`page=main`,
// `page=ops/deploys`), not its Mongo id — human-readable, deep-linkable and
// matching the `[[Wikilink]]` notation. Generated indexes use the `_index`
// slug (`page=_index`, `page=ops/_index`). The host owns `?doc=<container>`
// at the tab level; `?page` is the wiki's own sub-navigation.
const URL_PAGE_PARAM = 'page';

/** Space-qualified slug of a page/index view — the value used in `?page=`. */
function refFor(p: { space: string; slug: string } | null): string | null {
  if (!p) return null;
  return p.space ? `${p.space}/${p.slug}` : p.slug;
}

function pageRefFromUrl(): string | null {
  return new URLSearchParams(window.location.search).get(URL_PAGE_PARAM);
}

/** Resolve a `?page=` ref to a selectable page id (+ view when known). */
function findByRef(ref: string): { id: string; page: WikiPageView | null } | null {
  const v = view.value;
  if (!v) return null;
  const p = v.pages.find((q) => refFor(q) === ref);
  if (p) return { id: p.id, page: p };
  if (ref === '_index' && v.indexPageId) return { id: v.indexPageId, page: null };
  for (const sp of v.spaces) {
    if (sp.indexId && ref === (sp.name ? `${sp.name}/_index` : '_index')) {
      return { id: sp.indexId, page: null };
    }
  }
  return null;
}

function syncPageToUrl(ref: string | null, mode: 'push' | 'replace'): void {
  const params = new URLSearchParams(window.location.search);
  if (ref) params.set(URL_PAGE_PARAM, ref);
  else params.delete(URL_PAGE_PARAM);
  const query = params.toString();
  const next = `${window.location.pathname}${query ? `?${query}` : ''}`;
  const current = `${window.location.pathname}${window.location.search}`;
  if (next === current) return;
  const state = window.history.state;
  if (mode === 'push') window.history.pushState(state, '', next);
  else window.history.replaceState(state, '', next);
}

async function selectPage(
  id: string,
  page: WikiPageView | null,
  history: 'push' | 'replace' | 'none' = 'push',
): Promise<void> {
  if (id === activePageId.value) return;
  if (editorRef.value?.flush()) await nextTick();
  activePageId.value = id;
  const resolved = page ?? findPageById(id) ?? syntheticIndexView(id);
  activePageView.value = resolved;
  if (resolved) currentSpace.value = resolved.space;
  saveStatus.value = 'idle';
  lastSaveError.value = null;
  if (history !== 'none') syncPageToUrl(refFor(resolved), history);
  await loadActivePageContent();
}

function onWikiPopState(): void {
  const v = view.value;
  if (!v) return;
  const ref = pageRefFromUrl();
  const hit = ref ? findByRef(ref) : null;
  const targetId = hit?.id ?? v.mainPageId ?? null;
  if (!targetId || targetId === activePageId.value) return;
  void selectPage(targetId, hit?.page ?? findPageById(targetId), 'none');
}

async function loadActivePageContent(options: { force?: boolean } = {}): Promise<void> {
  if (!activePageId.value) {
    activeMarkdown.value = null;
    return;
  }
  pageLoading.value = true;
  pageError.value = null;
  try {
    const id = activePageId.value;
    if (!options.force && withinSelfWriteWindow(id)) return;
    const fresh = await brainFetchText(`documents/${encodeURIComponent(id)}/content`);
    const ours = lastSavedBodies.value.get(id);
    if (!options.force && ours != null && fresh === ours) return;
    activeMarkdown.value = fresh;
  } catch (e) {
    pageError.value = e instanceof Error ? e.message : 'Could not load page.';
    activeMarkdown.value = null;
  } finally {
    pageLoading.value = false;
  }
}

async function onEditorSave(body: string): Promise<void> {
  if (!activePageId.value) return;
  const id = activePageId.value;
  saveStatus.value = 'saving';
  lastSavedBodies.value.set(id, body);
  lastSelfWriteAt.value.set(id, Date.now());
  try {
    await brainSendRaw<unknown>(
      'PUT',
      `documents/${encodeURIComponent(id)}/content`,
      body,
      'text/markdown',
    );
    lastSelfWriteAt.value.set(id, Date.now());
    if (id === activePageId.value) {
      saveStatus.value = 'saved';
      lastSaveError.value = null;
    }
  } catch (e) {
    if (id === activePageId.value) {
      saveStatus.value = 'error';
      lastSaveError.value = e instanceof Error ? e.message : 'Save failed.';
    }
  }
}

function onEditorDirty(dirty: boolean): void {
  if (dirty) {
    saveStatus.value = 'dirty';
    if (activePageId.value) lastSelfWriteAt.value.set(activePageId.value, Date.now());
  }
}

// ── Image upload + vance: resolution (verbatim from WorkbookAppKind) ──
async function uploadImage(file: File): Promise<string | null> {
  const assetsFolder = `${folder.value}/assets`;
  const ts = Date.now();
  const safe = file.name
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, '_')
    .replace(/^_+|_+$/g, '');
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
    console.error('[Wiki] image upload failed', e);
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

/** In-app routing for `vance:` hrefs — in-wiki links switch the page. */
function openVanceLink(href: string, openInNewTab: boolean): boolean {
  if (!href.startsWith('vance:')) return false;
  let parsed: URL;
  try { parsed = new URL(href); } catch { return false; }
  if (parsed.protocol !== 'vance:') return false;
  const targetProject = parsed.hostname ? decodeURIComponent(parsed.hostname) : projectId.value;
  const path = decodeURIComponent(parsed.pathname.replace(/^\//, ''));
  if (!path) return false;

  if (targetProject === projectId.value) {
    const v = view.value;
    const page = v?.pages.find((p) => p.path === path) ?? null;
    if (page) { void selectPage(page.id, page); return true; }
    if (v) {
      if (v.indexPagePath === path && v.indexPageId) { void selectPage(v.indexPageId, null); return true; }
      for (const sp of v.spaces) {
        if (sp.indexPath === path && sp.indexId) { void selectPage(sp.indexId, null); return true; }
      }
    }
  }

  const newTab = openInNewTab ? window.open('about:blank', '_blank') : null;
  void (async () => {
    try {
      const dto = await brainFetch<{ id: string }>(
        'GET',
        `documents/by-path?projectId=${encodeURIComponent(targetProject)}&path=${encodeURIComponent(path)}`,
      );
      const cur = new URL(window.location.href);
      cur.searchParams.set('doc', dto.id);
      if (targetProject !== projectId.value) {
        cur.searchParams.set('projectId', targetProject);
        cur.searchParams.delete('sessionId');
      }
      const finalUrl = cur.toString();
      if (newTab) newTab.location.href = finalUrl;
      else window.location.href = finalUrl;
    } catch (e) {
      console.warn('[Wiki] vance: link could not be resolved', path, e);
      if (newTab) newTab.close();
    }
  })();
  return true;
}

// ── Wikilink callbacks (the wiki core) ─────────────────────────────
function resolveWikiLinkSync(target: string): boolean {
  return knownSlugs.value.has(slugify(targetName(target)));
}

async function openWikiLink(target: string): Promise<void> {
  try {
    const resp = await resolveWikiLinkApi(
      projectId.value, folder.value, target, currentSpace.value,
    );
    if (resp.exists && resp.id) {
      await selectPage(resp.id, findPageById(resp.id));
      return;
    }
    const name = targetName(target).trim() || target;
    const where = resp.createSpace ? ` in space "${resp.createSpace}"` : '';
    if (!window.confirm(`Page "${name}" does not exist. Create it${where}?`)) return;
    const created = await createWikiPage(projectId.value, folder.value, {
      title: name,
      ...(resp.createSpace ? { space: resp.createSpace } : {}),
    });
    await loadWiki();
    await selectPage(created.id, created);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not open wiki link.';
  }
}

// ── Compose (generic Damogran runner; not wiki-specific) ───────────
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

// ── Top-nav actions ────────────────────────────────────────────────
function openSpace(name: string): void {
  currentSpace.value = name;
  const sp = spaces.value.find((s) => s.name === name);
  const target =
    sp?.mainId
    ?? sp?.indexId
    ?? view.value?.pages.find((p) => p.space === name)?.id
    ?? null;
  if (target) void selectPage(target, findPageById(target));
}

/** Jump to the wiki's curated home (root `main`). */
function openHome(): void {
  const id = view.value?.mainPageId ?? null;
  if (id) { void selectPage(id, findPageById(id)); return; }
  // No curated main yet — clear the selection so the "create main" prompt
  // shows instead of navigating to some other page.
  activePageId.value = null;
  activePageView.value = null;
  activeMarkdown.value = null;
  syncPageToUrl(null, 'push');
}

/**
 * Create the wiki's home page (`main.md`). The slug must be exactly `main`
 * for the folder-reader to recognise it as the home, so the title is "Main"
 * (slugifies to `main`); the heading is editable afterwards. Opens it.
 */
async function createMain(): Promise<void> {
  creating.value = true;
  error.value = null;
  try {
    const page = await createWikiPage(projectId.value, folder.value, { title: 'Main' });
    await loadWiki();
    await selectPage(page.id, page);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not create the main page.';
  } finally {
    creating.value = false;
  }
}

function openCurrentIndex(): void {
  const id = currentSpaceView.value?.indexId ?? view.value?.indexPageId ?? null;
  if (id) void selectPage(id, null);
}

async function rebuild(): Promise<void> {
  rebuilding.value = true;
  try {
    if (editorRef.value?.flush()) await nextTick();
    await rebuildWiki(projectId.value, folder.value);
    await loadWiki();
    await loadActivePageContent({ force: true });
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Rebuild failed.';
  } finally {
    rebuilding.value = false;
  }
}

// ── New page ───────────────────────────────────────────────────────
const newPageOpen = ref(false);
const newPageTitle = ref('');
const newPageError = ref<string | null>(null);
const creating = ref(false);
const newPageInputRef = ref<HTMLInputElement | null>(null);

async function openNewPage(): Promise<void> {
  newPageOpen.value = true;
  newPageTitle.value = '';
  newPageError.value = null;
  await nextTick();
  newPageInputRef.value?.focus();
}
function closeNewPage(): void {
  newPageOpen.value = false;
  newPageError.value = null;
  creating.value = false;
}
async function submitNewPage(): Promise<void> {
  const t = newPageTitle.value.trim();
  if (!t) { newPageError.value = 'Title required'; return; }
  creating.value = true;
  newPageError.value = null;
  try {
    const page = await createWikiPage(projectId.value, folder.value, {
      title: t,
      ...(currentSpace.value ? { space: currentSpace.value } : {}),
    });
    closeNewPage();
    await loadWiki();
    await selectPage(page.id, page);
  } catch (e) {
    newPageError.value = e instanceof Error ? e.message : 'Could not create page.';
    creating.value = false;
  }
}

async function deleteActivePage(): Promise<void> {
  const p = activePageView.value;
  if (!p || activeIsIndex.value) return;
  if (!window.confirm(`Delete page "${p.title}"?`)) return;
  try {
    await deleteWikiPage(projectId.value, folder.value, p.id);
    activePageId.value = null;
    activePageView.value = null;
    activeMarkdown.value = null;
    await loadWiki();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Delete failed.';
  }
}

// ── Search ─────────────────────────────────────────────────────────
const searchQuery = ref('');
const searchResults = ref<WikiDocumentItem[]>([]);
const searchOpen = ref(false);
const searching = ref(false);

async function runSearch(): Promise<void> {
  const q = searchQuery.value.trim();
  if (!q) { searchResults.value = []; searchOpen.value = false; return; }
  searching.value = true;
  try {
    const resp = await searchWikiDocuments(projectId.value, q, folder.value);
    searchResults.value = resp.items ?? [];
    searchOpen.value = true;
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Search failed.';
  } finally {
    searching.value = false;
  }
}
function pickSearchResult(item: WikiDocumentItem): void {
  searchOpen.value = false;
  searchQuery.value = '';
  const page = findPageById(item.id);
  if (page) { void selectPage(page.id, page); return; }
  openVanceLink(`vance:/${encodeURI(item.path)}`, false);
}

// Wiki red-link-from-search: when the typed name matches no existing page
// slug, offer to create it — the "page doesn't exist yet, create it now"
// affordance every wiki has. Creates in the current space and opens it.
const searchCreateName = computed(() => searchQuery.value.trim());
const searchCanCreate = computed(
  () =>
    searchOpen.value
    && searchCreateName.value.length > 0
    && !knownSlugs.value.has(slugify(searchCreateName.value)),
);

async function createFromSearch(): Promise<void> {
  const t = searchCreateName.value;
  if (!t) return;
  creating.value = true;
  try {
    const page = await createWikiPage(projectId.value, folder.value, {
      title: t,
      ...(currentSpace.value ? { space: currentSpace.value } : {}),
    });
    searchOpen.value = false;
    searchQuery.value = '';
    searchResults.value = [];
    await loadWiki();
    await selectPage(page.id, page);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not create page.';
  } finally {
    creating.value = false;
  }
}

// ── Right panel ────────────────────────────────────────────────────
type RightTab = 'notes' | 'versions' | 'backlinks';
const rightOpen = ref(false);
const rightTab = ref<RightTab>('notes');
function toggleRight(tab: RightTab): void {
  if (rightOpen.value && rightTab.value === tab) rightOpen.value = false;
  else { rightTab.value = tab; rightOpen.value = true; }
}

// ── Live watch ─────────────────────────────────────────────────────
useDocumentPrefixReaction({
  prefix: computed(() => `${folder.value}/`),
  debounceMs: 250,
  onRemoteChange: async (paths) => {
    const activeId = activePageId.value;
    if (activeId != null && withinSelfWriteWindow(activeId)) return;
    await loadWiki();
    const v = view.value;
    if (!v || !activePageId.value) return;
    const activePath = activePageView.value?.path ?? null;
    if (activePath && paths.includes(activePath)) await loadActivePageContent();
  },
});

watch(folder, () => {
  activePageId.value = null;
  activePageView.value = null;
  activeMarkdown.value = null;
  currentSpace.value = '';
  void loadWiki();
});

onMounted(() => {
  window.addEventListener('popstate', onWikiPopState);
  void loadWiki();
});
onBeforeUnmount(() => {
  window.removeEventListener('popstate', onWikiPopState);
});

const saveStatusLabel = computed<string | null>(() => {
  switch (saveStatus.value) {
    case 'dirty': return 'Edited';
    case 'saving': return 'Saving…';
    case 'saved': return 'Saved';
    case 'error': return lastSaveError.value ?? 'Save failed';
    default: return null;
  }
});

// Remount the editor when the active page changes (Tiptap owns one PM
// instance per mount).
const editorKey = computed(() => activePageId.value ?? 'empty');
</script>

<template>
  <div class="wiki-app">
    <!-- Top nav -->
    <header class="wiki-app__topbar">
      <div class="wiki-app__brand" :title="folder">{{ title }}</div>

      <label class="wiki-app__space">
        <span class="wiki-app__space-caret">▾</span>
        <select
          class="wiki-app__space-select"
          :value="currentSpace"
          @change="openSpace(($event.target as HTMLSelectElement).value)"
        >
          <option
            v-for="s in spaces"
            :key="s.name"
            :value="s.name"
          >{{ s.title }} ({{ s.pageCount }})</option>
        </select>
      </label>

      <div class="wiki-app__search">
        <input
          v-model="searchQuery"
          type="search"
          class="wiki-app__search-input"
          placeholder="Search pages…"
          @keydown.enter.prevent="runSearch"
          @keydown.escape="searchOpen = false"
        />
        <button class="wiki-app__btn" :disabled="searching" @click="runSearch">🔍</button>
        <div v-if="searchOpen" class="wiki-app__search-results">
          <ul v-if="searchResults.length" class="wiki-app__search-list">
            <li
              v-for="r in searchResults"
              :key="r.id"
              class="wiki-app__search-row"
              @click="pickSearchResult(r)"
            >
              <span class="wiki-app__search-title">{{ r.title || r.path }}</span>
              <span class="wiki-app__search-path">{{ r.path }}</span>
            </li>
          </ul>
          <div v-else class="wiki-app__search-empty">No matching page.</div>
          <button
            v-if="searchCanCreate"
            type="button"
            class="wiki-app__search-create"
            :disabled="creating"
            @click="createFromSearch"
          >
            ＋ Create page “{{ searchCreateName }}”{{ currentSpace ? ` in ${currentSpace}` : '' }}
          </button>
        </div>
      </div>

      <span class="wiki-app__spacer" />

      <span v-if="saveStatusLabel" class="wiki-app__save" :class="`wiki-app__save--${saveStatus}`">
        {{ saveStatusLabel }}
      </span>

      <button class="wiki-app__btn" title="New page" :disabled="creating" @click="openNewPage">＋ New</button>
      <button class="wiki-app__btn" title="Home — main page" @click="openHome">🏠</button>
      <button class="wiki-app__btn" title="Index — generated page list" @click="openCurrentIndex">📄</button>
      <button
        class="wiki-app__btn"
        :disabled="rebuilding"
        :title="rebuilding ? 'Rebuilding…' : 'Rebuild indexes + backlinks'"
        @click="rebuild"
      >{{ rebuilding ? '…' : '↻' }}</button>
      <button
        class="wiki-app__btn"
        :class="{ 'wiki-app__btn--active': rightOpen && rightTab === 'backlinks' }"
        title="What links here"
        @click="toggleRight('backlinks')"
      >🔗</button>
      <button
        class="wiki-app__btn"
        :class="{ 'wiki-app__btn--active': rightOpen && rightTab === 'notes' }"
        title="Notes"
        @click="toggleRight('notes')"
      >📝</button>
      <button
        class="wiki-app__btn"
        :class="{ 'wiki-app__btn--active': rightOpen && rightTab === 'versions' }"
        title="Versions"
        @click="toggleRight('versions')"
      >🕐</button>
    </header>

    <!-- New-page inline form -->
    <form v-if="newPageOpen" class="wiki-app__newpage" @submit.prevent="submitNewPage">
      <input
        ref="newPageInputRef"
        v-model="newPageTitle"
        type="text"
        class="wiki-app__newpage-input"
        :placeholder="currentSpace ? `New page in ${currentSpace}…` : 'New page title…'"
        :disabled="creating"
        @keydown.escape="closeNewPage"
      />
      <button type="submit" class="wiki-app__btn wiki-app__btn--primary" :disabled="creating || !newPageTitle.trim()">
        {{ creating ? 'Creating…' : 'Create' }}
      </button>
      <button type="button" class="wiki-app__btn" :disabled="creating" @click="closeNewPage">Cancel</button>
      <span v-if="newPageError" class="wiki-app__error-inline">{{ newPageError }}</span>
    </form>

    <div v-if="error" class="wiki-app__error">{{ error }}</div>

    <!-- Body -->
    <div class="wiki-app__body">
      <main class="wiki-app__main">
        <div v-if="loading" class="wiki-app__hint">Loading wiki…</div>
        <div v-else-if="!activePageId && view && !view.mainPageId" class="wiki-app__missing">
          <div class="wiki-app__missing-title">The home page “main” doesn’t exist yet.</div>
          <p class="wiki-app__missing-text">
            A wiki opens on its <code>main</code> page. Create it now — or open the
            <button type="button" class="wiki-app__linkbtn" @click="openCurrentIndex">index</button>.
          </p>
          <button
            type="button"
            class="wiki-app__btn wiki-app__btn--primary"
            :disabled="creating"
            @click="createMain"
          >{{ creating ? 'Creating…' : '＋ Create “main” page' }}</button>
        </div>
        <div v-else-if="!activePageId" class="wiki-app__hint">
          No page selected. Create one with ＋ New.
        </div>
        <template v-else>
          <div class="wiki-app__pagehead">
            <span class="wiki-app__pagetitle">{{ pageDisplayTitle }}</span>
            <span v-if="activeIsIndex" class="wiki-app__badge">generated · read-only</span>
            <span class="wiki-app__spacer" />
            <button
              v-if="!activeIsIndex && !(activePageView && activePageView.main)"
              class="wiki-app__btn wiki-app__btn--danger"
              title="Delete page"
              @click="deleteActivePage"
            >🗑</button>
          </div>
          <div v-if="pageError" class="wiki-app__error">{{ pageError }}</div>
          <div v-else-if="pageLoading" class="wiki-app__hint">Loading page…</div>
          <WorkPageEditor
            v-else-if="activeMarkdown != null && activePageView"
            :key="editorKey"
            ref="editorRef"
            :document="{ id: activePageId, path: activePageView.path, projectId, title: activePageView.title }"
            :source="activeMarkdown"
            :editable="editorEditable"
            :current-project-id="projectId"
            :upload-image="uploadImage"
            :resolve-image-src="resolveVanceImageSrc"
            :open-link="openVanceLink"
            :resolve-wiki-link="resolveWikiLinkSync"
            :open-wiki-link="openWikiLink"
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
      </main>

      <aside v-if="rightOpen" class="wiki-app__right">
        <WikiBacklinksPanel
          v-if="rightTab === 'backlinks'"
          :project-id="projectId"
          :folder="folder"
          :path="activePageView?.path ?? null"
          @navigate="(p) => selectPage(p.id, p)"
        />
        <WikiNotesPanel v-else-if="rightTab === 'notes'" :document-id="activePageId" />
        <WikiVersionsPanel
          v-else
          :document-id="activePageId"
          @restored="loadActivePageContent({ force: true })"
        />
      </aside>
    </div>
  </div>
</template>

<style scoped>
.wiki-app {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.wiki-app__topbar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.4rem 0.75rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.15);
  background: hsl(var(--b1));
}
.wiki-app__brand {
  font-weight: 700;
  font-size: 0.95rem;
  max-width: 16rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wiki-app__space {
  display: inline-flex;
  align-items: center;
  position: relative;
}
.wiki-app__space-caret {
  position: absolute;
  right: 0.4rem;
  pointer-events: none;
  opacity: 0.5;
  font-size: 0.7rem;
}
.wiki-app__space-select {
  appearance: none;
  border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 6px;
  background: transparent;
  padding: 0.2rem 1.4rem 0.2rem 0.5rem;
  font-size: 0.8rem;
  cursor: pointer;
  max-width: 14rem;
}
.wiki-app__search {
  position: relative;
  display: flex;
  align-items: center;
  gap: 0.25rem;
}
.wiki-app__search-input {
  border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 6px;
  background: transparent;
  padding: 0.2rem 0.5rem;
  font-size: 0.8rem;
  width: 12rem;
}
.wiki-app__search-results {
  position: absolute;
  top: 100%;
  left: 0;
  margin-top: 0.25rem;
  min-width: 18rem;
  max-height: 20rem;
  overflow-y: auto;
  list-style: none;
  padding: 0.25rem;
  background: hsl(var(--b1));
  border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 8px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.18);
  z-index: 50;
}
.wiki-app__search-list { list-style: none; margin: 0; padding: 0; }
.wiki-app__search-empty { padding: 0.6rem; font-size: 0.8rem; opacity: 0.6; }
.wiki-app__search-create {
  display: block;
  width: 100%;
  text-align: left;
  margin-top: 0.25rem;
  padding: 0.45rem 0.5rem;
  border: none;
  border-top: 1px solid hsl(var(--bc) / 0.12);
  background: transparent;
  cursor: pointer;
  font-size: 0.82rem;
  color: hsl(var(--p));
}
.wiki-app__search-create:hover:not(:disabled) { background: hsl(var(--bc) / 0.08); }
.wiki-app__search-create:disabled { opacity: 0.5; cursor: default; }
.wiki-app__search-row {
  display: flex;
  flex-direction: column;
  padding: 0.35rem 0.5rem;
  border-radius: 6px;
  cursor: pointer;
}
.wiki-app__search-row:hover { background: hsl(var(--bc) / 0.08); }
.wiki-app__search-title { font-size: 0.85rem; font-weight: 600; }
.wiki-app__search-path { font-size: 0.7rem; opacity: 0.55; }
.wiki-app__spacer { flex: 1; }
.wiki-app__save { font-size: 0.72rem; opacity: 0.7; }
.wiki-app__save--error { color: #d33; opacity: 1; }
.wiki-app__save--saved { color: #2a8; }
.wiki-app__btn {
  border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 6px;
  background: transparent;
  padding: 0.2rem 0.55rem;
  font-size: 0.8rem;
  cursor: pointer;
  white-space: nowrap;
}
.wiki-app__btn:hover:not(:disabled) { background: hsl(var(--bc) / 0.08); }
.wiki-app__btn:disabled { opacity: 0.5; cursor: default; }
.wiki-app__btn--active { background: hsl(var(--bc) / 0.14); }
.wiki-app__btn--primary { border-color: hsl(var(--p) / 0.6); }
.wiki-app__btn--danger:hover:not(:disabled) { background: rgba(221, 51, 51, 0.12); }
.wiki-app__newpage {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.4rem 0.75rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.1);
}
.wiki-app__newpage-input {
  border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 6px;
  background: transparent;
  padding: 0.25rem 0.5rem;
  font-size: 0.85rem;
  min-width: 18rem;
}
.wiki-app__error {
  padding: 0.5rem 0.75rem;
  color: #d33;
  font-size: 0.82rem;
}
.wiki-app__error-inline { color: #d33; font-size: 0.78rem; }
.wiki-app__hint {
  padding: 2rem;
  text-align: center;
  opacity: 0.6;
  font-size: 0.85rem;
}
.wiki-app__missing {
  max-width: 32rem;
  margin: 3rem auto;
  padding: 1.5rem;
  text-align: center;
  border: 1px dashed hsl(var(--bc) / 0.25);
  border-radius: 10px;
}
.wiki-app__missing-title { font-size: 1.05rem; font-weight: 600; margin-bottom: 0.5rem; }
.wiki-app__missing-text { font-size: 0.85rem; opacity: 0.75; margin-bottom: 1rem; }
.wiki-app__linkbtn {
  border: none;
  background: none;
  padding: 0;
  color: hsl(var(--p));
  cursor: pointer;
  text-decoration: underline;
  font: inherit;
}
.wiki-app__body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.wiki-app__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}
.wiki-app__pagehead {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.75rem 1.5rem 0.25rem;
}
.wiki-app__pagetitle { font-size: 1.4rem; font-weight: 700; }
.wiki-app__badge {
  font-size: 0.68rem;
  padding: 0.1rem 0.4rem;
  border-radius: 4px;
  background: hsl(var(--bc) / 0.1);
  opacity: 0.8;
}
.wiki-app__right {
  width: 320px;
  flex-shrink: 0;
  border-left: 1px solid hsl(var(--bc) / 0.15);
  background: hsl(var(--b1));
  min-height: 0;
  overflow: hidden;
}
</style>
