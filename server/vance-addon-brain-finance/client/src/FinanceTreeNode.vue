<script setup lang="ts">
import type { FinanceNodeDto } from './generated/finance/FinanceNodeDto';
import type { NodeAction, NodeSnapshot } from './types';
import { VIconButton } from '@vance/components';
import { useT } from './i18n';

const t = useT();

const props = defineProps<{
  node: FinanceNodeDto;
  depth: number;
  selectedName: string | null;
  computedMap: Record<string, NodeSnapshot> | null;
  unitKey: 'perYear' | 'perMonth' | 'perWeek' | 'perDay';
  onAction: (action: NodeAction, name: string) => void;
}>();

function figureValue(): number | null {
  const snap = props.computedMap?.[props.node.name];
  return snap ? snap[props.unitKey] : null;
}

function figure(): string | null {
  const v = figureValue();
  return v === null ? null : v.toLocaleString(undefined, { maximumFractionDigits: 2 });
}
</script>

<template>
  <div>
    <div
      class="flex items-center gap-1 rounded px-1 py-0.5 cursor-pointer hover:bg-(--v-fill)/50"
      :class="{ 'bg-(--v-selection) text-(--v-accent-text)': selectedName === node.name }"
      :style="{ paddingLeft: depth * 16 + 4 + 'px' }"
      @click="onAction('select', node.name)"
    >
      <span v-if="node.icon" class="shrink-0">{{ node.icon }}</span>
      <span v-if="node.sign < 0" class="shrink-0 text-(--v-danger) font-mono">−</span>
      <span class="truncate flex-1">{{ node.title || node.name }}</span>
      <span
        v-if="figure() !== null"
        class="shrink-0 tabular-nums text-xs font-medium"
        :class="(figureValue() ?? 0) < 0 ? 'text-(--v-danger)' : 'text-(--v-success)'"
        :title="t('finance.tree.perUnit', { unit: unitKey.replace('per', '').toLowerCase() })"
      >
        {{ figure() }}
      </span>
      <span class="shrink-0 flex items-center gap-0.5">
        <VIconButton icon="add" size="sm" :label="t('finance.tree.addChild')" @click.stop="onAction('add-child', node.name)" />
        <VIconButton icon="arrow-up" size="sm" :label="t('finance.tree.up')" @click.stop="onAction('move-up', node.name)" />
        <VIconButton icon="arrow-down" size="sm" :label="t('finance.tree.down')" @click.stop="onAction('move-down', node.name)" />
        <VIconButton icon="arrow-right" size="sm" :label="t('finance.tree.indent')" @click.stop="onAction('indent', node.name)" />
        <VIconButton icon="arrow-left" size="sm" :label="t('finance.tree.outdent')" @click.stop="onAction('outdent', node.name)" />
        <VIconButton icon="delete" size="sm" danger :label="t('finance.tree.delete')" @click.stop="onAction('remove', node.name)" />
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

