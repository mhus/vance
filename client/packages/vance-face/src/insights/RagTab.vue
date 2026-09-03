<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { VAlert, VButton, VCard, VEmptyState, VInput } from '@/components';
import { useRag } from '@/composables/useRag';

const props = defineProps<{ projectId: string | null }>();

const state = useRag();
const rebuildConfirmOpen = ref(false);

watch(
  () => props.projectId,
  (next) => {
    rebuildConfirmOpen.value = false;
    if (next) void state.load(next);
    else state.clear();
  },
  { immediate: true },
);

function refresh(): void {
  if (props.projectId) void state.load(props.projectId);
}

async function reindex(): Promise<void> {
  if (!props.projectId) return;
  await state.reindex(props.projectId, false);
}

async function rebuild(): Promise<void> {
  if (!props.projectId) return;
  rebuildConfirmOpen.value = false;
  await state.reindex(props.projectId, true);
}

const searchDisabled = computed(() => {
  if (!props.projectId) return true;
  if (state.searching.value) return true;
  if (!state.status.value?.exists) return true;
  return state.searchQuery.value.trim().length === 0;
});

/** Cascade-resolved tenant/project setting — `"none"` means RAG is off here. */
const embeddingDisabled = computed(
  () => !!state.status.value && !state.status.value.enabled,
);

const providerMismatch = computed(() => {
  const s = state.status.value;
  if (!s || !s.exists) return false;
  return !!s.embeddingProvider && s.embeddingProvider !== s.effectiveProvider;
});

async function runSearch(): Promise<void> {
  if (!props.projectId) return;
  const query = state.searchQuery.value.trim();
  if (query.length === 0) return;
  await state.search(props.projectId, query);
}

function fmtTime(value: string | null | undefined): string {
  if (!value) return '—';
  return String(value).replace('T', ' ').slice(0, 19);
}

function fmtScore(score: number): string {
  return score.toFixed(4);
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <VAlert v-if="state.error.value" variant="error">
      <span>{{ state.error.value }}</span>
    </VAlert>

    <VEmptyState
      v-if="!projectId"
      :headline="$t('insights.rag.noProjectHeadline')"
      :body="$t('insights.rag.noProjectBody')"
    />

    <template v-else>
      <VAlert v-if="embeddingDisabled" variant="info">
        <span>
          {{ $t('insights.rag.disabledPre') }}
          <code>ai.embedding.provider = none</code>{{ $t('insights.rag.disabledMid') }}
          <strong>{{ $t('insights.rag.llmSettings') }}</strong>
          {{ $t('insights.rag.disabledPost') }} <code>embedded</code>,
          <code>gemini</code> {{ $t('insights.rag.disabledOr') }} <code>openai</code>
          {{ $t('insights.rag.disabledEnd') }}
        </span>
      </VAlert>

      <VCard :title="$t('insights.rag.cardTitle')">
        <div v-if="state.loading.value" class="opacity-70">{{ $t('insights.rag.loading') }}</div>
        <template v-else-if="state.status.value">
          <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
            <dt class="opacity-60">{{ $t('insights.rag.status') }}</dt>
            <dd>
              <span v-if="embeddingDisabled" class="badge-empty">{{ $t('insights.rag.statusDisabled') }}</span>
              <span v-else-if="state.status.value.exists" class="badge-ok">{{ $t('insights.rag.statusActive') }}</span>
              <span v-else class="badge-empty">{{ $t('insights.rag.statusNotCreated') }}</span>
            </dd>
            <dt class="opacity-60">{{ $t('insights.rag.effectiveProvider') }}</dt>
            <dd>
              <code>{{ state.status.value.effectiveProvider }}</code>
              <span
                v-if="providerMismatch"
                class="ml-2 text-xs opacity-70"
                :title="$t('insights.rag.pinnedTitle', {
                  created: state.status.value.embeddingProvider,
                  effective: state.status.value.effectiveProvider,
                })"
              >
                {{ $t('insights.rag.pinnedPre') }}
                <code>{{ state.status.value.embeddingProvider }}</code>{{ $t('insights.rag.pinnedPost') }}
              </span>
            </dd>
            <template v-if="state.status.value.exists">
              <dt class="opacity-60">{{ $t('insights.rag.ragId') }}</dt>
              <dd class="font-mono text-xs">{{ state.status.value.ragId }}</dd>
              <dt class="opacity-60">{{ $t('insights.rag.embeddingModel') }}</dt>
              <dd>{{ state.status.value.embeddingModel ?? '—' }}</dd>
              <dt class="opacity-60">{{ $t('insights.rag.chunks') }}</dt>
              <dd>{{ state.status.value.chunkCount }}</dd>
              <dt class="opacity-60">{{ $t('insights.rag.created') }}</dt>
              <dd>{{ fmtTime(state.status.value.createdAt) }}</dd>
            </template>
          </dl>
          <p
            v-if="!embeddingDisabled && !state.status.value.exists"
            class="text-xs opacity-70 mt-2"
          >
            {{ $t('insights.rag.autoCreatePre') }}
            <em>{{ $t('insights.rag.autoCreateReindex') }}</em>.
          </p>
        </template>
      </VCard>

      <template v-if="!embeddingDisabled">
      <VCard :title="$t('insights.rag.actions')">
        <p class="text-xs opacity-70 mb-3">
          <strong>{{ $t('insights.rag.actionsHintPre') }}</strong>
          {{ $t('insights.rag.actionsHintMid') }} <code>documents/</code>
          {{ $t('insights.rag.actionsHintKeep') }}
          <strong>{{ $t('insights.rag.actionsHintRebuild') }}</strong>
          {{ $t('insights.rag.actionsHintPost') }}
        </p>
        <div class="flex flex-wrap gap-2">
          <VButton
            :disabled="state.busy.value"
            @click="reindex"
          >
            {{ state.busy.value ? $t('insights.rag.working') : $t('insights.rag.reindex') }}
          </VButton>
          <VButton
            variant="ghost"
            :disabled="state.busy.value"
            @click="rebuildConfirmOpen = true"
          >
            {{ $t('insights.rag.rebuildButton') }}
          </VButton>
          <VButton variant="ghost" :disabled="state.busy.value" @click="refresh">
            {{ $t('insights.rag.refresh') }}
          </VButton>
        </div>

        <div
          v-if="rebuildConfirmOpen"
          class="mt-3 border border-warning/40 bg-warning/10 rounded p-3 text-sm"
        >
          <p class="mb-2">
            {{ $t('insights.rag.confirmDropPre') }}
            <strong>{{ $t('insights.rag.confirmDropWord') }}</strong>
            {{ $t('insights.rag.confirmDropMid') }} <code>_documents</code>
            {{ $t('insights.rag.confirmDropPost') }}
          </p>
          <p class="text-xs opacity-70 mb-3">
            {{ $t('insights.rag.confirmHint') }}
          </p>
          <div class="flex gap-2">
            <VButton variant="danger" @click="rebuild">{{ $t('insights.rag.confirmYes') }}</VButton>
            <VButton variant="ghost" @click="rebuildConfirmOpen = false">{{ $t('insights.rag.cancel') }}</VButton>
          </div>
        </div>

        <div
          v-if="state.lastResult.value"
          class="mt-3 text-xs opacity-70"
        >
          {{ $t('insights.rag.lastRun') }}
          <strong>{{ state.lastResult.value.rebuild
            ? $t('insights.rag.lastRunRebuild')
            : $t('insights.rag.lastRunReindex') }}</strong>
          {{ $t('insights.rag.lastRunQueued', { count: state.lastResult.value.documentsQueued }) }}
        </div>
      </VCard>

      <VCard :title="$t('insights.rag.searchTitle')">
        <p class="text-xs opacity-70 mb-3">
          {{ $t('insights.rag.searchHintPre') }}
          <code>&lt;rag-context&gt;</code> {{ $t('insights.rag.searchHintPost') }}
        </p>
        <form class="flex gap-2 items-start" @submit.prevent="runSearch">
          <VInput
            v-model="state.searchQuery.value"
            :placeholder="$t('insights.rag.searchPlaceholder')"
            :disabled="!state.status.value?.exists || state.searching.value"
            class="flex-1"
          />
          <VButton type="submit" :disabled="searchDisabled">
            {{ state.searching.value ? $t('insights.rag.searching') : $t('insights.rag.search') }}
          </VButton>
        </form>

        <p
          v-if="!state.status.value?.exists"
          class="text-xs opacity-60 mt-2"
        >
          {{ $t('insights.rag.searchUnavailable') }}
        </p>

        <VAlert v-if="state.searchError.value" variant="error" class="mt-3">
          <span>{{ state.searchError.value }}</span>
        </VAlert>

        <template v-if="state.searched.value && !state.searchError.value">
          <p
            v-if="state.searchHits.value.length === 0"
            class="text-sm opacity-60 mt-3"
          >
            {{ $t('insights.rag.noMatches') }}
          </p>
          <ol v-else class="mt-3 flex flex-col gap-2">
            <li
              v-for="(hit, idx) in state.searchHits.value"
              :key="`${hit.sourceRef ?? 'no-source'}-${hit.position}-${idx}`"
              class="border border-base-300 rounded p-3 text-sm bg-base-100/40"
            >
              <div class="flex justify-between gap-2 text-xs opacity-70 mb-1">
                <span class="font-mono truncate" :title="hit.sourceRef ?? ''">
                  {{ hit.sourceRef ?? '—' }}<span class="opacity-50"> #{{ hit.position }}</span>
                </span>
                <span class="font-mono whitespace-nowrap">
                  {{ $t('insights.rag.score', { value: fmtScore(hit.score) }) }}
                </span>
              </div>
              <pre class="whitespace-pre-wrap break-words text-xs opacity-90 m-0">{{ hit.content }}</pre>
            </li>
          </ol>
        </template>
      </VCard>
      </template>
    </template>
  </div>
</template>

<style scoped>
.badge-ok {
  display: inline-block;
  font-size: 0.75rem;
  padding: 0.1rem 0.5rem;
  border-radius: 0.375rem;
  background: color-mix(in oklab, var(--color-success) 18%, transparent);
  color: var(--color-success-content);
}
.badge-empty {
  display: inline-block;
  font-size: 0.75rem;
  padding: 0.1rem 0.5rem;
  border-radius: 0.375rem;
  background: color-mix(in oklab, var(--color-base-content) 10%, transparent);
  color: color-mix(in oklab, var(--color-base-content) 60%, transparent);
}
</style>
