<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  CodeEditor,
  VAlert,
  VButton,
  VCheckbox,
  VInput,
  VSelect,
  VTagEditor,
} from '@/components';
import type { KanbanCardUpdateRequest, KanbanCardView } from '@vance/generated';

const props = defineProps<{ card: KanbanCardView }>();
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
const body = ref(props.card.body ?? '');

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
  body.value !== (props.card.body ?? ''),
);

function arraysEqual(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

function save(): void {
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
        <label class="text-sm font-medium">Body (Markdown)</label>
        <CodeEditor v-model="body" mime-type="text/markdown" :rows="14" />
        <div v-if="body" class="text-xs text-base-content/50">
          GFM checkboxes feed the board's subtasks progress badge.
        </div>
      </div>

      <VAlert variant="info" class="text-xs">
        Path: {{ card.path }}
      </VAlert>
    </div>

    <div class="flex items-center justify-between p-4 border-t border-base-300">
      <VButton variant="ghost" class="text-error" @click="confirmDelete">Delete</VButton>
      <div class="flex gap-2">
        <VButton variant="ghost" :disabled="!dirty" @click="emit('close')">Discard</VButton>
        <VButton variant="primary" :disabled="!dirty" @click="save">Save</VButton>
      </div>
    </div>
  </div>
</template>
