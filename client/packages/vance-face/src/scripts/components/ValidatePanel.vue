<script setup lang="ts">
import { ref, computed } from 'vue';
import { VAlert, VButton } from '@/components';
import { brainFetch } from '@vance/shared';
import type {
  ScriptValidateResponse,
  ScriptDeepValidateResponse,
  ScriptDeepWarning,
} from '@vance/generated';
import type { ScriptFile } from '../types';

interface Props {
  file: ScriptFile;
}

const props = defineProps<Props>();

const quickResult = ref<ScriptValidateResponse | null>(null);
const deepResult = ref<ScriptDeepValidateResponse | null>(null);
const quickBusy = ref(false);
const deepBusy = ref(false);
const error = ref<string | null>(null);

const cachedDeepWarnings = computed<ScriptDeepWarning[] | null>(() => {
  if (!props.file.lastDeepReviewWarningsJson) return null;
  try {
    return JSON.parse(props.file.lastDeepReviewWarningsJson) as ScriptDeepWarning[];
  } catch {
    return null;
  }
});

const reviewedHashMatches = computed<boolean>(() => {
  return !!props.file.lastDeepReviewedHash && !props.file.dirty;
});

async function runQuick(): Promise<void> {
  quickBusy.value = true;
  error.value = null;
  try {
    quickResult.value = await brainFetch<ScriptValidateResponse>(
      'POST',
      'scripts/validate',
      {
        body: {
          scriptId: props.file.id,
          code: props.file.inlineText,
          sourceName: props.file.path,
        },
      },
    );
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Validate failed';
  } finally {
    quickBusy.value = false;
  }
}

async function runDeep(): Promise<void> {
  deepBusy.value = true;
  error.value = null;
  try {
    deepResult.value = await brainFetch<ScriptDeepValidateResponse>(
      'POST',
      'scripts/validate-deep',
      {
        body: {
          scriptId: props.file.id,
          code: props.file.inlineText,
          sourceName: props.file.path,
        },
      },
    );
    // Also mirror onto the file so the badge above the editor flips
    // to "still reviewed" until the next edit.
    // We don't compute the hash client-side — server already cached
    // it; on next file-load it comes back through DocumentDto.
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Deep validate failed';
  } finally {
    deepBusy.value = false;
  }
}

defineExpose({ runQuick, runDeep });
</script>

<template>
  <div class="p-3 text-sm space-y-3">
    <div class="flex items-center gap-2">
      <VButton size="sm" :loading="quickBusy" @click="runQuick">Quick Validate</VButton>
      <VButton size="sm" variant="secondary" :loading="deepBusy" @click="runDeep">Deep Validate (LLM)</VButton>
    </div>

    <VAlert v-if="error" variant="error">{{ error }}</VAlert>

    <div v-if="quickResult" class="text-sm">
      <div class="font-semibold mb-1">Quick:</div>
      <div v-if="quickResult.ok" class="text-success">✓ no parse errors</div>
      <ul v-else class="list-disc pl-4 text-error">
        <li v-for="(e, i) in (quickResult.errors ?? [])" :key="i">
          <span class="font-mono">[{{ e.line }}:{{ e.column }}]</span> {{ e.message }}
        </li>
      </ul>
    </div>

    <div v-if="deepResult" class="text-sm">
      <div class="font-semibold mb-1">Deep Review:</div>
      <div v-if="deepResult.summary" class="italic opacity-70 mb-1">{{ deepResult.summary }}</div>
      <div v-if="(deepResult.warnings ?? []).length === 0" class="text-success">✓ no issues found</div>
      <ul v-else class="space-y-1">
        <li
          v-for="(w, i) in (deepResult.warnings ?? [])"
          :key="i"
          :class="[
            'border-l-2 pl-2',
            w.severity === 'error' ? 'border-error' :
              w.severity === 'warn' ? 'border-warning' : 'border-info',
          ]"
        >
          <div class="text-xs font-mono opacity-60">{{ w.category }} · L{{ w.line }}</div>
          <div>{{ w.message }}</div>
        </li>
      </ul>
    </div>

    <div v-if="!deepResult && cachedDeepWarnings" class="text-sm opacity-80">
      <div class="font-semibold mb-1">
        Cached Deep Review
        <span v-if="reviewedHashMatches" class="text-success text-xs">(matches current)</span>
        <span v-else class="text-warning text-xs">(content has changed since)</span>
      </div>
      <ul v-if="cachedDeepWarnings.length > 0" class="space-y-1 opacity-80">
        <li
          v-for="(w, i) in cachedDeepWarnings"
          :key="i"
          :class="[
            'border-l-2 pl-2',
            w.severity === 'error' ? 'border-error' :
              w.severity === 'warn' ? 'border-warning' : 'border-info',
          ]"
        >
          <div class="text-xs font-mono opacity-60">{{ w.category }} · L{{ w.line }}</div>
          <div>{{ w.message }}</div>
        </li>
      </ul>
      <div v-else class="text-success text-sm">✓ no issues flagged</div>
    </div>
  </div>
</template>
