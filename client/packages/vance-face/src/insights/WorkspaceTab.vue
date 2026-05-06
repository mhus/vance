<script setup lang="ts">
import { ref, watch } from 'vue';
import { VAlert, VButton, VEmptyState } from '@/components';
import { useWorkspaceTree } from '@/composables/useWorkspaceTree';
import { useWorkspaceFile } from '@/composables/useWorkspaceFile';
import WorkspaceTreeNode from './WorkspaceTreeNode.vue';
import WorkspaceFileViewer from './WorkspaceFileViewer.vue';
import type { WorkspaceTreeNodeDto } from '@vance/generated';

const props = defineProps<{ projectId: string | null }>();

const tree = useWorkspaceTree();
const file = useWorkspaceFile();
const selectedNode = ref<WorkspaceTreeNodeDto | null>(null);

watch(
  () => props.projectId,
  async (next, prev) => {
    if (next === prev) return;
    selectedNode.value = null;
    file.clear();
    if (next) {
      await tree.loadRoot(next);
    }
  },
  { immediate: true },
);

function onToggle(path: string, isDir: boolean): void {
  if (!props.projectId) return;
  void tree.toggle(props.projectId, path, isDir);
}

async function onSelectFile(node: WorkspaceTreeNodeDto): Promise<void> {
  if (!props.projectId) return;
  selectedNode.value = node;
  await file.load(props.projectId, node.path, node.name);
}
</script>

<template>
  <div class="flex h-[calc(100vh-12rem)] min-h-0 gap-3">
    <!-- ─── Tree pane ─── -->
    <div class="flex flex-col w-80 shrink-0 min-h-0 border border-base-300 rounded">
      <div v-if="!projectId" class="p-4 opacity-60 text-sm">
        {{ $t('workspace.pickProjectHint') }}
      </div>

      <div
        v-else-if="tree.loading.value && !tree.root.value"
        class="flex-1 flex items-center justify-center text-sm opacity-60"
      >
        <span class="loading loading-spinner loading-md" />
      </div>

      <VAlert v-else-if="tree.error.value" variant="error" class="m-3">
        {{ tree.error.value }}
      </VAlert>

      <div v-else-if="tree.root.value" class="flex-1 min-h-0 overflow-auto p-2">
        <WorkspaceTreeNode
          :node="tree.root.value"
          :expanded="tree.expanded"
          :loading-paths="tree.loadingPaths"
          :selected-path="selectedNode?.path ?? null"
          :level="0"
          @toggle="onToggle"
          @select-file="onSelectFile"
        />
      </div>

      <div
        v-else
        class="flex-1 flex items-center justify-center px-4"
      >
        <VEmptyState
          :headline="$t('workspace.empty.emptyTreeHeadline')"
          :body="$t('workspace.empty.emptyTreeBody')"
        />
      </div>

      <div
        v-if="projectId"
        class="p-2 border-t border-base-300 flex justify-between items-center text-xs opacity-60"
      >
        <span>{{ $t('workspace.footer.podHint') }}</span>
        <VButton
          variant="ghost"
          size="sm"
          @click="projectId && tree.loadRoot(projectId)"
        >
          {{ $t('workspace.footer.refresh') }}
        </VButton>
      </div>
    </div>

    <!-- ─── File-viewer pane ─── -->
    <div class="flex-1 min-w-0 border border-base-300 rounded">
      <WorkspaceFileViewer
        :name="selectedNode?.name ?? null"
        :path="selectedNode?.path ?? null"
        :loading="file.loading.value"
        :error="file.error.value"
        :result="file.result.value"
      />
    </div>
  </div>
</template>
