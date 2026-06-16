<script setup lang="ts">
/**
 * Slart script-generate / update dialog. Modal wrapper around the
 * {@code scripts/generate} endpoint and the polling-driven result.
 * Apply pushes the generated code back to the editor via the
 * {@code apply} event; the shell forwards it as an {@code update}
 * so the autosave pipeline picks it up.
 *
 * <p>Two modes, controlled by the {@code mode} prop:
 * <ul>
 *   <li>{@code CREATE} — blank editor, generate from scratch.</li>
 *   <li>{@code UPDATE} — existing editor body, modify per description.
 *       Optional pre-filled failure reason from the most recent
 *       Hactar FAILED run (provided by the shell).</li>
 * </ul>
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { VAlert, VButton, VModal, VTextarea } from '@/components';
import { brainFetch } from '@vance/shared';
import type {
  ScriptGenerateRequest,
  ScriptGenerateResponse,
  ScriptGenerationResult,
} from '@vance/generated';
import type { CortexDocument } from '../types';

interface Props {
  document: CortexDocument;
  projectId: string;
  /** Chat-session id from the Cortex shell. The server binds the
   *  Slart process to this session so the user can chase it in chat
   *  later; fallback to a placeholder when missing. */
  sessionId?: string | null;
  /** Operation mode — picks the system-prompt branch on the server
   *  and the dialog title here. */
  mode: 'CREATE' | 'UPDATE';
  /** Optional pre-fill for UPDATE mode — the FAILED-run reason from
   *  the Cortex Run panel. Surfaces as a description hint. */
  failureReason?: string | null;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'apply', code: string): void;
}>();

const visible = ref(false);
const prompt = ref(initialPrompt());
const busy = ref(false);
const polling = ref(false);
const error = ref<string | null>(null);
const thinkProcessId = ref<string | null>(null);
const result = ref<ScriptGenerationResult | null>(null);

let pollTimer: number | null = null;

const sessionId = computed<string>(() => props.sessionId ?? '_cortex');

const title = computed<string>(() =>
  props.mode === 'UPDATE'
    ? 'Slart · update this script'
    : 'Slart · generate a new script',
);

const cta = computed<string>(() =>
  props.mode === 'UPDATE' ? 'Update' : 'Generate',
);

function initialPrompt(): string {
  if (props.mode === 'UPDATE' && props.failureReason) {
    return `The previous run failed with:\n\n> ${props.failureReason
      .split('\n')
      .join('\n> ')}\n\nPlease fix this.`;
  }
  return '';
}

async function start(): Promise<void> {
  if (!prompt.value.trim()) {
    error.value = 'Description required';
    return;
  }
  busy.value = true;
  error.value = null;
  result.value = null;

  const body: ScriptGenerateRequest = {
    prompt: prompt.value,
    mode: props.mode,
  };
  if (props.mode === 'UPDATE' && props.document) {
    body.existingScriptId = props.document.id;
  }
  if (props.mode === 'UPDATE' && props.failureReason) {
    body.failureReason = props.failureReason;
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
      const terminal =
        r.status === 'CLOSED'
        || r.status === 'FAILED'
        || r.reason === 'DONE'
        || r.reason === 'FAILED';
      if (terminal) {
        polling.value = false;
        return;
      }
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Poll failed';
      polling.value = false;
      return;
    }
    pollTimer = window.setTimeout(tick, 2500);
  };
  tick();
}

function applyResult(): void {
  if (!result.value?.code) return;
  emit('apply', result.value.code);
  close();
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
  <VModal
    v-model="visible"
    :title="title"
    :close-on-backdrop="false"
    @update:model-value="(v: boolean) => !v && close()"
  >
    <div class="space-y-3 p-1">
      <div v-if="!result || !result.code">
        <label class="label">
          <span class="label-text font-semibold">
            {{
              mode === 'UPDATE'
                ? 'What should change?'
                : 'What should the script do?'
            }}
          </span>
        </label>
        <VTextarea
          v-model="prompt"
          :rows="6"
          :placeholder="
            mode === 'UPDATE'
              ? 'Example: \'Also send a Slack notification when the inbox is empty.\''
              : 'Example: \'Read a number from args.n and return its factorial.\''
          "
        />
        <div v-if="mode === 'UPDATE'" class="text-xs opacity-70 mt-1">
          Slart sees the current script body and preserves its
          structure where possible. The new version lands in a
          fresh Slart bucket — the original document only changes
          if you click <em>Apply to editor</em>.
        </div>
        <div class="flex items-center gap-2 mt-3">
          <VButton variant="primary" :loading="busy" @click="start">
            {{ cta }}
          </VButton>
          <span v-if="polling" class="text-sm opacity-70">Slart is thinking…</span>
          <span v-if="result?.status" class="text-sm font-mono opacity-70">
            {{ result.status }}<span v-if="result.reason"> / {{ result.reason }}</span>
          </span>
        </div>
      </div>

      <VAlert v-if="error" variant="error">{{ error }}</VAlert>

      <div v-if="result?.code">
        <div class="font-semibold mb-1">Generated script:</div>
        <pre
          class="bg-base-200 p-2 rounded text-xs max-h-72 overflow-y-auto whitespace-pre-wrap font-mono"
        >{{ result.code }}</pre>
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
