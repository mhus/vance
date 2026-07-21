<script setup lang="ts">
/**
 * Renders a single Damogran compose output artifact. Content lives in the
 * (transient) workspace — addressed by the artifact's
 * {@code vance-workspace:/<dir>/<rel>} URI — so it is loaded on demand from the
 * workspace REST surface (never inlined into the document). This mirrors a
 * Jupyter cell output: workspace-sourced, flushed when the project unloads.
 *
 * Renders markdown / images / PDF / text, plus structured Vance kinds
 * (records → table, tree, chart, …) via the kind-renderer registry when the
 * output explicitly declares a kind (`as: records`) — so only canonical-format
 * outputs go through those renderers; a raw file stays text.
 */
import { computed, onMounted } from 'vue';
import { CodeEditor, MarkdownView, VAlert, VButton, VCard } from '@/components';
import { useWorkspaceFile, workspaceFileUrl } from '@/composables/useWorkspaceFile';
import { resolveRenderer } from '@/kindRenderers/registry';
import ComposeProcessOutput from './ComposeProcessOutput.vue';

interface OutputArtifact {
  path: string;
  uri: string;
  kind?: string;
  mime?: string;
  title?: string;
}
const props = defineProps<{ projectId: string; output: OutputArtifact }>();

const WORKSPACE_PREFIX = 'vance-workspace:/';
const PROCESS_PREFIX = 'vance-process:';

/** An `agent`-task output points at a session process, not a workspace file. */
const isProcess = computed<boolean>(() => props.output.uri.startsWith(PROCESS_PREFIX));

/** `vance-workspace:/<dir>/<rel>` → `<dir>/<rel>` (the workspace REST path). */
const wsPath = computed<string>(() =>
  props.output.uri.startsWith(WORKSPACE_PREFIX)
    ? props.output.uri.slice(WORKSPACE_PREFIX.length)
    : props.output.uri,
);

const { result, loading, error, load } = useWorkspaceFile();
onMounted(() => {
  if (!isProcess.value) void load(props.projectId, wsPath.value, props.output.path);
});

const url = computed<string>(() => workspaceFileUrl(props.projectId, wsPath.value));
const isPdf = computed<boolean>(() => props.output.path.toLowerCase().endsWith('.pdf'));
const title = computed<string>(() => props.output.title ?? props.output.path);

// Media / plain kinds are handled by the mode branches below; everything else
// that the registry knows (records/tree/chart/list/graph/mindmap/…) renders
// through its inline component with the raw file content as `content`.
const MODE_KINDS = new Set(['image', 'svg', 'markdown', 'text', 'pdf']);
const structuredRenderer = computed(() => {
  const kind = props.output.kind;
  if (!kind || MODE_KINDS.has(kind)) return null;
  return resolveRenderer(kind, 'inline')?.inline ?? null;
});
</script>

<template>
  <ComposeProcessOutput v-if="isProcess" :project-id="projectId" :output="output" />
  <VCard v-else :title="title">
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <p v-else-if="loading" class="text-sm opacity-60">Loading…</p>
    <template v-else-if="result">
      <component
        :is="structuredRenderer"
        v-if="structuredRenderer && result.text != null"
        mode="inline"
        :content="result.text"
        :meta="{}"
      />
      <iframe
        v-else-if="isPdf"
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
