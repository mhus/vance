<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  VAlert,
  VButton,
  VCheckbox,
  VInput,
  VSelect,
  VTagEditor,
  VTextarea,
} from '@/components';
import type {
  CalendarEventUpdateRequest,
  CalendarEventView,
  CalendarLaneView,
} from '@vance/generated';

const props = defineProps<{
  event: CalendarEventView;
  lanes: CalendarLaneView[];
}>();
const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'update', patch: CalendarEventUpdateRequest): void;
  (e: 'delete'): void;
}>();

const title = ref(props.event.title);
const start = ref(props.event.start);
const end = ref(props.event.end ?? '');
const allDay = ref(props.event.allDay);
const location = ref(props.event.location ?? '');
const attendees = ref<string[]>([...props.event.attendees]);
const recurrence = ref(props.event.recurrence ?? '');
const tags = ref<string[]>([...props.event.tags]);
const notes = ref(props.event.notes ?? '');
const targetLane = ref(props.event.lane);

watch(
  () => props.event.id,
  () => {
    title.value = props.event.title;
    start.value = props.event.start;
    end.value = props.event.end ?? '';
    allDay.value = props.event.allDay;
    location.value = props.event.location ?? '';
    attendees.value = [...props.event.attendees];
    recurrence.value = props.event.recurrence ?? '';
    tags.value = [...props.event.tags];
    notes.value = props.event.notes ?? '';
    targetLane.value = props.event.lane;
  },
);

const dirty = computed<boolean>(() =>
  title.value !== props.event.title ||
  start.value !== props.event.start ||
  end.value !== (props.event.end ?? '') ||
  allDay.value !== props.event.allDay ||
  location.value !== (props.event.location ?? '') ||
  !arraysEqual(attendees.value, props.event.attendees) ||
  recurrence.value !== (props.event.recurrence ?? '') ||
  !arraysEqual(tags.value, props.event.tags) ||
  notes.value !== (props.event.notes ?? '') ||
  targetLane.value !== props.event.lane,
);

function arraysEqual(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

function save(): void {
  const patch: CalendarEventUpdateRequest = {};
  if (title.value !== props.event.title) patch.title = title.value;
  if (start.value !== props.event.start) patch.start = start.value;
  if (end.value !== (props.event.end ?? '')) patch.end = end.value;
  if (allDay.value !== props.event.allDay) patch.allDay = allDay.value;
  if (location.value !== (props.event.location ?? '')) patch.location = location.value;
  if (!arraysEqual(attendees.value, props.event.attendees)) patch.attendees = attendees.value;
  if (recurrence.value !== (props.event.recurrence ?? '')) patch.recurrence = recurrence.value;
  if (!arraysEqual(tags.value, props.event.tags)) patch.tags = tags.value;
  if (notes.value !== (props.event.notes ?? '')) patch.notes = notes.value;
  if (targetLane.value !== props.event.lane) patch.targetLane = targetLane.value;
  emit('update', patch);
}

function confirmDelete(): void {
  if (window.confirm(`Delete event "${props.event.title}"?`)) emit('delete');
}
</script>

<template>
  <div class="flex flex-col h-full">
    <div class="flex items-center justify-between p-4 border-b border-base-300">
      <h2 class="text-lg font-semibold">Event detail</h2>
      <button
        class="text-base-content/60 hover:text-base-content text-xl leading-none"
        @click="emit('close')"
      >×</button>
    </div>

    <div class="flex-1 overflow-y-auto p-4 flex flex-col gap-3">
      <VInput v-model="title" label="Title" />

      <div class="grid grid-cols-2 gap-2">
        <VInput v-model="start" label="Start" placeholder="YYYY-MM-DD[THH:mm]" />
        <VInput v-model="end" label="End" placeholder="(optional)" />
      </div>

      <VCheckbox v-model="allDay" label="All-day event" />

      <VSelect
        :model-value="targetLane"
        label="Lane"
        :options="lanes.map((l) => ({ value: l.name, label: l.title ?? l.name }))"
        @update:model-value="(v) => targetLane = (v as string | null) ?? props.event.lane"
      />

      <VInput v-model="location" label="Location" />
      <VTagEditor v-model="attendees" label="Attendees" />

      <VInput
        v-model="recurrence"
        label="Recurrence (RRULE)"
        placeholder="FREQ=WEEKLY;BYDAY=MO,…"
      />

      <VTagEditor v-model="tags" label="Tags" />

      <VTextarea v-model="notes" label="Notes" :rows="4" />

      <div class="flex gap-2 mt-2">
        <a
          v-if="event.googleUrl"
          :href="event.googleUrl"
          target="_blank"
          rel="noopener"
          class="flex-1 text-center text-sm bg-base-200 hover:bg-base-300 rounded px-3 py-2"
        >Add to Google</a>
        <a
          v-if="event.outlookUrl"
          :href="event.outlookUrl"
          target="_blank"
          rel="noopener"
          class="flex-1 text-center text-sm bg-base-200 hover:bg-base-300 rounded px-3 py-2"
        >Add to Outlook</a>
      </div>

      <VAlert variant="info" class="text-xs">
        Source: {{ event.sourcePath }}
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
