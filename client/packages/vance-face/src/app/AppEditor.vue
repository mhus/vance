<script setup lang="ts">
import { defineAsyncComponent, onMounted, ref } from 'vue';
import { EditorShell, VAlert, VEmptyState } from '@/components';
import { brainFetch } from '@vance/shared';

interface DocumentSummary {
  id: string;
  path: string;
  title?: string;
  mimeType?: string;
  kind?: string;
  headers?: Record<string, string>;
  projectId: string;
}

// Lazy-load app sub-editors so the bundle stays slim.
const KanbanBoard = defineAsyncComponent(() => import('./kanban/KanbanBoard.vue'));
const CalendarPlanner = defineAsyncComponent(() => import('./calendar/CalendarPlanner.vue'));
const SlideshowApp = defineAsyncComponent(() => import('./slideshow/SlideshowApp.vue'));

const projectId = ref<string>('');
const folder = ref<string>('');
const appType = ref<string | null>(null);
const docTitle = ref<string>('');
const loading = ref(true);
const error = ref<string | null>(null);

onMounted(async () => {
  try {
    const params = new URLSearchParams(window.location.search);
    const queryProject = params.get('projectId') ?? '';
    const queryFolder = params.get('folder') ?? '';
    const queryDoc = params.get('documentId');

    projectId.value = queryProject;

    if (queryDoc) {
      // Resolve folder + app type from the manifest document.
      const doc = await brainFetch<DocumentSummary>('GET', `documents/${queryDoc}`);
      projectId.value = doc.projectId;
      folder.value = doc.path.replace(/\/_app\.yaml$/, '');
      appType.value = doc.headers?.app ?? null;
      docTitle.value = doc.title ?? folder.value;
    } else if (queryFolder) {
      folder.value = queryFolder;
      // Look up the manifest to learn the app type.
      const url = `documents/by-path?projectId=${encodeURIComponent(projectId.value)}&path=${encodeURIComponent(folder.value + '/_app.yaml')}`;
      const doc = await brainFetch<DocumentSummary>('GET', url);
      appType.value = doc.headers?.app ?? null;
      docTitle.value = doc.title ?? folder.value;
    } else {
      error.value = 'Missing folder or documentId in the URL.';
    }
  } catch (e) {
    error.value = `Could not load app manifest: ${(e as Error).message}`;
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <EditorShell :title="docTitle || 'App'" full-height>
    <div v-if="loading" class="p-8 text-base-content/70">Loading…</div>
    <VAlert v-else-if="error" variant="error" class="m-4">
      {{ error }}
    </VAlert>
    <KanbanBoard
      v-else-if="appType === 'kanban'"
      :project-id="projectId"
      :folder="folder"
      :title="docTitle"
    />
    <CalendarPlanner
      v-else-if="appType === 'calendar'"
      :project-id="projectId"
      :folder="folder"
      :title="docTitle"
    />
    <SlideshowApp
      v-else-if="appType === 'slideshow'"
      :project-id="projectId"
      :folder="folder"
      :title="docTitle"
    />
    <VEmptyState
      v-else
      headline="Unknown app type"
      :body="`No editor registered for app type '${appType ?? '(missing)'}'. The folder's _app.yaml may be malformed.`"
    />
  </EditorShell>
</template>
