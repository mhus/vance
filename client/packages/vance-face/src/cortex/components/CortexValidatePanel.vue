<script setup lang="ts">
/**
 * Inline results panel for Quick / Deep validate. Sits below the
 * editor next to the run-log panel and shares its shape — the two
 * validate runs are toolbar actions now, so their output belongs in
 * the tab, not behind a modal the user has to dismiss before touching
 * the code the warnings point at.
 *
 * <p>Purely presentational: fetching lives in
 * {@link useScriptValidation}.
 */
import { computed } from 'vue';
import type { ScriptDeepWarning } from '@vance/generated';
import type { CortexDocument } from '../types';
import type { ScriptValidation } from '../composables/useScriptValidation';

interface Props {
  document: CortexDocument;
  validation: ScriptValidation;
}

const props = defineProps<Props>();

const cachedDeepWarnings = computed<ScriptDeepWarning[] | null>(() => {
  const raw = props.document.lastDeepReviewWarningsJson;
  if (!raw) return null;
  try {
    return JSON.parse(raw) as ScriptDeepWarning[];
  } catch {
    return null;
  }
});

const reviewedHashMatches = computed<boolean>(
  () => !!props.document.lastDeepReviewedHash && !props.document.dirty,
);

function severityClass(severity: string | null | undefined): string {
  if (severity === 'error') return 'border-error';
  if (severity === 'warn') return 'border-warning';
  return 'border-info';
}
</script>

<template>
  <div
    class="flex-none border-t border-base-300 flex flex-col overflow-hidden"
    style="max-height: 45%; min-height: 6rem;"
  >
    <div class="flex items-center gap-2 px-3 py-1 bg-base-200 text-xs font-mono border-b border-base-300">
      <span class="uppercase tracking-wide opacity-70">{{ $t('cortex.validate.title') }}</span>
      <span v-if="validation.quickBusy.value" class="text-info">{{ $t('cortex.validate.quickRunning') }}</span>
      <span v-if="validation.deepBusy.value" class="text-info">{{ $t('cortex.validate.deepRunning') }}</span>
      <span class="flex-1" />
      <button
        type="button"
        class="opacity-60 hover:opacity-100 hover:bg-base-300 rounded px-1"
        :title="$t('cortex.validate.close')"
        @click="validation.close()"
      >✕</button>
    </div>

    <div class="flex-1 min-h-0 overflow-y-auto p-2 text-sm space-y-3">
      <div
        v-if="validation.error.value"
        class="px-2 py-1 bg-error/10 text-error text-xs font-mono whitespace-pre-wrap"
      >{{ validation.error.value }}</div>

      <div v-if="validation.quickResult.value">
        <div class="font-semibold mb-1">{{ $t('cortex.validate.quick') }}</div>
        <div v-if="validation.quickResult.value.ok" class="text-success">
          {{ $t('cortex.validate.noParseErrors') }}
        </div>
        <ul v-else class="list-disc pl-4 text-error">
          <li v-for="(e, i) in (validation.quickResult.value.errors ?? [])" :key="i">
            <span class="font-mono">[{{ e.line }}:{{ e.column }}]</span> {{ e.message }}
          </li>
        </ul>
      </div>

      <div v-if="validation.deepResult.value">
        <div class="font-semibold mb-1">{{ $t('cortex.validate.deep') }}</div>
        <div v-if="validation.deepResult.value.summary" class="italic opacity-70 mb-1">
          {{ validation.deepResult.value.summary }}
        </div>
        <div v-if="(validation.deepResult.value.warnings ?? []).length === 0" class="text-success">
          {{ $t('cortex.validate.noIssuesFound') }}
        </div>
        <ul v-else class="space-y-1">
          <li
            v-for="(w, i) in (validation.deepResult.value.warnings ?? [])"
            :key="i"
            class="border-l-2 pl-2"
            :class="severityClass(w.severity)"
          >
            <div class="text-xs font-mono opacity-60">{{ w.category }} · L{{ w.line }}</div>
            <div>{{ w.message }}</div>
          </li>
        </ul>
      </div>

      <div v-if="!validation.deepResult.value && cachedDeepWarnings" class="opacity-80">
        <div class="font-semibold mb-1">
          {{ $t('cortex.validate.cachedDeep') }}
          <span v-if="reviewedHashMatches" class="text-success text-xs">{{ $t('cortex.validate.matchesCurrent') }}</span>
          <span v-else class="text-warning text-xs">{{ $t('cortex.validate.contentChanged') }}</span>
        </div>
        <ul v-if="cachedDeepWarnings.length > 0" class="space-y-1">
          <li
            v-for="(w, i) in cachedDeepWarnings"
            :key="i"
            class="border-l-2 pl-2"
            :class="severityClass(w.severity)"
          >
            <div class="text-xs font-mono opacity-60">{{ w.category }} · L{{ w.line }}</div>
            <div>{{ w.message }}</div>
          </li>
        </ul>
        <div v-else class="text-success text-sm">{{ $t('cortex.validate.noIssues') }}</div>
      </div>

      <div
        v-if="!validation.error.value
          && !validation.quickResult.value
          && !validation.deepResult.value
          && !cachedDeepWarnings"
        class="opacity-50 text-xs"
      >{{ $t('cortex.validate.noResult') }}</div>
    </div>
  </div>
</template>
