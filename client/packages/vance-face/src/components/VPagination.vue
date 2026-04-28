<script setup lang="ts">
import { computed } from 'vue';

interface Props {
  /** Zero-based current page. */
  page: number;
  pageSize: number;
  totalCount: number;
}

const props = defineProps<Props>();
const emit = defineEmits<{ (e: 'update:page', page: number): void }>();

const pageCount = computed<number>(() => {
  if (props.pageSize <= 0) return 1;
  return Math.max(1, Math.ceil(props.totalCount / props.pageSize));
});

const firstShownIndex = computed<number>(() =>
  props.totalCount === 0 ? 0 : props.page * props.pageSize + 1,
);
const lastShownIndex = computed<number>(() =>
  Math.min(props.totalCount, (props.page + 1) * props.pageSize),
);

function setPage(p: number): void {
  const clamped = Math.max(0, Math.min(p, pageCount.value - 1));
  if (clamped !== props.page) emit('update:page', clamped);
}
</script>

<template>
  <div class="flex items-center justify-between gap-3 text-sm">
    <span class="opacity-70">
      <template v-if="totalCount === 0">No items</template>
      <template v-else>
        {{ firstShownIndex }}–{{ lastShownIndex }} of {{ totalCount }}
      </template>
    </span>
    <div class="join">
      <button
        type="button"
        class="btn btn-sm btn-ghost join-item"
        :disabled="page <= 0"
        @click="setPage(0)"
      >«</button>
      <button
        type="button"
        class="btn btn-sm btn-ghost join-item"
        :disabled="page <= 0"
        @click="setPage(page - 1)"
      >‹</button>
      <span class="btn btn-sm btn-ghost join-item pointer-events-none">
        {{ page + 1 }} / {{ pageCount }}
      </span>
      <button
        type="button"
        class="btn btn-sm btn-ghost join-item"
        :disabled="page >= pageCount - 1"
        @click="setPage(page + 1)"
      >›</button>
      <button
        type="button"
        class="btn btn-sm btn-ghost join-item"
        :disabled="page >= pageCount - 1"
        @click="setPage(pageCount - 1)"
      >»</button>
    </div>
  </div>
</template>
