<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue';
import { VAlert, VButton, VModal, VTextarea } from '@/components';
import { brainFetch, BrainWebSocket, getTenantId } from '@vance/shared';
import type {
  ScriptExecuteResponse,
  ScriptExecutionEventData,
  ScriptExecutionStatus,
} from '@vance/generated';
import type { ScriptFile } from '../types';

interface Props {
  file: ScriptFile;
  projectId: string;
}

const props = defineProps<Props>();

const emit = defineEmits<{ (e: 'close'): void }>();

// Initial false so VModal's watch fires on the false→true transition
// inside onMounted — otherwise <dialog>.showModal() never runs and
// nothing appears on screen. See specification/web-ui.md §7.7.
const visible = ref(false);
const argsJson = ref('{}');
const argsError = ref<string | null>(null);
const state = ref<'idle' | 'starting' | 'running' | 'finished' | 'failed' | 'cancelled'>('idle');
const executionId = ref<string | null>(null);
const logLines = ref<string[]>([]);
const resultValue = ref<unknown>(null);
const errorMessage = ref<string | null>(null);
const durationMs = ref<number | null>(null);

let ws: BrainWebSocket | null = null;
let wsSubscribed = false;
let pollTimer: number | null = null;

async function start(): Promise<void> {
  let parsed: Record<string, unknown> = {};
  argsError.value = null;
  if (argsJson.value.trim()) {
    try {
      parsed = JSON.parse(argsJson.value);
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('args must be a JSON object');
      }
    } catch (e) {
      argsError.value = e instanceof Error ? e.message : 'Invalid JSON';
      return;
    }
  }

  state.value = 'starting';
  logLines.value = [];
  resultValue.value = null;
  errorMessage.value = null;
  durationMs.value = null;
  wsSubscribed = false;

  let resp: ScriptExecuteResponse;
  try {
    resp = await brainFetch<ScriptExecuteResponse>(
      'POST',
      `scripts/execute?projectId=${encodeURIComponent(props.projectId)}`,
      {
        body: {
          scriptId: props.file.id,
          args: parsed,
          sourceName: props.file.path,
        },
      },
    );
  } catch (e) {
    state.value = 'failed';
    errorMessage.value = e instanceof Error ? e.message : 'Execute failed';
    return;
  }

  executionId.value = resp.executionId;
  state.value = 'running';

  // Best-effort WS subscribe for live streaming. If it fails for any
  // reason — no session, network blip, server rejection — fall back
  // to polling. The execution itself already runs server-side; we
  // just need a way to surface its state to the user.
  try {
    if (!ws) {
      ws = await BrainWebSocket.connect({
        tenant: getTenantId() ?? '',
        profile: 'web',
        clientVersion: '0.1.0',
      });
      bindWs(ws);
    }
    await ws.send('script-execution-subscribe', { executionId: resp.executionId });
    wsSubscribed = true;
  } catch (e) {
    console.warn('[script-cortex] WS subscribe failed, falling back to polling:', e);
    wsSubscribed = false;
  }

  // Polling backstop: covers the WS-failed case AND the race where
  // the worker finishes before our subscribe lands.
  startPolling();
}

function bindWs(w: BrainWebSocket): void {
  w.on<ScriptExecutionEventData>('script-execution-started', (d) => {
    if (d.executionId !== executionId.value) return;
    state.value = 'running';
  });
  w.on<ScriptExecutionEventData>('script-execution-log', (d) => {
    if (d.executionId !== executionId.value) return;
    logLines.value.push(`[${d.stream}] ${d.logLine ?? ''}`);
    if (logLines.value.length > 5000) {
      logLines.value = logLines.value.slice(-4000);
    }
  });
  w.on<ScriptExecutionEventData>('script-execution-finished', (d) => {
    if (d.executionId !== executionId.value) return;
    state.value = 'finished';
    resultValue.value = d.resultValue ?? null;
    durationMs.value = d.durationMs ?? null;
    stopPolling();
  });
  w.on<ScriptExecutionEventData>('script-execution-failed', (d) => {
    if (d.executionId !== executionId.value) return;
    state.value = 'failed';
    errorMessage.value = d.errorMessage ?? 'Execution failed';
    durationMs.value = d.durationMs ?? null;
    stopPolling();
  });
  w.on<ScriptExecutionEventData>('script-execution-cancelled', (d) => {
    if (d.executionId !== executionId.value) return;
    state.value = 'cancelled';
    durationMs.value = d.durationMs ?? null;
    stopPolling();
  });
}

function startPolling(): void {
  stopPolling();
  const tick = async (): Promise<void> => {
    if (!executionId.value) return;
    try {
      const snap = await brainFetch<ScriptExecutionStatus>(
        'GET',
        `scripts/executions/${executionId.value}`,
      );
      // Merge the snapshot. WS events take precedence when they
      // arrive, but if WS is dead the snapshot drives the UI.
      const snapState = snap.state;
      if (snapState !== 'running') {
        state.value = snapState as typeof state.value;
        resultValue.value = snap.resultValue ?? null;
        errorMessage.value = snap.errorMessage ?? null;
        durationMs.value = snap.durationMs ?? null;
      }
      // Log buffer — only patch when WS isn't streaming, otherwise
      // we'd duplicate lines.
      if (!wsSubscribed && snap.logBuffer && snap.logBuffer.length > 0) {
        logLines.value = snap.logBuffer.map((l) => `[buffered] ${l}`);
      }
      if (snapState !== 'running') {
        stopPolling();
        return;
      }
    } catch (e) {
      // 404 = the execution evicted (5min retention). Stop polling.
      console.warn('[script-cortex] poll error:', e);
      stopPolling();
      return;
    }
    pollTimer = window.setTimeout(tick, 1_500);
  };
  pollTimer = window.setTimeout(tick, 800);
}

function stopPolling(): void {
  if (pollTimer != null) {
    window.clearTimeout(pollTimer);
    pollTimer = null;
  }
}

async function cancel(): Promise<void> {
  if (!executionId.value) return;
  try {
    await brainFetch<void>(
      'POST',
      `scripts/executions/${executionId.value}/cancel`,
    );
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : 'Cancel failed';
  }
}

function close(): void {
  visible.value = false;
  emit('close');
}

onBeforeUnmount(() => {
  stopPolling();
  if (ws) {
    ws.close();
    ws = null;
  }
});

onMounted(() => {
  visible.value = true;
});

function fmtResult(v: unknown): string {
  if (v === null || v === undefined) return '(no return value)';
  if (typeof v === 'string') return v;
  try { return JSON.stringify(v, null, 2); } catch { return String(v); }
}
</script>

<template>
  <VModal v-model="visible" :title="`Execute · ${file.path}`" :close-on-backdrop="false" @update:model-value="(v: boolean) => !v && close()">
    <div class="space-y-3 p-1">
      <div>
        <label class="label">
          <span class="label-text font-semibold">args (JSON object)</span>
        </label>
        <VTextarea
          v-model="argsJson"
          :rows="3"
          placeholder='{ "n": 7 }'
          :disabled="state === 'running' || state === 'starting'"
        />
        <VAlert v-if="argsError" variant="error" class="mt-1">{{ argsError }}</VAlert>
      </div>

      <div class="flex items-center gap-2">
        <VButton
          v-if="state === 'idle' || state === 'finished' || state === 'failed' || state === 'cancelled'"
          variant="primary"
          @click="start"
        >Run</VButton>
        <VButton
          v-if="state === 'running' || state === 'starting'"
          variant="danger"
          @click="cancel"
        >Cancel</VButton>
        <span class="text-sm opacity-70 font-mono">
          state: {{ state }}
          <span v-if="durationMs !== null"> · {{ durationMs }}ms</span>
          <span v-if="executionId" class="opacity-50"> · {{ executionId.substring(0, 8) }}</span>
        </span>
      </div>

      <div>
        <div class="label">
          <span class="label-text font-semibold">Output</span>
        </div>
        <pre class="font-mono text-xs bg-base-200 p-2 rounded max-h-64 overflow-y-auto whitespace-pre-wrap">{{ logLines.length === 0 ? '(empty)' : logLines.join('\n') }}</pre>
      </div>

      <div v-if="state === 'finished'">
        <div class="label">
          <span class="label-text font-semibold text-success">Result</span>
        </div>
        <pre class="font-mono text-xs bg-base-200 p-2 rounded whitespace-pre-wrap">{{ fmtResult(resultValue) }}</pre>
      </div>

      <VAlert v-if="errorMessage" variant="error">{{ errorMessage }}</VAlert>
    </div>

    <template #actions>
      <VButton variant="ghost" @click="close">Close</VButton>
    </template>
  </VModal>
</template>
