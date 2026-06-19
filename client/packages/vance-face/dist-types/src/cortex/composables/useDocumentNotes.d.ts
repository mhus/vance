import { type Ref } from 'vue';
import type { DocumentNoteDto } from '@vance/generated';
import type { CortexDocument } from '../types';
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
export declare function useDocumentNotes(doc: Ref<CortexDocument | null>): {
    notes: import("vue").ComputedRef<DocumentNoteDto[]>;
    linesWithNotes: import("vue").ComputedRef<number[]>;
    noteAtLine: (line: number) => DocumentNoteDto | null;
    addNote: (text: string, line?: number | null) => Promise<DocumentNoteDto | null>;
    updateNote: (noteId: string, patch: {
        text?: string;
        done?: boolean;
        line?: number;
    }) => Promise<DocumentNoteDto | null>;
    deleteNote: (noteId: string) => Promise<void>;
};
//# sourceMappingURL=useDocumentNotes.d.ts.map