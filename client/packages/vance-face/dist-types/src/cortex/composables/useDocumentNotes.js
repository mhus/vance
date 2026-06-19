import { computed, onBeforeUnmount, watch } from 'vue';
import { brainFetch } from '@vance/shared';
import { onDocumentNoteChanged } from '@/ws/wsConnectionStore';
/**
 * Note-CRUD wrapper around the {@code /brain/{tenant}/documents/{id}/notes}
 * endpoints. The {@code notes} list and {@link linesWithNotes} are
 * computed reactively from the host document's embedded {@code notes}
 * map; on add/update/delete the composable rewrites the local map so the
 * UI updates immediately, without waiting for the next document fetch.
 *
 * <p>Server-side {@code userId} is taken from the JWT, not from the
 * client — the create-request payload only carries text + optional line.
 *
 * <p>"Sentinel for explicit-unset of the line anchor" is intentionally
 * not exposed here: the v1 UI never un-anchors a note (the user either
 * deletes-and-readds or just leaves the stale line as-is). If/when
 * that's needed, the server already supports {@code Integer.MIN_VALUE}.
 */
export function useDocumentNotes(doc) {
    const notesMap = computed(() => doc.value?.notes ?? {});
    /**
     * Notes sorted by {@code (order ?? createdAtMs)} ascending. Notes
     * without an explicit order fall back to insertion-time, so a freshly
     * created note naturally lands at the bottom; drag-reorder writes an
     * explicit {@code order} (midpoint of neighbours) and that takes over.
     */
    const notes = computed(() => {
        const sortKey = (n) => typeof n.order === 'number' ? n.order : n.createdAtMs;
        return Object.values(notesMap.value).sort((a, b) => {
            const diff = sortKey(a) - sortKey(b);
            // Stable tie-breaker on id keeps the order deterministic when two
            // notes happen to share a sort key (rare with floats, common when
            // both fall back to createdAt at the same millisecond).
            return diff !== 0 ? diff : a.id.localeCompare(b.id);
        });
    });
    /** Lines that have at least one anchored note — fed to the CodeEditor gutter. */
    const linesWithNotes = computed(() => notes.value
        .map((n) => n.line)
        .filter((l) => typeof l === 'number'));
    /** Look up the first note anchored at {@code line}, if any. */
    function noteAtLine(line) {
        return notes.value.find((n) => n.line === line) ?? null;
    }
    async function addNote(text, line = null) {
        const d = doc.value;
        if (!d)
            return null;
        const body = { text, line: line ?? undefined };
        const created = await brainFetch('POST', `documents/${encodeURIComponent(d.id)}/notes`, { body });
        d.notes = { ...(d.notes ?? {}), [created.id]: created };
        return created;
    }
    async function updateNote(noteId, patch) {
        const d = doc.value;
        if (!d)
            return null;
        const body = {};
        if (patch.text !== undefined)
            body.text = patch.text;
        if (patch.done !== undefined)
            body.done = patch.done;
        if (patch.line !== undefined)
            body.line = patch.line;
        if (patch.order !== undefined)
            body.order = patch.order;
        const updated = await brainFetch('PUT', `documents/${encodeURIComponent(d.id)}/notes/${encodeURIComponent(noteId)}`, { body });
        d.notes = { ...(d.notes ?? {}), [noteId]: updated };
        return updated;
    }
    /**
     * Drag-reorder helper — compute a new {@code order} value for {@code noteId}
     * given the sorted index it should land at after the drop. Uses the
     * "midpoint of neighbours" technique so reordering N notes never
     * requires re-numbering. Adjacent boundary cases:
     * <ul>
     *   <li>landing at position 0 → {@code (first.order ?? createdAt) - 1}</li>
     *   <li>landing at the end → {@code (last.order ?? createdAt) + 1}</li>
     *   <li>else → midpoint of the two neighbours</li>
     * </ul>
     *
     * <p>Last-writer-wins is harmless: two concurrent reorders just settle
     * on the latest server value. Float precision is sufficient for the
     * 1000-note cap many orders of magnitude over.
     */
    async function moveNoteTo(noteId, toIndex) {
        const list = notes.value;
        const without = list.filter((n) => n.id !== noteId);
        const target = Math.max(0, Math.min(toIndex, without.length));
        const sortKey = (n) => typeof n.order === 'number' ? n.order : n.createdAtMs;
        let newOrder;
        if (without.length === 0) {
            newOrder = Date.now();
        }
        else if (target === 0) {
            newOrder = sortKey(without[0]) - 1;
        }
        else if (target >= without.length) {
            newOrder = sortKey(without[without.length - 1]) + 1;
        }
        else {
            newOrder = (sortKey(without[target - 1]) + sortKey(without[target])) / 2;
        }
        await updateNote(noteId, { order: newOrder });
    }
    async function deleteNote(noteId) {
        const d = doc.value;
        if (!d)
            return;
        await brainFetch('DELETE', `documents/${encodeURIComponent(d.id)}/notes/${encodeURIComponent(noteId)}`);
        const next = { ...(d.notes ?? {}) };
        delete next[noteId];
        d.notes = next;
    }
    // ─── Live updates (documents.note-changed) ──────────────────────
    //
    // Last-writer-wins: remote add/update writes the note verbatim into
    // the local map, remote delete drops it. Server already filters out
    // the local writer's echo via the X-Editor-Id header. The composable
    // swaps the subscription whenever the watched document's path
    // changes (different tab activated, doc renamed, …) and tears it
    // down on unmount.
    let unsubscribe = null;
    function attach(path) {
        unsubscribe = onDocumentNoteChanged(path, (notification) => {
            const d = doc.value;
            if (!d || d.path !== notification.path)
                return;
            const next = { ...(d.notes ?? {}) };
            if (notification.kind === 'deleted') {
                delete next[notification.noteId];
            }
            else if (notification.note) {
                next[notification.noteId] = notification.note;
            }
            d.notes = next;
        });
    }
    function detach() {
        if (unsubscribe) {
            try {
                unsubscribe();
            }
            catch { /* ignore */ }
            unsubscribe = null;
        }
    }
    watch(() => doc.value?.path ?? null, (path) => {
        detach();
        if (path)
            attach(path);
    }, { immediate: true });
    onBeforeUnmount(() => {
        detach();
    });
    return {
        notes,
        linesWithNotes,
        noteAtLine,
        addNote,
        updateNote,
        deleteNote,
        moveNoteTo,
    };
}
//# sourceMappingURL=useDocumentNotes.js.map