<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, computed } from 'vue';
import { VAlert, VButton, VModal, VTextarea } from '@/components';
import { brainFetch } from '@vance/shared';
import type {
  ScriptGenerateRequest,
  ScriptGenerateResponse,
  ScriptGenerationResult,
} from '@vance/generated';
import type { ScriptFile } from '../types';

interface Props {
  file?: ScriptFile | null;
  projectId: string;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'apply', code: string): void;
}>();

// Initial false so VModal's watch fires on the false→true transition
// inside onMounted — otherwise <dialog>.showModal() never runs and
// nothing appears on screen.
const visible = ref(false);
const prompt = ref('');
const includeExisting = ref(true);
const busy = ref(false);
const polling = ref(false);
const error = ref<string | null>(null);
const thinkProcessId = ref<string | null>(null);
const result = ref<ScriptGenerationResult | null>(null);

let pollTimer: number | null = null;

const sessionId = computed<string>(() => {
  // Web-UI client has a session at WS-level; for the REST-driven
  // generation we use a transient session bound to this tab if
  // available, otherwise fall back to "_cortex".
  try {
    const data = sessionStorage.getItem('vance.sessionId');
    if (data) return data;
  } catch {
    // ignore
  }
  return '_cortex';
});

async function start(): Promise<void> {
  if (!prompt.value.trim()) {
    error.value = 'Prompt required';
    return;
  }
  busy.value = true;
  error.value = null;
  result.value = null;

  const body: ScriptGenerateRequest = {
    prompt: prompt.value,
  };
  if (includeExisting.value && props.file) {
    body.existingScriptId = props.file.id;
  }

  try {
    const resp = await brainFetch<ScriptGenerateResponse>(
      'POST',
      `scripts/generate?projectId=${encodeURIComponent(props.projectId)}&sessionId=${encodeURIComponent(sessionId.value)}`,
      { body },
    );
    thinkProcessId.value = resp.thinkProcessId;
    pollUntilDone();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Generation failed to start';
  } finally {
    busy.value = false;
  }
}

function pollUntilDone(): void {
  polling.value = true;
  const tick = async (): Promise<void> => {
    if (!thinkProcessId.value) {
      polling.value = false;
      return;
    }
    try {
      const r = await brainFetch<ScriptGenerationResult>(
        'GET',
        `scripts/generations/${thinkProcessId.value}/result`,
      );
      result.value = r;
      const terminal = r.status === 'CLOSED' || r.status === 'FAILED' || r.reason === 'DONE' || r.reason === 'FAILED';
      if (terminal) {
        polling.value = false;
        return;
      }
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Poll failed';
      polling.value = false;
      return;
    }
    pollTimer = window.setTimeout(tick, 2_500);
  };
  tick();
}

function applyResult(): void {
  if (!result.value?.code) return;
  emit('apply', result.value.code);
}

function close(): void {
  visible.value = false;
  if (pollTimer != null) {
    window.clearTimeout(pollTimer);
    pollTimer = null;
  }
  emit('close');
}

onMounted(() => {
  visible.value = true;
});

onBeforeUnmount(() => {
  if (pollTimer != null) {
    window.clearTimeout(pollTimer);
    pollTimer = null;
  }
});
</script>

<template>
  <VModal v-model="visible" title="DeepThought · generate / improve script" :close-on-backdrop="false" @update:model-value="(v: boolean) => !v && close()">
    <div class="space-y-3 p-1">
      <div v-if="!result || !result.code">
        <label class="label">
          <span class="label-text font-semibold">Prompt</span>
        </label>
        <VTextarea
          v-model="prompt"
          :rows="6"
          placeholder="Describe what the script should do. Example: 'Read a number from args.n and return its factorial via console.log.'"
        />

        <label v-if="file" class="flex items-center gap-2 mt-2 text-sm">
          <input
            v-model="includeExisting"
            type="checkbox"
            class="checkbox checkbox-sm"
          />
          <span>Include current script as context (improve mode)</span>
        </label>

        <div class="flex items-center gap-2 mt-3">
          <VButton variant="primary" :loading="busy" @click="start">Generate</VButton>
          <span v-if="polling" class="text-sm opacity-70">Deep Thought is thinking…</span>
          <span v-if="result?.status" class="text-sm font-mono opacity-70">
            {{ result.status }}<span v-if="result.reason"> / {{ result.reason }}</span>
          </span>
        </div>
      </div>

      <VAlert v-if="error" variant="error">{{ error }}</VAlert>

      <div v-if="result?.planSketch && !result.code" class="text-sm">
        <div class="font-semibold mb-1">Plan sketch:</div>
        <pre class="bg-base-200 p-2 rounded text-xs whitespace-pre-wrap">{{ result.planSketch }}</pre>
      </div>

      <div v-if="result?.code">
        <div class="font-semibold mb-1">Generated script:</div>
        <pre class="bg-base-200 p-2 rounded text-xs max-h-72 overflow-y-auto whitespace-pre-wrap font-mono">{{ result.code }}</pre>
        <div v-if="result.reviewerNotes" class="mt-2 text-xs opacity-70">
          <div class="font-semibold">Reviewer notes:</div>
          <pre class="whitespace-pre-wrap">{{ result.reviewerNotes }}</pre>
        </div>
        <div class="flex gap-2 mt-3">
          <VButton variant="primary" @click="applyResult">Apply to editor</VButton>
          <VButton variant="ghost" @click="result = null">Re-generate</VButton>
        </div>
      </div>

      <div v-if="result?.failureReason" class="text-sm text-error">
        <span class="font-semibold">FAILED:</span> {{ result.failureReason }}
      </div>
    </div>

    <template #actions>
      <VButton variant="ghost" @click="close">Close</VButton>
    </template>
  </VModal>
</template>
