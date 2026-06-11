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
function isBinaryMime(mime: string | null | undefined): boolean {
  const m = (mime ?? '').toLowerCase();
  if (!m) return false;
  if (m.startsWith('image/')) return true;
  if (m.startsWith('audio/')) return true;
  if (m.startsWith('video/')) return true;
  if (m === 'application/pdf' || m === 'application/zip'
      || m === 'application/octet-stream') return true;
  return false;
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
 * cortex_get_selection client tool reads this; CodeTabRenderer writes
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
      inlineText: d.inlineText ?? '',
      dirty: false,
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
    tab.inlineText = text;
    tab.dirty = true;
  }

  async function saveTab(id: string): Promise<void> {
    const tab = openTabs.value.find((t) => t.id === id);
    if (!tab || !tab.dirty) return;
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
    setActiveTab,
    closeTab,
    updateActiveContent,
    saveActive,
    saveTab,
    saveAllDirty,
    createFile,
    deleteFile,
    currentSelection,
    setSelection,
    clearSelection,
  };
});
