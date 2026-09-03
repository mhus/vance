<script setup lang="ts">
import { watch } from 'vue';
import { VAlert, VEmptyState } from '@/components';
import { useSessionClientTools } from '@/composables/useInsights';

const props = defineProps<{ sessionId: string | null }>();

const state = useSessionClientTools();

watch(
  () => props.sessionId,
  (next) => {
    if (next) state.load(next);
    else state.clear();
  },
  { immediate: true },
);

function paramNames(schema: Record<string, unknown> | undefined): string[] {
  if (!schema || typeof schema !== 'object') return [];
  const props = (schema as { properties?: Record<string, unknown> }).properties;
  if (!props || typeof props !== 'object') return [];
  return Object.keys(props);
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <div v-if="!sessionId" class="opacity-60 text-sm">
      {{ $t('insights.liveTools.pickSession') }}
    </div>

    <div v-else-if="state.loading.value" class="text-sm opacity-60">
      {{ $t('insights.liveTools.loading') }}
    </div>

    <VAlert v-else-if="state.error.value" variant="error">
      {{ state.error.value }}
    </VAlert>

    <template v-else-if="state.data.value">
      <div class="flex items-center gap-3 text-xs opacity-80">
        <span
          class="px-1.5 py-0.5 rounded"
          :class="state.data.value.bound ? 'badge-bound' : 'badge-unbound'"
        >{{ state.data.value.bound
          ? $t('insights.liveTools.bound')
          : $t('insights.liveTools.notBound') }}</span>
        <span v-if="state.data.value.editorId" class="font-mono">
          {{ $t('insights.liveTools.editor', { id: state.data.value.editorId }) }}
        </span>
        <span v-else class="opacity-60">{{ $t('insights.liveTools.noConnection') }}</span>
      </div>

      <VEmptyState
        v-if="!state.data.value.tools || state.data.value.tools.length === 0"
        :headline="state.data.value.bound
          ? $t('insights.liveTools.emptyHeadlineBound')
          : $t('insights.liveTools.emptyHeadlineUnbound')"
        :body="state.data.value.bound
          ? $t('insights.liveTools.emptyBodyBound')
          : $t('insights.liveTools.emptyBodyUnbound')"
      />

      <table v-else class="table table-sm">
        <thead>
          <tr>
            <th class="w-40">{{ $t('insights.liveTools.colName') }}</th>
            <th class="w-20">{{ $t('insights.liveTools.colPrimary') }}</th>
            <th>{{ $t('insights.liveTools.colDescription') }}</th>
            <th class="w-48">{{ $t('insights.liveTools.colParams') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in state.data.value.tools" :key="t.name">
            <td class="font-mono">{{ t.name }}</td>
            <td>
              <span v-if="t.primary" class="badge-primary-tool">{{ $t('insights.liveTools.primary') }}</span>
              <span v-else class="opacity-50 text-xs">{{ $t('insights.liveTools.secondary') }}</span>
            </td>
            <td class="text-xs opacity-80">{{ t.description }}</td>
            <td class="text-xs font-mono opacity-80">
              <span v-if="paramNames(t.paramsSchema).length === 0" class="opacity-50">—</span>
              <span v-else>{{ paramNames(t.paramsSchema).join(', ') }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </template>
  </div>
</template>

<style scoped>
.badge-bound {
  background: color-mix(in oklab, var(--color-success) 18%, transparent);
  color: var(--color-success);
}
.badge-unbound {
  background: var(--color-base-300);
  color: color-mix(in oklab, var(--color-base-content) 60%, transparent);
}
.badge-primary-tool {
  display: inline-block;
  padding: 0.05rem 0.4rem;
  border-radius: 0.25rem;
  background: color-mix(in oklab, var(--color-primary) 18%, transparent);
  color: var(--color-primary);
  font-size: 0.7rem;
}
</style>
