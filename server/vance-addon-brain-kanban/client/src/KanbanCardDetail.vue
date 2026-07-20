<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  VAlert,
  VButton,
  VCheckbox,
  VInput,
  VModal,
  VSelect,
  VTagEditor,
} from '@vance/components';
import { WorkPageEditor } from '@vance/block-editor';
import type { KanbanCardUpdateRequest } from './generated/kanban/KanbanCardUpdateRequest';
import type { KanbanCardView } from './generated/kanban/KanbanCardView';

const props = defineProps<{ card: KanbanCardView; projectId: string }>();
const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'update', patch: KanbanCardUpdateRequest): void;
  (e: 'delete'): void;
}>();

// Local edit state — staged until the user clicks Save. Replaying the
// card's fields into refs makes the form straightforward to wire and
// keeps unsaved edits visible across re-renders of the same card.
const title = ref(props.card.title);
const priority = ref(props.card.priority ?? '');
const assignee = ref(props.card.assignee ?? '');
const labels = ref<string[]>([...props.card.labels]);
const dueDate = ref(props.card.dueDate ?? '');
const estimate = ref<number | null>(props.card.estimate ?? null);
const blocked = ref(props.card.blocked);

// Card body, edited in the shared block editor (WorkPageEditor, bodyOnly).
// ONE ref: it feeds :source AND captures the serialized markdown on @save
// (fired on modal close via editorRef.save()). With autoSaveMs=0 the editor
// never emits mid-edit, so `body` stays stable while typing (no reload /
// cursor reset); reopening the modal shows the latest edits because
// :source === body. `bodyDirty` mirrors the editor's live dirty state.
const body = ref(props.card.body ?? '');
const bodyDirty = ref(false);
const editorRef = ref<{ save: () => void } | null>(null);
const editorDocument = computed(() => ({
  id: props.card.path,
  path: props.card.path,
  projectId: props.projectId,
  mimeType: 'text/markdown',
}));

// Content opens in a roomy modal (the side panel is too narrow for the
// block editor). The editor is lazy-mounted while the modal is open;
// closing it flushes the latest markdown into editedBody before unmount,
// so the panel's Save button persists it with the rest of the patch.
const contentOpen = ref(false);
function onContentModal(open: boolean): void {
  if (!open) editorRef.value?.save();
  contentOpen.value = open;
}

watch(
  () => props.card.path,
  () => {
    title.value = props.card.title;
    priority.value = props.card.priority ?? '';
    assignee.value = props.card.assignee ?? '';
    labels.value = [...props.card.labels];
    dueDate.value = props.card.dueDate ?? '';
    estimate.value = props.card.estimate ?? null;
    blocked.value = props.card.blocked;
    body.value = props.card.body ?? '';
    bodyDirty.value = false;
  },
);

const dirty = computed<boolean>(() =>
  title.value !== props.card.title ||
  (priority.value || null) !== (props.card.priority ?? null) ||
  (assignee.value || null) !== (props.card.assignee ?? null) ||
  !arraysEqual(labels.value, props.card.labels) ||
  (dueDate.value || null) !== (props.card.dueDate ?? null) ||
  estimate.value !== (props.card.estimate ?? null) ||
  blocked.value !== props.card.blocked ||
  bodyDirty.value ||
  body.value !== (props.card.body ?? ''),
);

function arraysEqual(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

function save(): void {
  // Flush the editor first (if the modal is open): save() emits @save
  // synchronously, so `body` holds the latest markdown before we patch.
  editorRef.value?.save();
  const patch: KanbanCardUpdateRequest = {};
  if (title.value !== props.card.title) patch.title = title.value;
  if (priority.value !== (props.card.priority ?? '')) patch.priority = priority.value;
  if (assignee.value !== (props.card.assignee ?? '')) patch.assignee = assignee.value;
  if (!arraysEqual(labels.value, props.card.labels)) patch.labels = labels.value;
  if (dueDate.value !== (props.card.dueDate ?? '')) patch.dueDate = dueDate.value;
  if (estimate.value !== (props.card.estimate ?? null)) {
    if (estimate.value !== null) patch.estimate = estimate.value;
  }
  if (blocked.value !== props.card.blocked) patch.blocked = blocked.value;
  if (body.value !== (props.card.body ?? '')) patch.body = body.value;
  emit('update', patch);
}

function confirmDelete(): void {
  if (window.confirm(`Delete card "${props.card.title}"?`)) emit('delete');
}
</script>

<template>
  <div class="flex flex-col h-full">
    <div class="flex items-center justify-between p-4 border-b border-base-300">
      <h2 class="text-lg font-semibold">Card detail</h2>
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

      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">Content</label>
        <VButton variant="ghost" class="justify-start" @click="onContentModal(true)">
          ✎ Content bearbeiten…
        </VButton>
        <div v-if="bodyDirty" class="text-xs text-warning">Ungespeicherte Content-Änderungen.</div>
      </div>

      <VAlert variant="info" class="text-xs">
        Path: {{ card.path }}
      </VAlert>
    </div>

    <VModal
      :model-value="contentOpen"
      title="Card-Inhalt"
      size="xl"
      :close-on-backdrop="false"
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
          @save="(md: string) => body = md"
          @dirty="(d: boolean) => bodyDirty = d"
        />
      </div>
      <template #actions>
        <VButton variant="primary" @click="onContentModal(false)">Fertig</VButton>
      </template>
    </VModal>

    <div class="flex items-center justify-between p-4 border-t border-base-300">
      <VButton variant="ghost" class="text-error" @click="confirmDelete">Delete</VButton>
      <div class="flex gap-2">
        <VButton variant="ghost" :disabled="!dirty" @click="emit('close')">Discard</VButton>
        <VButton variant="primary" :disabled="!dirty" @click="save">Save</VButton>
      </div>
    </div>
  </div>
</template>
