<script setup lang="ts">
import { computed, ref, watch } from 'vue';

/**
 * Renderer for the inline slash-command popup. Wired up via
 * {@link ./SlashCommands.ts} and rendered into a Tippy-anchored
 * floater. Arrow keys + Enter to pick; the host extension forwards
 * those keys via the exposed {@code onKeyDown} handler.
 */
export interface SlashCommandItem {
  id: string;
  title: string;
  hint: string;
  // command is called by the extension's `command` option, not here —
  // the extension owns the Tiptap chain. The list just exposes which
  // item was picked.
}

const props = defineProps<{
  items: SlashCommandItem[];
  command: (item: SlashCommandItem) => void;
}>();

const selectedIndex = ref(0);

watch(
  () => props.items,
  () => {
    selectedIndex.value = 0;
  },
);

function selectItem(index: number) {
  const item = props.items[index];
  if (item) props.command(item);
}

function onKeyDown(payload: { event: KeyboardEvent }): boolean {
  const { event } = payload;
  if (event.key === 'ArrowUp') {
    selectedIndex.value =
      (selectedIndex.value + props.items.length - 1) % Math.max(props.items.length, 1);
    return true;
  }
  if (event.key === 'ArrowDown') {
    selectedIndex.value = (selectedIndex.value + 1) % Math.max(props.items.length, 1);
    return true;
  }
  if (event.key === 'Enter') {
    selectItem(selectedIndex.value);
    return true;
  }
  return false;
}

const hasItems = computed(() => props.items.length > 0);

defineExpose({ onKeyDown });
</script>

<template>
  <div class="slash-list">
    <div v-if="!hasItems" class="slash-list__empty">No matching block</div>
    <button
      v-for="(item, i) in items"
      :key="item.id"
      class="slash-list__item"
      :class="{ 'slash-list__item--selected': i === selectedIndex }"
      type="button"
      @click="selectItem(i)"
      @mouseenter="selectedIndex = i"
    >
      <div class="slash-list__title">{{ item.title }}</div>
      <div class="slash-list__hint">{{ item.hint }}</div>
    </button>
  </div>
</template>

<style>
.slash-list {
  background: oklch(var(--b1));
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.5rem;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.12);
  padding: 0.25rem;
  min-width: 16rem;
  max-height: 60vh;
  overflow-y: auto;
}
.slash-list__empty {
  padding: 0.5rem 0.75rem;
  color: oklch(var(--bc) / 0.65);
  font-size: 0.85rem;
  font-style: italic;
}
.slash-list__item {
  display: block;
  width: 100%;
  text-align: left;
  padding: 0.4rem 0.6rem;
  border: none;
  background: none;
  border-radius: 0.375rem;
  cursor: pointer;
  color: inherit;
}
.slash-list__item--selected {
  background: oklch(var(--p) / 0.15);
  color: oklch(var(--p));
}
.slash-list__title {
  font-weight: 500;
  font-size: 0.9rem;
}
.slash-list__hint {
  font-size: 0.78rem;
  color: oklch(var(--bc) / 0.65);
}
.slash-list__item--selected .slash-list__hint {
  color: inherit;
  opacity: 0.8;
}
</style>
