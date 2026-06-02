<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  CodeEditor,
  EditorShell,
  type FocusZone,
  VAlert,
  VButton,
  VEmptyState,
  VInput,
  VModal,
  VSelect,
} from '@/components';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { useScriptStore } from './stores/scriptStore';
import FileTreeSidebar from './components/FileTreeSidebar.vue';
import EditorTabs from './components/EditorTabs.vue';
import ValidatePanel from './components/ValidatePanel.vue';
import ExecutionDialog from './components/ExecutionDialog.vue';
import HactarPanel from './components/HactarPanel.vue';

const projectsState = useTenantProjects();
const store = useScriptStore();

const selectedProjectId = ref<string | null>(null);
const showCreate = ref(false);
const createPath = ref('');
const createError = ref<string | null>(null);
const creating = ref(false);
const saving = ref(false);
const saveError = ref<string | null>(null);

const showExecuteDialog = ref(false);
const showHactar = ref(false);

// Sidebar (project selector + file tree) vs. main (editor) vs.
// right (validate panel). Same convention as other editors —
// clicking a row in the file tree pulls focus to main.
const focusZone = ref<FocusZone>('main');

onMounted(async () => {
  await projectsState.reload();
  const params = new URLSearchParams(window.location.search);
  const fromUrl = params.get('projectId');
  if (fromUrl) {
    selectedProjectId.value = fromUrl;
  } else if (projectsState.projects.value.length === 1) {
    selectedProjectId.value = projectsState.projects.value[0].name;
  }
});

watch(selectedProjectId, async (pid) => {
  if (!pid) return;
  const url = new URL(window.location.href);
  url.searchParams.set('projectId', pid);
  window.history.replaceState(null, '', url.toString());
  await store.loadList(pid);
});

const activeTab = computed(() => store.activeTab);

const editorMime = computed<string>(() => {
  const t = activeTab.value;
  if (!t) return 'text/javascript';
  if (t.mimeType) return t.mimeType;
  // Derive from path if missing.
  const lower = t.path.toLowerCase();
  if (lower.endsWith('.js') || lower.endsWith('.mjs')) return 'text/javascript';
  if (lower.endsWith('.json')) return 'application/json';
  if (lower.endsWith('.md')) return 'text/markdown';
  if (lower.endsWith('.yml') || lower.endsWith('.yaml')) return 'application/yaml';
  return 'text/plain';
});

const isExecutable = computed<boolean>(() => {
  const t = activeTab.value;
  if (!t) return false;
  const lower = t.path.toLowerCase();
  return lower.endsWith('.js') || lower.endsWith('.mjs');
});

async function onSave(): Promise<void> {
  saving.value = true;
  saveError.value = null;
  try {
    await store.saveActive();
  } catch (e) {
    saveError.value = e instanceof Error ? e.message : 'Save failed';
  } finally {
    saving.value = false;
  }
}

function onNew(parentPath: string): void {
  // Default to scripts/ when the user clicks "+ new" at the root —
  // the most common case, but they're free to delete it and write
  // any other project-relative path (e.g. skills/myskill/foo.js,
  // documents/, etc.).
  createPath.value = parentPath ? `${parentPath}/` : 'scripts/';
  createError.value = null;
  showCreate.value = true;
}

async function confirmCreate(): Promise<void> {
  if (!createPath.value.trim()) {
    createError.value = 'Path required';
    return;
  }
  creating.value = true;
  createError.value = null;
  try {
    await store.createFile({
      path: createPath.value.trim(),
      inlineText: '',
    });
    showCreate.value = false;
  } catch (e) {
    createError.value = e instanceof Error ? e.message : 'Create failed';
  } finally {
    creating.value = false;
  }
}

async function onDelete(id: string): Promise<void> {
  if (!confirm('Delete this file?')) return;
  await store.deleteFile(id);
}

function onExecute(): void {
  if (!activeTab.value) return;
  showExecuteDialog.value = true;
}

function onOpenHactar(): void {
  showHactar.value = true;
}

function onHactarApplied(code: string): void {
  if (!activeTab.value) return;
  store.updateActiveContent(code);
  showHactar.value = false;
}

const projectOptions = computed(() =>
  projectsState.projects.value.map((p) => ({ value: p.name, label: p.title ?? p.name })));
</script>

<template>
  <EditorShell
    v-model:focus-zone="focusZone"
    title="Script Cortex"
    :full-height="true"
    :show-sidebar="true"
    :show-right-panel="!!activeTab"
    focus-model="auto"
    title-clickable
    help-path="script-cortex.md"
    @title-click="focusZone = 'sidebar'"
  >
    <template #sidebar>
      <div class="flex flex-col h-full min-h-0">
        <!-- Project selector — lives at the top of the sidebar
             (replaces the old {@code #topbar-extra} dropdown so it
             sits inside the sidebar zone with the rest of the
             navigation). Sticky-like via a non-shrinking row. -->
        <div class="p-3 border-b border-base-300 shrink-0">
          <VSelect
            v-if="projectOptions.length > 0"
            v-model="selectedProjectId"
            :options="projectOptions"
            placeholder="Select project…"
          />
        </div>
        <div class="flex-1 min-h-0 overflow-y-auto">
          <FileTreeSidebar
            v-if="selectedProjectId"
            :root="store.fileTree"
            :active-file-id="store.activeTabId"
            @open-file="(id) => { focusZone = 'main'; store.openFile(id); }"
            @new-file="onNew"
            @delete-file="onDelete"
          />
          <div v-else class="p-3 text-sm opacity-60">
            Pick a project first.
          </div>
        </div>
      </div>
    </template>

    <div class="flex flex-col h-full min-h-0">
      <EditorTabs
        :tabs="store.openTabs"
        :active-tab-id="store.activeTabId"
        @select="store.setActiveTab"
        @close="store.closeTab"
      />

      <div
        v-if="!activeTab"
        class="flex-1 flex items-center justify-center"
      >
        <VEmptyState
          headline="No file open"
          body="Pick a file from the left, or create a new one."
        />
      </div>

      <div v-else class="flex-1 flex flex-col min-h-0">
        <div class="flex items-center gap-2 px-3 py-2 border-b border-base-300 bg-base-100 text-sm">
          <span class="font-mono opacity-80 truncate">{{ activeTab.path }}</span>
          <span v-if="activeTab.dirty" class="opacity-60">●</span>
          <span class="flex-1" />
          <VButton size="sm" :loading="saving" :disabled="!activeTab.dirty" @click="onSave">Save</VButton>
          <VButton
            v-if="isExecutable"
            size="sm"
            variant="primary"
            @click="onExecute"
          >Execute</VButton>
          <VButton
            size="sm"
            variant="ghost"
            @click="onOpenHactar"
          >🧠 Hactar</VButton>
        </div>

        <VAlert v-if="saveError" variant="error" class="m-2">{{ saveError }}</VAlert>

        <div class="flex-1 min-h-0 overflow-hidden">
          <CodeEditor
            :model-value="activeTab.inlineText"
            :mime-type="editorMime"
            @update:model-value="store.updateActiveContent"
          />
        </div>
      </div>
    </div>

    <!-- Right panel only when a file is open — visibility driven by
         the {@code show-right-panel} prop on EditorShell so the
         column collapses entirely when there's nothing to show.
         The {@code v-if} sits on the inner content (not the slot
         template) to keep Vue's slot-presence detection stable. -->
    <template #right-panel>
      <div v-if="activeTab" class="h-full overflow-y-auto">
        <ValidatePanel :file="activeTab" />
      </div>
    </template>
  </EditorShell>

  <VModal v-model="showCreate" title="New file">
    <div class="space-y-2 p-2">
      <VInput
        v-model="createPath"
        label="Path"
        placeholder="utils/sum.js"
      />
      <VAlert v-if="createError" variant="error">{{ createError }}</VAlert>
      <div class="flex justify-end gap-2 pt-2">
        <VButton variant="ghost" @click="showCreate = false">Cancel</VButton>
        <VButton variant="primary" :loading="creating" @click="confirmCreate">Create</VButton>
      </div>
    </div>
  </VModal>

  <ExecutionDialog
    v-if="showExecuteDialog && activeTab && selectedProjectId"
    :file="activeTab"
    :project-id="selectedProjectId"
    @close="showExecuteDialog = false"
  />

  <HactarPanel
    v-if="showHactar && selectedProjectId"
    :file="activeTab"
    :project-id="selectedProjectId"
    @close="showHactar = false"
    @apply="onHactarApplied"
  />
</template>
