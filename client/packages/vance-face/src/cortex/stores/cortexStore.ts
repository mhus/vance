import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import type { CortexDocument, FolderNode } from '../types';
import { brainFetch, brainFetchText, brainSendRaw } from '@vance/shared';
import type {
  DocumentDto,
  DocumentListResponse,
  DocumentSummary,
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

/**
 * Holds open-tabs state, the active-tab pointer, and the project's full
 * document list for the Cortex view. Persists nothing across reloads in
 * v1 — re-opening a document after reload is one click in the tree.
 *
 * Talks to the general {@code /brain/{tenant}/documents} endpoints — not
 * the ScriptCortex-specific {@code /scripts} endpoints — so Cortex sees
 * all document types in a project, not only scripts.
 *
 * v1 fetches the whole project's document list in one paged call with a
 * large page size. If projects grow past ~500 documents, we'll switch to
 * a tree-friendly endpoint or virtual scrolling.
 */
/**
 * Current text selection inside the active tab's editor. {@code null}
 * when nothing is selected (or the active doc isn't a text doc). The
 * cortex_get_selection client tool reads this; DocumentTabShell writes
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
      mimeType: s.mimeType ?? null,
      kind: s.kind ?? null,
      inlineText: '', // populated on full load via openFile
      dirty: false,
    };
  }

  function dtoToDocument(d: DocumentDto): CortexDocument {
    return {
      id: d.id,
      path: d.path,
      name: d.name,
      title: d.title ?? null,
      mimeType: d.mimeType ?? null,
      kind: d.kind ?? null,
      inlineText: d.inlineText ?? '',
      dirty: false,
      lastDeepReviewedHash: d.lastDeepReviewedHash ?? null,
      lastDeepReviewWarningsJson: d.lastDeepReviewWarningsJson ?? null,
    };
  }

  async function loadList(pid: string): Promise<void> {
    projectId.value = pid;
    loading.value = true;
    error.value = null;
    try {
      const params = new URLSearchParams({
        projectId: pid,
        page: '0',
        size: '500',
      });
      const data = await brainFetch<DocumentListResponse>(
        'GET',
        `documents?${params}`,
      );
      files.value = (data.items ?? []).map(summaryToDocument);
      // Virtual folders are ephemeral by design — a refresh discards
      // any the user staged that didn't get a real file moved into it.
      virtualFolders.value = new Set();
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load documents.';
    } finally {
      loading.value = false;
    }
  }

  async function openFile(id: string): Promise<void> {
    const existing = openTabs.value.find((t) => t.id === id);
    if (existing) {
      activeTabId.value = id;
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
    if (!isBinaryMime(dto.mimeType)) {
      const text = await brainFetchText(
        `documents/${encodeURIComponent(id)}/content`,
      );
      file.inlineText = text ?? '';
    }
    openTabs.value = [...openTabs.value, file];
    activeTabId.value = id;
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
    const dto = await brainFetch<DocumentDto>(
      'GET',
      `documents/${encodeURIComponent(id)}`,
    );
    const fresh = dtoToDocument(dto);
    if (!isBinaryMime(dto.mimeType)) {
      const text = await brainFetchText(
        `documents/${encodeURIComponent(id)}/content`,
      );
      fresh.inlineText = text ?? '';
    }
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
    tab.inlineText = text;
    tab.dirty = true;
  }

  async function saveTab(id: string): Promise<void> {
    const tab = openTabs.value.find((t) => t.id === id);
    if (!tab || !tab.dirty) return;
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
    files.value = files.value.filter((f) => f.id !== id);
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
   * Group the file list into a recursive folder tree based on
   * forward-slash-separated path segments. Files at the root sit
   * directly under the synthetic root node with path === "".
   */
  const fileTree = computed<FolderNode>(() => {
    const root: FolderNode = { path: '', name: '', children: [], files: [] };
    const folderIndex = new Map<string, FolderNode>();
    folderIndex.set('', root);
    for (const f of files.value) {
      const segments = f.path.split('/');
      const fileName = segments.pop()!;
      let current = root;
      let prefix = '';
      for (const seg of segments) {
        prefix = prefix ? `${prefix}/${seg}` : seg;
        let next = folderIndex.get(prefix);
        if (!next) {
          next = { path: prefix, name: seg, children: [], files: [] };
          folderIndex.set(prefix, next);
          current.children.push(next);
        }
        current = next;
      }
      current.files.push({ ...f, name: fileName });
    }
    // Merge in virtual (file-less) folders. Same walk as the file
    // loop, just without anything to push at the leaf — empty
    // FolderNodes get created along the way as needed.
    for (const vpath of virtualFolders.value) {
      const segments = vpath.split('/');
      let current = root;
      let prefix = '';
      for (const seg of segments) {
        prefix = prefix ? `${prefix}/${seg}` : seg;
        let next = folderIndex.get(prefix);
        if (!next) {
          next = { path: prefix, name: seg, children: [], files: [] };
          folderIndex.set(prefix, next);
          current.children.push(next);
        }
        current = next;
      }
    }
    function sortNode(n: FolderNode): void {
      n.children.sort((a, b) => a.name.localeCompare(b.name));
      n.files.sort((a, b) => a.name.localeCompare(b.name));
      n.children.forEach(sortNode);
    }
    sortNode(root);
    return root;
  });

  return {
    projectId,
    files,
    openTabs,
    activeTabId,
    activeTab,
    loading,
    error,
    fileTree,
    loadList,
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
    addVirtualFolder,
    currentSelection,
    setSelection,
    clearSelection,
  };
});
