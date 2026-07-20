<script setup lang="ts">
/**
 * Cortex View-tab for `kind: compose` (Damogran) documents. Runs the compose
 * (async on the server) and renders progress + outputs.
 *
 * Runs are async: a quick compose returns its result inline; a longer one
 * returns a `runId` we poll (status + live tail) and park in a `$run:` block so
 * a page refresh resumes polling. On completion the produced artifacts land in
 * the `$output:` block (survive refresh); content is workspace-sourced (see
 * {@link ComposeOutput}), persistent results come from the compose's `export`.
 */
import { computed, inject, onMounted, onUnmounted, ref, type Ref } from 'vue';
import yaml from 'js-yaml';
import {
  postComposeRun,
  pollComposeRun,
  cancelComposeRun,
  readComposeOutputs,
  readFixedOutputs,
  writeComposeOutputs,
  writeComposeRun,
  readComposeRun,
  clearComposeManaged,
  type ComposeOutputView,
  type ComposeRunResponse,
} from '@vance/shared';
import { VAlert, VButton, VCard } from '@/components';
import { useCortexStore } from '@/cortex/stores/cortexStore';
import ComposeOutput from './ComposeOutput.vue';

interface Props {
  /** The compose YAML (the kind-registry parsed model = identity(text)). */
  doc: string;
}
const props = defineProps<Props>();
/** Write the manifest back (`$run:` / `$output:` block) → shell auto-saves. */
const emit = defineEmits<{ (e: 'update:doc', doc: string): void }>();
const store = useCortexStore();

const POLL_MS = 3000;

/** Non-null project id — Cortex always has one open; '' only pre-selection. */
const projectId = computed<string>(() => store.projectId ?? '');

/** Active cortex session (provided by EditorApp; null when chatless). */
const sessionId = inject<Ref<string | null>>('vance:session-id', ref(null));

/** Directory of the open compose document — relative `vance:` paths resolve against it. */
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

const running = ref(false);
const fetchError = ref<string | null>(null);
const result = ref<ComposeRunResponse | null>(null);
const progress = ref<ComposeRunResponse | null>(null);
/** Server runId of the in-flight run — set in phase 2 (polling); enables Stop. */
const runId = ref<string | null>(null);
const cancelling = ref(false);

/**
 * Run button states: ▶ idle → run; "…" busy while the start REST is in flight
 * (phase 1, no runId — nothing to cancel); ■ stop once a runId is known
 * (phase 2, polling) → server-side cancel.
 */
const canStop = computed(() => running.value && runId.value != null);
const runGlyph = computed(() => (!running.value ? '▶ Run compose' : runId.value ? '■ Stop' : '…'));

let polling = false;
let timer: ReturnType<typeof setTimeout> | undefined;

/** Outputs a prior run recorded in `$output:` — shown when no fresh result. */
const persisted = computed<ComposeOutputView[]>(() => readComposeOutputs(props.doc));
/** User-pinned `output:` override — wins over run/`$output` outputs. */
const fixedOutputs = computed<ComposeOutputView[]>(() => readFixedOutputs(props.doc));

function hasOutputs(r: ComposeRunResponse): boolean {
  return (r.tasks ?? []).some((t) => (t.outputs?.length ?? 0) > 0);
}

function runOutputs(r: ComposeRunResponse): ComposeOutputView[] {
  return (r.tasks ?? []).flatMap((t) =>
    (t.outputs ?? []).map((o) => ({ path: o.path, uri: o.uri, kind: o.kind, title: o.title })),
  );
}

function stopTimer(): void {
  polling = false;
  if (timer) clearTimeout(timer);
  timer = undefined;
}

/** A run reached a terminal state — render it, persist $output, clear polling. */
function finishWith(resp: ComposeRunResponse): void {
  stopTimer();
  running.value = false;
  cancelling.value = false;
  runId.value = null;
  progress.value = null;
  result.value = resp;
  // A user-pinned `output:` wins → don't write $output (just drop $run).
  const pinned = readFixedOutputs(props.doc).length > 0;
  if (resp.success && !pinned) {
    emit('update:doc', writeComposeOutputs(props.doc, runOutputs(resp)));
  } else {
    // Failure or pinned output: clear the parked $run marker (no $output to keep).
    emit('update:doc', clearComposeManaged(props.doc));
  }
}

function startPolling(runId: string): void {
  polling = true;
  const tick = async (): Promise<void> => {
    if (!polling) return;
    try {
      const resp = await pollComposeRun(projectId.value, runId);
      if (resp.running) {
        progress.value = resp;
        timer = setTimeout(tick, POLL_MS);
      } else {
        finishWith(resp);
      }
    } catch {
      // Run gone (pod restart?) or transient error — stop, surface, drop $run.
      stopTimer();
      running.value = false;
      progress.value = null;
      fetchError.value = 'Lauf nicht mehr verfügbar (Pod-Neustart?) — bitte neu ausführen.';
      emit('update:doc', clearComposeManaged(props.doc));
    }
  };
  timer = setTimeout(tick, POLL_MS);
}

async function run(): Promise<void> {
  stopTimer();
  running.value = true;
  fetchError.value = null;
  result.value = null;
  progress.value = null;
  try {
    const resp = await postComposeRun(projectId.value, {
      composeYaml: props.doc,
      composeBasePath: composeBasePath.value,
      sessionId: sessionId.value,
    });
    if (resp.running && resp.runId) {
      // Long run: park the runId so a refresh resumes, then poll.
      runId.value = resp.runId;
      progress.value = resp;
      emit('update:doc', writeComposeRun(props.doc, { id: resp.runId, startedAt: new Date().toISOString() }));
      startPolling(resp.runId);
    } else {
      finishWith(resp);
    }
  } catch (e) {
    running.value = false;
    fetchError.value = e instanceof Error ? e.message : 'Compose run failed.';
  }
}

/** ■ Stop (phase 2): cancel the in-flight run on the server. */
async function stop(): Promise<void> {
  const rid = runId.value;
  if (!rid) return;
  cancelling.value = true;
  try {
    const resp = await cancelComposeRun(projectId.value, rid);
    if (!resp.running) finishWith(resp); // else the poll loop observes it shortly
  } catch {
    // Best-effort — the poll loop still winds things down.
  } finally {
    cancelling.value = false;
  }
}

/** Drop the shown outputs (managed `$output:`/`$run:` block) and reset state. */
function clearOutput(): void {
  stopTimer();
  running.value = false;
  progress.value = null;
  result.value = null;
  fetchError.value = null;
  runId.value = null;
  emit('update:doc', clearComposeManaged(props.doc));
}

function onRunButton(): void {
  if (!running.value) run();
  else if (canStop.value) stop();
}

onMounted(() => {
  // Resume a run that was in flight before a refresh.
  const marker = readComposeRun(props.doc);
  if (marker) {
    running.value = true;
    runId.value = marker.id;
    startPolling(marker.id);
  }
});
onUnmounted(stopTimer);
</script>

<template>
  <div class="flex flex-col gap-3 p-4">
    <div v-if="meta.title || meta.description" class="flex flex-col gap-1">
      <h3 v-if="meta.title" class="text-base font-semibold">{{ meta.title }}</h3>
      <p v-if="meta.description" class="text-sm opacity-70 whitespace-pre-line">{{ meta.description }}</p>
    </div>
    <div class="flex items-center gap-2">
      <VButton
        :variant="canStop ? 'danger' : 'primary'"
        size="sm"
        :disabled="cancelling || (running && !canStop)"
        @click="onRunButton"
      >{{ runGlyph }}</VButton>
      <VButton variant="ghost" size="sm" :disabled="running" @click="clearOutput">
        Clear Output
      </VButton>
      <span v-if="cancelling" class="text-sm opacity-70">stoppe…</span>
      <span v-else-if="result" class="text-sm opacity-70">
        Workspace: {{ result.workspace }} · {{ result.success ? 'success' : 'failed' }}
      </span>
      <span v-else-if="progress" class="text-sm opacity-70">
        Läuft… Task {{ (progress.currentTaskIndex ?? 0) + 1 }}{{ progress.currentTaskType ? ` (${progress.currentTaskType})` : '' }}
      </span>
    </div>

    <VAlert v-if="fetchError" variant="error">{{ fetchError }}</VAlert>
    <VAlert v-else-if="result && !result.success" variant="error">
      {{ result.error ?? 'Compose failed.' }}
    </VAlert>

    <!-- Live progress (long run): tail of the current exec -->
    <VCard v-if="progress">
      <pre class="text-xs whitespace-pre-wrap overflow-auto max-h-64">{{ progress.tail && progress.tail.length ? progress.tail.join('\n') : '… läuft, warte auf Ausgabe' }}</pre>
    </VCard>

    <!-- Fixed `output:` override wins over run/persisted outputs. -->
    <template v-if="fixedOutputs.length">
      <ComposeOutput
        v-for="(out, oi) in fixedOutputs"
        :key="oi"
        :project-id="projectId"
        :output="out"
      />
    </template>

    <template v-else-if="result">
      <div v-for="(task, ti) in result.tasks ?? []" :key="ti" class="flex flex-col gap-2">
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
    <template v-else-if="!progress && persisted.length">
      <ComposeOutput
        v-for="(out, oi) in persisted"
        :key="oi"
        :project-id="projectId"
        :output="out"
      />
    </template>
    <p v-else-if="!running && !progress && !fetchError" class="text-sm opacity-60">
      Press “Run compose” to provision the workspace and run the tasks.
    </p>
  </div>
</template>
