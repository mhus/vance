import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import type { CortexDocument, FolderNode } from '../types';
import {
  buildFolderTree,
  parentFolderOf,
  type FolderState,
} from '../folderTree';
import { brainFetch, brainFetchText, brainSendRaw, readKindFromBody } from '@vance/shared';
import { ensureKindsForDocument } from '@/platform/addonRegistry';
import type {
  AccentColor,
  DocumentDto,
  DocumentFolderListResponse,
  DocumentSummary,
  WriterRole,
} from '@vance/generated';

/**
 * Heuristic for "this document is not text we should pull as inline
 * content". Mirrors the same check in clientToolService.ts — images
 * and other binaries are surfaced by their renderer (e.g. ImageView
 * loads via documentContentUrl) and don't need their bytes streamed
 * into JS memory.
 */
export function isBinaryMime(mime: string | null | undefined): boolean {
  const m = (mime ?? '').toLowerCase();
  if (!m) return false;
  if (m.startsWith('image/')) return true;
  if (m.startsWith('audio/')) return true;
  if (m.startsWith('video/')) return true;
  if (m.startsWith('font/')) return true;
  // Office Open XML — docx/xlsx/pptx.
  if (m.startsWith('application/vnd.openxmlformats-officedocument.')) return true;
  // Legacy Office (.doc, .xls, .ppt) and other MS binaries.
  if (m.startsWith('application/vnd.ms-')) return true;
  // OpenDocument (.odt, .ods, .odp).
  if (m.startsWith('application/vnd.oasis.opendocument.')) return true;
  if (m === 'application/pdf') return true;
  if (m === 'application/zip' || m === 'application/x-zip-compressed') return true;
  if (m === 'application/x-tar' || m === 'application/gzip') return true;
  if (m === 'application/x-7z-compressed' || m === 'application/x-rar') return true;
  if (m === 'application/octet-stream') return true;
  if (m === 'application/x-msdownload') return true;
  if (m === 'application/wasm') return true;
  return false;
}

/**
 * Extension-based binary detection used when the server returned a
 * blank/unknown mime. Kept in sync with {@link isBinaryMime}'s coverage
 * — every entry here is "definitely not text we should round-trip
 * through a CodeEditor" because the bytes carry framing the editor
 * cannot reproduce.
 */
const BINARY_EXTS = [
  '.pdf',
  '.docx', '.xlsx', '.pptx', '.doc', '.xls', '.ppt',
  '.odt', '.ods', '.odp',
  '.zip', '.tar', '.gz', '.tgz', '.7z', '.rar',
  '.png', '.jpg', '.jpeg', '.gif', '.webp', '.bmp', '.ico',
  '.mp3', '.wav', '.ogg', '.flac', '.aac', '.m4a',
  '.mp4', '.mkv', '.avi', '.mov', '.webm',
  '.ttf', '.otf', '.woff', '.woff2', '.eot',
  '.exe', '.dll', '.so', '.dylib', '.wasm',
];

/**
 * Document-level binary check. Uses the mime when present, falls back
 * to extension when the mime is blank or generic. Routes binding
 * resolution to the read-only preview path and gates the save pipeline
 * so a stray dirty-flag can't overwrite a binary file with the empty
 * inlineText we (correctly) declined to load.
 */
export function isBinaryDoc(doc: {
  mimeType?: string | null;
  path: string;
}): boolean {
  if (isBinaryMime(doc.mimeType)) return true;
  const m = (doc.mimeType ?? '').toLowerCase();
  if (m) return false; // server gave us a non-binary mime — trust it.
  const path = doc.path.toLowerCase();
  return BINARY_EXTS.some((ext) => path.endsWith(ext));
}

interface CreateBody {
  path: string;
  title?: string | null;
  tags?: string[];
  mimeType?: string | null;
  inlineText?: string;
}

export interface MetaUpdateBody {
  title?: string | null;
  /**
   * Set the accent color. Sending {@code null} (or omitting the field)
   * leaves the current value untouched — use {@link clearColor} to
   * actively remove an existing color.
   */
  color?: AccentColor | null;
  /** Send {@code true} to clear an already-set color. */
  clearColor?: boolean;
  tags?: string[];
  mimeType?: string | null;
  /**
   * Move/rename via full path. Server re-derives {@code name} from the
   * trailing segment; for a pure rename the caller swaps the last
   * segment of the current path and leaves the rest untouched.
   */
  newPath?: string | null;
  autoSummary?: boolean;
  summaryDirty?: boolean;
  ragEnabled?: 'auto' | 'on' | 'off';
}

/**
 * Holds open-tabs state, the active-tab pointer, and the project's full
 * document list for the Cortex view. Persists nothing across reloads in
 * v1 — re-opening a document after reload is one click in the tree.
 *
 * Talks to the general {@code /brain/{tenant}/documents} endpoints — not
 * the ScriptCortex-specific {@code /scripts} endpoints — so Cortex sees
 * all document types in a project, not only scripts.
 *
 * The tree loads one folder at a time ({@link loadFolder}), not the project.
 * It used to fetch a flat list with a large page size and derive the folders
 * from the paths; that was wrong twice over — the server clamps the list to
 * 200 rows however many are asked for, so the tree was a silent fragment in
 * any larger project, and a mounted source has no upper bound at all, so
 * "every document" is not a quantity a client can hold.
 */
/**
 * Current text selection inside the active tab's editor. {@code null}
 * when nothing is selected (or the active doc isn't a text doc). The
 * doc_get_selection client tool reads this; DocumentTabShell writes
 * it via the CodeEditor's {@code selection-changed} emit.
 */
export interface CortexSelection {
  docId: string;
  docPath: string;
  from: number;
  to: number;
  text: string;
}

export const useCortexStore = defineStore('cortex', () => {
  const projectId = ref<string | null>(null);
  const files = ref<CortexDocument[]>([]);
  const openTabs = ref<CortexDocument[]>([]);
  const activeTabId = ref<string | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const currentSelection = ref<CortexSelection | null>(null);

  // Client-only virtual folders. The server has no folder entity —
  // folders exist implicitly via document path prefixes. To let the
  // user "stage" an empty folder as a drop target before any document
  // lives there, we merge these path strings into the {@link fileTree}
  // computation. Wiped on next {@link loadList} (and not persisted),
  // matching the spec: a virtual folder vanishes on refresh unless a
  // file has since materialised it.
  const virtualFolders = ref<Set<string>>(new Set());

  /**
   * What is known per folder, keyed by folder path ({@code ''} is the root).
   * The tree's structure comes from here, not from aggregating document paths
   * — see {@code folderTree.ts}.
   */
  const folderStates = ref<Map<string, FolderState>>(new Map());

  /**
   * Which folders are open. Lives here rather than in the sidebar because it
   * is no longer a view detail: expanding a folder is what loads it, and a
   * reload has to restore the same set.
   */
  const expanded = ref<Set<string>>(new Set(['']));

  /**
   * Files per folder request. The server clamps this to 200 whatever we ask
   * for, so asking for more only hides the truncation — the tree reports the
   * remainder instead ({@code FolderNode.moreFiles}).
   */
  const FOLDER_PAGE_SIZE = 200;

  /**
   * Keep a loaded folder's counters honest after a local add/remove, so the
   * "N more" row does not claim files that are already on screen (or hide the
   * ones that are not). Only touches folders we actually loaded — for an
   * unloaded one there is no count to correct.
   */
  function bumpFolderCount(path: string, delta: number): void {
    const state = folderStates.value.get(path);
    if (!state?.loaded) return;
    patchFolderState(path, {
      totalFiles: Math.max(0, state.totalFiles + delta),
      loadedFiles: Math.max(0, state.loadedFiles + delta),
    });
  }

  function patchFolderState(path: string, patch: Partial<FolderState>): void {
    const next = new Map(folderStates.value);
    const before = next.get(path) ?? {
      folders: [],
      loaded: false,
      loading: false,
      error: null,
      totalFiles: 0,
      loadedFiles: 0,
    };
    next.set(path, { ...before, ...patch });
    folderStates.value = next;
  }

  const activeTab = computed<CortexDocument | null>(() => {
    if (!activeTabId.value) return null;
    return openTabs.value.find((t) => t.id === activeTabId.value) ?? null;
  });

  function summaryToDocument(s: DocumentSummary): CortexDocument {
    return {
      id: s.id,
      path: s.path,
      name: s.name,
      title: s.title ?? null,
      color: s.color ?? null,
      mimeType: s.mimeType ?? null,
      kind: s.kind ?? null,
      inlineText: '', // populated on full load via openFile
      dirty: false,
      baselineInlineText: '',
    };
  }

  function dtoToDocument(d: DocumentDto): CortexDocument {
    const text = d.inlineText ?? '';
    return {
      id: d.id,
      path: d.path,
      name: d.name,
      title: d.title ?? null,
      color: d.color ?? null,
      mimeType: d.mimeType ?? null,
      kind: d.kind ?? null,
      inlineText: text,
      dirty: false,
      // Fresh load — baseline equals the content we just received.
      baselineInlineText: text,
      lastDeepReviewedHash: d.lastDeepReviewedHash ?? null,
      lastDeepReviewWarningsJson: d.lastDeepReviewWarningsJson ?? null,
      tags: d.tags ?? [],
      size: d.size ?? null,
      createdAtMs: d.createdAtMs ?? null,
      createdBy: d.createdBy ?? null,
      summary: d.summary ?? null,
      summarizedAtMs: d.summarizedAtMs ?? null,
      autoSummary: d.autoSummary ?? null,
      summaryDirty: d.summaryDirty ?? null,
      ragEnabled: d.ragEnabled ?? null,
      headers: d.headers ? { ...d.headers } : {},
      // Defensive copy — server returns a plain map; we want our own
      // reference so the composable's d.notes = {...} mutations are
      // local to this tab and don't share with other tab copies.
      notes: d.notes ? { ...d.notes } : {},
      lockedFor: d.lockedFor ? [...d.lockedFor] : [],
    };
  }

  /**
   * Persist editable metadata fields (title, tags) without touching
   * the document body. Mirrors the {@code PUT /documents/{id}}
   * surface the legacy DocumentApp used.
   */
  /**
   * Patch the soft document-lock — replaces {@code lockedFor} outright.
   * Server-side normalisation auto-adds {@code AI} when {@code USER}
   * or {@code KIT} is present, so caller can submit just the user's
   * intent ("USER lock") and the response carries the canonical set.
   *
   * <p>Throws on 409 (returned when the document is already locked at
   * a level that blocks the caller — should not normally happen for
   * the lock endpoint itself, but kept for symmetry).
   */
  async function updateLock(id: string, lockedFor: WriterRole[]): Promise<void> {
    const dto = await brainFetch<DocumentDto>(
      'PATCH',
      `documents/${encodeURIComponent(id)}/lock`,
      { body: { lockedFor } },
    );
    const tabIdx = openTabs.value.findIndex((t) => t.id === id);
    if (tabIdx >= 0) {
      const tab = openTabs.value[tabIdx];
      openTabs.value = [
        ...openTabs.value.slice(0, tabIdx),
        { ...tab, lockedFor: dto.lockedFor ? [...dto.lockedFor] : [] },
        ...openTabs.value.slice(tabIdx + 1),
      ];
    }
  }

  async function updateMeta(id: string, body: MetaUpdateBody): Promise<void> {
    const dto = await brainFetch<DocumentDto>(
      'PUT',
      `documents/${encodeURIComponent(id)}`,
      { body },
    );
    const tabIdx = openTabs.value.findIndex((t) => t.id === id);
    if (tabIdx >= 0) {
      const tab = openTabs.value[tabIdx];
      const preservedText = tab.inlineText;
      const preservedDirty = tab.dirty;
      const fresh = dtoToDocument(dto);
      openTabs.value = [
        ...openTabs.value.slice(0, tabIdx),
        { ...fresh, inlineText: preservedText, dirty: preservedDirty, baselineInlineText: tab.baselineInlineText },
        ...openTabs.value.slice(tabIdx + 1),
      ];
    }
    const fIdx = files.value.findIndex((f) => f.id === id);
    if (fIdx >= 0) {
      files.value = [
        ...files.value.slice(0, fIdx),
        {
          ...files.value[fIdx],
          path: dto.path,
          name: dto.name,
          title: dto.title ?? null,
          color: dto.color ?? null,
          mimeType: dto.mimeType ?? null,
        },
        ...files.value.slice(fIdx + 1),
      ];
    }
  }

  /**
   * Load the project's root folder — the tree's entry point.
   *
   * <p>One folder, not the project. The tree used to fetch a flat list of
   * every document and derive its folders from the paths, which broke twice
   * over: the server clamps that list to 200 rows however many are asked for,
   * so in any larger project the tree was silently a fragment; and a mounted
   * source ({@code _ext}) has no upper bound at all, so "all documents" is not
   * a quantity a client can hold. Folders now load on expand
   * ({@link loadFolder}).
   */
  async function loadList(pid: string): Promise<void> {
    const switching = projectId.value !== pid;
    projectId.value = pid;
    if (switching) {
      files.value = [];
      folderStates.value = new Map();
      expanded.value = new Set(['']);
    }
    // Virtual folders are ephemeral by design — a refresh discards
    // any the user staged that didn't get a real file moved into it.
    virtualFolders.value = new Set();
    await loadFolder('', { force: true });
    // Re-read whatever the user had open, so a refresh keeps the shape of the
    // tree it refreshed instead of collapsing to the root.
    const openFolders = [...expanded.value].filter((p) => p !== '');
    for (const path of openFolders) {
      await loadFolder(path, { force: true });
    }
  }

  /**
   * Fetch one folder: its direct subfolders and one page of its files.
   *
   * <p>Idempotent by default — an already-loaded folder is left alone, which
   * is what makes re-expanding a folder free. {@code force} is for the
   * explicit reload and for the write paths that changed the folder's content.
   */
  async function loadFolder(
    path: string,
    opts: { force?: boolean } = {},
  ): Promise<void> {
    const pid = projectId.value;
    if (!pid) return;
    const current = folderStates.value.get(path);
    if (current && !opts.force && (current.loaded || current.loading)) return;

    patchFolderState(path, { loading: true, error: null });
    // The root's spinner is the tree's spinner; a subfolder's is its own row,
    // so it must not blank the whole sidebar.
    if (path === '') loading.value = true;
    try {
      const params = new URLSearchParams({
        projectId: pid,
        path,
        page: '0',
        size: String(FOLDER_PAGE_SIZE),
      });
      const data = await brainFetch<DocumentFolderListResponse>(
        'GET',
        `documents/folder?${params}`,
      );
      const rows = (data.files ?? []).map(summaryToDocument);
      // Replace this folder's rows rather than merging: a file deleted or
      // moved away server-side has to disappear here too, and every row we
      // hold for this folder came from this same call.
      const others = files.value.filter((f) => parentFolderOf(f.path) !== path);
      files.value = [...others, ...rows];
      patchFolderState(path, {
        folders: [...(data.folders ?? [])],
        loaded: true,
        loading: false,
        // A mounted folder can answer 200 and still not hold what the source
        // holds — a failed refresh, or a folder too large to materialise. The
        // rows are then stale or absent, and only this says so; an untouched
        // `error` would render the folder as plainly empty.
        error: data.mountFailure ?? null,
        totalFiles: data.totalCount ?? rows.length,
        loadedFiles: rows.length,
      });
      if (path === '') error.value = null;
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to load folder.';
      patchFolderState(path, { loading: false, error: message });
      // The root failing is the tree failing; a subfolder failing is a row
      // that says so, and must not replace the tree with an error.
      if (path === '') error.value = message;
    } finally {
      if (path === '') loading.value = false;
    }
  }

  /** Expand or collapse a folder; expanding loads it if it is new. */
  async function toggleFolder(path: string): Promise<void> {
    const next = new Set(expanded.value);
    if (next.has(path)) {
      next.delete(path);
      expanded.value = next;
      return;
    }
    next.add(path);
    expanded.value = next;
    await loadFolder(path);
  }

  /**
   * Make a document path visible: load and expand every folder on the way to
   * it. Used by the deep link, by "reveal active file" and after a create —
   * all three know a path and nothing else, and the folders along it may never
   * have been opened.
   */
  async function expandTo(documentPath: string): Promise<void> {
    const segments = parentFolderOf(documentPath).split('/').filter(Boolean);
    const next = new Set(expanded.value);
    let prefix = '';
    // Sequential on purpose: each level's listing is what tells us the next
    // one exists, and a burst of parallel requests for a path nobody has
    // opened is exactly the load this refactor removes.
    await loadFolder('');
    for (const seg of segments) {
      prefix = prefix ? `${prefix}/${seg}` : seg;
      next.add(prefix);
      await loadFolder(prefix);
    }
    expanded.value = next;
  }

  /**
   * Path of the content endpoint for a tab, carrying its read parameters
   * when it has any. One helper because every read of a parameterised view
   * has to append them — a caller that forgets gets the base document back
   * and no indication that it asked for something else.
   */
  function contentPath(id: string, viewQuery?: string): string {
    const base = `documents/${encodeURIComponent(id)}/content`;
    return viewQuery ? `${base}?${viewQuery}` : base;
  }

  /**
   * Open a document as a tab, optionally as a parameterised view.
   *
   * <p>An already-open tab is re-read when {@code viewQuery} differs from
   * what it currently shows — same row, different answer, so switching
   * windows is a reload rather than a second tab. Opening the same view
   * again is just an activation.
   */
  async function openFile(id: string, viewQuery?: string): Promise<void> {
    const existing = openTabs.value.find((t) => t.id === id);
    if (existing) {
      activeTabId.value = id;
      if ((existing.viewQuery ?? undefined) !== (viewQuery || undefined)) {
        existing.viewQuery = viewQuery || undefined;
        await reloadTab(id);
      }
      return;
    }
    // Two-step load after the inline→storage migration: DTO carries
    // metadata only, the body lives behind /documents/{id}/content. See
    // composables/useDocuments.ts loadContent() for the same pattern.
    const dto = await brainFetch<DocumentDto>(
      'GET',
      `documents/${encodeURIComponent(id)}`,
    );
    const file = dtoToDocument(dto);
    file.viewQuery = viewQuery || undefined;
    if (!isBinaryMime(dto.mimeType)) {
      const text = await brainFetchText(contentPath(id, file.viewQuery));
      file.inlineText = text ?? '';
      // dtoToDocument seeded baseline from the (null) DTO inlineText;
      // overwrite it with the actually-loaded body so the live-change
      // reaction can compute "dirty" correctly from the first edit.
      file.baselineInlineText = file.inlineText;
    }
    await ensureKindAddonLoaded(file);
    openTabs.value = [...openTabs.value, file];
    activeTabId.value = id;
  }

  /**
   * Pull in the addon that owns this document's kind, before the tab is handed
   * to the shell.
   *
   * <p>Addon kinds are no longer registered at boot (see
   * platform/addonRegistry.ts) — the fetch happens here, on a path that is
   * already async because it just loaded the body. Doing it here rather than in
   * the shell is what keeps `resolveBinding` synchronous *and* keeps the reader
   * from seeing the catch-all CodeEditor render the raw YAML for a frame before
   * the real view swaps in.
   *
   * <p>The kind is read the same way `docTypeRegistry.effectiveKind` reads it:
   * a mounted document's row carries no kind until something reads its body, so
   * the body we just loaded is the better source. That logic is duplicated
   * rather than imported because docTypeRegistry imports `isBinaryDoc` from
   * this module — importing it back would close a cycle.
   */
  async function ensureKindAddonLoaded(file: CortexDocument): Promise<void> {
    const kind = file.kind ?? readKindFromBody(file.inlineText, file.mimeType) ?? null;
    await ensureKindsForDocument(kind, file.headers?.app ?? null);
  }

  function setActiveTab(id: string): void {
    activeTabId.value = id;
  }

  /**
   * Move a document to a new path. Used by the file tree's drag &
   * drop — drag a file onto a folder row to call this with the folder's
   * path + the file's basename. The server validates conflicts (409 on
   * existing path) and we surface the error to the caller.
   */
  async function moveFile(id: string, newPath: string): Promise<void> {
    const updated = await brainFetch<DocumentDto>(
      'PUT',
      `documents/${encodeURIComponent(id)}`,
      { body: { newPath } },
    );
    const patch = {
      path: updated.path,
      name: updated.name,
    };
    const fIdx = files.value.findIndex((f) => f.id === id);
    if (fIdx >= 0) {
      files.value = [
        ...files.value.slice(0, fIdx),
        { ...files.value[fIdx], ...patch },
        ...files.value.slice(fIdx + 1),
      ];
    }
    const tIdx = openTabs.value.findIndex((t) => t.id === id);
    if (tIdx >= 0) {
      openTabs.value = [
        ...openTabs.value.slice(0, tIdx),
        { ...openTabs.value[tIdx], ...patch },
        ...openTabs.value.slice(tIdx + 1),
      ];
    }
  }

  /**
   * Upload one external (OS file system) file into the project at the
   * given folder. The server picks a unique path if the basename
   * collides — caller doesn't have to dedupe. Returns the new document
   * summary so the caller can refresh visual state or open it as a tab.
   */
  async function uploadExternalFile(
    file: File,
    folderPath: string,
  ): Promise<CortexDocument> {
    if (!projectId.value) throw new Error('No project selected');
    const form = new FormData();
    form.append('file', file);
    const targetPath = folderPath ? `${folderPath}/${file.name}` : file.name;
    form.append('path', targetPath);
    if (file.type) form.append('mimeType', file.type);
    const params = new URLSearchParams({ projectId: projectId.value });
    const dto = await brainFetch<DocumentDto>(
      'POST',
      `documents/upload?${params}`,
      { body: form },
    );
    const created = dtoToDocument(dto);
    files.value = [...files.value, created];
    bumpFolderCount(parentFolderOf(created.path), 1);
    return created;
  }

  /**
   * Re-fetch metadata + content for an already-open tab and replace the
   * in-memory copy. Any local dirty edits on that tab are dropped — the
   * caller is responsible for confirming with the user beforehand.
   */
  async function reloadTab(id: string): Promise<void> {
    const idx = openTabs.value.findIndex((t) => t.id === id);
    if (idx < 0) return;
    // Carried over from the tab, not re-derived: a reload of a
    // parameterised view that dropped the parameters would silently swap
    // the content for the base document's.
    const viewQuery = openTabs.value[idx].viewQuery;
    const dto = await brainFetch<DocumentDto>(
      'GET',
      `documents/${encodeURIComponent(id)}`,
    );
    const fresh = dtoToDocument(dto);
    fresh.viewQuery = viewQuery;
    if (!isBinaryMime(dto.mimeType)) {
      const text = await brainFetchText(contentPath(id, viewQuery));
      fresh.inlineText = text ?? '';
      fresh.baselineInlineText = fresh.inlineText;
    }
    // A reload can change the kind — a `$meta.kind:` edit, or a parameterised
    // view that renders as something else — so the addon lookup runs again.
    await ensureKindAddonLoaded(fresh);
    openTabs.value = [
      ...openTabs.value.slice(0, idx),
      fresh,
      ...openTabs.value.slice(idx + 1),
    ];
  }

  function closeTab(id: string): void {
    const idx = openTabs.value.findIndex((t) => t.id === id);
    if (idx < 0) return;
    openTabs.value = openTabs.value.filter((t) => t.id !== id);
    if (activeTabId.value === id) {
      activeTabId.value =
        openTabs.value.length === 0
          ? null
          : openTabs.value[Math.max(0, idx - 1)].id;
    }
  }

  function updateActiveContent(text: string): void {
    const tab = activeTab.value;
    if (!tab) return;
    // Binary documents have an empty {@link CortexDocument.inlineText}
    // (we deliberately don't fetch their bytes as text). Any update
    // path that reached here would mark the tab dirty and queue a save
    // that overwrites the server file with empty bytes — refuse.
    if (isBinaryDoc(tab)) return;
    // A parameterised view has nothing to write back to: the content was
    // computed for a set of read parameters, while a save would replace
    // the *document* — a different thing that happens to share the row.
    if (tab.viewQuery) return;
    tab.inlineText = text;
    tab.dirty = true;
  }

  async function saveTab(id: string): Promise<void> {
    const tab = openTabs.value.find((t) => t.id === id);
    if (!tab || !tab.dirty) return;
    // Defense in depth, same shape as the binary guard below: whatever
    // marked a view tab dirty, the save must not go out.
    if (tab.viewQuery) {
      tab.dirty = false;
      return;
    }
    // Defense in depth: same reason as {@link updateActiveContent}. A
    // binary doc that somehow has dirty=true (race, stale state) must
    // not get its bytes replaced with our blank inlineText.
    if (isBinaryDoc(tab)) {
      tab.dirty = false;
      return;
    }
    // Content lives at /documents/{id}/content after the inline→storage
    // migration. The body is the raw text (not JSON); Content-Type
    // carries the doc's mime so the server can re-classify on save.
    // See composables/useDocuments.ts replaceContent() for the canonical
    // pattern.
    const mime = (tab.mimeType ?? '').trim() || 'text/plain';
    const dto = await brainSendRaw<DocumentDto>(
      'PUT',
      `documents/${encodeURIComponent(tab.id)}/content`,
      tab.inlineText,
      `${mime}; charset=utf-8`,
    );
    // The server DTO has inlineText=null after migration — keep our
    // local copy so Vue doesn't redraw the editor with an empty body.
    const preservedText = tab.inlineText;
    const fresh = dtoToDocument(dto);
    Object.assign(tab, fresh);
    tab.inlineText = preservedText;
    // Save succeeded → the editor buffer is the new baseline. Without
    // this the live-change reaction would still see the tab as dirty
    // and pop a banner on the next remote echo.
    tab.baselineInlineText = preservedText;
    tab.dirty = false;
    const li = files.value.findIndex((f) => f.id === tab.id);
    if (li >= 0) {
      files.value[li] = {
        ...files.value[li],
        path: dto.path,
        name: dto.name,
        title: dto.title ?? null,
        mimeType: dto.mimeType ?? files.value[li].mimeType,
      };
    }
  }

  async function saveActive(): Promise<void> {
    if (!activeTabId.value) return;
    await saveTab(activeTabId.value);
  }

  /**
   * Flush every tab with pending edits. Sequential to keep server-side
   * order predictable — tabs are few, so we don't need parallelism.
   */
  async function saveAllDirty(): Promise<void> {
    const dirtyTabs = openTabs.value.filter((t) => t.dirty);
    for (const t of dirtyTabs) {
      try {
        await saveTab(t.id);
      } catch (e) {
        console.warn(`Auto-save failed for ${t.path}`, e);
      }
    }
  }

  async function createFile(body: CreateBody): Promise<CortexDocument> {
    if (!projectId.value) throw new Error('No project selected');
    const params = new URLSearchParams({ projectId: projectId.value });
    const dto = await brainFetch<DocumentDto>(
      'POST',
      `documents?${params}`,
      { body },
    );
    const file = dtoToDocument(dto);
    // The create response carries metadata only — bodies live behind
    // /documents/{id}/content since the inline→storage migration. Seed
    // both the visible text and the dirty-baseline from the request
    // we just sent so the new tab reflects the templated/typed stub
    // (and a later save does not overwrite the server copy with '').
    const initialText = body.inlineText ?? '';
    file.inlineText = initialText;
    file.baselineInlineText = initialText;
    files.value = [...files.value, summaryToDocument({
      id: dto.id,
      projectId: dto.projectId,
      path: dto.path,
      name: dto.name,
      title: dto.title,
      mimeType: dto.mimeType,
      size: dto.size,
      tags: dto.tags ?? [],
      createdAtMs: dto.createdAtMs,
      createdBy: dto.createdBy,
      inline: dto.inline,
      kind: dto.kind,
    })];
    bumpFolderCount(parentFolderOf(dto.path), 1);
    openTabs.value = [...openTabs.value, file];
    activeTabId.value = file.id;
    return file;
  }

  function setSelection(sel: CortexSelection | null): void {
    currentSelection.value = sel;
  }

  function clearSelection(): void {
    currentSelection.value = null;
  }

  async function deleteFile(id: string): Promise<void> {
    await brainFetch<void>('DELETE', `documents/${encodeURIComponent(id)}`);
    const gone = files.value.find((f) => f.id === id);
    files.value = files.value.filter((f) => f.id !== id);
    if (gone) bumpFolderCount(parentFolderOf(gone.path), -1);
    closeTab(id);
  }

  /**
   * Stage an empty folder so it appears in the tree as a drop target.
   * The path is normalised (trimmed, slashes stripped). No-op for an
   * empty path or a path that already corresponds to an existing file
   * folder (insertion is idempotent — {@link fileTree}'s loop dedupes
   * by path).
   */
  function addVirtualFolder(path: string): void {
    const normalised = path.trim().replace(/^\/+|\/+$/g, '');
    if (!normalised) return;
    virtualFolders.value = new Set(virtualFolders.value).add(normalised);
  }

  /**
   * The folder tree as far as it has been loaded. Structure comes from the
   * folder listings, not from aggregating document paths — see
   * {@code folderTree.ts} for why that distinction is the whole refactor.
   */
  const fileTree = computed<FolderNode>(() =>
    buildFolderTree({
      folderStates: folderStates.value,
      files: files.value,
      virtualFolders: virtualFolders.value,
    }),
  );

  return {
    projectId,
    files,
    openTabs,
    activeTabId,
    activeTab,
    loading,
    error,
    fileTree,
    expanded,
    loadList,
    loadFolder,
    toggleFolder,
    expandTo,
    openFile,
    reloadTab,
    moveFile,
    uploadExternalFile,
    setActiveTab,
    closeTab,
    updateActiveContent,
    saveActive,
    saveTab,
    saveAllDirty,
    createFile,
    deleteFile,
    updateMeta,
    updateLock,
    addVirtualFolder,
    currentSelection,
    setSelection,
    clearSelection,
  };
});
