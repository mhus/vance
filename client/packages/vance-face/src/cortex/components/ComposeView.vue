<script setup lang="ts">
/**
 * Cortex View-tab for `kind: compose` (Damogran) documents — the notebook
 * face of a compose. The Edit tab is the raw YAML CodeEditor (provided by the
 * shell); this View tab runs the compose and renders its outputs below.
 *
 * The run posts the *current* YAML text (the parsed `doc` model, which tracks
 * unsaved edits) as `composeYaml`, so the WIP loop — tweak, run, inspect — does
 * not require a save first. Outputs are transient and workspace-sourced (see
 * {@link ComposeOutput}); persistent results come from the compose's own
 * `export` step.
 */
import { computed, inject, ref, type Ref } from 'vue';
import yaml from 'js-yaml';
import {
  brainFetch,
  readComposeOutputs,
  writeComposeOutputs,
  type ComposeOutputView,
} from '@vance/shared';
import { VAlert, VButton, VCard } from '@/components';
import { useCortexStore } from '@/cortex/stores/cortexStore';
import ComposeOutput from './ComposeOutput.vue';

interface Props {
  /** The compose YAML (the kind-registry parsed model = identity(text)). */
  doc: string;
}
const props = defineProps<Props>();
/** Write the manifest back (with a fresh `$output:` block) → shell auto-saves. */
const emit = defineEmits<{ (e: 'update:doc', doc: string): void }>();
const store = useCortexStore();

/** Non-null project id — Cortex always has one open; '' only pre-selection. */
const projectId = computed<string>(() => store.projectId ?? '');

/**
 * Active cortex session (provided by EditorApp; null when chatless). Passed to
 * the run so it binds to the session's primary chat process — the compose's
 * WorkTarget is then shared with the chat.
 */
const sessionId = inject<Ref<string | null>>('vance:session-id', ref(null));

/**
 * Directory of the open compose document — relative `vance:` import/export
 * paths resolve against it (like the legacy tex-compose behaviour). Empty at
 * project root.
 */
const composeBasePath = computed<string>(() => {
  const path = store.activeTab?.path ?? '';
  const slash = path.lastIndexOf('/');
  return slash > 0 ? path.slice(0, slash) : '';
});

/** Optional title/description from the manifest, shown above the Run button. */
const meta = computed<{ title?: string; description?: string }>(() => {
  try {
    const parsed = yaml.load(props.doc);
    if (parsed && typeof parsed === 'object') {
      const p = parsed as Record<string, unknown>;
      return {
        title: typeof p.title === 'string' ? p.title : undefined,
        description: typeof p.description === 'string' ? p.description : undefined,
      };
    }
  } catch {
    // Invalid YAML mid-edit — just show no meta.
  }
  return {};
});

interface OutputArtifact {
  path: string;
  uri: string;
  kind?: string;
  mime?: string;
  title?: string;
}
interface TaskResult {
  status: string;
  outputs?: OutputArtifact[];
  error?: string;
  log?: string;
}
interface ComposeRunResponse {
  success: boolean;
  workspace: string;
  error?: string;
  tasks: TaskResult[];
}

const running = ref(false);
const fetchError = ref<string | null>(null);
const result = ref<ComposeRunResponse | null>(null);

/**
 * Outputs a prior run recorded in the manifest's `$output:` block — so
 * produced files survive a page refresh (the in-memory `result` does not).
 * Shown only when there is no fresh run `result` to display.
 */
const persisted = computed<ComposeOutputView[]>(() => readComposeOutputs(props.doc));

function hasOutputs(r: ComposeRunResponse): boolean {
  return r.tasks.some((t) => (t.outputs?.length ?? 0) > 0);
}

/** Flatten a run's per-task outputs into the persistable artifact list. */
function runOutputs(r: ComposeRunResponse): ComposeOutputView[] {
  return r.tasks.flatMap((t) =>
    (t.outputs ?? []).map((o) => ({ path: o.path, uri: o.uri, kind: o.kind, title: o.title })),
  );
}

async function run(): Promise<void> {
  running.value = true;
  fetchError.value = null;
  result.value = null;
  try {
    const response = await brainFetch<ComposeRunResponse>('POST', 'compose/run', {
      body: {
        projectId: projectId.value,
        composeYaml: props.doc,
        composeBasePath: composeBasePath.value,
        sessionId: sessionId.value,
      },
    });
    result.value = response;
    // Record the produced artifacts into the manifest so a refresh re-shows
    // them (only on success — a failure keeps the last good `$output`).
    if (response.success) {
      emit('update:doc', writeComposeOutputs(props.doc, runOutputs(response)));
    }
  } catch (e) {
    fetchError.value = e instanceof Error ? e.message : 'Compose run failed.';
  } finally {
    running.value = false;
  }
}
</script>

<template>
  <div class="flex flex-col gap-3 p-4">
    <div v-if="meta.title || meta.description" class="flex flex-col gap-1">
      <h3 v-if="meta.title" class="text-base font-semibold">{{ meta.title }}</h3>
      <p v-if="meta.description" class="text-sm opacity-70 whitespace-pre-line">{{ meta.description }}</p>
    </div>
    <div class="flex items-center gap-3">
      <VButton variant="primary" size="sm" :loading="running" @click="run">
        ▶ Run compose
      </VButton>
      <span v-if="result" class="text-sm opacity-70">
        Workspace: {{ result.workspace }} · {{ result.success ? 'success' : 'failed' }}
      </span>
    </div>

    <VAlert v-if="fetchError" variant="error">{{ fetchError }}</VAlert>
    <VAlert v-else-if="result && !result.success" variant="error">
      {{ result.error ?? 'Compose failed.' }}
    </VAlert>

    <template v-if="result">
      <div v-for="(task, ti) in result.tasks" :key="ti" class="flex flex-col gap-2">
        <VAlert v-if="task.status !== 'success' && task.error" variant="error">
          Task {{ ti + 1 }}: {{ task.error }}
        </VAlert>
        <ComposeOutput
          v-for="(out, oi) in task.outputs ?? []"
          :key="oi"
          :project-id="projectId"
          :output="out"
        />
        <VCard v-if="task.log && (task.outputs?.length ?? 0) === 0">
          <pre class="text-xs whitespace-pre-wrap overflow-auto">{{ task.log }}</pre>
        </VCard>
      </div>
      <p v-if="result.success && !hasOutputs(result)" class="text-sm opacity-60">
        Run finished — no declared outputs.
      </p>
    </template>
    <template v-else-if="persisted.length">
      <ComposeOutput
        v-for="(out, oi) in persisted"
        :key="oi"
        :project-id="projectId"
        :output="out"
      />
    </template>
    <p v-else-if="!running && !fetchError" class="text-sm opacity-60">
      Press “Run compose” to provision the workspace and run the tasks.
    </p>
  </div>
</template>
