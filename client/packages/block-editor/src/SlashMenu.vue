<script setup lang="ts">
import { ref } from 'vue';

defineEmits<{
  (e: 'pick', kind: string): void;
  (e: 'close'): void;
}>();

const groups = ref([
  {
    label: 'Text',
    items: [
      { kind: 'paragraph', label: 'Text', hint: 'Plain paragraph' },
      { kind: 'heading-1', label: 'Heading 1', hint: 'Large section heading' },
      { kind: 'heading-2', label: 'Heading 2', hint: 'Medium heading' },
      { kind: 'heading-3', label: 'Heading 3', hint: 'Small heading' },
      { kind: 'quote', label: 'Quote', hint: 'Set-off citation' },
    ],
  },
  {
    label: 'Lists',
    items: [
      { kind: 'bullet', label: 'Bullet list', hint: '- item' },
      { kind: 'numbered', label: 'Numbered list', hint: '1. item' },
      { kind: 'todo', label: 'Todo', hint: '- [ ] task' },
    ],
  },
  {
    label: 'Blocks',
    items: [
      { kind: 'code', label: 'Code block', hint: 'Fenced code' },
      { kind: 'divider', label: 'Divider', hint: '---' },
      { kind: 'callout', label: 'Callout', hint: 'Info / Warn / Note' },
      { kind: 'toggle', label: 'Toggle', hint: 'Collapsible section' },
      { kind: 'link-card', label: 'Link card', hint: 'Rich URL preview' },
      { kind: 'dataview', label: 'Dataview', hint: 'Embed aggregation (stub)' },
    ],
  },
]);
</script>

<template>
  <div class="slash-menu" @click.self="$emit('close')">
    <div class="slash-menu__panel">
      <header>
        <span>Insert block</span>
        <button class="slash-menu__close" @click="$emit('close')">×</button>
      </header>
      <div v-for="group in groups" :key="group.label" class="slash-menu__group">
        <div class="slash-menu__group-label">{{ group.label }}</div>
        <button
          v-for="item in group.items"
          :key="item.kind"
          class="slash-menu__item"
          @click="$emit('pick', item.kind)"
        >
          <span class="slash-menu__item-label">{{ item.label }}</span>
          <span class="slash-menu__item-hint">{{ item.hint }}</span>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.slash-menu {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.2);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  z-index: 50;
  padding-top: 6rem;
}
.slash-menu__panel {
  width: 100%;
  max-width: 28rem;
  background: oklch(var(--b1));
  border-radius: 0.5rem;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.15);
  padding: 0.5rem;
  max-height: 70vh;
  overflow-y: auto;
}
.slash-menu__panel header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.25rem 0.5rem 0.75rem;
  font-weight: 600;
  border-bottom: 1px solid oklch(var(--bc) / 0.18);
}
.slash-menu__close {
  background: none;
  border: none;
  font-size: 1.25rem;
  cursor: pointer;
  color: oklch(var(--bc) / 0.65);
}
.slash-menu__group {
  margin-top: 0.5rem;
}
.slash-menu__group-label {
  font-size: 0.75rem;
  text-transform: uppercase;
  color: oklch(var(--bc) / 0.65);
  padding: 0.25rem 0.5rem;
}
.slash-menu__item {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  padding: 0.5rem 0.75rem;
  border: none;
  background: none;
  border-radius: 0.375rem;
  cursor: pointer;
  text-align: left;
}
.slash-menu__item:hover {
  background: oklch(var(--bc) / 0.06);
}
.slash-menu__item-label {
  font-weight: 500;
}
.slash-menu__item-hint {
  font-size: 0.8em;
  color: oklch(var(--bc) / 0.65);
}
</style>
