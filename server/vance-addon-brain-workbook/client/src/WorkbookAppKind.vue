<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, onMounted, provide, ref, watch, type Component } from 'vue';
import {
  brainFetch,
  brainFetchText,
  brainSendRaw,
  documentContentUrl,
  useDocumentPrefixReaction,
} from '@vance/shared';
import { WorkPageEditor, parseDocument } from '@vance/block-editor';
import {
  scanWorkbook,
  rebuildWorkbook,
  createWorkbookPage,
  updateWorkbookPage,
  deleteWorkbookPage,
  reorderWorkbookPages,
  renameWorkbookSection,
  duplicateWorkbookPage,
  setWorkbookLandingPage,
} from './api';
import AssetPickerModal from './AssetPickerModal.vue';
import EmojiPickerModal from './EmojiPickerModal.vue';
import LinkPickerModal from './LinkPickerModal.vue';
import EmbedPickerModal from './EmbedPickerModal.vue';
import FormPickerModal from './FormPickerModal.vue';
import InputPickerModal from './InputPickerModal.vue';
import type { WorkbookView } from './generated/workbook/WorkbookView';
import type { WorkbookPageView } from './generated/workbook/WorkbookPageView';

/**
 * Workbook view — Master-Detail with inplace editing (Notion-style).
 *
 *  ┌────────────┬─────────────────────────────────────┐
 *  │            │                                     │
 *  │  Sidebar   │   Active page (WorkPageEditor, live)  │
 *  │  page list │                                     │
 *  │            │                                     │
 *  └────────────┴─────────────────────────────────────┘
 *
 *  - Sidebar: page-tree grouped by section. Click swaps the active
 *    page. Pending edits are flushed before the switch.
 *  - Right pane: Tiptap WorkPageEditor mounted with the page's Markdown
 *    source. Auto-saves debounced (~800ms after last keystroke) via
 *    PUT documents/{id}/content.
 *  - Initial selection: `landingPage` → `_index.md` → first page.
 *  - Save status indicator in the page header: "Saving…" / "Saved".
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
// Sidebar header — prefer the manifest title from `_app.yaml`
// (returned by /scan as view.title) over the container document's
// title field, since that's the one workbook authors edit. Final
// fallback is the folder name so the header is never blank.
const title = computed(
  () => view.value?.title ?? props.document.title ?? folder.value,
);

const view = ref<WorkbookView | null>(null);
const error = ref<string | null>(null);
const loading = ref(false);
const rebuilding = ref(false);

const activePageId = ref<string | null>(null);
const activePageView = ref<WorkbookPageView | null>(null);
const activeMarkdown = ref<string | null>(null);
const pageLoading = ref(false);
const pageError = ref<string | null>(null);

// Parsed front-matter of the active page — drives the header
// (icon, cover, title override, description override). Updated
// whenever activeMarkdown changes.
const parsedPage = computed(() =>
  activeMarkdown.value == null ? null : parseDocument(activeMarkdown.value),
);

// Local header cache — refreshed by onEditorSave with the freshly
// serialised front-matter so icon/cover edits show up immediately.
// Without this we'd have to wait for an activeMarkdown refresh, which
// the self-write quiet window deliberately suppresses to protect the
// cursor. Reset on page switch.
interface HeaderCache {
  icon: string | null;
  cover: string | null;
  title: string | null;
  description: string | null;
}
const headerCache = ref<HeaderCache | null>(null);

const pageIcon = computed(
  () => headerCache.value?.icon ?? parsedPage.value?.icon ?? null,
);
const pageCover = computed(
  () => headerCache.value?.cover ?? parsedPage.value?.cover ?? null,
);
const pageDisplayTitle = computed(
  () =>
    headerCache.value?.title
    ?? parsedPage.value?.title
    ?? activePageView.value?.title
    ?? '',
);
const pageDisplayDescription = computed(
  () =>
    headerCache.value?.description
    ?? parsedPage.value?.description
    ?? activePageView.value?.description
    ?? null,
);

type SaveStatus = 'idle' | 'dirty' | 'saving' | 'saved' | 'error';
const saveStatus = ref<SaveStatus>('idle');
const lastSaveError = ref<string | null>(null);

const editorRef = ref<{
  save: () => void;
  flush: () => boolean;
  insertImage: (src: string, alt: string) => void;
  updateHeader: (patch: {
    title?: string | null;
    description?: string | null;
    icon?: string | null;
    cover?: string | null;
  }) => void;
  getHeader: () => {
    title: string | null;
    description: string | null;
    icon: string | null;
    cover: string | null;
  };
  applyLink: (href: string, openInNewTab?: boolean) => void;
  clearLink: () => void;
  currentLinkHref: () => string | null;
  insertEmbed: (uri: string) => void;
  insertForm: (data: string) => void;
  insertInput: (data: string) => void;
} | null>(null);

// Asset picker is shared between two destinations: inline image
// insertion from the slash-menu (mode='content') and the cover image
// on the active page (mode='cover'). Tracking the mode here avoids
// passing it through WorkPageEditor — the editor itself doesn't care.
type AssetPickerMode = 'content' | 'cover';
const assetPickerOpen = ref(false);
const assetPickerMode = ref<AssetPickerMode>('content');
function openAssetPicker() {
  assetPickerMode.value = 'content';
  assetPickerOpen.value = true;
}
function openCoverPicker() {
  assetPickerMode.value = 'cover';
  assetPickerOpen.value = true;
}
function onAssetPick(src: string, alt: string) {
  if (assetPickerMode.value === 'cover') {
    editorRef.value?.updateHeader({ cover: src });
  } else {
    editorRef.value?.insertImage(src, alt);
  }
  assetPickerOpen.value = false;
}

// Link picker — modal with two tabs (project document search + direct
// URL). Opened by the editor's bubble-menu link button via the
// `openLinkPicker` callback. The modal emits the chosen href +
// "open in new tab" flag back; we call into the editor ref to apply
// the mark on the current selection.
const linkPickerOpen = ref(false);
const linkPickerInitialHref = ref<string | null>(null);
function openLinkPicker() {
  linkPickerInitialHref.value = editorRef.value?.currentLinkHref() ?? null;
  linkPickerOpen.value = true;
}
function closeLinkPicker() {
  linkPickerOpen.value = false;
  linkPickerInitialHref.value = null;
}
function onLinkPicked(href: string, openInNewTab: boolean) {
  editorRef.value?.applyLink(href, openInNewTab);
  closeLinkPicker();
}
function onLinkClear() {
  editorRef.value?.clearLink();
  closeLinkPicker();
}

// Kind-aware embed renderer — vance-face provides a component that
// takes a single `uri` prop and renders the full embed (mindmap /
// tree / chart / …) via the kind registry. We inject it by string
// key so this addon doesn't have to import vance-face directly.
// Null = vance-face not the host (e.g. running in notepad standalone
// outside cortex) → block-editor falls back to its kind-icon card.
const embedComponent = inject<Component | null>('vance:embed-component', null);

// Editable-form renderer — vance-face provides a component that takes a
// single `data` prop (the data doc URI) and renders the form via
// the shared form-engine. Injected by string key like embed-component;
// null = not in a vance-face host → block-editor shows a fallback.
const formComponent = inject<Component | null>('vance:form-component', null);

// Page mode (design vs work) — per app-instance, client-only, default
// "work" (the user enters data). Switching to "design" lets embedded
// forms edit their own field schema. Provided down the component tree
// so the deeply-nested VanceFormView NodeViews can inject it reactively
// without threading it through the block-editor.
type PageMode = 'design' | 'work';
const pageMode = ref<PageMode>('work');
provide('vance:page-mode', pageMode);
function togglePageMode() {
  pageMode.value = pageMode.value === 'work' ? 'design' : 'work';
}

// The generated `_index.md` is rewritten on every rebuild/recreate, so
// editing it would be silently discarded — keep it read-only regardless of
// page mode.
const activeIsIndex = computed(
  () => view.value?.indexPageId != null && activePageId.value === view.value.indexPageId,
);
const editorEditable = computed(() => pageMode.value === 'design' && !activeIsIndex.value);

// ── Embed picker (slash-command /embed) ───────────────────────────
const embedPickerOpen = ref(false);
function openEmbedPicker() { embedPickerOpen.value = true; }
function closeEmbedPicker() { embedPickerOpen.value = false; }
function onEmbedPicked(uri: string) {
  editorRef.value?.insertEmbed(uri);
  closeEmbedPicker();
}

// ── Form picker (slash-command /form) ─────────────────────────────
const formPickerOpen = ref(false);
function openFormPicker() { formPickerOpen.value = true; }
function closeFormPicker() { formPickerOpen.value = false; }
function onFormPicked(configUri: string) {
  editorRef.value?.insertForm(configUri);
  closeFormPicker();
}

// ── Input block (slash-command /input) ────────────────────────────
// Parse a vance: URI into { projectId, path }, falling back to the
// current project when no authority segment is present.
function parseVanceTarget(uri: string): { projectId: string; path: string } | null {
  let parsed: URL;
  try { parsed = new URL(uri); } catch { return null; }
  if (parsed.protocol !== 'vance:') return null;
  const target = parsed.hostname ? decodeURIComponent(parsed.hostname) : projectId.value;
  const path = decodeURIComponent(parsed.pathname.replace(/^\//, ''));
  if (!target || !path) return null;
  return { projectId: target, path };
}

async function loadInput(uri: string): Promise<string> {
  const t = parseVanceTarget(uri);
  if (!t) return '';
  const params = new URLSearchParams({ projectId: t.projectId, doc: t.path });
  const resp = await brainFetch<{ content: string }>('GET', `addon/workbook/input?${params}`);
  return resp.content ?? '';
}

async function saveInput(
  uri: string, content: string, saveScript: string, session: boolean,
): Promise<void> {
  const t = parseVanceTarget(uri);
  if (!t) throw new Error(`Invalid input URI: ${uri}`);
  const params = new URLSearchParams({ projectId: t.projectId, doc: t.path });
  if (saveScript && saveScript.trim()) {
    params.set('saveScript', saveScript.trim());
    if (session) params.set('session', 'true');
  }
  await brainFetch<void>('POST', `addon/workbook/input/save?${params}`, { body: { content } });
}

const inputPickerOpen = ref(false);
function openInputPicker() { inputPickerOpen.value = true; }
function closeInputPicker() { inputPickerOpen.value = false; }
function onInputPicked(uri: string) {
  editorRef.value?.insertInput(uri);
  closeInputPicker();
}

// ── Button block (slash /button) — run the button's script ────────
// A bare name resolves relative to the app folder; `vance:/…` is
// project-absolute. Runs server-side via the script/run endpoint.
async function runButtonScript(scriptRef: string): Promise<void> {
  let ref = scriptRef.trim();
  if (ref.startsWith('vance:')) ref = ref.slice('vance:'.length);
  const path = ref.startsWith('/')
    ? ref.slice(1)
    : (folder.value ? `${folder.value}/${ref}` : ref);
  const params = new URLSearchParams({ projectId: projectId.value, script: path });
  await brainFetch<void>('POST', `addon/workbook/script/run?${params}`, { body: {} });
}

/**
 * Embed NodeView resolver — turn a {@code vance:} URI into the
 * metadata the card needs (kind, title, path). Uses the existing
 * {@code /documents/by-path} lookup; null on 404 → NodeView shows
 * a "not found" placeholder.
 */
async function resolveEmbedDoc(uri: string): Promise<{
  id: string;
  path: string;
  title: string | null;
  kind: string | null;
  mimeType: string | null;
} | null> {
  let parsed: URL;
  try { parsed = new URL(uri); } catch { return null; }
  if (parsed.protocol !== 'vance:') return null;
  const target = parsed.hostname
    ? decodeURIComponent(parsed.hostname)
    : projectId.value;
  const path = decodeURIComponent(parsed.pathname.replace(/^\//, ''));
  if (!target || !path) return null;
  try {
    const dto = await brainFetch<{
      id: string; path: string; title?: string | null;
      kind?: string | null; mimeType?: string | null;
    }>(
      'GET',
      `documents/by-path?projectId=${encodeURIComponent(target)}` +
        `&path=${encodeURIComponent(path)}`,
    );
    return {
      id: dto.id,
      path: dto.path,
      title: dto.title ?? null,
      kind: dto.kind ?? null,
      mimeType: dto.mimeType ?? null,
    };
  } catch {
    return null;
  }
}

// Any open modal should hide the editor's floating bubble menus —
// tippy.js's default z-index sits above our modal layer, so without
// this flag the inline-mark + image-width toolbars float on top of
// the picker dialogs.
const editorFloatingSuppressed = computed(
  () =>
    linkPickerOpen.value
    || iconPickerOpen.value
    || assetPickerOpen.value
    || embedPickerOpen.value
    || formPickerOpen.value
    || inputPickerOpen.value,
);

// On clicking an embed card's "Open" button, the NodeView dispatches
// a `vance:open-embed` CustomEvent that bubbles up the DOM. Route it
// through our vance: handler so the document opens in a new tab
// (cortex with ?doc=...). Registered as a plain DOM listener instead
// of `@vance:open-embed` because Vue's template compiler chokes on
// the colon-in-event-name when combined with a `:` modifier.
const workbookRootRef = ref<HTMLElement | null>(null);
function onOpenEmbedEvent(e: Event) {
  const detail = (e as CustomEvent<{ uri: string; openInNewTab: boolean }>).detail;
  if (!detail?.uri) return;
  openVanceLink(detail.uri, detail.openInNewTab);
}
onMounted(() => {
  workbookRootRef.value?.addEventListener('vance:open-embed', onOpenEmbedEvent);
});
onBeforeUnmount(() => {
  workbookRootRef.value?.removeEventListener('vance:open-embed', onOpenEmbedEvent);
});

// Icon picker — modal with a searchable emoji grid (provided by
// `emoji-picker-element`). The element renders as a native custom
// element; we set up `isCustomElement` in vite.config so Vue lets it
// through unresolved.
const iconPickerOpen = ref(false);
function openIconPicker() {
  iconPickerOpen.value = true;
}
function closeIconPicker() {
  iconPickerOpen.value = false;
}
function onEmojiPicked(unicode: string) {
  editorRef.value?.updateHeader({ icon: unicode });
  iconPickerOpen.value = false;
}
function removeIcon() {
  editorRef.value?.updateHeader({ icon: null });
  iconPickerOpen.value = false;
}
function removeCover() {
  editorRef.value?.updateHeader({ cover: null });
}

// Self-write tracker — the last body we PUT for the active page. The
// server's editorId-based filter should already prevent the
// documents.changed broadcast from echoing back to us, but in practice
// (server may normalise whitespace, ID header may be missing on a stale
// session, frame ordering may interleave) we can't rely on that alone.
const lastSavedBodies = ref<Map<string, string>>(new Map());

// Quiet-window after a save during which we suppress remote-content
// reloads for the active page. Without this, the WS echo of our own
// write rebuilds the ProseMirror doc → cursor jumps. 3 s is long
// enough to cover RTT + broadcast lag, short enough that genuine
// cross-user edits land quickly.
const SELF_WRITE_QUIET_MS = 3000;
const lastSelfWriteAt = ref<Map<string, number>>(new Map());

function withinSelfWriteWindow(id: string): boolean {
  const t = lastSelfWriteAt.value.get(id);
  return t != null && Date.now() - t < SELF_WRITE_QUIET_MS;
}

const creating = ref(false);
const newPageOpen = ref(false);
const newPageTitle = ref('');
const newPageSection = ref('');
const newPageError = ref<string | null>(null);
const newPageTitleInputRef = ref<HTMLInputElement | null>(null);

const existingSections = computed<string[]>(() => {
  const v = view.value;
  if (!v) return [];
  const set = new Set<string>();
  for (const p of v.pages) {
    if (p.section) set.add(p.section);
  }
  return [...set].sort();
});

async function openNewPage() {
  newPageOpen.value = true;
  newPageTitle.value = '';
  newPageError.value = null;
  // Pre-fill section from the currently selected page so the new page
  // lands next to it — most natural workflow.
  newPageSection.value = activePageView.value?.section ?? '';
  await nextTick();
  newPageTitleInputRef.value?.focus();
}

function closeNewPage() {
  newPageOpen.value = false;
  newPageError.value = null;
  creating.value = false;
}

async function submitNewPage() {
  const title = newPageTitle.value.trim();
  if (!title) {
    newPageError.value = 'Title required';
    return;
  }
  creating.value = true;
  newPageError.value = null;
  try {
    const section = newPageSection.value.trim();
    const page = await createWorkbookPage(projectId.value, folder.value, {
      title,
      ...(section ? { section } : {}),
    });
    closeNewPage();
    await loadWorkbook();
    await selectPage(page.id, page);
  } catch (e) {
    newPageError.value = e instanceof Error ? e.message : 'Could not create page.';
    creating.value = false;
  }
}

async function loadWorkbook() {
  loading.value = true;
  error.value = null;
  try {
    view.value = await scanWorkbook(projectId.value, folder.value);
    if (activePageId.value == null) {
      pickInitialPage();
    } else {
      // Pages list excludes underscore-prefixed system files
      // (_index.md, _app.yaml). For the index we synthesise a view
      // from the scan response so a rebuild — which reloads view
      // while the index is open — doesn't wipe the editor.
      const matched =
        view.value.pages.find((p) => p.id === activePageId.value)
        ?? syntheticIndexView(activePageId.value)
        ?? null;
      activePageView.value = matched;
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not scan workbook.';
    view.value = null;
  } finally {
    loading.value = false;
  }
}

function pickInitialPage() {
  const v = view.value;
  if (!v) return;
  if (v.landingPageId) {
    void selectPage(v.landingPageId, null);
    return;
  }
  if (v.indexPageId) {
    void selectPage(v.indexPageId, null);
    return;
  }
  if (v.pages.length > 0) {
    void selectPage(v.pages[0].id, v.pages[0]);
  }
}

async function selectPage(id: string, page: WorkbookPageView | null) {
  if (id === activePageId.value) return;
  // Flush pending edits on the current page before switching.
  if (editorRef.value?.flush()) {
    // Give the @save handler one tick to fire + the PUT to start.
    await nextTick();
  }
  activePageId.value = id;
  activePageView.value = page ?? findPageById(id) ?? syntheticIndexView(id);
  saveStatus.value = 'idle';
  lastSaveError.value = null;
  headerCache.value = null;
  await loadActivePageContent();
}

function findPageById(id: string): WorkbookPageView | null {
  const v = view.value;
  if (!v) return null;
  return v.pages.find((p) => p.id === id) ?? null;
}

/**
 * The generated `_index.md` is intentionally NOT in `view.pages` (the
 * folder reader filters underscore-prefixed files out). Without a
 * corresponding {@link WorkbookPageView} the editor template's
 * {@code v-if="activeMarkdown != null && activePageView"} short-
 * circuits and the index never paints. Synthesise a minimal view here
 * so the index opens like any other page.
 */
function syntheticIndexView(id: string): WorkbookPageView | null {
  const v = view.value;
  if (!v || v.indexPageId !== id || !v.indexPagePath) return null;
  const leaf = v.indexPagePath.substring(v.indexPagePath.lastIndexOf('/') + 1);
  const relativePath = v.indexPagePath.startsWith(folder.value + '/')
    ? v.indexPagePath.substring(folder.value.length + 1)
    : v.indexPagePath;
  return {
    id,
    path: v.indexPagePath,
    relativePath,
    section: '',
    title: leaf.replace(/\.workpage\.md$|\.canvas\.md$|\.md$/, '') || 'Index',
    description: undefined,
    icon: '⌂',
    sortIndex: undefined,
  };
}

async function loadActivePageContent(options: { force?: boolean } = {}) {
  if (!activePageId.value) {
    activeMarkdown.value = null;
    return;
  }
  pageLoading.value = true;
  pageError.value = null;
  try {
    const id = activePageId.value;
    if (!options.force && withinSelfWriteWindow(id)) {
      return;
    }
    const fresh = await brainFetchText(
      `documents/${encodeURIComponent(id)}/content`,
    );
    const ours = lastSavedBodies.value.get(id);
    if (!options.force && ours != null && fresh === ours) {
      return;
    }
    activeMarkdown.value = fresh;
  } catch (e) {
    pageError.value = e instanceof Error ? e.message : 'Could not load page.';
    activeMarkdown.value = null;
  } finally {
    pageLoading.value = false;
  }
}

async function onEditorSave(body: string) {
  if (!activePageId.value) return;
  const id = activePageId.value;
  saveStatus.value = 'saving';
  lastSavedBodies.value.set(id, body);
  lastSelfWriteAt.value.set(id, Date.now());
  // Cache freshly-saved header so icon/cover changes show up
  // immediately — activeMarkdown stays frozen during the self-write
  // quiet window to keep the cursor stable.
  const parsedBody = parseDocument(body);
  headerCache.value = {
    icon: parsedBody.icon,
    cover: parsedBody.cover,
    title: parsedBody.title,
    description: parsedBody.description,
  };
  try {
    await brainSendRaw<unknown>(
      'PUT',
      `documents/${encodeURIComponent(id)}/content`,
      body,
      'text/markdown',
    );
    // Refresh the timestamp on successful save — the WS echo arrives
    // *after* the PUT response, not before, so the suppression window
    // is measured from response-time.
    lastSelfWriteAt.value.set(id, Date.now());
    // Only update status. DO NOT touch activeMarkdown — the editor
    // already holds the right content; rewriting it would trip the
    // `watch(() => props.source, …)` and rebuild the ProseMirror doc,
    // dropping the cursor.
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

/**
 * Upload a dropped/pasted image into `<workbook>/assets/`. Returns
 * the streaming-content URL so the editor can embed it as
 * `<img src>` immediately. Path collisions are avoided by
 * timestamp-prefix; the sanitised filename keeps URLs readable.
 */
/**
 * Resolve a {@code vance:} URI to an HTTP {@code <img src>}. The
 * block-editor's image NodeView calls this whenever it encounters a
 * URI in image-block src. Returns null on failure (NodeView shows a
 * broken-link placeholder).
 *
 * - {@code vance:/<path>?kind=image}            → current project
 * - {@code vance://<projectId>/<path>?kind=image} → explicit project
 */
async function resolveVanceImageSrc(uri: string): Promise<string | null> {
  let parsed: URL;
  try { parsed = new URL(uri); } catch { return null; }
  if (parsed.protocol !== 'vance:') return null;
  const targetProject = parsed.hostname
    ? decodeURIComponent(parsed.hostname)
    : projectId.value;
  const path = decodeURIComponent(parsed.pathname.replace(/^\//, ''));
  if (!targetProject || !path) return null;
  try {
    const dto = await brainFetch<{ id: string }>(
      'GET',
      `documents/by-path?projectId=${encodeURIComponent(targetProject)}` +
        `&path=${encodeURIComponent(path)}`,
    );
    return documentContentUrl(dto.id);
  } catch {
    return null;
  }
}

/**
 * In-app routing for {@code vance:} hrefs in the editor. The editor
 * calls this on ⌘/Ctrl+click; we resolve the path → document id and
 * navigate to the same URL with an updated {@code ?doc=...} query.
 * Returning {@code true} tells the editor not to fall back to a raw
 * {@code window.open(href)} — the browser can't navigate the
 * {@code vance:} scheme on its own.
 *
 * A blank tab is opened synchronously (when the user asked for a
 * new tab) so the browser counts the click as a user-initiated
 * action. We then navigate that tab once the by-path lookup returns.
 */
function openVanceLink(href: string, openInNewTab: boolean): boolean {
  if (!href.startsWith('vance:')) return false;
  let parsed: URL;
  try { parsed = new URL(href); } catch { return false; }
  if (parsed.protocol !== 'vance:') return false;

  const targetProject = parsed.hostname
    ? decodeURIComponent(parsed.hostname)
    : projectId.value;
  const path = decodeURIComponent(parsed.pathname.replace(/^\//, ''));
  if (!path) return false;

  // Open the placeholder synchronously so the browser sees a direct
  // user-gesture. Without this, async-then-window.open gets popup-
  // blocked by Safari + Firefox.
  const newTab = openInNewTab ? window.open('about:blank', '_blank') : null;

  void (async () => {
    try {
      const dto = await brainFetch<{ id: string }>(
        'GET',
        `documents/by-path?projectId=${encodeURIComponent(targetProject)}` +
          `&path=${encodeURIComponent(path)}`,
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
      console.warn('[Workbook] vance: link could not be resolved', path, e);
      if (newTab) newTab.close();
    }
  })();
  return true;
}

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
    await brainFetch<{ id: string }>(
      'POST',
      'documents/upload',
      { body: form },
    );
    // Return a vance: URI rather than the absolute HTTP content URL —
    // the markdown on disk must be portable across Brain instances /
    // project renames. The image NodeView resolves it back to a real
    // <img src> via /documents/by-path on render.
    return `vance:/${encodeURI(path)}?kind=image`;
  } catch (e) {
    console.error('[Workbook] image upload failed', e);
    return null;
  }
}

function onEditorDirty(dirty: boolean) {
  if (dirty) {
    saveStatus.value = 'dirty';
    // Start (or refresh) the self-write quiet window the moment the
    // user types — without this an auto-save round-trip is the only
    // thing keeping the editor immune to WS echoes, but a WS push that
    // arrives BEFORE the first save would still clobber the cursor.
    if (activePageId.value) {
      lastSelfWriteAt.value.set(activePageId.value, Date.now());
    }
  }
}

async function rebuild() {
  rebuilding.value = true;
  try {
    if (editorRef.value?.flush()) {
      await nextTick();
    }
    await rebuildWorkbook(projectId.value, folder.value);
    await loadWorkbook();
    // Rebuild may have regenerated _index.md or another page the user
    // is viewing — force the reload so the fresh server content lands
    // even if it byte-matches our last write.
    await loadActivePageContent({ force: true });
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Rebuild failed.';
  } finally {
    rebuilding.value = false;
  }
}

// Sidebar uses the latest known icon/title — for the active page that
// includes the in-memory headerCache so an emoji-picker / rename
// change shows up immediately, before the next workbook scan.
function effectiveIcon(p: WorkbookPageView): string | null {
  if (p.id === activePageId.value && headerCache.value?.icon != null) {
    return headerCache.value.icon;
  }
  return p.icon ?? null;
}
function effectiveTitle(p: WorkbookPageView): string {
  if (p.id === activePageId.value && headerCache.value?.title != null) {
    return headerCache.value.title;
  }
  return p.title;
}

// ── Page context menu (right-click + ⋯ button) ────────────────────
const ctxMenu = ref<{ page: WorkbookPageView; x: number; y: number } | null>(null);
const renameDialog = ref<WorkbookPageView | null>(null);
const renameValue = ref('');
const renameSection = ref('');
const renameError = ref<string | null>(null);
const renameBusy = ref(false);
const renameInputRef = ref<HTMLInputElement | null>(null);

function openCtxMenu(page: WorkbookPageView, e: MouseEvent) {
  e.preventDefault();
  ctxMenu.value = { page, x: e.clientX, y: e.clientY };
}
function closeCtxMenu() {
  ctxMenu.value = null;
}
async function openRename(page: WorkbookPageView) {
  closeCtxMenu();
  renameDialog.value = page;
  renameValue.value = page.title;
  renameSection.value = page.section ?? '';
  renameError.value = null;
  await nextTick();
  renameInputRef.value?.focus();
  renameInputRef.value?.select();
}
function closeRename() {
  renameDialog.value = null;
  renameError.value = null;
  renameBusy.value = false;
}
async function submitRename() {
  const page = renameDialog.value;
  if (!page) return;
  const newTitle = renameValue.value.trim();
  if (!newTitle) {
    renameError.value = 'Title required';
    return;
  }
  const newSection = renameSection.value.trim();
  const patch: { title?: string; section?: string } = {};
  if (newTitle !== page.title) patch.title = newTitle;
  if (newSection !== (page.section ?? '')) patch.section = newSection;
  if (Object.keys(patch).length === 0) {
    closeRename();
    return;
  }
  renameBusy.value = true;
  try {
    await updateWorkbookPage(projectId.value, folder.value, page.id, patch);
    // Reflect immediately in the local header cache for the active page.
    if (page.id === activePageId.value && patch.title) {
      headerCache.value = { ...(headerCache.value ?? {
        icon: null, cover: null, title: null, description: null,
      }), title: patch.title };
    }
    closeRename();
    await loadWorkbook();
  } catch (e) {
    renameError.value = e instanceof Error ? e.message : 'Rename failed.';
    renameBusy.value = false;
  }
}
async function duplicatePage(page: WorkbookPageView) {
  closeCtxMenu();
  try {
    const copy = await duplicateWorkbookPage(projectId.value, folder.value, page.id);
    await loadWorkbook();
    // Open the new copy right away — matches the typical user intent
    // ("I wanted to start from this page, then edit").
    await selectPage(copy.id, copy);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Duplicate failed.';
  }
}

async function togglePinLanding(page: WorkbookPageView) {
  closeCtxMenu();
  const v = view.value;
  if (!v) return;
  const isLanding = v.landingPageId === page.id;
  try {
    view.value = await setWorkbookLandingPage(projectId.value, folder.value, {
      pageId: isLanding ? undefined : page.id,
    });
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not update landing page.';
  }
}

async function confirmDelete(page: WorkbookPageView) {
  closeCtxMenu();
  if (!window.confirm(`Delete page "${page.title}"?`)) return;
  try {
    await deleteWorkbookPage(projectId.value, folder.value, page.id);
    if (page.id === activePageId.value) {
      activePageId.value = null;
      activePageView.value = null;
      activeMarkdown.value = null;
      headerCache.value = null;
    }
    await loadWorkbook();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Delete failed.';
  }
}

// ── Section rename ────────────────────────────────────────────────
// Mirrors the page context-menu pattern (right-click + ⋯ button) for
// consistency. Inline-edit on the label, server batches the path-move
// for every page currently in that section. Empty sections vanish on
// their own — they only exist as path prefixes, no first-class entity.
const sectionCtxMenu = ref<{ section: string; x: number; y: number } | null>(null);
const editingSection = ref<string | null>(null);
const editingSectionValue = ref('');
const sectionInputRef = ref<HTMLInputElement | null>(null);

function openSectionCtxMenu(section: string, e: MouseEvent) {
  // Top-level ('') has nothing renameable, so skip the menu altogether.
  if (!section) return;
  e.preventDefault();
  sectionCtxMenu.value = { section, x: e.clientX, y: e.clientY };
}
function closeSectionCtxMenu() {
  sectionCtxMenu.value = null;
}
async function startSectionRename(section: string) {
  closeSectionCtxMenu();
  if (!section) return;
  editingSection.value = section;
  editingSectionValue.value = section;
  await nextTick();
  sectionInputRef.value?.focus();
  sectionInputRef.value?.select();
}
function cancelSectionRename() {
  editingSection.value = null;
  editingSectionValue.value = '';
}
async function commitSectionRename() {
  const from = editingSection.value;
  if (from == null) return;
  const to = editingSectionValue.value.trim();
  editingSection.value = null;
  if (to === from) return;
  try {
    await renameWorkbookSection(projectId.value, folder.value, { from, to });
    await loadWorkbook();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Section rename failed.';
  }
}

// ── Drag-and-drop reorder ─────────────────────────────────────────
// Native HTML5 D&D over the page-row list. Drop semantics:
//   * onto another row inside the SAME section → reorder
//   * onto a row of a DIFFERENT section → move page into that section
//     and insert at the drop position (path-update via PUT /page/{id},
//     then reorder of the target section)
//   * onto a section label → move + append to the end of that section
type DropPosition = 'before' | 'after';
const dragPageId = ref<string | null>(null);
const dropTarget = ref<{ pageId: string; position: DropPosition } | null>(null);
const dropSectionTarget = ref<string | null>(null); // section name (incl. '')

function onDragStart(p: WorkbookPageView, e: DragEvent) {
  dragPageId.value = p.id;
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move';
    // Some browsers require text/plain payload to start a drag at all.
    e.dataTransfer.setData('text/plain', p.id);
  }
}
function onDragEnd() {
  dragPageId.value = null;
  dropTarget.value = null;
  dropSectionTarget.value = null;
}
function onRowDragOver(p: WorkbookPageView, e: DragEvent) {
  if (!dragPageId.value || dragPageId.value === p.id) return;
  e.preventDefault();
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
  // Top half = before, bottom half = after — classic insertion semantics.
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
  const half = rect.top + rect.height / 2;
  const position: DropPosition = e.clientY < half ? 'before' : 'after';
  dropTarget.value = { pageId: p.id, position };
  dropSectionTarget.value = null;
}
function onSectionDragOver(section: string, e: DragEvent) {
  if (!dragPageId.value) return;
  e.preventDefault();
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
  dropSectionTarget.value = section;
  dropTarget.value = null;
}
async function onRowDrop(p: WorkbookPageView) {
  const sourceId = dragPageId.value;
  const target = dropTarget.value;
  dragPageId.value = null;
  dropTarget.value = null;
  dropSectionTarget.value = null;
  if (!sourceId || !target || sourceId === p.id) return;
  await applyReorder(sourceId, p.section, target.pageId, target.position);
}
async function onSectionDrop(section: string) {
  const sourceId = dragPageId.value;
  dragPageId.value = null;
  dropTarget.value = null;
  dropSectionTarget.value = null;
  if (!sourceId) return;
  // Drop on the bare section label = move to end of section.
  await applyReorder(sourceId, section, null, 'after');
}

async function applyReorder(
  sourceId: string,
  targetSection: string,
  anchorPageId: string | null,
  position: DropPosition,
) {
  const v = view.value;
  if (!v) return;
  const source = v.pages.find((q) => q.id === sourceId);
  if (!source) return;

  // Cross-section move first — path-update — so the next scan/reorder
  // sees the page where it now belongs.
  if (source.section !== targetSection) {
    try {
      await updateWorkbookPage(projectId.value, folder.value, sourceId, {
        section: targetSection,
      });
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Move failed.';
      return;
    }
  }

  // Build the new in-section order from the *current* view, sans source.
  const sectionPages = v.pages
    .filter((q) => q.section === targetSection && q.id !== sourceId)
    .slice();
  let insertAt = sectionPages.length; // default: append
  if (anchorPageId) {
    const idx = sectionPages.findIndex((q) => q.id === anchorPageId);
    if (idx >= 0) insertAt = position === 'before' ? idx : idx + 1;
  }
  sectionPages.splice(insertAt, 0, source);

  try {
    await reorderWorkbookPages(projectId.value, folder.value, {
      orderedIds: sectionPages.map((q) => q.id),
    });
    await loadWorkbook();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Reorder failed.';
  }
}

// Sidebar filter — case-insensitive substring match against title +
// section. Empty input shows everything. Filter runs client-side; for
// the page sizes we expect (~dozens, occasionally low hundreds) this
// is well below any perceptible cost and avoids a server round-trip.
const filterText = ref('');
function pageMatchesFilter(p: WorkbookPageView, needle: string): boolean {
  if (!needle) return true;
  const t = (effectiveTitle(p) ?? '').toLowerCase();
  const s = (p.section ?? '').toLowerCase();
  return t.includes(needle) || s.includes(needle);
}

function pagesBySection(): Map<string, WorkbookPageView[]> {
  const grouped = new Map<string, WorkbookPageView[]>();
  if (!view.value) return grouped;
  const needle = filterText.value.trim().toLowerCase();
  for (const p of view.value.pages) {
    if (!pageMatchesFilter(p, needle)) continue;
    const key = p.section ?? '';
    let list = grouped.get(key);
    if (!list) {
      list = [];
      grouped.set(key, list);
    }
    list.push(p);
  }
  return grouped;
}

useDocumentPrefixReaction({
  prefix: computed(() => `${folder.value}/`),
  debounceMs: 250,
  onRemoteChange: async (paths) => {
    const activeId = activePageId.value;
    if (activeId != null && withinSelfWriteWindow(activeId)) {
      // Self-echo of our own write. Skip the tree reload too — even
      // though setContent on the editor is guarded by the quiet
      // window, re-running scanWorkbook replaces `view.value` and
      // forces a parent re-render that interacts badly with the
      // Tiptap editor lifecycle (focus / cursor get lost).
      return;
    }
    // Refresh tree first; then reload the active page only if it was
    // among the changed paths.
    await loadWorkbook();
    const v = view.value;
    if (!v || !activePageId.value) return;
    const active = v.pages.find((p) => p.id === activePageId.value);
    const activePath = active?.path
      ?? (activePageId.value === v.indexPageId ? v.indexPagePath : null)
      ?? (activePageId.value === v.landingPageId ? v.landingPagePath : null);
    if (activePath && paths.includes(activePath)) {
      await loadActivePageContent();
    }
  },
});

watch(folder, () => {
  activePageId.value = null;
  activePageView.value = null;
  activeMarkdown.value = null;
  void loadWorkbook();
});

onMounted(() => loadWorkbook());

const saveStatusLabel = computed<string | null>(() => {
  switch (saveStatus.value) {
    case 'dirty': return 'Edited';
    case 'saving': return 'Saving…';
    case 'saved': return 'Saved';
    case 'error': return lastSaveError.value ?? 'Save failed';
    default: return null;
  }
});

// Force the WorkPageEditor to remount when the active page changes so the
// initial source is loaded cleanly (Tiptap doesn't track per-document
// state — it just owns one ProseMirror instance per mount).
const editorKey = computed(() => activePageId.value ?? 'empty');
</script>

<template>
  <div ref="workbookRootRef" class="workbook-app">
    <aside class="workbook-app__sidebar">
      <header class="workbook-app__title">
        <div class="workbook-app__title-text">{{ title }}</div>
        <div class="workbook-app__title-actions">
          <button
            class="workbook-app__icon-btn"
            :class="{ 'workbook-app__icon-btn--active': pageMode === 'design' }"
            :title="pageMode === 'design'
              ? 'Design mode — editing form fields. Click for Work mode.'
              : 'Work mode — entering data. Click for Design mode.'"
            @click="togglePageMode"
          >{{ pageMode === 'design' ? '🛠' : '✎' }}</button>
          <button
            class="workbook-app__icon-btn"
            :disabled="creating"
            title="New page"
            @click="openNewPage"
          >+</button>
          <button
            class="workbook-app__icon-btn"
            :disabled="rebuilding"
            :title="rebuilding ? 'Rebuilding…' : 'Rebuild _index.md'"
            @click="rebuild"
          >
            {{ rebuilding ? '…' : '↻' }}
          </button>
        </div>
      </header>

      <div class="workbook-app__search">
        <input
          v-model="filterText"
          type="search"
          class="workbook-app__search-input"
          placeholder="Filter pages…"
          @keydown.escape="filterText = ''"
        />
      </div>

      <form
        v-if="newPageOpen"
        class="workbook-app__new-page"
        @submit.prevent="submitNewPage"
      >
        <input
          ref="newPageTitleInputRef"
          v-model="newPageTitle"
          type="text"
          class="workbook-app__new-page-input"
          placeholder="Page title"
          :disabled="creating"
          @keydown.escape="closeNewPage"
        />
        <input
          v-model="newPageSection"
          type="text"
          class="workbook-app__new-page-input"
          placeholder="Section (optional)"
          list="workbook-sections"
          :disabled="creating"
        />
        <datalist id="workbook-sections">
          <option v-for="s in existingSections" :key="s" :value="s" />
        </datalist>
        <div v-if="newPageError" class="workbook-app__error">{{ newPageError }}</div>
        <div class="workbook-app__new-page-actions">
          <button
            type="submit"
            class="workbook-app__new-page-btn workbook-app__new-page-btn--primary"
            :disabled="creating || !newPageTitle.trim()"
          >
            {{ creating ? 'Creating…' : 'Create' }}
          </button>
          <button
            type="button"
            class="workbook-app__new-page-btn"
            :disabled="creating"
            @click="closeNewPage"
          >Cancel</button>
        </div>
      </form>

      <div v-if="error" class="workbook-app__error">{{ error }}</div>

      <div v-if="view" class="workbook-app__tree">
        <template v-if="view.indexPageId && (!filterText.trim() || 'index workbook'.includes(filterText.trim().toLowerCase()))">
          <div class="workbook-app__section-label">Workbook</div>
          <button
            class="workbook-app__page-link"
            :class="{
              'workbook-app__page-link--active': activePageId === view.indexPageId,
            }"
            @click="selectPage(view.indexPageId!, null)"
          >
            <span class="workbook-app__page-link-icon">⌂</span>
            Index
          </button>
        </template>

        <template v-for="[section, pages] in pagesBySection()" :key="section">
          <div
            class="workbook-app__section-label"
            :class="{ 'workbook-app__section-label--drop': dropSectionTarget === section }"
            @dragover="onSectionDragOver(section, $event)"
            @drop="onSectionDrop(section)"
            @contextmenu="openSectionCtxMenu(section, $event)"
          >
            <input
              v-if="editingSection === section"
              ref="sectionInputRef"
              v-model="editingSectionValue"
              type="text"
              class="workbook-app__section-input"
              @keydown.enter.prevent="commitSectionRename"
              @keydown.escape="cancelSectionRename"
              @blur="commitSectionRename"
              @click.stop
            />
            <template v-else>
              <span class="workbook-app__section-label-text">{{ section || 'Pages' }}</span>
              <button
                v-if="section"
                class="workbook-app__section-menu"
                title="More actions"
                @click.stop="openSectionCtxMenu(section, $event)"
              >⋯</button>
            </template>
          </div>
          <div
            v-for="p in pages"
            :key="p.id"
            class="workbook-app__page-row"
            :class="{
              'workbook-app__page-row--active': activePageId === p.id,
              'workbook-app__page-row--drop-before':
                dropTarget && dropTarget.pageId === p.id && dropTarget.position === 'before',
              'workbook-app__page-row--drop-after':
                dropTarget && dropTarget.pageId === p.id && dropTarget.position === 'after',
              'workbook-app__page-row--dragging': dragPageId === p.id,
            }"
            draggable="true"
            @dragstart="onDragStart(p, $event)"
            @dragend="onDragEnd"
            @dragover="onRowDragOver(p, $event)"
            @drop="onRowDrop(p)"
          >
            <button
              class="workbook-app__page-link"
              :class="{
                'workbook-app__page-link--active': activePageId === p.id,
              }"
              @click="selectPage(p.id, p)"
              @contextmenu="openCtxMenu(p, $event)"
              :title="p.description ?? p.path"
            >
              <span class="workbook-app__page-link-icon" :class="{ 'workbook-app__page-link-icon--emoji': !!effectiveIcon(p) }">{{ effectiveIcon(p) ?? '·' }}</span>
              <span class="workbook-app__page-link-title">{{ effectiveTitle(p) }}</span>
              <span v-if="view && view.landingPageId === p.id" class="workbook-app__landing-pin" title="Landing page">📌</span>
            </button>
            <button
              class="workbook-app__page-row-menu"
              title="More actions"
              @click.stop="openCtxMenu(p, $event)"
            >⋯</button>
          </div>
        </template>
      </div>

      <div
        v-if="view && view.pages.length === 0 && !view.indexPageId"
        class="workbook-app__empty"
      >
        Noch keine Pages.
      </div>
    </aside>

    <main class="workbook-app__main">
      <div v-if="loading" class="workbook-app__main-empty">Lade Workbook…</div>

      <template v-else-if="activePageId">
        <div v-if="pageCover" class="workbook-app__page-cover-wrap">
          <img
            :src="pageCover"
            alt=""
            class="workbook-app__page-cover"
          />
          <div
            v-if="pageMode === 'design'"
            class="workbook-app__page-cover-actions"
          >
            <button
              class="workbook-app__page-cover-btn"
              @click="openCoverPicker"
            >Change cover</button>
            <button
              class="workbook-app__page-cover-btn"
              @click="removeCover"
            >Remove</button>
          </div>
        </div>
        <header v-if="activePageView" class="workbook-app__page-header">
          <div
            v-if="pageMode === 'design' && (!pageCover || !pageIcon)"
            class="workbook-app__page-add-row"
          >
            <button
              v-if="!pageIcon"
              class="workbook-app__page-add-btn"
              @click="openIconPicker"
            >😀 Add icon</button>
            <button
              v-if="!pageCover"
              class="workbook-app__page-add-btn"
              @click="openCoverPicker"
            >🖼 Add cover</button>
          </div>
          <div class="workbook-app__page-header-row">
            <h1 class="workbook-app__page-title">
              <button
                v-if="pageIcon && pageMode === 'design'"
                class="workbook-app__page-icon"
                title="Change icon"
                @click="openIconPicker"
              >{{ pageIcon }}</button>
              <span
                v-else-if="pageIcon"
                class="workbook-app__page-icon workbook-app__page-icon--static"
              >{{ pageIcon }}</span>
              {{ pageDisplayTitle }}
            </h1>
            <span
              v-if="saveStatusLabel"
              class="workbook-app__save-status"
              :class="`workbook-app__save-status--${saveStatus}`"
            >
              {{ saveStatusLabel }}
            </span>
          </div>
          <div v-if="activePageView.section" class="workbook-app__page-section">
            {{ activePageView.section }}
          </div>
          <p v-if="pageDisplayDescription" class="workbook-app__page-description">
            {{ pageDisplayDescription }}
          </p>
        </header>

        <div v-if="pageLoading" class="workbook-app__main-empty">Lade Page…</div>
        <div v-else-if="pageError" class="workbook-app__error">{{ pageError }}</div>
        <WorkPageEditor
          v-else-if="activeMarkdown != null && activePageView"
          :key="editorKey"
          ref="editorRef"
          :document="{
            id: activePageId,
            path: activePageView.path,
            projectId,
            title: activePageView.title,
            inlineText: activeMarkdown,
            mimeType: 'text/markdown',
          }"
          :source="activeMarkdown"
          :upload-image="uploadImage"
          :open-asset-picker="openAssetPicker"
          :open-link-picker="openLinkPicker"
          :current-project-id="projectId"
          :resolve-image-src="resolveVanceImageSrc"
          :suppress-floating="editorFloatingSuppressed"
          :open-link="openVanceLink"
          :open-embed-picker="openEmbedPicker"
          :resolve-embed-doc="resolveEmbedDoc"
          :embed-component="embedComponent ?? undefined"
          :open-form-picker="openFormPicker"
          :form-component="formComponent ?? undefined"
          :load-input="loadInput"
          :save-input="saveInput"
          :open-input-picker="openInputPicker"
          :run-button-script="runButtonScript"
          :editable="editorEditable"
          @save="onEditorSave"
          @dirty="onEditorDirty"
        />
        <AssetPickerModal
          v-if="assetPickerOpen"
          :project-id="projectId"
          :workbook-folder="folder"
          :upload-image="uploadImage"
          @pick="onAssetPick"
          @close="assetPickerOpen = false"
        />
        <EmojiPickerModal
          v-if="iconPickerOpen"
          :has-current="!!pageIcon"
          @pick="onEmojiPicked"
          @remove="removeIcon"
          @close="closeIconPicker"
        />
        <LinkPickerModal
          v-if="linkPickerOpen"
          :project-id="projectId"
          :initial-href="linkPickerInitialHref"
          @pick="onLinkPicked"
          @clear="onLinkClear"
          @close="closeLinkPicker"
        />
        <EmbedPickerModal
          v-if="embedPickerOpen"
          :project-id="projectId"
          :folder="folder"
          @pick="onEmbedPicked"
          @close="closeEmbedPicker"
        />
        <FormPickerModal
          v-if="formPickerOpen"
          :project-id="projectId"
          :folder="folder"
          @pick="onFormPicked"
          @close="closeFormPicker"
        />
        <InputPickerModal
          v-if="inputPickerOpen"
          :project-id="projectId"
          :folder="folder"
          @pick="onInputPicked"
          @close="closeInputPicker"
        />
      </template>

      <div v-else class="workbook-app__main-empty">
        Wähle eine Page aus der Sidebar.
      </div>
    </main>

    <div
      v-if="ctxMenu"
      class="workbook-app__ctx-backdrop"
      @click="closeCtxMenu"
      @contextmenu.prevent="closeCtxMenu"
    >
      <div
        class="workbook-app__ctx-menu"
        :style="{ left: ctxMenu.x + 'px', top: ctxMenu.y + 'px' }"
        @click.stop
      >
        <button class="workbook-app__ctx-item" @click="openRename(ctxMenu.page)">Rename / Move…</button>
        <button class="workbook-app__ctx-item" @click="duplicatePage(ctxMenu.page)">Duplicate</button>
        <button class="workbook-app__ctx-item" @click="togglePinLanding(ctxMenu.page)">
          {{ view && view.landingPageId === ctxMenu.page.id ? 'Unpin landing page' : 'Pin as landing page' }}
        </button>
        <button class="workbook-app__ctx-item workbook-app__ctx-item--danger" @click="confirmDelete(ctxMenu.page)">Delete</button>
      </div>
    </div>

    <div
      v-if="sectionCtxMenu"
      class="workbook-app__ctx-backdrop"
      @click="closeSectionCtxMenu"
      @contextmenu.prevent="closeSectionCtxMenu"
    >
      <div
        class="workbook-app__ctx-menu"
        :style="{ left: sectionCtxMenu.x + 'px', top: sectionCtxMenu.y + 'px' }"
        @click.stop
      >
        <button class="workbook-app__ctx-item" @click="startSectionRename(sectionCtxMenu.section)">Rename section…</button>
      </div>
    </div>

    <div
      v-if="renameDialog"
      class="workbook-app__modal-backdrop"
      @click.self="closeRename"
    >
      <form class="workbook-app__modal" @submit.prevent="submitRename">
        <div class="workbook-app__modal-header">Rename / Move</div>
        <label class="workbook-app__modal-label">
          Title
          <input
            ref="renameInputRef"
            v-model="renameValue"
            type="text"
            class="workbook-app__modal-input"
            :disabled="renameBusy"
            @keydown.escape="closeRename"
          />
        </label>
        <label class="workbook-app__modal-label">
          Section
          <input
            v-model="renameSection"
            type="text"
            class="workbook-app__modal-input"
            list="workbook-sections-rename"
            placeholder="(top-level)"
            :disabled="renameBusy"
          />
        </label>
        <datalist id="workbook-sections-rename">
          <option v-for="s in existingSections" :key="s" :value="s" />
        </datalist>
        <div v-if="renameError" class="workbook-app__error">{{ renameError }}</div>
        <div class="workbook-app__modal-actions">
          <button
            type="submit"
            class="workbook-app__new-page-btn workbook-app__new-page-btn--primary"
            :disabled="renameBusy || !renameValue.trim()"
          >{{ renameBusy ? 'Saving…' : 'Save' }}</button>
          <button
            type="button"
            class="workbook-app__new-page-btn"
            :disabled="renameBusy"
            @click="closeRename"
          >Cancel</button>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.workbook-app {
  display: grid;
  grid-template-columns: 260px 1fr;
  height: 100%;
  min-height: 0;
  background: oklch(var(--b1));
}
.workbook-app__sidebar {
  border-right: 1px solid oklch(var(--bc) / 0.18);
  padding: 0.75rem 0.5rem;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
  background: oklch(var(--b2));
}
.workbook-app__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 0.5rem 0.75rem;
  border-bottom: 1px solid oklch(var(--bc) / 0.18);
  margin-bottom: 0.5rem;
}
.workbook-app__title-text {
  font-weight: 600;
  font-size: 0.95rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.workbook-app__title-actions {
  display: flex;
  gap: 0.25rem;
}
.workbook-app__icon-btn {
  font-size: 0.95rem;
  width: 1.75rem;
  height: 1.75rem;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.25rem;
  background: oklch(var(--bc) / 0.06);
  cursor: pointer;
  flex-shrink: 0;
  line-height: 1;
}
.workbook-app__icon-btn:hover:not(:disabled) {
  background: oklch(var(--bc) / 0.1);
}
.workbook-app__icon-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.workbook-app__icon-btn--active {
  background: oklch(var(--p) / 0.18);
  color: oklch(var(--p));
}

.workbook-app__search {
  padding: 0 0.25rem 0.5rem;
}
.workbook-app__search-input {
  width: 100%;
  padding: 0.3rem 0.5rem;
  font-size: 0.85rem;
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.25rem;
  background: oklch(var(--b1));
  color: oklch(var(--bc));
  box-sizing: border-box;
}
.workbook-app__search-input:focus {
  outline: none;
  border-color: oklch(var(--p));
}

.workbook-app__new-page {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  margin: 0 0.25rem 0.5rem;
  padding: 0.5rem;
  background: oklch(var(--bc) / 0.06);
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.375rem;
}
.workbook-app__new-page-input {
  padding: 0.25rem 0.5rem;
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.25rem;
  font-size: 0.85rem;
  background: oklch(var(--b1));
}
.workbook-app__new-page-actions {
  display: flex;
  gap: 0.25rem;
  justify-content: flex-end;
}
.workbook-app__new-page-btn {
  padding: 0.25rem 0.625rem;
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.25rem;
  background: oklch(var(--bc) / 0.06);
  cursor: pointer;
  font-size: 0.85rem;
}
.workbook-app__new-page-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.workbook-app__new-page-btn--primary {
  background: oklch(var(--p) / 0.15);
  color: oklch(var(--pc));
  border-color: oklch(var(--p) / 0.15);
}
.workbook-app__new-page-btn--primary:hover:not(:disabled) {
  filter: brightness(0.95);
}
.workbook-app__error {
  background: oklch(var(--er) / 0.12);
  color: oklch(var(--er));
  font-size: 0.85rem;
  padding: 0.5rem;
  border-radius: 0.25rem;
  margin: 0.5rem;
}
.workbook-app__tree {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}
.workbook-app__section-label {
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: oklch(var(--bc) / 0.65);
  padding: 0.5rem 0.25rem 0.25rem 0.5rem;
  display: flex;
  align-items: center;
  gap: 0.25rem;
  border-radius: 0.25rem;
}
.workbook-app__section-label-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.workbook-app__section-menu {
  background: none;
  border: 0;
  color: oklch(var(--bc) / 0.65);
  cursor: pointer;
  padding: 0 0.35rem;
  font-size: 1rem;
  line-height: 1;
  border-radius: 3px;
  opacity: 0;
  transition: opacity 0.1s ease;
}
.workbook-app__section-label:hover .workbook-app__section-menu {
  opacity: 1;
}
.workbook-app__section-menu:hover {
  background: oklch(var(--bc) / 0.18);
  color: oklch(var(--bc));
}
.workbook-app__page-row {
  position: relative;
  display: flex;
  align-items: stretch;
  border-radius: 0.25rem;
}
.workbook-app__page-row--dragging {
  opacity: 0.45;
}
.workbook-app__page-row--drop-before::before,
.workbook-app__page-row--drop-after::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  height: 2px;
  background: oklch(var(--p));
  pointer-events: none;
}
.workbook-app__page-row--drop-before::before { top: -1px; }
.workbook-app__page-row--drop-after::after { bottom: -1px; }
.workbook-app__section-label--drop {
  background: rgba(59, 130, 246, 0.12);
  border-radius: 0.25rem;
}
.workbook-app__section-input {
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  width: 100%;
  padding: 0.125rem 0.25rem;
  border: 1px solid oklch(var(--p));
  border-radius: 3px;
  background: oklch(var(--b1));
  color: oklch(var(--bc));
  font-family: inherit;
}
.workbook-app__page-row:hover {
  background: oklch(var(--bc) / 0.06);
}
.workbook-app__page-row--active {
  background: oklch(var(--bc) / 0.06);
}
.workbook-app__page-link {
  text-align: left;
  padding: 0.375rem 0.5rem;
  border-radius: 0.25rem 0 0 0.25rem;
  background: none;
  border: none;
  cursor: pointer;
  color: inherit;
  font-size: 0.9rem;
  display: flex;
  align-items: center;
  gap: 0.4em;
  flex: 1;
  min-width: 0;
}
.workbook-app__page-row-menu {
  background: none;
  border: 0;
  color: oklch(var(--bc) / 0.65);
  cursor: pointer;
  padding: 0 0.5rem;
  font-size: 1rem;
  opacity: 0;
  transition: opacity 0.1s ease;
  border-radius: 0 0.25rem 0.25rem 0;
}
.workbook-app__page-row:hover .workbook-app__page-row-menu,
.workbook-app__page-row--active .workbook-app__page-row-menu {
  opacity: 1;
}
.workbook-app__page-row-menu:hover {
  background: oklch(var(--bc) / 0.18);
  color: oklch(var(--bc));
}
.workbook-app__ctx-backdrop {
  position: fixed;
  inset: 0;
  z-index: 900;
}
.workbook-app__ctx-menu {
  position: fixed;
  background: oklch(var(--b1));
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 6px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
  min-width: 180px;
  padding: 0.25rem 0;
  display: flex;
  flex-direction: column;
}
.workbook-app__ctx-item {
  background: none;
  border: 0;
  text-align: left;
  padding: 0.5rem 0.75rem;
  font-size: 0.875rem;
  cursor: pointer;
  color: oklch(var(--bc));
}
.workbook-app__ctx-item:hover {
  background: oklch(var(--bc) / 0.06);
}
.workbook-app__ctx-item--danger {
  color: oklch(var(--er));
}
.workbook-app__ctx-item--danger:hover {
  background: oklch(var(--er) / 0.1);
}
.workbook-app__modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.workbook-app__modal {
  background: oklch(var(--b1));
  border-radius: 8px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.25);
  padding: 1rem;
  width: 360px;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.workbook-app__modal-header {
  font-size: 1rem;
  font-weight: 600;
  color: oklch(var(--bc));
}
.workbook-app__modal-label {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  font-size: 0.8rem;
  color: oklch(var(--bc) / 0.65);
}
.workbook-app__modal-input {
  font-size: 0.95rem;
  padding: 0.4rem 0.5rem;
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 4px;
  background: oklch(var(--b1));
  color: oklch(var(--bc));
}
.workbook-app__modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  margin-top: 0.5rem;
}
.workbook-app__page-link--active {
  background: oklch(var(--p) / 0.15);
  color: oklch(var(--p));
  font-weight: 500;
}
.workbook-app__page-link-icon {
  font-size: 0.85em;
  opacity: 0.45;
  width: 1.1em;
  text-align: center;
  flex-shrink: 0;
}
.workbook-app__page-link-icon--emoji {
  font-size: 1em;
  opacity: 1;
}
.workbook-app__page-link-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  flex: 1;
}
.workbook-app__landing-pin {
  font-size: 0.7em;
  opacity: 0.7;
  flex-shrink: 0;
}
.workbook-app__empty {
  color: oklch(var(--bc) / 0.65);
  font-size: 0.85rem;
  padding: 0.5rem;
}
.workbook-app__main {
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.workbook-app__main-empty {
  color: oklch(var(--bc) / 0.65);
  font-style: italic;
  padding: 2rem;
}
.workbook-app__page-header {
  max-width: 760px;
  margin: 0 auto;
  padding: 1.5rem 2rem 0;
  width: 100%;
  box-sizing: border-box;
}
.workbook-app__page-header-row {
  display: flex;
  align-items: baseline;
  gap: 1rem;
  flex-wrap: wrap;
}
.workbook-app__page-cover-wrap {
  position: relative;
}
.workbook-app__page-cover-wrap:hover .workbook-app__page-cover-actions {
  opacity: 1;
}
.workbook-app__page-cover {
  display: block;
  width: 100%;
  max-height: 14rem;
  object-fit: cover;
  background: oklch(var(--bc) / 0.06);
}
.workbook-app__page-cover-actions {
  position: absolute;
  right: 0.75rem;
  bottom: 0.75rem;
  display: flex;
  gap: 0.25rem;
  opacity: 0;
  transition: opacity 0.15s ease;
}
.workbook-app__page-cover-btn {
  background: rgba(0, 0, 0, 0.55);
  color: oklch(var(--pc));
  border: 0;
  border-radius: 4px;
  font-size: 0.75rem;
  padding: 0.25rem 0.5rem;
  cursor: pointer;
}
.workbook-app__page-cover-btn:hover {
  background: rgba(0, 0, 0, 0.75);
}
.workbook-app__page-add-row {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
}
.workbook-app__page-add-btn {
  background: transparent;
  border: 0;
  color: oklch(var(--bc) / 0.65);
  font-size: 0.85rem;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
  cursor: pointer;
}
.workbook-app__page-add-btn:hover {
  background: oklch(var(--bc) / 0.06);
  color: oklch(var(--bc));
}
.workbook-app__page-icon {
  font-size: 1.2em;
  line-height: 1;
  margin-right: 0.25em;
  background: transparent;
  border: 0;
  padding: 0;
  cursor: pointer;
  font-family: inherit;
}
.workbook-app__page-icon:hover {
  filter: brightness(1.2);
}
.workbook-app__page-icon--static {
  cursor: default;
}
.workbook-app__page-icon--static:hover {
  filter: none;
}
.workbook-app__page-title {
  font-size: 1.875rem;
  font-weight: 700;
  margin: 0 0 0.25em;
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 0.4em;
}
.workbook-app__page-section {
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: oklch(var(--bc) / 0.65);
  margin-bottom: 0.25rem;
}
.workbook-app__page-description {
  color: oklch(var(--bc) / 0.65);
  margin: 0 0 1rem;
}
.workbook-app__save-status {
  font-size: 0.8rem;
  padding: 0.125rem 0.5rem;
  border-radius: 9999px;
  font-weight: 500;
}
.workbook-app__save-status--dirty {
  background: oklch(var(--bc) / 0.06);
  color: oklch(var(--wa));
}
.workbook-app__save-status--saving {
  background: oklch(var(--bc) / 0.06);
  color: oklch(var(--in));
}
.workbook-app__save-status--saved {
  background: oklch(var(--bc) / 0.06);
  color: oklch(var(--su));
}
.workbook-app__save-status--error {
  background: oklch(var(--er) / 0.15);
  color: oklch(var(--er));
}
</style>
