<script setup lang="ts">
import type { FinanceNodeDto } from './generated/finance/FinanceNodeDto';
import type { DropPosition, DropTarget, NodeAction, NodeSnapshot } from './types';
import { VIconButton } from '@vance/components';
import { nodeColorClass, nodeColorStyle } from './nodeColor';
import { useT } from './i18n';

const t = useT();

const props = defineProps<{
  node: FinanceNodeDto;
  depth: number;
  /** An ancestor is disabled — this node is computed but does not count. */
  inactive: boolean;
  selectedName: string | null;
  computedMap: Record<string, NodeSnapshot> | null;
  unitKey: 'perYear' | 'perMonth' | 'perWeek' | 'perDay';
  onAction: (action: NodeAction, name: string) => void;
  /** Drop marker currently shown (owned by the editor). */
  dropTarget: DropTarget | null;
  onDragStart: (name: string) => void;
  onDragOver: (name: string, position: DropPosition) => boolean;
  onDrop: () => void;
  onDragEnd: () => void;
}>();

function startDrag(e: DragEvent): void {
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move';
    // Some browsers require a text/plain payload to start a drag at all.
    e.dataTransfer.setData('text/plain', props.node.name);
  }
  props.onDragStart(props.node.name);
}

/** Top quarter = before, bottom quarter = after, middle = as child. */
function overRow(e: DragEvent): void {
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
  const y = (e.clientY - rect.top) / rect.height;
  const position: DropPosition = y < 0.25 ? 'before' : y > 0.75 ? 'after' : 'inside';
  if (!props.onDragOver(props.node.name, position)) return;
  e.preventDefault(); // allow the drop
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
}

function dropMark(): DropPosition | null {
  return props.dropTarget?.name === props.node.name ? props.dropTarget.position : null;
}

function figureValue(): number | null {
  const snap = props.computedMap?.[props.node.name];
  return snap ? snap[props.unitKey] : null;
}

/** Switched off itself or sitting below a switched-off ancestor. */
function muted(): boolean {
  return props.node.disabled || props.inactive;
}

function figureClass(): string {
  if (props.node.disabled) return 'text-(--v-label-secondary)';
  return (figureValue() ?? 0) < 0 ? 'text-(--v-danger)' : 'text-(--v-success)';
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
      :class="{
        'bg-(--v-selection) text-(--v-accent-text)': selectedName === node.name,
        'opacity-50': muted(),
        'shadow-[inset_0_2px_0_var(--v-accent)]': dropMark() === 'before',
        'shadow-[inset_0_-2px_0_var(--v-accent)]': dropMark() === 'after',
        'ring-2 ring-inset ring-(--v-accent)': dropMark() === 'inside',
      }"
      :style="{ paddingLeft: depth * 16 + 4 + 'px' }"
      @click="onAction('select', node.name)"
      @dragover="overRow"
      @drop.prevent="onDrop()"
    >
      <span
        v-if="node.color"
        class="shrink-0 size-2 rounded-full"
        :class="nodeColorClass(node.color)"
        :style="nodeColorStyle(node.color)"
      />
      <span v-if="node.icon" class="shrink-0">{{ node.icon }}</span>
      <span v-if="node.sign < 0" class="shrink-0 text-(--v-danger) font-mono">−</span>
      <span
        class="truncate flex-1 cursor-grab active:cursor-grabbing"
        :class="{ 'line-through': node.disabled }"
        :draggable="depth > 0"
        @dragstart="startDrag"
        @dragend="onDragEnd()"
      >{{ node.title || node.name }}</span>
      <span
        v-if="figure() !== null"
        class="shrink-0 tabular-nums text-xs font-medium"
        :class="figureClass()"
        :title="t('finance.tree.perUnit', { unit: unitKey.replace('per', '').toLowerCase() })"
      >
        {{ figure() }}
      </span>
      <span class="shrink-0 flex items-center gap-0.5">
        <VIconButton
          icon="power"
          size="sm"
          :active="node.disabled"
          :label="node.disabled ? t('finance.tree.enable') : t('finance.tree.disable')"
          @click.stop="onAction('toggle-disabled', node.name)"
        />
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
      :inactive="muted()"
      :selected-name="selectedName"
      :computed-map="computedMap"
      :unit-key="unitKey"
      :on-action="onAction"
      :drop-target="dropTarget"
      :on-drag-start="onDragStart"
      :on-drag-over="onDragOver"
      :on-drop="onDrop"
      :on-drag-end="onDragEnd"
    />
  </div>
</template>

