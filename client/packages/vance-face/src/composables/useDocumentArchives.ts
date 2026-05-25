import { ref, type Ref } from 'vue';
import type {
  DocumentArchiveDto,
  DocumentArchiveListResponse,
  DocumentArchiveSummary,
  DocumentDto,
} from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * Reactive wrapper around the document-archive REST endpoints. One instance
 * per editor — caches the version list for the currently-selected document
 * plus the currently-previewed archive entry. The owning editor calls
 * {@link load} when a document opens, {@link preview} when the user picks a
 * version, {@link restore} / {@link remove} for the two destructive actions.
 *
 * <p>Same posture as {@code useDocuments}: this composable owns its loading /
 * error state so the editor can render a banner without juggling Promise
 * lifecycles.
 */
export function useDocumentArchives(): {
  items: Ref<DocumentArchiveSummary[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  preview: Ref<DocumentArchiveDto | null>;
  previewLoading: Ref<boolean>;
  load: (documentId: string) => Promise<void>;
  open: (documentId: string, archiveId: string) => Promise<void>;
  clearPreview: () => void;
  remove: (documentId: string, archiveId: string) => Promise<boolean>;
  restore: (documentId: string, archiveId: string) => Promise<DocumentDto | null>;
} {
  const items = ref<DocumentArchiveSummary[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const preview = ref<DocumentArchiveDto | null>(null);
  const previewLoading = ref(false);

  async function load(documentId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const data = await brainFetch<DocumentArchiveListResponse>(
        'GET',
        `documents/${encodeURIComponent(documentId)}/archives`,
      );
      items.value = data.items ?? [];
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load archives.';
      items.value = [];
    } finally {
      loading.value = false;
    }
  }

  async function open(documentId: string, archiveId: string): Promise<void> {
    previewLoading.value = true;
    error.value = null;
    try {
      preview.value = await brainFetch<DocumentArchiveDto>(
        'GET',
        `documents/${encodeURIComponent(documentId)}/archives/${encodeURIComponent(archiveId)}`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load archive.';
      preview.value = null;
    } finally {
      previewLoading.value = false;
    }
  }

  function clearPreview(): void {
    preview.value = null;
  }

  async function remove(documentId: string, archiveId: string): Promise<boolean> {
    loading.value = true;
    error.value = null;
    try {
      await brainFetch<void>(
        'DELETE',
        `documents/${encodeURIComponent(documentId)}/archives/${encodeURIComponent(archiveId)}`,
      );
      items.value = items.value.filter((a: DocumentArchiveSummary) => a.id !== archiveId);
      if (preview.value?.id === archiveId) {
        preview.value = null;
      }
      return true;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete archive.';
      return false;
    } finally {
      loading.value = false;
    }
  }

  async function restore(
    documentId: string,
    archiveId: string,
  ): Promise<DocumentDto | null> {
    loading.value = true;
    error.value = null;
    try {
      const restored = await brainFetch<DocumentDto>(
        'POST',
        `documents/${encodeURIComponent(documentId)}/archives/${encodeURIComponent(archiveId)}/restore`,
      );
      // The restore itself archived the previous live content — refresh
      // the list so the new entry shows up.
      await load(documentId);
      return restored;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to restore archive.';
      return null;
    } finally {
      loading.value = false;
    }
  }

  return {
    items,
    loading,
    error,
    preview,
    previewLoading,
    load,
    open,
    clearPreview,
    remove,
    restore,
  };
}
