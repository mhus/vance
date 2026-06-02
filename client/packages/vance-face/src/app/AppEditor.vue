<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref } from 'vue';
import { EditorShell, VAlert, VEmptyState } from '@/components';
import type { Crumb } from '@/components';
import { brainFetch } from '@vance/shared';
import { useTenantProjects } from '@/composables/useTenantProjects';

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

const projectsState = useTenantProjects();
const projectTitle = computed<string>(() => {
  if (!projectId.value) return '';
  const match = projectsState.projects.value.find((p) => p.name === projectId.value);
  return match?.title ?? projectId.value;
});

/**
 * Build a URL that opens the Documents picker at a specific folder
 * inside the current project. The picker honors {@code ?path=…} on
 * mount, so this lands the user exactly where the app folder sits
 * (mirrors {@link DocumentApp.applyPathFilter}).
 */
function documentsUrl(pathPrefix: string): string {
  const params = new URLSearchParams({ projectId: projectId.value });
  if (pathPrefix) params.set('path', pathPrefix);
  return `/documents.html?${params.toString()}`;
}

/**
 * Top-bar breadcrumbs. Mirrors the {@link DocumentApp}'s pattern:
 * project title → each folder segment (clickable, drilling the
 * Documents picker into that level) → the app title as a final,
 * non-clickable leaf. The app editor itself has no internal
 * navigation, so the breadcrumb is the only way back into the
 * Documents view.
 */
const breadcrumbs = computed<Crumb[]>(() => {
  if (!projectId.value) return [];
  const crumbs: Crumb[] = [];

  // Project root → Documents picker at the project root.
  crumbs.push({
    text: projectTitle.value,
    onClick: () => window.location.assign(documentsUrl('')),
  });

  // Folder segments. The app folder itself (the last segment) is
  // also clickable — clicking it lands the picker inside the
  // folder that contains the {@code _app.yaml}, so the user can
  // see the file alongside the rest of the folder's content.
  if (folder.value) {
    const segments = folder.value.split('/').filter((s) => s.length > 0);
    let acc = '';
    for (const seg of segments) {
      acc += seg + '/';
      const target = acc;
      crumbs.push({
        text: seg,
        onClick: () => window.location.assign(documentsUrl(target)),
      });
    }
  }

  // App title — final crumb, non-clickable (the user is here).
  if (docTitle.value && docTitle.value !== folder.value) {
    crumbs.push(docTitle.value);
  }

  return crumbs;
});

onMounted(async () => {
  // Fire-and-forget — breadcrumbs upgrade to titled crumbs once
  // the project list lands. The app sub-editor render path doesn't
  // depend on it, so we don't block on the request.
  void projectsState.reload();

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
  <EditorShell :title="docTitle || 'App'" :breadcrumbs="breadcrumbs" full-height>
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
