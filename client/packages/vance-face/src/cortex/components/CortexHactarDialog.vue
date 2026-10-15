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
import { useI18n } from 'vue-i18n';
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

const { t } = useI18n();

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
    ? t('cortex.hactar.titleUpdate')
    : t('cortex.hactar.titleGenerate'),
);

const cta = computed<string>(() =>
  props.mode === 'UPDATE' ? t('cortex.hactar.update') : t('cortex.hactar.generate'),
);

// The update hint only applies while the prompt is shown — once a script
// came back, the dialog is about applying it.
const description = computed<string | undefined>(() =>
  props.mode === 'UPDATE' && !result.value?.code
    ? t('cortex.hactar.updateDescription', { apply: t('cortex.hactar.applyToEditor') })
    : undefined,
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
    error.value = t('cortex.descriptionRequired');
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
    :description="description"
    :close-on-backdrop="false"
    @update:model-value="(v: boolean) => !v && close()"
  >
    <div class="flex flex-col gap-4">
      <template v-if="!result || !result.code">
        <VTextarea
          v-model="prompt"
          :rows="6"
          :label="mode === 'UPDATE'
            ? $t('cortex.hactar.promptLabelUpdate')
            : $t('cortex.hactar.promptLabelGenerate')"
          :placeholder="mode === 'UPDATE'
            ? $t('cortex.hactar.promptPlaceholderUpdate')
            : $t('cortex.hactar.promptPlaceholderGenerate')"
        />
        <div
          v-if="polling || result?.status"
          class="flex items-center gap-2 text-sm text-(--v-label-secondary)"
        >
          <span v-if="polling">{{ $t('cortex.hactar.thinking') }}</span>
          <span v-if="result?.status" class="font-mono">
            {{ result.status }}<span v-if="result.reason"> / {{ result.reason }}</span>
          </span>
        </div>
      </template>

      <VAlert v-if="error" variant="error">{{ error }}</VAlert>

      <div v-if="result?.code" class="flex flex-col gap-1">
        <span class="text-xs font-semibold text-(--v-label-secondary)">
          {{ $t('cortex.hactar.generatedScript') }}
        </span>
        <pre
          class="bg-(--v-fill) p-2 rounded-(--v-radius-control) text-xs max-h-72 overflow-y-auto whitespace-pre-wrap font-mono"
        >{{ result.code }}</pre>
      </div>

      <div v-if="result?.failureReason" class="text-sm text-(--v-danger)">
        <span class="font-semibold">{{ $t('cortex.hactar.failed') }}</span> {{ result.failureReason }}
      </div>
    </div>

    <template #actions>
      <VButton variant="secondary" @click="close">{{ $t('cortex.hactar.close') }}</VButton>
      <template v-if="result?.code">
        <VButton variant="secondary" @click="result = null">{{ $t('cortex.hactar.regenerate') }}</VButton>
        <VButton variant="primary" @click="applyResult">{{ $t('cortex.hactar.applyToEditor') }}</VButton>
      </template>
      <VButton v-else variant="primary" :loading="busy" @click="start">
        {{ cta }}
      </VButton>
    </template>
  </VModal>
</template>
