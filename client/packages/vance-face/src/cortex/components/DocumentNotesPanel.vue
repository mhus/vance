<script setup lang="ts">
/**
 * Sticky-notes side panel. Lives inside {@code DocumentTabShell}, right
 * of the editor body, when the user has toggled notes on. Cards are
 * sorted by createdAt ascending; each card is yellow-tinted (gelber
 * Zettel) and supports inline edit + done-checkbox + delete.
 *
 * <p>Anchored notes show a "📍 Z. N" chip; clicking the chip emits
 * {@code jump-to-line} so the host can scroll the editor.
 *
 * <p>The composable does the REST round-trips and the local-map updates
 * — this component is pure presentation.
 */
import { computed, nextTick, ref, watch } from 'vue';
import type { DocumentNoteDto } from '@vance/generated';

interface Props {
  notes: DocumentNoteDto[];
  /** Note ids that should pulse / scroll-into-view (set by parent on gutter click). */
  highlightedNoteId?: string | null;
}

const props = withDefaults(defineProps<Props>(), {
  highlightedNoteId: null,
});

const emit = defineEmits<{
  (e: 'add'): void;
  (e: 'update', noteId: string, patch: { text?: string; done?: boolean }): void;
  (e: 'delete', noteId: string): void;
  (e: 'jump-to-line', line: number): void;
}>();

/** Per-note dirty buffer for inline-edit. Server is patched on blur. */
const draftText = ref<Record<string, string>>({});

function startEdit(note: DocumentNoteDto) {
  if (draftText.value[note.id] === undefined) {
    draftText.value[note.id] = note.text;
  }
}

function commit(note: DocumentNoteDto) {
  const buf = draftText.value[note.id];
  if (buf === undefined) return;
  if (buf !== note.text) {
    emit('update', note.id, { text: buf });
  }
  // Keep the buffer so a subsequent re-focus doesn't lose mid-edit state
  // until the parent re-renders with the persisted value.
}

function toggleDone(note: DocumentNoteDto) {
  emit('update', note.id, { done: !note.done });
}

function relTime(ms: number): string {
  if (!ms) return '';
  const now = Date.now();
  const delta = Math.max(0, now - ms);
  if (delta < 60_000) return 'jetzt';
  if (delta < 3_600_000) return `vor ${Math.floor(delta / 60_000)} min`;
  if (delta < 86_400_000) return `vor ${Math.floor(delta / 3_600_000)} h`;
  return new Date(ms).toLocaleDateString();
}

/** Scroll the highlighted note into view + brief pulse. */
const cardRefs = ref<Record<string, HTMLElement | null>>({});
function setCardRef(noteId: string, el: HTMLElement | null) {
  cardRefs.value[noteId] = el;
}
watch(
  () => props.highlightedNoteId,
  (id) => {
    if (!id) return;
    void nextTick(() => {
      const el = cardRefs.value[id];
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
  },
);

const isEmpty = computed(() => props.notes.length === 0);
</script>

<template>
  <aside class="notes-panel">
    <header class="notes-panel-header">
      <span class="text-sm font-semibold">Notes</span>
      <span class="text-xs opacity-60">{{ notes.length }}</span>
      <span class="flex-1" />
      <button
        type="button"
        class="text-xs px-2 py-0.5 rounded border border-base-300
               hover:bg-base-200"
        title="Neue Notiz"
        @click="emit('add')"
      >＋</button>
    </header>

    <div v-if="isEmpty" class="notes-panel-empty">
      Keine Notizen. Klick neben eine Zeilennummer oder den ＋-Button.
    </div>

    <div v-else class="notes-panel-list">
      <article
        v-for="note in notes"
        :key="note.id"
        :ref="(el) => setCardRef(note.id, el as HTMLElement | null)"
        class="note-card"
        :class="{
          'note-card--done': note.done,
          'note-card--pulse': highlightedNoteId === note.id,
        }"
      >
        <header class="note-card-header">
          <input
            type="checkbox"
            :checked="note.done"
            :title="note.done ? 'Erledigt' : 'Als erledigt markieren'"
            @change="toggleDone(note)"
          />
          <span class="text-xs font-semibold truncate" :title="note.userId">
            {{ note.userId }}
          </span>
          <button
            v-if="note.line != null"
            type="button"
            class="text-xs px-1 rounded bg-base-100/60 border border-base-300/60
                   hover:bg-base-100"
            :title="`Sprung zu Zeile ${note.line}`"
            @click="emit('jump-to-line', note.line!)"
          >📍 Z. {{ note.line }}</button>
          <span class="flex-1" />
          <span class="text-[10px] opacity-60" :title="new Date(note.updatedAtMs).toLocaleString()">
            {{ relTime(note.updatedAtMs) }}
          </span>
          <button
            type="button"
            class="opacity-50 hover:opacity-100 hover:text-error"
            title="Löschen"
            @click="emit('delete', note.id)"
          >×</button>
        </header>
        <textarea
          :value="draftText[note.id] ?? note.text"
          class="note-card-text"
          rows="2"
          @focus="startEdit(note)"
          @input="(e) => (draftText[note.id] = (e.target as HTMLTextAreaElement).value)"
          @blur="commit(note)"
        />
      </article>
    </div>
  </aside>
</template>

<style scoped>
.notes-panel {
  display: flex;
  flex-direction: column;
  width: 320px;
  flex-shrink: 0;
  border-left: 1px solid hsl(var(--bc) / 0.15);
  background: hsl(var(--b1));
  min-height: 0;
}

.notes-panel-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.15);
}

.notes-panel-empty {
  padding: 1rem;
  font-size: 0.8rem;
  opacity: 0.6;
  text-align: center;
}

.notes-panel-list {
  flex: 1;
  overflow-y: auto;
  padding: 0.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.note-card {
  background: #fff8c5;        /* light yellow — "gelber Zettel" */
  color: #1a1a1a;             /* readable on yellow regardless of theme */
  border: 1px solid #e8d678;
  border-radius: 6px;
  padding: 0.5rem;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}

.note-card--done {
  opacity: 0.55;
  background: #fbf6dc;
}
.note-card--done .note-card-text {
  text-decoration: line-through;
  text-decoration-thickness: 1px;
}

.note-card-header {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 0.75rem;
}
.note-card-header input[type='checkbox'] {
  margin: 0;
}

.note-card-text {
  width: 100%;
  background: transparent;
  border: none;
  resize: vertical;
  font-family: inherit;
  font-size: 0.85rem;
  line-height: 1.35;
  outline: none;
  min-height: 2.2rem;
  color: inherit;
}
.note-card-text:focus {
  outline: 1px solid #c8a90a;
  outline-offset: 1px;
}

.note-card--pulse {
  animation: notePulse 1.2s ease-out 1;
}
@keyframes notePulse {
  0% { box-shadow: 0 0 0 0 rgba(200, 169, 10, 0.7); }
  100% { box-shadow: 0 0 0 12px rgba(200, 169, 10, 0); }
}
</style>
