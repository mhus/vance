<script setup lang="ts">
import type { FinanceNodeDto } from './generated/finance/FinanceNodeDto';
import type { NodeAction, NodeSnapshot } from './types';

const props = defineProps<{
  node: FinanceNodeDto;
  depth: number;
  selectedName: string | null;
  computedMap: Record<string, NodeSnapshot> | null;
  unitKey: 'perYear' | 'perMonth' | 'perWeek' | 'perDay';
  onAction: (action: NodeAction, name: string) => void;
}>();

function figure(): string | null {
  const snap = props.computedMap?.[props.node.name];
  if (!snap) return null;
  const v = snap[props.unitKey];
  return v.toLocaleString(undefined, { maximumFractionDigits: 2 });
}
</script>

<template>
  <div>
    <div
      class="flex items-center gap-1 rounded px-1 py-0.5 cursor-pointer hover:bg-black/5 dark:hover:bg-white/5"
      :class="{ 'bg-black/10 dark:bg-white/10': selectedName === node.name }"
      :style="{ paddingLeft: depth * 16 + 4 + 'px' }"
      @click="onAction('select', node.name)"
    >
      <span v-if="node.icon" class="shrink-0">{{ node.icon }}</span>
      <span v-if="node.sign < 0" class="shrink-0 text-red-500 font-mono">−</span>
      <span class="truncate flex-1">{{ node.title || node.name }}</span>
      <span v-if="figure() !== null" class="shrink-0 tabular-nums text-xs opacity-70">
        {{ figure() }}
      </span>
      <span class="shrink-0 flex items-center gap-0.5">
        <button class="fx-btn" title="Add child" @click.stop="onAction('add-child', node.name)">＋</button>
        <button class="fx-btn" title="Up" @click.stop="onAction('move-up', node.name)">↑</button>
        <button class="fx-btn" title="Down" @click.stop="onAction('move-down', node.name)">↓</button>
        <button class="fx-btn" title="Indent" @click.stop="onAction('indent', node.name)">→</button>
        <button class="fx-btn" title="Outdent" @click.stop="onAction('outdent', node.name)">←</button>
        <button class="fx-btn" title="Delete" @click.stop="onAction('remove', node.name)">🗑</button>
      </span>
    </div>
    <FinanceTreeNode
      v-for="child in node.children"
      :key="child.name"
      :node="child"
      :depth="depth + 1"
      :selected-name="selectedName"
      :computed-map="computedMap"
      :unit-key="unitKey"
      :on-action="onAction"
    />
  </div>
</template>

<style scoped>
.fx-btn {
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 0.7rem;
  padding: 0 0.2rem;
  border-radius: 0.2rem;
  opacity: 0.6;
}
.fx-btn:hover {
  opacity: 1;
}
</style>
