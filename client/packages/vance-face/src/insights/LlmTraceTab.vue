<script setup lang="ts">
import { ref, watch } from 'vue';
import { VAlert, VButton, VEmptyState, VPagination } from '@/components';
import { useLlmTraces, type LlmTraceTurn } from '@/composables/useLlmTraces';
import type { LlmTraceDto } from '@vance/generated';

const props = defineProps<{
  /** Mongo id of the process whose trace history to load. */
  processId: string;
}>();

const traces = useLlmTraces();

// Per-turn open/closed state — tracked client-side, not from the
// server. Fresh page-load defaults to all collapsed; the user can
// expand whichever round-trip they want to inspect.
const expanded = ref<Set<string>>(new Set());

watch(
  () => props.processId,
  async (next) => {
    expanded.value = new Set();
    if (!next) {
      traces.reset();
      return;
    }
    await traces.loadPage(next, 0);
  },
  { immediate: true },
);

function toggle(turnId: string): void {
  if (expanded.value.has(turnId)) {
    expanded.value.delete(turnId);
  } else {
    expanded.value.add(turnId);
  }
  // Trigger reactivity — Set mutations don't propagate by default.
  expanded.value = new Set(expanded.value);
}

async function changePage(p: number): Promise<void> {
  await traces.loadPage(props.processId, p);
  expanded.value = new Set();
}

function fmtTokens(n: number | null): string {
  if (n == null) return '—';
  if (n < 1_000) return String(n);
  if (n < 1_000_000) return `${(n / 1_000).toFixed(1)}k`;
  return `${(n / 1_000_000).toFixed(1)}M`;
}

function fmtMs(ms: number | null): string {
  if (ms == null) return '—';
  if (ms < 1_000) return `${ms}ms`;
  return `${(ms / 1_000).toFixed(1)}s`;
}

function fmtTime(iso: string | null): string {
  if (!iso) return '';
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

function turnLabel(t: LlmTraceTurn): string {
  // Short prefix of the turnId — enough to differentiate but doesn't
  // dominate the header.
  const id = t.turnId.startsWith('__loose:')
    ? '(orphan)'
    : t.turnId.slice(0, 8);
  return id;
}

function legBadge(leg: LlmTraceDto): { label: string; cls: string } {
  switch (leg.direction) {
    case 'input':
      return { label: leg.role ?? 'input', cls: 'leg--input' };
    case 'output':
      return { label: 'output', cls: 'leg--output' };
    case 'tool_call':
      return { label: `tool-call: ${leg.toolName ?? '?'}`, cls: 'leg--tool-call' };
    case 'tool_result':
      return { label: `tool-result: ${leg.toolName ?? '?'}`, cls: 'leg--tool-result' };
    default:
      return { label: leg.direction || '?', cls: 'leg--default' };
  }
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <VAlert v-if="traces.error.value" variant="error">
      <span>{{ traces.error.value }}</span>
    </VAlert>

    <div v-if="traces.loading.value && traces.items.value.length === 0" class="opacity-70">
      Loading LLM traces…
    </div>

    <VEmptyState
      v-else-if="!traces.loading.value && traces.items.value.length === 0"
      headline="No LLM traces"
      body="Either tracing.llm was off when this process ran, or all rows have aged out (90-day TTL)."
    />

    <ul v-else class="flex flex-col gap-2">
      <li
        v-for="turn in traces.turns.value"
        :key="turn.turnId"
        class="turn"
      >
        <button
          type="button"
          class="turn-header"
          :aria-expanded="expanded.has(turn.turnId)"
          @click="toggle(turn.turnId)"
        >
          <span class="turn-disclosure">
            {{ expanded.has(turn.turnId) ? '▾' : '▸' }}
          </span>
          <span class="turn-id">#{{ turnLabel(turn) }}</span>
          <span class="turn-time">{{ fmtTime(turn.startedAt) }}</span>
          <span class="turn-meta">
            <span v-if="turn.modelAlias">{{ turn.modelAlias }}</span>
            <span v-if="turn.tokensIn != null || turn.tokensOut != null">
              {{ fmtTokens(turn.tokensIn) }} in / {{ fmtTokens(turn.tokensOut) }} out
            </span>
            <span v-if="turn.elapsedMs != null">{{ fmtMs(turn.elapsedMs) }}</span>
            <span v-if="turn.toolCallCount > 0" class="turn-tools">
              · {{ turn.toolCallCount }} tool-call{{ turn.toolCallCount === 1 ? '' : 's' }}
            </span>
          </span>
          <span class="turn-leg-count">{{ turn.legs.length }} legs</span>
        </button>

        <div v-if="expanded.has(turn.turnId)" class="turn-body">
          <div
            v-for="leg in turn.legs"
            :key="leg.id ?? `${turn.turnId}-${leg.sequence}`"
            class="leg"
            :class="legBadge(leg).cls"
          >
            <div class="leg-header">
              <span class="leg-badge">{{ legBadge(leg).label }}</span>
              <span v-if="leg.toolCallId" class="leg-tool-id">
                id={{ leg.toolCallId }}
              </span>
              <span class="leg-seq">seq {{ leg.sequence }}</span>
            </div>
            <pre v-if="leg.content" class="leg-content">{{ leg.content }}</pre>
            <div v-else class="leg-empty">(empty)</div>
          </div>
        </div>
      </li>
    </ul>

    <VPagination
      v-if="traces.totalCount.value > traces.pageSize.value"
      :page="traces.page.value"
      :page-size="traces.pageSize.value"
      :total-count="traces.totalCount.value"
      @change="changePage"
    />
  </div>
</template>

<style scoped>
.turn {
  border: 1px solid rgba(127, 127, 127, 0.2);
  border-radius: 0.375rem;
  overflow: hidden;
}
.turn-header {
  width: 100%;
  display: grid;
  grid-template-columns: 1.25rem auto auto 1fr auto;
  gap: 0.5rem;
  align-items: center;
  padding: 0.5rem 0.75rem;
  text-align: left;
  background: transparent;
  border: 0;
  cursor: pointer;
}
.turn-header:hover {
  background: rgba(127, 127, 127, 0.05);
}
.turn-disclosure {
  font-family: monospace;
  opacity: 0.6;
}
.turn-id {
  font-family: monospace;
  font-size: 0.75rem;
  opacity: 0.7;
}
.turn-time {
  font-size: 0.75rem;
  opacity: 0.6;
}
.turn-meta {
  display: flex;
  gap: 0.75rem;
  font-size: 0.75rem;
  opacity: 0.85;
}
.turn-tools {
  opacity: 0.7;
}
.turn-leg-count {
  font-size: 0.7rem;
  opacity: 0.5;
}
.turn-body {
  border-top: 1px solid rgba(127, 127, 127, 0.15);
  padding: 0.5rem 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.leg {
  border-left: 3px solid rgba(127, 127, 127, 0.3);
  padding: 0.25rem 0.5rem;
}
.leg--input { border-left-color: #6c7780; }
.leg--output { border-left-color: #4a90e2; }
.leg--tool-call { border-left-color: #f5a623; }
.leg--tool-result { border-left-color: #7ed321; }
.leg-header {
  display: flex;
  gap: 0.75rem;
  font-size: 0.7rem;
  opacity: 0.7;
  margin-bottom: 0.25rem;
}
.leg-badge {
  font-family: monospace;
  font-weight: 600;
}
.leg-tool-id {
  font-family: monospace;
}
.leg-seq {
  margin-left: auto;
  opacity: 0.6;
}
.leg-content {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 0.8rem;
  font-family: ui-monospace, monospace;
  background: rgba(127, 127, 127, 0.06);
  padding: 0.4rem 0.5rem;
  border-radius: 0.25rem;
  max-height: 400px;
  overflow-y: auto;
}
.leg-empty {
  font-size: 0.75rem;
  opacity: 0.5;
  font-style: italic;
}
</style>
