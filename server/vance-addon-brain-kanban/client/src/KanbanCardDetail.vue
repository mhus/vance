<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import {
  VButton,
  VCheckbox,
  VColorPicker,
  VInput,
  VModal,
  VSelect,
  VTagEditor,
} from '@vance/components';
import { AccentColor } from '@vance/generated';
import { WorkPageEditor } from '@vance/block-editor';
import { updateKanbanCard } from './api';
import type { KanbanCardUpdateRequest } from './generated/kanban/KanbanCardUpdateRequest';
import type { KanbanCardView } from './generated/kanban/KanbanCardView';

/**
 * Card detail panel — two content levels, both auto-saved:
 *
 *  1. Attributes (title, priority, assignee, labels, dueDate, estimate,
 *     blocked) edited inline in this panel.
 *  2. Content (Markdown body) edited in the roomy modal via the shared
 *     WorkPageEditor.
 *
 * Both levels flow through a SINGLE debounced save loop that PATCHes the
 * card once per cycle (attributes + body in one request). One writer per
 * card avoids read-modify-write races between the two levels — the server
 * re-serialises via CardCodec, so the client never serialises the card
 * itself.
 *
 * Live updates: the parent bumps {@code remoteRevision} when a remote
 * edit to THIS card lands (outside our self-write window). We then re-seed
 * the fields + editor source from the fresh {@code card} prop. Our own
 * saves do NOT bump it, so the cursor stays put while typing.
 */
const props = defineProps<{
  card: KanbanCardView;
  projectId: string;
  folder: string;
  remoteRevision: number;
}>();
const emit = defineEmits<{
  (e: 'close'): void;
  // A save cycle started (user edited) — lets the board open its
  // self-write quiet window before the PATCH round-trip even completes.
  (e: 'dirty'): void;
  // A PATCH succeeded — carries the authoritative card view back so the
  // board updates its array + refreshes the self-write timestamp.
  (e: 'saved', card: KanbanCardView): void;
  (e: 'delete'): void;
}>();

const AUTOSAVE_MS = 800;

// ── Attribute edit state ──────────────────────────────────────────
const title = ref(props.card.title);
const priority = ref(props.card.priority ?? '');
const assignee = ref(props.card.assignee ?? '');
const labels = ref<string[]>([...props.card.labels]);
const dueDate = ref(props.card.dueDate ?? '');
const estimate = ref<number | null>(props.card.estimate ?? null);
const blocked = ref(props.card.blocked);
// Document-level accent color; the wire value is the AccentColor enum name
// (a string enum, so the cast is a no-op at runtime).
const color = ref<AccentColor | null>((props.card.color as AccentColor | null) ?? null);

// Editor source — only reseeded on card switch / remote edit, never on
// our own save, so typing doesn't rebuild the ProseMirror doc (cursor
// stays). `latestBody` is the live markdown pulled from the editor on
// each flush (autoSaveMs=0 means the editor only emits @save when WE
// call editorRef.save()).
const body = ref(props.card.body ?? '');
let latestBody = props.card.body ?? '';
// Persistent "the user has edited the body" flag. Set on a real editor
// edit; NOT cleared by the editor's own dirty(false) after we pull markdown
// via editorRef.save(). This gates whether the body goes into the patch:
// the editor's parse→serialize round-trip is not byte-identical to the
// on-disk source, so an untouched body must never look changed.
const bodyDirty = ref(false);
const editorRef = ref<{ save: () => void; flush: () => boolean } | null>(null);
const editorDocument = computed(() => ({
  id: props.card.path,
  path: props.card.path,
  projectId: props.projectId,
  mimeType: 'text/markdown',
}));

const contentOpen = ref(false);

// ── Save status ───────────────────────────────────────────────────
type SaveStatus = 'idle' | 'dirty' | 'saving' | 'saved' | 'error';
const saveStatus = ref<SaveStatus>('idle');
const saveError = ref<string | null>(null);

const saveStatusLabel = computed<string | null>(() => {
  switch (saveStatus.value) {
    case 'dirty': return 'Bearbeitet…';
    case 'saving': return 'Speichern…';
    case 'saved': return 'Gespeichert';
    case 'error': return saveError.value ?? 'Speichern fehlgeschlagen';
    default: return null;
  }
});

// ── Debounced single-writer save loop ─────────────────────────────
let saveTimer: ReturnType<typeof setTimeout> | null = null;
// Serialise saves: never let two PATCHes on the same card run concurrently
// (they'd race on the document's optimistic-lock version). A flush while one
// is in flight just queues a single follow-up that re-diffs against the
// freshly-saved card.
let saving = false;
let resaveQueued = false;
// Suppress the field watcher while we reseed refs from the prop, so a
// remote refresh / card switch doesn't look like a user edit.
let suppress = false;

function reseed(): void {
  suppress = true;
  title.value = props.card.title;
  priority.value = props.card.priority ?? '';
  assignee.value = props.card.assignee ?? '';
  labels.value = [...props.card.labels];
  dueDate.value = props.card.dueDate ?? '';
  estimate.value = props.card.estimate ?? null;
  blocked.value = props.card.blocked;
  color.value = (props.card.color as AccentColor | null) ?? null;
  body.value = props.card.body ?? '';
  latestBody = props.card.body ?? '';
  bodyDirty.value = false;
  saveStatus.value = 'idle';
  saveError.value = null;
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = null; }
  void nextTick(() => { suppress = false; });
}

// Card switch (path change) or remote edit (revision bump) → adopt the
// server state. On a card switch the parent flushes the previous card
// first (flushNow), so we don't lose pending edits here.
watch(() => props.card.path, reseed);
watch(() => props.remoteRevision, reseed);

watch(
  [title, priority, assignee, labels, dueDate, estimate, blocked, color],
  schedule,
  { deep: true },
);

function schedule(): void {
  if (suppress) return;
  saveStatus.value = 'dirty';
  emit('dirty');
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(() => { saveTimer = null; void flushSave(); }, AUTOSAVE_MS);
}

function buildPatch(): KanbanCardUpdateRequest {
  const p: KanbanCardUpdateRequest = {};
  if (title.value !== props.card.title) p.title = title.value;
  if ((priority.value || '') !== (props.card.priority ?? '')) p.priority = priority.value;
  if ((assignee.value || '') !== (props.card.assignee ?? '')) p.assignee = assignee.value;
  if (!arraysEqual(labels.value, props.card.labels)) p.labels = labels.value;
  if ((dueDate.value || '') !== (props.card.dueDate ?? '')) p.dueDate = dueDate.value;
  // Estimate can't be cleared through the merge endpoint (no null-signal),
  // so only send a concrete new value.
  if (estimate.value !== (props.card.estimate ?? null) && estimate.value !== null) {
    p.estimate = estimate.value;
  }
  if (blocked.value !== props.card.blocked) p.blocked = blocked.value;
  const cardColor = (props.card.color as AccentColor | null) ?? null;
  if (color.value !== cardColor) {
    if (color.value === null) p.clearColor = true;
    else p.color = color.value;
  }
  // Only send the body when the user actually edited it (see bodyDirty).
  if (bodyDirty.value && latestBody !== (props.card.body ?? '')) p.body = latestBody;
  return p;
}

async function flushSave(): Promise<void> {
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = null; }
  // Pull the freshest markdown out of the editor (sync @save) — but only if
  // the user touched it, so an unedited body never round-trips into a change.
  if (bodyDirty.value) editorRef.value?.save();

  // A save is already in flight — coalesce into a single follow-up.
  if (saving) { resaveQueued = true; return; }

  const patch = buildPatch();
  if (Object.keys(patch).length === 0) {
    if (saveStatus.value === 'dirty') saveStatus.value = 'idle';
    return;
  }
  const path = props.card.path;
  const savedBody = patch.body !== undefined;
  saving = true;
  saveStatus.value = 'saving';
  emit('dirty'); // refresh the self-write window at save time too
  try {
    const updated = await updateKanbanCard(props.projectId, props.folder, path, patch);
    if (savedBody && props.card.path === path) bodyDirty.value = false;
    // Only paint status if the same card is still selected — otherwise
    // the switch already reseeded us for a different card.
    if (props.card.path === path) {
      saveStatus.value = 'saved';
      saveError.value = null;
    }
    emit('saved', updated);
  } catch (e) {
    if (props.card.path === path) {
      saveStatus.value = 'error';
      saveError.value = (e as Error).message;
    }
  } finally {
    saving = false;
    // Run the queued follow-up now that props.card reflects the last save.
    if (resaveQueued) { resaveQueued = false; void flushSave(); }
  }
}

function onBodySave(md: string): void {
  latestBody = md;
}

function onBodyDirty(d: boolean): void {
  // Only react to a real user edit. The editor also emits dirty(false)
  // after we pull markdown via editorRef.save() and after a source reseed —
  // those must NOT clear our persistent "user edited the body" flag, which
  // is reset explicitly on a successful body save / reseed.
  if (d) {
    bodyDirty.value = true;
    schedule();
  }
}

function onContentModal(open: boolean): void {
  if (!open) {
    // Persist only if the user actually edited the body; otherwise closing
    // the untouched dialog must not trigger a (round-trip-noise) save.
    if (bodyDirty.value) {
      void flushSave();
      body.value = latestBody;
    }
  }
  contentOpen.value = open;
}

function arraysEqual(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

function confirmDelete(): void {
  if (window.confirm(`Delete card "${props.card.title}"?`)) emit('delete');
}

// Called by the board before switching to another card, so pending edits
// on this card are persisted first.
function flushNow(): void {
  if (saveTimer || bodyDirty.value || saveStatus.value === 'dirty') void flushSave();
}
// Called by the board on a repeat click of the already-open card — opens
// the content dialog as the second step of the progressive disclosure.
function openContent(): void {
  onContentModal(true);
}
defineExpose({ flushNow, openContent });

onBeforeUnmount(() => {
  if (saveTimer || bodyDirty.value) { if (saveTimer) clearTimeout(saveTimer); void flushSave(); }
});
</script>

<template>
  <div class="flex flex-col h-full">
    <div class="flex items-center justify-between p-4 border-b border-base-300">
      <div class="flex items-center gap-3 min-w-0">
        <h2 class="text-lg font-semibold">Card detail</h2>
        <span
          v-if="saveStatusLabel"
          class="text-xs whitespace-nowrap"
          :class="{
            'text-base-content/50': saveStatus === 'dirty' || saveStatus === 'saving',
            'text-success': saveStatus === 'saved',
            'text-error': saveStatus === 'error',
          }"
        >{{ saveStatusLabel }}</span>
      </div>
      <button
        class="text-base-content/60 hover:text-base-content text-xl leading-none"
        @click="emit('close')"
      >×</button>
    </div>

    <div class="flex-1 overflow-y-auto p-4 flex flex-col gap-3">
      <VInput v-model="title" label="Title" />

      <div class="grid grid-cols-2 gap-2">
        <VSelect
          :model-value="priority"
          label="Priority"
          :options="[
            { value: '', label: 'No priority' },
            { value: 'low', label: 'Low' },
            { value: 'med', label: 'Medium' },
            { value: 'high', label: 'High' },
            { value: 'critical', label: 'Critical' },
          ]"
          @update:model-value="(v) => priority = (v as string | null) ?? ''"
        />
        <VInput v-model="assignee" label="Assignee" />
      </div>

      <div class="grid grid-cols-2 gap-2">
        <VInput v-model="dueDate" label="Due date" placeholder="YYYY-MM-DD" />
        <VInput
          :model-value="estimate === null ? '' : String(estimate)"
          label="Estimate"
          @update:model-value="(v: string) => estimate = v === '' ? null : Number(v)"
        />
      </div>

      <VTagEditor v-model="labels" label="Labels" />

      <VCheckbox v-model="blocked" label="Blocked" />

      <VColorPicker
        :model-value="color"
        label="Color"
        @update:model-value="(v) => color = v"
      />

      <div class="flex flex-col gap-1">
        <VButton variant="ghost" class="justify-start" @click="onContentModal(true)">
          ✎ Content bearbeiten…
        </VButton>
      </div>
    </div>

    <VModal
      :model-value="contentOpen"
      title="Card-Inhalt"
      size="xl"
      :close-on-backdrop="true"
      @update:model-value="onContentModal"
    >
      <!-- px-8 gives the block drag-handle (⠿, sits ~20px left of each
           block) a gutter so it doesn't get clipped by the scroll box. -->
      <div class="min-h-[55vh] max-h-[72vh] overflow-y-auto px-8">
        <WorkPageEditor
          v-if="contentOpen"
          ref="editorRef"
          :document="editorDocument"
          :source="body"
          :auto-save-ms="0"
          body-only
          :current-project-id="projectId"
          @save="onBodySave"
          @dirty="onBodyDirty"
        />
      </div>
      <template #actions>
        <span class="text-xs text-base-content/50 mr-auto self-center">
          {{ saveStatusLabel }}
        </span>
        <VButton variant="primary" @click="onContentModal(false)">Fertig</VButton>
      </template>
    </VModal>

    <div class="flex items-center justify-end p-4 border-t border-base-300">
      <VButton variant="ghost" class="text-error" @click="confirmDelete">Delete</VButton>
    </div>
  </div>
</template>
