<script setup lang="ts">
import { computed } from 'vue';
import type { WorkspaceTreeNodeDto } from '@vance/generated';
import { WorkspaceNodeType } from '@vance/generated';

interface Props {
  node: WorkspaceTreeNodeDto;
  /** Reactive set of expanded folder paths. */
  expanded: Set<string>;
  /** Reactive set of paths whose children fetch is in flight. */
  loadingPaths: Set<string>;
  /** Currently-selected file path (for highlight); null when nothing selected. */
  selectedPath: string | null;
  /** Indentation depth — children pass parent + 1. */
  level: number;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  (e: 'toggle', path: string, isDir: boolean): void;
  (e: 'selectFile', node: WorkspaceTreeNodeDto): void;
}>();

const isDir = computed(() => props.node.type === WorkspaceNodeType.DIR);
const isExpanded = computed(() => props.expanded.has(props.node.path));
const isLoading = computed(() => props.loadingPaths.has(props.node.path));
const isSelected = computed(() => props.selectedPath === props.node.path);

function onClick(): void {
  if (isDir.value) {
    emit('toggle', props.node.path, true);
  } else {
    emit('selectFile', props.node);
  }
}

const sortedChildren = computed<WorkspaceTreeNodeDto[]>(() => {
  if (!props.node.children) return [];
  // Folders first, then files; alphabetic within each group.
  return props.node.children.slice().sort((a, b) => {
    if (a.type !== b.type) return a.type === WorkspaceNodeType.DIR ? -1 : 1;
    return a.name.localeCompare(b.name);
  });
});

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
</script>

<template>
  <div>
    <button
      type="button"
      class="row"
      :class="{ 'row--selected': isSelected }"
      :style="{ paddingLeft: `${level * 1.25 + 0.5}rem` }"
      @click="onClick"
    >
      <span class="chevron">
        <template v-if="isDir">
          <span v-if="isLoading" class="loading loading-spinner loading-xs" />
          <span v-else-if="isExpanded">▾</span>
          <span v-else>▸</span>
        </template>
      </span>
      <span class="icon" :class="isDir ? 'opacity-70' : 'opacity-40'">
        {{ isDir ? '📁' : '📄' }}
      </span>
      <span class="name truncate">{{ node.name }}</span>
      <span v-if="!isDir" class="size">{{ formatSize(node.size) }}</span>
    </button>

    <template v-if="isDir && isExpanded && node.children !== undefined">
      <WorkspaceTreeNode
        v-for="child in sortedChildren"
        :key="child.path"
        :node="child"
        :expanded="expanded"
        :loading-paths="loadingPaths"
        :selected-path="selectedPath"
        :level="level + 1"
        @toggle="(p, isD) => emit('toggle', p, isD)"
        @select-file="(n) => emit('selectFile', n)"
      />
    </template>
  </div>
</template>

<style scoped>
.row {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  width: 100%;
  padding: 0.25rem 0.5rem 0.25rem 0;
  text-align: left;
  font-size: 0.875rem;
  border-radius: 0.25rem;
  background: transparent;
  border: 0;
  color: inherit;
  cursor: pointer;
  white-space: nowrap;
}
.row:hover {
  background: oklch(var(--b2));
}
.row--selected {
  background: oklch(var(--p) / 0.12);
  color: oklch(var(--p));
  font-weight: 500;
}
.chevron {
  width: 1rem;
  display: inline-flex;
  justify-content: center;
  font-size: 0.75rem;
  opacity: 0.7;
}
.icon {
  width: 1rem;
}
.name {
  flex: 1;
  min-width: 0;
}
.size {
  font-size: 0.7rem;
  opacity: 0.55;
  font-variant-numeric: tabular-nums;
}
</style>
