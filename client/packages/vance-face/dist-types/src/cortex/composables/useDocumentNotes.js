import { computed } from 'vue';
import { brainFetch } from '@vance/shared';
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
    /** Notes sorted by createdAt ascending — same order as Mongo's LinkedHashMap insertion. */
    const notes = computed(() => Object.values(notesMap.value).sort((a, b) => a.createdAtMs - b.createdAtMs));
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
        const updated = await brainFetch('PUT', `documents/${encodeURIComponent(d.id)}/notes/${encodeURIComponent(noteId)}`, { body });
        d.notes = { ...(d.notes ?? {}), [noteId]: updated };
        return updated;
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
    return {
        notes,
        linesWithNotes,
        noteAtLine,
        addNote,
        updateNote,
        deleteNote,
    };
}
//# sourceMappingURL=useDocumentNotes.js.map