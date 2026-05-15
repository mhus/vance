<script setup lang="ts">
import { ref, watch } from 'vue';
import { VAlert, VButton, VCard, VEmptyState } from '@/components';
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

function fmtTime(value: string | null | undefined): string {
  if (!value) return '—';
  return String(value).replace('T', ' ').slice(0, 19);
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
      <VCard title="Project RAG — _documents">
        <div v-if="state.loading.value" class="opacity-70">Loading…</div>
        <template v-else-if="state.status.value">
          <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
            <dt class="opacity-60">Status</dt>
            <dd>
              <span v-if="state.status.value.exists" class="badge-ok">active</span>
              <span v-else class="badge-empty">not created</span>
            </dd>
            <template v-if="state.status.value.exists">
              <dt class="opacity-60">RAG id</dt>
              <dd class="font-mono text-xs">{{ state.status.value.ragId }}</dd>
              <dt class="opacity-60">Embedding provider</dt>
              <dd>{{ state.status.value.embeddingProvider ?? '—' }}</dd>
              <dt class="opacity-60">Embedding model</dt>
              <dd>{{ state.status.value.embeddingModel ?? '—' }}</dd>
              <dt class="opacity-60">Chunks</dt>
              <dd>{{ state.status.value.chunkCount }}</dd>
              <dt class="opacity-60">Created</dt>
              <dd>{{ fmtTime(state.status.value.createdAt) }}</dd>
            </template>
          </dl>
          <p v-if="!state.status.value.exists" class="text-xs opacity-70 mt-2">
            The project's default RAG is created automatically the next time the
            project is brought to RUNNING, or when you press <em>Reindex</em>.
          </p>
        </template>
      </VCard>

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
