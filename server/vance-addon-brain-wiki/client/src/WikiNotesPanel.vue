<script setup lang="ts">
/**
 * Sticky-notes side panel for the active wiki page. A trimmed-down,
 * dependency-free variant of vance-face's DocumentNotesPanel (which lives
 * in the host package and is not importable from an addon): same REST
 * contract on {@code /documents/{id}/notes}, but no drag-reorder (drops
 * the vue-draggable-plus dependency). Notes are read from the page's
 * DocumentDto {@code notes} map and mutated in place after each write.
 */
import { computed, ref, watch } from 'vue';
import { brainFetch } from '@vance/shared';
import type {
  DocumentDto,
  DocumentNoteDto,
  DocumentNoteCreateRequest,
  DocumentNoteUpdateRequest,
} from '@vance/generated';

const props = defineProps<{
  documentId: string | null;
}>();

const notesMap = ref<Record<string, DocumentNoteDto>>({});
const loading = ref(false);
const error = ref<string | null>(null);

const notes = computed<DocumentNoteDto[]>(() =>
  Object.values(notesMap.value).sort((a, b) => {
    const diff = a.createdAtMs - b.createdAtMs;
    return diff !== 0 ? diff : a.id.localeCompare(b.id);
  }),
);

async function load(id: string | null): Promise<void> {
  if (!id) {
    notesMap.value = {};
    return;
  }
  loading.value = true;
  error.value = null;
  try {
    const dto = await brainFetch<DocumentDto>(
      'GET',
      `documents/${encodeURIComponent(id)}`,
    );
    notesMap.value = dto.notes ?? {};
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load notes.';
    notesMap.value = {};
  } finally {
    loading.value = false;
  }
}

watch(() => props.documentId, (id) => void load(id), { immediate: true });

async function addNote(): Promise<void> {
  const id = props.documentId;
  if (!id) return;
  const body: DocumentNoteCreateRequest = { text: '' };
  try {
    const created = await brainFetch<DocumentNoteDto>(
      'POST',
      `documents/${encodeURIComponent(id)}/notes`,
      { body },
    );
    notesMap.value = { ...notesMap.value, [created.id]: created };
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not add note.';
  }
}

async function patchNote(
  noteId: string,
  patch: { text?: string; done?: boolean },
): Promise<void> {
  const id = props.documentId;
  if (!id) return;
  const body: DocumentNoteUpdateRequest = {};
  if (patch.text !== undefined) body.text = patch.text;
  if (patch.done !== undefined) body.done = patch.done;
  try {
    const updated = await brainFetch<DocumentNoteDto>(
      'PUT',
      `documents/${encodeURIComponent(id)}/notes/${encodeURIComponent(noteId)}`,
      { body },
    );
    notesMap.value = { ...notesMap.value, [noteId]: updated };
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not update note.';
  }
}

async function deleteNote(noteId: string): Promise<void> {
  const id = props.documentId;
  if (!id) return;
  try {
    await brainFetch<void>(
      'DELETE',
      `documents/${encodeURIComponent(id)}/notes/${encodeURIComponent(noteId)}`,
    );
    const next = { ...notesMap.value };
    delete next[noteId];
    notesMap.value = next;
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not delete note.';
  }
}

function onTextInput(note: DocumentNoteDto, e: Event): void {
  const ta = e.target as HTMLTextAreaElement;
  const value = ta.value;
  if (value !== note.text) void patchNote(note.id, { text: value });
}
</script>

<template>
  <div class="wiki-notes">
    <header class="wiki-notes__header">
      <span class="wiki-notes__title">Notes</span>
      <span class="wiki-notes__count">{{ notes.length }}</span>
      <span class="wiki-notes__spacer" />
      <button
        type="button"
        class="wiki-notes__add"
        title="New note"
        :disabled="!documentId"
        @click="addNote"
      >＋</button>
    </header>

    <div v-if="error" class="wiki-notes__error">{{ error }}</div>
    <div v-else-if="loading" class="wiki-notes__hint">Loading…</div>
    <div v-else-if="notes.length === 0" class="wiki-notes__hint">
      No notes yet. Click ＋ to add one.
    </div>

    <div v-else class="wiki-notes__list">
      <article
        v-for="note in notes"
        :key="note.id"
        class="wiki-notes__card"
        :class="{ 'wiki-notes__card--done': note.done }"
      >
        <header class="wiki-notes__card-head">
          <input
            type="checkbox"
            :checked="note.done"
            :title="note.done ? 'Done' : 'Mark done'"
            @change="patchNote(note.id, { done: !note.done })"
          />
          <span class="wiki-notes__card-user" :title="note.userId">{{ note.userId }}</span>
          <span class="wiki-notes__spacer" />
          <button
            type="button"
            class="wiki-notes__del"
            title="Delete"
            @click="deleteNote(note.id)"
          >×</button>
        </header>
        <textarea
          class="wiki-notes__text"
          rows="2"
          :value="note.text"
          @change="(e) => onTextInput(note, e)"
        />
      </article>
    </div>
  </div>
</template>

<style scoped>
.wiki-notes {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.wiki-notes__header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.15);
}
.wiki-notes__title { font-size: 0.85rem; font-weight: 600; }
.wiki-notes__count { font-size: 0.75rem; opacity: 0.6; }
.wiki-notes__spacer { flex: 1; }
.wiki-notes__add,
.wiki-notes__del {
  border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
  padding: 0 0.4rem;
  line-height: 1.4rem;
}
.wiki-notes__add:hover { background: hsl(var(--bc) / 0.08); }
.wiki-notes__del { border: none; opacity: 0.5; }
.wiki-notes__del:hover { opacity: 1; color: #d33; }
.wiki-notes__hint,
.wiki-notes__error {
  padding: 1rem;
  font-size: 0.8rem;
  opacity: 0.7;
  text-align: center;
}
.wiki-notes__error { color: #d33; }
.wiki-notes__list {
  flex: 1;
  overflow-y: auto;
  padding: 0.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.wiki-notes__card {
  background: #fff8c5;
  color: #1a1a1a;
  border: 1px solid #e8d678;
  border-radius: 6px;
  padding: 0.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}
.wiki-notes__card--done { opacity: 0.55; }
.wiki-notes__card--done .wiki-notes__text { text-decoration: line-through; }
.wiki-notes__card-head {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 0.72rem;
}
.wiki-notes__card-user { font-weight: 600; overflow: hidden; text-overflow: ellipsis; }
.wiki-notes__text {
  width: 100%;
  background: transparent;
  border: none;
  resize: vertical;
  font-family: inherit;
  font-size: 0.85rem;
  line-height: 1.35;
  outline: none;
  color: inherit;
}
.wiki-notes__text:focus { outline: 1px solid #c8a90a; outline-offset: 1px; }
</style>
