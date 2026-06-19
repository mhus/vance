import { computed, onBeforeUnmount, watch, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type {
  DocumentNoteCreateRequest,
  DocumentNoteDto,
  DocumentNoteUpdateRequest,
} from '@vance/generated';
import { onDocumentNoteChanged } from '@/ws/wsConnectionStore';
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
export function useDocumentNotes(doc: Ref<CortexDocument | null>) {
  const notesMap = computed<Record<string, DocumentNoteDto>>(
    () => doc.value?.notes ?? {},
  );

  /** Notes sorted by createdAt ascending — same order as Mongo's LinkedHashMap insertion. */
  const notes = computed<DocumentNoteDto[]>(() =>
    Object.values(notesMap.value).sort((a, b) => a.createdAtMs - b.createdAtMs),
  );

  /** Lines that have at least one anchored note — fed to the CodeEditor gutter. */
  const linesWithNotes = computed<number[]>(() =>
    notes.value
      .map((n) => n.line)
      .filter((l): l is number => typeof l === 'number'),
  );

  /** Look up the first note anchored at {@code line}, if any. */
  function noteAtLine(line: number): DocumentNoteDto | null {
    return notes.value.find((n) => n.line === line) ?? null;
  }

  async function addNote(text: string, line: number | null = null): Promise<DocumentNoteDto | null> {
    const d = doc.value;
    if (!d) return null;
    const body: DocumentNoteCreateRequest = { text, line: line ?? undefined };
    const created = await brainFetch<DocumentNoteDto>(
      'POST',
      `documents/${encodeURIComponent(d.id)}/notes`,
      { body },
    );
    d.notes = { ...(d.notes ?? {}), [created.id]: created };
    return created;
  }

  async function updateNote(
    noteId: string,
    patch: { text?: string; done?: boolean; line?: number },
  ): Promise<DocumentNoteDto | null> {
    const d = doc.value;
    if (!d) return null;
    const body: DocumentNoteUpdateRequest = {};
    if (patch.text !== undefined) body.text = patch.text;
    if (patch.done !== undefined) body.done = patch.done;
    if (patch.line !== undefined) body.line = patch.line;
    const updated = await brainFetch<DocumentNoteDto>(
      'PUT',
      `documents/${encodeURIComponent(d.id)}/notes/${encodeURIComponent(noteId)}`,
      { body },
    );
    d.notes = { ...(d.notes ?? {}), [noteId]: updated };
    return updated;
  }

  async function deleteNote(noteId: string): Promise<void> {
    const d = doc.value;
    if (!d) return;
    await brainFetch<void>(
      'DELETE',
      `documents/${encodeURIComponent(d.id)}/notes/${encodeURIComponent(noteId)}`,
    );
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
  let unsubscribe: (() => void) | null = null;

  function attach(path: string): void {
    unsubscribe = onDocumentNoteChanged(path, (notification) => {
      const d = doc.value;
      if (!d || d.path !== notification.path) return;
      const next = { ...(d.notes ?? {}) };
      if (notification.kind === 'deleted') {
        delete next[notification.noteId];
      } else if (notification.note) {
        next[notification.noteId] = notification.note;
      }
      d.notes = next;
    });
  }

  function detach(): void {
    if (unsubscribe) {
      try { unsubscribe(); } catch { /* ignore */ }
      unsubscribe = null;
    }
  }

  watch(
    () => doc.value?.path ?? null,
    (path) => {
      detach();
      if (path) attach(path);
    },
    { immediate: true },
  );

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
  };
}
