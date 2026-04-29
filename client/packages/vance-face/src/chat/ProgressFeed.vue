<script setup lang="ts">
import { computed } from 'vue';
import type { ProcessProgressNotification } from '@vance/generated';
import { VEmptyState } from '@components/index';

// Same enum-string-compat note as MessageBubble: Jackson serialises
// {@code ProgressKind} / {@code StatusTag} as their enum name, while
// the generated TS enum is numeric. Cast to string for runtime match.
type Kind = 'METRICS' | 'PLAN' | 'STATUS';

const props = defineProps<{
  events: ProcessProgressNotification[];
}>();

const reversed = computed(() => props.events.slice().reverse());

function kindOf(event: ProcessProgressNotification): Kind {
  return event.kind as unknown as Kind;
}

function summarise(event: ProcessProgressNotification): string {
  switch (kindOf(event)) {
    case 'METRICS': {
      const m = event.metrics;
      if (!m) return 'metrics';
      const inK = Math.round(m.tokensInTotal / 100) / 10;
      const outK = Math.round(m.tokensOutTotal / 100) / 10;
      return `${m.llmCallCount} calls · ${inK}k in / ${outK}k out`;
    }
    case 'PLAN':
      return event.plan?.rootNode?.title ?? 'plan updated';
    case 'STATUS':
      return event.status?.text ?? 'status';
    default:
      return String(event.kind);
  }
}

function tagOf(event: ProcessProgressNotification): string | null {
  if (kindOf(event) !== 'STATUS' || !event.status) return null;
  return event.status.tag as unknown as string;
}
</script>

<template>
  <div class="p-3 flex flex-col gap-3 min-h-0">
    <div class="text-xs uppercase tracking-wide opacity-60 font-semibold px-1">
      Live progress
    </div>

    <VEmptyState
      v-if="reversed.length === 0"
      headline="No progress yet"
      body="Live status from running processes shows up here."
    />

    <ol v-else class="flex flex-col gap-1.5 text-sm">
      <li
        v-for="(event, idx) in reversed"
        :key="`${event.processId}-${event.emittedAt}-${idx}`"
        class="bg-base-200 rounded px-2.5 py-1.5"
      >
        <div class="flex items-center gap-1.5 text-xs opacity-60">
          <span class="font-mono">{{ event.engine }}</span>
          <span class="opacity-50">·</span>
          <span class="truncate">{{ event.processTitle || event.processName }}</span>
          <span v-if="tagOf(event)" class="ml-auto px-1.5 rounded bg-base-300 text-[10px] uppercase">
            {{ tagOf(event) }}
          </span>
        </div>
        <div class="mt-0.5 break-words">{{ summarise(event) }}</div>
      </li>
    </ol>
  </div>
</template>
