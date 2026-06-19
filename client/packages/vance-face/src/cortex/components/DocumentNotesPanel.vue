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
import { computed, nextTick, onUpdated, ref, watch } from 'vue';
import { VueDraggable } from 'vue-draggable-plus';
import type { DocumentNoteDto } from '@vance/generated';

/**
 * Max textarea height for sticky notes. Beyond this the textarea
 * scrolls internally instead of growing further — keeps a few-page-long
 * note from pushing every other card off-screen.
 */
const TEXTAREA_MAX_PX = 320;

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
  /**
   * Drag-reorder finished. {@code toIndex} is the new position in the
   * sorted list; host composable computes the midpoint-between-neighbours
   * order value and patches the server.
   */
  (e: 'reorder', noteId: string, toIndex: number): void;
}>();

/**
 * Mutable working copy of {@link Props.notes} — VueDraggable mutates
 * the bound array in place during drag. We resync from the parent on
 * every prop change so live-updates from other clients overwrite our
 * local mid-drag state cleanly.
 */
const draggable = ref<DocumentNoteDto[]>([...props.notes]);
watch(
  () => props.notes,
  (next) => {
    draggable.value = [...next];
  },
  { deep: true },
);

function onDragEnd(): void {
  // Diff the final draggable order against props.notes to identify the
  // single note that moved. With single-item drag we expect exactly one
  // id at a different index — pick it and emit.
  for (let i = 0; i < draggable.value.length; i++) {
    const id = draggable.value[i].id;
    const oldIndex = props.notes.findIndex((n) => n.id === id);
    if (oldIndex !== i) {
      emit('reorder', id, i);
      return;
    }
  }
}

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

/**
 * Auto-grow the textareas so the visible card height tracks the note's
 * content length. The text-area's {@code rows} attribute would only
 * grow the min-size; we drive {@code style.height} dynamically from
 * {@code scrollHeight}, capped at {@link TEXTAREA_MAX_PX} so very long
 * notes scroll internally instead of dominating the panel.
 */
const textareaRefs = ref<Record<string, HTMLTextAreaElement | null>>({});
function setTextareaRef(noteId: string, el: HTMLTextAreaElement | null) {
  textareaRefs.value[noteId] = el;
  if (el) autoResize(el);
}
function autoResize(el: HTMLTextAreaElement) {
  el.style.height = 'auto';
  const target = Math.min(el.scrollHeight, TEXTAREA_MAX_PX);
  el.style.height = `${target}px`;
}
function onTextInput(note: DocumentNoteDto, e: Event) {
  const ta = e.target as HTMLTextAreaElement;
  draftText.value[note.id] = ta.value;
  autoResize(ta);
}
// Resize after every render — covers external mutations from live
// events that update note.text without going through onTextInput.
onUpdated(() => {
  for (const id of Object.keys(textareaRefs.value)) {
    const el = textareaRefs.value[id];
    if (el) autoResize(el);
  }
});
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

    <VueDraggable
      v-else
      v-model="draggable"
      class="notes-panel-list"
      :animation="150"
      handle=".note-drag-handle"
      ghost-class="note-card--ghost"
      chosen-class="note-card--chosen"
      drag-class="note-card--drag"
      @end="onDragEnd"
    >
      <article
        v-for="note in draggable"
        :key="note.id"
        :ref="(el) => setCardRef(note.id, el as HTMLElement | null)"
        class="note-card"
        :class="{
          'note-card--done': note.done,
          'note-card--pulse': highlightedNoteId === note.id,
        }"
      >
        <header class="note-card-header note-drag-handle" title="Zum Verschieben ziehen">
          <input
            type="checkbox"
            :checked="note.done"
            :title="note.done ? 'Erledigt' : 'Als erledigt markieren'"
            @change="toggleDone(note)"
            @mousedown.stop
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
            @click.stop="emit('jump-to-line', note.line!)"
            @mousedown.stop
          >📍 Z. {{ note.line }}</button>
          <span class="flex-1" />
          <span class="text-[10px] opacity-60" :title="new Date(note.updatedAtMs).toLocaleString()">
            {{ relTime(note.updatedAtMs) }}
          </span>
          <button
            type="button"
            class="opacity-50 hover:opacity-100 hover:text-error"
            title="Löschen"
            @click.stop="emit('delete', note.id)"
            @mousedown.stop
          >×</button>
        </header>
        <textarea
          :value="draftText[note.id] ?? note.text"
          :ref="(el) => setTextareaRef(note.id, el as HTMLTextAreaElement | null)"
          class="note-card-text"
          rows="1"
          @focus="startEdit(note)"
          @input="(e) => onTextInput(note, e)"
          @blur="commit(note)"
          @mousedown.stop
        />
      </article>
    </VueDraggable>
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
.note-drag-handle {
  cursor: grab;
}
.note-drag-handle:active {
  cursor: grabbing;
}
/* Buttons + inputs inside the drag handle should keep their pointer
 * cursor so the user knows they're clickable, not draggable. The
 * @mousedown.stop in the template prevents the drag from starting on
 * these targets. */
.note-drag-handle button,
.note-drag-handle input,
.note-drag-handle textarea {
  cursor: auto;
}

/* Drag visuals — VueDraggable applies these classes during a drag. */
.note-card--ghost {
  opacity: 0.4;
  border-style: dashed;
}
.note-card--chosen {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.18);
}
.note-card--drag {
  cursor: grabbing;
}

.note-card-text {
  width: 100%;
  background: transparent;
  border: none;
  /* JS auto-grows to fit content up to TEXTAREA_MAX_PX (320px). Beyond
   * that the textarea scrolls internally. Manual resize is disabled —
   * the height tracks the content, no user-action needed. */
  resize: none;
  overflow-y: auto;
  font-family: inherit;
  font-size: 0.85rem;
  line-height: 1.35;
  outline: none;
  min-height: 1.5rem;
  max-height: 320px;
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
