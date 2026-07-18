<script setup lang="ts">
/**
 * Renders a single Damogran compose output artifact. Content lives in the
 * (transient) workspace — addressed by the artifact's
 * {@code vance-workspace:/<dir>/<rel>} URI — so it is loaded on demand from the
 * workspace REST surface (never inlined into the document). This mirrors a
 * Jupyter cell output: workspace-sourced, flushed when the project unloads.
 *
 * v1 renders markdown / images / PDF / text; structured Vance kinds
 * (records/tree/chart/…) render as text for now (inline kind-renderer wiring is
 * a follow-up — feeding raw workspace text into those renderers needs
 * per-kind canonical formats).
 */
import { computed, onMounted } from 'vue';
import { CodeEditor, MarkdownView, VAlert, VButton, VCard } from '@/components';
import { useWorkspaceFile, workspaceFileUrl } from '@/composables/useWorkspaceFile';

interface OutputArtifact {
  path: string;
  uri: string;
  kind?: string;
  mime?: string;
  title?: string;
}
const props = defineProps<{ projectId: string; output: OutputArtifact }>();

const WORKSPACE_PREFIX = 'vance-workspace:/';

/** `vance-workspace:/<dir>/<rel>` → `<dir>/<rel>` (the workspace REST path). */
const wsPath = computed<string>(() =>
  props.output.uri.startsWith(WORKSPACE_PREFIX)
    ? props.output.uri.slice(WORKSPACE_PREFIX.length)
    : props.output.uri,
);

const { result, loading, error, load } = useWorkspaceFile();
onMounted(() => {
  void load(props.projectId, wsPath.value, props.output.path);
});

const url = computed<string>(() => workspaceFileUrl(props.projectId, wsPath.value));
const isPdf = computed<boolean>(() => props.output.path.toLowerCase().endsWith('.pdf'));
const title = computed<string>(() => props.output.title ?? props.output.path);
</script>

<template>
  <VCard :title="title">
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <p v-else-if="loading" class="text-sm opacity-60">Loading…</p>
    <template v-else-if="result">
      <iframe
        v-if="isPdf"
        :src="url"
        :title="title"
        class="w-full h-96 border-0"
      ></iframe>
      <img
        v-else-if="result.mode === 'image'"
        :src="url"
        :alt="title"
        class="max-w-full rounded"
      />
      <MarkdownView v-else-if="result.mode === 'markdown'" :source="result.text" />
      <CodeEditor
        v-else-if="result.mode === 'text'"
        :model-value="result.text ?? ''"
        :mime-type="result.mimeType"
        :read-only="true"
      />
      <VButton v-else variant="link" size="sm" :href="url">
        Download {{ output.path }}
      </VButton>
    </template>
  </VCard>
</template>
