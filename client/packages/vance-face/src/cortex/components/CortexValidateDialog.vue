<script setup lang="ts">
/**
 * Quick + Deep validate for the current script. Replaces the
 * right-panel ValidatePanel from ScriptCortex with a modal so the
 * right panel can stay on Chat / Help.
 *
 * <p>Quick = parse-only syntax check (cheap). Deep = LLM-driven
 * static review (slow, cached server-side via
 * {@code lastDeepReviewedHash}). Both endpoints accept either
 * {@code scriptId} (server loads body) or inline {@code code}; we
 * pass {@code code} so unsaved edits validate too.
 */
import { computed, onMounted, ref } from 'vue';
import { VAlert, VButton, VModal } from '@/components';
import { brainFetch } from '@vance/shared';
import type {
  ScriptValidateResponse,
  ScriptDeepValidateResponse,
  ScriptDeepWarning,
} from '@vance/generated';
import type { CortexDocument } from '../types';

interface Props {
  document: CortexDocument;
}

const props = defineProps<Props>();

const emit = defineEmits<{ (e: 'close'): void }>();

// VModal opens on a false→true transition inside onMounted — see
// specification/web-ui.md §7.7.
const visible = ref(false);
const quickResult = ref<ScriptValidateResponse | null>(null);
const deepResult = ref<ScriptDeepValidateResponse | null>(null);
const quickBusy = ref(false);
const deepBusy = ref(false);
const error = ref<string | null>(null);

const cachedDeepWarnings = computed<ScriptDeepWarning[] | null>(() => {
  const raw = props.document.lastDeepReviewWarningsJson;
  if (!raw) return null;
  try {
    return JSON.parse(raw) as ScriptDeepWarning[];
  } catch {
    return null;
  }
});

const reviewedHashMatches = computed<boolean>(() => {
  return !!props.document.lastDeepReviewedHash && !props.document.dirty;
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
          scriptId: props.document.id,
          code: props.document.inlineText,
          sourceName: props.document.path,
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
          scriptId: props.document.id,
          code: props.document.inlineText,
          sourceName: props.document.path,
        },
      },
    );
    // Server side caches lastDeepReviewedHash; next time the DTO
    // loads it'll come through dtoToDocument. We don't recompute
    // hash client-side.
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Deep validate failed';
  } finally {
    deepBusy.value = false;
  }
}

function close(): void {
  visible.value = false;
  emit('close');
}

onMounted(() => {
  visible.value = true;
});
</script>

<template>
  <VModal
    v-model="visible"
    :title="`Validate · ${document.path}`"
    @update:model-value="(v: boolean) => !v && close()"
  >
    <div class="space-y-3 p-1 text-sm">
      <div class="flex items-center gap-2">
        <VButton size="sm" :loading="quickBusy" @click="runQuick">Quick Validate</VButton>
        <VButton size="sm" variant="secondary" :loading="deepBusy" @click="runDeep">
          Deep Validate (LLM)
        </VButton>
      </div>

      <VAlert v-if="error" variant="error">{{ error }}</VAlert>

      <div v-if="quickResult">
        <div class="font-semibold mb-1">Quick:</div>
        <div v-if="quickResult.ok" class="text-success">✓ no parse errors</div>
        <ul v-else class="list-disc pl-4 text-error">
          <li v-for="(e, i) in (quickResult.errors ?? [])" :key="i">
            <span class="font-mono">[{{ e.line }}:{{ e.column }}]</span> {{ e.message }}
          </li>
        </ul>
      </div>

      <div v-if="deepResult">
        <div class="font-semibold mb-1">Deep Review:</div>
        <div v-if="deepResult.summary" class="italic opacity-70 mb-1">{{ deepResult.summary }}</div>
        <div v-if="(deepResult.warnings ?? []).length === 0" class="text-success">
          ✓ no issues found
        </div>
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

      <div v-if="!deepResult && cachedDeepWarnings" class="opacity-80">
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

    <template #actions>
      <VButton variant="ghost" @click="close">Close</VButton>
    </template>
  </VModal>
</template>
