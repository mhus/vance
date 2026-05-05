<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  EditorShell,
  VAlert,
  VButton,
  VEmptyState,
  VSelect,
  type Crumb,
} from '@/components';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { useWorkspaceTree } from '@/composables/useWorkspaceTree';
import { useWorkspaceFile } from '@/composables/useWorkspaceFile';
import WorkspaceTreeNode from './WorkspaceTreeNode.vue';
import WorkspaceFileViewer from './WorkspaceFileViewer.vue';
import type { WorkspaceTreeNodeDto } from '@vance/generated';

const { t } = useI18n();
const tenantProjects = useTenantProjects();
const tree = useWorkspaceTree();
const file = useWorkspaceFile();

const selectedProjectId = ref<string | null>(null);
const selectedNode = ref<WorkspaceTreeNodeDto | null>(null);

const projectOptions = computed(() => {
  return tenantProjects.projects.value
    .filter((p) => p.enabled)
    .map((p) => ({ value: p.name, label: p.title || p.name }));
});

const breadcrumbs = computed<Crumb[]>(() => {
  const root: Crumb = { text: t('workspace.pageTitle') };
  if (!selectedProjectId.value) return [root];
  const proj = tenantProjects.projects.value.find((p) => p.name === selectedProjectId.value);
  return [root, { text: proj?.title || selectedProjectId.value }];
});

onMounted(async () => {
  await tenantProjects.reload();
  // Restore from URL or fall back to first enabled project. URL is the
  // source of truth for deep-links — same convention as DocumentApp.
  const params = new URLSearchParams(window.location.search);
  const queryProject = params.get('projectId');
  const queryFile = params.get('path');
  if (queryProject && tenantProjects.projects.value.some((p) => p.name === queryProject)) {
    selectedProjectId.value = queryProject;
  } else if (tenantProjects.projects.value.length > 0) {
    selectedProjectId.value = tenantProjects.projects.value[0].name;
  }
  if (selectedProjectId.value && queryFile) {
    // Caller deep-linked into a file; load it once the tree is ready.
    await tree.loadRoot(selectedProjectId.value);
    await loadFileByPath(queryFile);
    return;
  }
  if (selectedProjectId.value) {
    await tree.loadRoot(selectedProjectId.value);
  }
});

watch(selectedProjectId, async (next, prev) => {
  if (!next || next === prev) return;
  syncQueryParam('projectId', next);
  syncQueryParam('path', null);
  selectedNode.value = null;
  file.clear();
  await tree.loadRoot(next);
});

function onToggle(path: string, isDir: boolean): void {
  if (!selectedProjectId.value) return;
  void tree.toggle(selectedProjectId.value, path, isDir);
}

async function onSelectFile(node: WorkspaceTreeNodeDto): Promise<void> {
  if (!selectedProjectId.value) return;
  selectedNode.value = node;
  syncQueryParam('path', node.path);
  await file.load(selectedProjectId.value, node.path, node.name);
}

async function loadFileByPath(path: string): Promise<void> {
  // Synthetic node for deep-link load — we may not have walked the
  // tree to this exact file yet, but the file endpoint doesn't care.
  const name = path.split('/').pop() ?? path;
  const synthetic: WorkspaceTreeNodeDto = {
    name,
    path,
    type: 0 as unknown as WorkspaceTreeNodeDto['type'], // FILE
    size: 0,
    children: undefined,
  };
  selectedNode.value = synthetic;
  if (!selectedProjectId.value) return;
  await file.load(selectedProjectId.value, path, name);
}

function syncQueryParam(key: string, value: string | null): void {
  const url = new URL(window.location.href);
  if (value === null || value === '') {
    url.searchParams.delete(key);
  } else {
    url.searchParams.set(key, value);
  }
  window.history.replaceState({}, '', url);
}
</script>

<template>
  <EditorShell :title="$t('workspace.pageTitle')" :breadcrumbs="breadcrumbs" wide-right-panel>
    <!-- ─── Sidebar: project picker ─── -->
    <template #sidebar>
      <div class="flex flex-col gap-3 p-2">
        <VSelect
          :model-value="selectedProjectId ?? ''"
          :options="projectOptions"
          :label="$t('workspace.sidebar.projectLabel')"
          @update:model-value="(v) => (selectedProjectId = v ? String(v) : null)"
        />

        <VAlert v-if="tenantProjects.error.value" variant="error">
          {{ tenantProjects.error.value }}
        </VAlert>

        <VEmptyState
          v-if="!tenantProjects.loading.value && projectOptions.length === 0"
          :headline="$t('workspace.sidebar.noProjectsHeadline')"
          :body="$t('workspace.sidebar.noProjectsBody')"
        />
      </div>
    </template>

    <!-- ─── Main: tree ─── -->
    <div class="flex flex-col h-full min-h-0">
      <div v-if="!selectedProjectId" class="flex-1 flex items-center justify-center">
        <VEmptyState
          :headline="$t('workspace.empty.pickProjectHeadline')"
          :body="$t('workspace.empty.pickProjectBody')"
        />
      </div>

      <div v-else-if="tree.loading.value && !tree.root.value" class="flex-1 flex items-center justify-center text-sm opacity-60">
        <span class="loading loading-spinner loading-md" />
      </div>

      <VAlert v-else-if="tree.error.value" variant="error" class="m-4">
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

      <div v-else class="flex-1 flex items-center justify-center px-4">
        <VEmptyState
          :headline="$t('workspace.empty.emptyTreeHeadline')"
          :body="$t('workspace.empty.emptyTreeBody')"
        />
      </div>

      <div class="p-2 border-t border-base-300 flex justify-between items-center text-xs opacity-60">
        <span>{{ $t('workspace.footer.podHint') }}</span>
        <VButton
          v-if="selectedProjectId"
          variant="ghost"
          size="sm"
          @click="selectedProjectId && tree.loadRoot(selectedProjectId)"
        >
          {{ $t('workspace.footer.refresh') }}
        </VButton>
      </div>
    </div>

    <!-- ─── Right panel: file preview ─── -->
    <template #right-panel>
      <WorkspaceFileViewer
        :name="selectedNode?.name ?? null"
        :path="selectedNode?.path ?? null"
        :loading="file.loading.value"
        :error="file.error.value"
        :result="file.result.value"
      />
    </template>
  </EditorShell>
</template>
