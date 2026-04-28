<script setup lang="ts" generic="T extends { id?: string | number }">
interface Props {
  items: T[];
  /** Optional key extractor — defaults to `item.id`, falls back to index. */
  itemKey?: (item: T, index: number) => string | number;
  /** When set, items are clickable and emit `select`. */
  selectable?: boolean;
  /** id of the currently selected item — gets highlighted. */
  selectedId?: string | number | null;
}

withDefaults(defineProps<Props>(), {
  selectable: false,
  selectedId: null,
});

defineEmits<{ (e: 'select', item: T): void }>();

function keyOf(item: { id?: string | number }, index: number, extractor?: (i: T, idx: number) => string | number): string | number {
  if (extractor) return extractor(item as T, index);
  return item.id ?? index;
}
</script>

<template>
  <ul class="flex flex-col gap-2">
    <li
      v-for="(item, index) in items"
      :key="keyOf(item, index, itemKey)"
      :class="[
        'card bg-base-100 shadow-sm border border-base-300',
        selectable ? 'cursor-pointer hover:border-primary' : '',
        selectedId !== null && item.id === selectedId ? 'border-primary' : '',
      ]"
      @click="selectable && $emit('select', item)"
    >
      <div class="card-body p-4">
        <slot :item="item" :index="index" />
      </div>
    </li>
  </ul>
</template>
