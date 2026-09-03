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
} from '@vance/components';
import type { CalendarEventUpdateRequest } from './generated/calendar/CalendarEventUpdateRequest';
import type { CalendarEventView } from './generated/calendar/CalendarEventView';
import type { CalendarLaneView } from './generated/calendar/CalendarLaneView';
import { useT } from './i18n';

const props = defineProps<{
  event: CalendarEventView;
  lanes: CalendarLaneView[];
}>();
const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'update', patch: CalendarEventUpdateRequest): void;
  (e: 'delete'): void;
}>();

const t = useT();

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
  if (window.confirm(t('calendar.detail.confirmDelete', { title: props.event.title }))) emit('delete');
}
</script>

<template>
  <div class="flex flex-col h-full">
    <div class="flex items-center justify-between p-4 border-b border-base-300">
      <h2 class="text-lg font-semibold">{{ t('calendar.detail.title') }}</h2>
      <button
        class="text-base-content/60 hover:text-base-content text-xl leading-none"
        @click="emit('close')"
      >×</button>
    </div>

    <div class="flex-1 overflow-y-auto p-4 flex flex-col gap-3">
      <VInput v-model="title" :label="t('calendar.detail.fieldTitle')" />

      <div class="grid grid-cols-2 gap-2">
        <VInput
          v-model="start"
          :label="t('calendar.detail.start')"
          :placeholder="t('calendar.detail.startPlaceholder')"
        />
        <VInput
          v-model="end"
          :label="t('calendar.detail.end')"
          :placeholder="t('calendar.detail.endPlaceholder')"
        />
      </div>

      <VCheckbox v-model="allDay" :label="t('calendar.detail.allDay')" />

      <VSelect
        :model-value="targetLane"
        :label="t('calendar.detail.lane')"
        :options="lanes.map((l) => ({ value: l.name, label: l.title ?? l.name }))"
        @update:model-value="(v) => targetLane = (v as string | null) ?? props.event.lane"
      />

      <VInput v-model="location" :label="t('calendar.detail.location')" />
      <VTagEditor v-model="attendees" :label="t('calendar.detail.attendees')" />

      <VInput
        v-model="recurrence"
        :label="t('calendar.detail.recurrence')"
        :placeholder="t('calendar.detail.recurrencePlaceholder')"
      />

      <VTagEditor v-model="tags" :label="t('calendar.detail.tags')" />

      <VTextarea v-model="notes" :label="t('calendar.detail.notes')" :rows="4" />

      <div class="flex gap-2 mt-2">
        <a
          v-if="event.googleUrl"
          :href="event.googleUrl"
          target="_blank"
          rel="noopener"
          class="flex-1 text-center text-sm bg-base-200 hover:bg-base-300 rounded px-3 py-2"
        >{{ t('calendar.detail.addToGoogle') }}</a>
        <a
          v-if="event.outlookUrl"
          :href="event.outlookUrl"
          target="_blank"
          rel="noopener"
          class="flex-1 text-center text-sm bg-base-200 hover:bg-base-300 rounded px-3 py-2"
        >{{ t('calendar.detail.addToOutlook') }}</a>
      </div>

      <VAlert variant="info" class="text-xs">
        {{ t('calendar.detail.source', { path: event.sourcePath }) }}
      </VAlert>
    </div>

    <div class="flex items-center justify-between p-4 border-t border-base-300">
      <VButton variant="ghost" class="text-error" @click="confirmDelete">{{ t('calendar.common.delete') }}</VButton>
      <div class="flex gap-2">
        <VButton variant="ghost" :disabled="!dirty" @click="emit('close')">{{ t('calendar.detail.discard') }}</VButton>
        <VButton variant="primary" :disabled="!dirty" @click="save">{{ t('calendar.common.save') }}</VButton>
      </div>
    </div>
  </div>
</template>
