import { ref } from 'vue';
import { brainFetch, brainFetchText } from '@vance/shared';
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
export function useDocumentArchives() {
    const items = ref([]);
    const loading = ref(false);
    const error = ref(null);
    const preview = ref(null);
    const previewLoading = ref(false);
    async function load(documentId) {
        loading.value = true;
        error.value = null;
        try {
            const data = await brainFetch('GET', `documents/${encodeURIComponent(documentId)}/archives`);
            items.value = data.items ?? [];
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load archives.';
            items.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    async function open(documentId, archiveId) {
        previewLoading.value = true;
        error.value = null;
        try {
            const dto = await brainFetch('GET', `documents/${encodeURIComponent(documentId)}/archives/${encodeURIComponent(archiveId)}`);
            // DocumentArchiveDto.inlineText is null since the full-storage
            // migration; for textual mime types pull the body via the
            // archive-content endpoint and patch the cached fields so the
            // preview pane renders inline instead of falling through to the
            // generic "binary" fallback.
            if (isTextualMime(dto.mimeType)) {
                const body = await brainFetchText(`documents/${encodeURIComponent(documentId)}/archives/${encodeURIComponent(archiveId)}/content`);
                dto.inlineText = body ?? '';
                dto.inline = true;
            }
            preview.value = dto;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load archive.';
            preview.value = null;
        }
        finally {
            previewLoading.value = false;
        }
    }
    function isTextualMime(mimeType) {
        if (!mimeType)
            return false;
        const base = mimeType.split(';')[0].trim().toLowerCase();
        return (base.startsWith('text/')
            || base.includes('json')
            || base.includes('yaml')
            || base.includes('xml')
            || base === 'application/javascript'
            || base === 'application/typescript'
            || base === 'application/sql'
            || base === 'application/x-sh');
    }
    function clearPreview() {
        preview.value = null;
    }
    async function remove(documentId, archiveId) {
        loading.value = true;
        error.value = null;
        try {
            await brainFetch('DELETE', `documents/${encodeURIComponent(documentId)}/archives/${encodeURIComponent(archiveId)}`);
            items.value = items.value.filter((a) => a.id !== archiveId);
            if (preview.value?.id === archiveId) {
                preview.value = null;
            }
            return true;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to delete archive.';
            return false;
        }
        finally {
            loading.value = false;
        }
    }
    async function restore(documentId, archiveId) {
        loading.value = true;
        error.value = null;
        try {
            const restored = await brainFetch('POST', `documents/${encodeURIComponent(documentId)}/archives/${encodeURIComponent(archiveId)}/restore`);
            // The restore itself archived the previous live content — refresh
            // the list so the new entry shows up.
            await load(documentId);
            return restored;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to restore archive.';
            return null;
        }
        finally {
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
//# sourceMappingURL=useDocumentArchives.js.map