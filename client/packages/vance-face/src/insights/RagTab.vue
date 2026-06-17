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
      headline="No project selected"
      body="Pick a project from the sidebar filter to manage its RAG."
    />

    <template v-else>
      <VAlert v-if="embeddingDisabled" variant="info">
        <span>
          RAG is disabled for this project —
          <code>ai.embedding.provider = none</code>. Open the
          <strong>LLM Settings</strong> form and pick <code>embedded</code>,
          <code>gemini</code> or <code>openai</code> to enable indexing and search.
        </span>
      </VAlert>

      <VCard title="Project RAG — _documents">
        <div v-if="state.loading.value" class="opacity-70">Loading…</div>
        <template v-else-if="state.status.value">
          <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
            <dt class="opacity-60">Status</dt>
            <dd>
              <span v-if="embeddingDisabled" class="badge-empty">disabled</span>
              <span v-else-if="state.status.value.exists" class="badge-ok">active</span>
              <span v-else class="badge-empty">not created</span>
            </dd>
            <dt class="opacity-60">Effective provider</dt>
            <dd>
              <code>{{ state.status.value.effectiveProvider }}</code>
              <span
                v-if="providerMismatch"
                class="ml-2 text-xs opacity-70"
                :title="'RAG was created with ' + state.status.value.embeddingProvider + ' — tenant now resolves to ' + state.status.value.effectiveProvider + '. Use Rebuild to migrate.'"
              >
                (RAG pinned to <code>{{ state.status.value.embeddingProvider }}</code>)
              </span>
            </dd>
            <template v-if="state.status.value.exists">
              <dt class="opacity-60">RAG id</dt>
              <dd class="font-mono text-xs">{{ state.status.value.ragId }}</dd>
              <dt class="opacity-60">Embedding model</dt>
              <dd>{{ state.status.value.embeddingModel ?? '—' }}</dd>
              <dt class="opacity-60">Chunks</dt>
              <dd>{{ state.status.value.chunkCount }}</dd>
              <dt class="opacity-60">Created</dt>
              <dd>{{ fmtTime(state.status.value.createdAt) }}</dd>
            </template>
          </dl>
          <p
            v-if="!embeddingDisabled && !state.status.value.exists"
            class="text-xs opacity-70 mt-2"
          >
            The project's default RAG is created automatically the next time the
            project is brought to RUNNING, or when you press <em>Reindex</em>.
          </p>
        </template>
      </VCard>

      <template v-if="!embeddingDisabled">
      <VCard title="Actions">
        <p class="text-xs opacity-70 mb-3">
          <strong>Reindex</strong> queues every active document under
          <code>documents/</code> for re-embedding into the existing RAG —
          embedding provider and model are kept. <strong>Rebuild</strong>
          drops the RAG and re-creates it with the current tenant embedding
          settings; use this after switching providers.
        </p>
        <div class="flex flex-wrap gap-2">
          <VButton
            :disabled="state.busy.value"
            @click="reindex"
          >
            {{ state.busy.value ? 'Working…' : 'Reindex' }}
          </VButton>
          <VButton
            variant="ghost"
            :disabled="state.busy.value"
            @click="rebuildConfirmOpen = true"
          >
            Rebuild with current embedding model
          </VButton>
          <VButton variant="ghost" :disabled="state.busy.value" @click="refresh">
            Refresh
          </VButton>
        </div>

        <div
          v-if="rebuildConfirmOpen"
          class="mt-3 border border-warning/40 bg-warning/10 rounded p-3 text-sm"
        >
          <p class="mb-2">
            This will <strong>drop</strong> the current <code>_documents</code> RAG
            (including all chunks) and re-create it with the tenant's current
            embedding settings. Then every active document will be queued for
            re-indexing.
          </p>
          <p class="text-xs opacity-70 mb-3">
            Confirm only if you switched embedding provider/model and want the
            new model applied retroactively.
          </p>
          <div class="flex gap-2">
            <VButton variant="danger" @click="rebuild">Yes, rebuild</VButton>
            <VButton variant="ghost" @click="rebuildConfirmOpen = false">Cancel</VButton>
          </div>
        </div>

        <div
          v-if="state.lastResult.value"
          class="mt-3 text-xs opacity-70"
        >
          Last run:
          <strong>{{ state.lastResult.value.rebuild ? 'rebuild' : 'reindex' }}</strong>
          — {{ state.lastResult.value.documentsQueued }} document(s) queued.
        </div>
      </VCard>

      <VCard title="Search">
        <p class="text-xs opacity-70 mb-3">
          Embed the query with the RAG's embedding model and return the
          top-20 most similar chunks. Useful to inspect what the model
          would inject as <code>&lt;rag-context&gt;</code> for a given
          prompt.
        </p>
        <form class="flex gap-2 items-start" @submit.prevent="runSearch">
          <VInput
            v-model="state.searchQuery.value"
            placeholder="Search the RAG…"
            :disabled="!state.status.value?.exists || state.searching.value"
            class="flex-1"
          />
          <VButton type="submit" :disabled="searchDisabled">
            {{ state.searching.value ? 'Searching…' : 'Search' }}
          </VButton>
        </form>

        <p
          v-if="!state.status.value?.exists"
          class="text-xs opacity-60 mt-2"
        >
          Search becomes available once the RAG has been created.
        </p>

        <VAlert v-if="state.searchError.value" variant="error" class="mt-3">
          <span>{{ state.searchError.value }}</span>
        </VAlert>

        <template v-if="state.searched.value && !state.searchError.value">
          <p
            v-if="state.searchHits.value.length === 0"
            class="text-sm opacity-60 mt-3"
          >
            No matches.
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
                <span class="font-mono whitespace-nowrap">score {{ fmtScore(hit.score) }}</span>
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
  background: hsl(var(--su) / 0.18);
  color: hsl(var(--suc));
}
.badge-empty {
  display: inline-block;
  font-size: 0.75rem;
  padding: 0.1rem 0.5rem;
  border-radius: 0.375rem;
  background: hsl(var(--bc) / 0.1);
  color: hsl(var(--bc) / 0.6);
}
</style>
