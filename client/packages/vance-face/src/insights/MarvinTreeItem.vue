<script setup lang="ts">
import type { MarvinNodeInsightsDto } from '@vance/generated';

export interface MarvinTreeNode {
  doc: MarvinNodeInsightsDto;
  children: MarvinTreeNode[];
}

defineProps<{ node: MarvinTreeNode }>();
const emit = defineEmits<{ (e: 'select-process', id: string): void }>();
</script>

<template>
  <div class="marvin-node">
    <div class="marvin-node-head">
      <span class="marvin-kind">{{ node.doc.taskKind }}</span>
      <span class="marvin-status">{{ node.doc.status }}</span>
      <span class="marvin-goal">{{ node.doc.goal || $t('insights.marvin.noGoal') }}</span>
      <button
        v-if="node.doc.spawnedProcessId"
        class="link ml-2"
        type="button"
        @click="emit('select-process', node.doc.spawnedProcessId!)"
      >{{ $t('insights.marvin.toWorker') }}</button>
    </div>
    <div v-if="node.doc.failureReason" class="marvin-failure">
      {{ $t('insights.marvin.failure', { reason: node.doc.failureReason }) }}
    </div>
    <ul v-if="node.children.length > 0" class="marvin-children">
      <li v-for="child in node.children" :key="child.doc.id">
        <MarvinTreeItem
          :node="child"
          @select-process="(id) => emit('select-process', id)"
        />
      </li>
    </ul>
  </div>
</template>

<style scoped>
.marvin-children {
  list-style: none;
  padding-left: 1.25rem;
  border-left: 1px dashed hsl(var(--bc) / 0.2);
}
.marvin-node-head {
  display: flex;
  gap: 0.5rem;
  align-items: baseline;
  padding: 0.25rem 0;
}
.marvin-kind {
  font-family: ui-monospace, monospace;
  font-size: 0.75rem;
  opacity: 0.7;
}
.marvin-status {
  font-size: 0.75rem;
  opacity: 0.6;
}
.marvin-goal { font-size: 0.875rem; }
.marvin-failure {
  font-size: 0.75rem;
  color: hsl(var(--er));
  padding-left: 1rem;
}
.link {
  color: hsl(var(--p));
  text-decoration: underline;
  background: transparent;
  cursor: pointer;
  font-family: ui-monospace, monospace;
  font-size: 0.85rem;
}
</style>
