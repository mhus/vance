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
      Select a session to inspect its live client tools.
    </div>

    <div v-else-if="state.loading.value" class="text-sm opacity-60">Loading client tools…</div>

    <VAlert v-else-if="state.error.value" variant="error">
      {{ state.error.value }}
    </VAlert>

    <template v-else-if="state.data.value">
      <div class="flex items-center gap-3 text-xs opacity-80">
        <span
          class="px-1.5 py-0.5 rounded"
          :class="state.data.value.bound ? 'badge-bound' : 'badge-unbound'"
        >{{ state.data.value.bound ? 'bound' : 'not bound' }}</span>
        <span v-if="state.data.value.connectionId" class="font-mono">
          conn: {{ state.data.value.connectionId }}
        </span>
        <span v-else class="opacity-60">no active connection</span>
      </div>

      <VEmptyState
        v-if="!state.data.value.tools || state.data.value.tools.length === 0"
        :headline="state.data.value.bound ? 'No client tools' : 'Client not connected'"
        :body="state.data.value.bound
          ? 'The client is connected but did not register any tools.'
          : 'Live tools appear when the client opens its WebSocket and pushes its tool list.'"
      />

      <table v-else class="table table-sm">
        <thead>
          <tr>
            <th class="w-40">Name</th>
            <th class="w-20">Primary</th>
            <th>Description</th>
            <th class="w-48">Params</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in state.data.value.tools" :key="t.name">
            <td class="font-mono">{{ t.name }}</td>
            <td>
              <span v-if="t.primary" class="badge-primary-tool">primary</span>
              <span v-else class="opacity-50 text-xs">secondary</span>
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
  background: oklch(var(--su) / 0.18);
  color: oklch(var(--su));
}
.badge-unbound {
  background: oklch(var(--b3));
  color: oklch(var(--bc) / 0.6);
}
.badge-primary-tool {
  display: inline-block;
  padding: 0.05rem 0.4rem;
  border-radius: 0.25rem;
  background: oklch(var(--p) / 0.18);
  color: oklch(var(--p));
  font-size: 0.7rem;
}
</style>
