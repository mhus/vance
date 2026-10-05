<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import type { ProcessProgressNotification } from '@vance/generated';
import { VEmptyState } from '@components/index';

const { t } = useI18n();

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
      if (!m) return t('chat.progress.metricsLabel');
      const inK = Math.round(m.tokensInTotal / 100) / 10;
      const outK = Math.round(m.tokensOutTotal / 100) / 10;
      return t('chat.progress.metricsLine', {
        calls: m.llmCallCount,
        tokensIn: inK,
        tokensOut: outK,
      });
    }
    case 'PLAN':
      return event.plan?.rootNode?.title ?? t('chat.progress.planUpdated');
    case 'STATUS': {
      const s = event.status;
      if (!s) return t('chat.progress.status');
      // Tool-boundary pings render the structured form — tool name plus
      // the call/outcome teaser — instead of the English prose in `text`.
      if (s.tool) {
        const teaser = firstLine(s.teaser);
        return teaser ? `${s.tool} · ${teaser}` : s.tool;
      }
      return s.text;
    }
    default:
      return String(event.kind);
  }
}
/** First line of a possibly multi-line teaser — the subject line. */
function firstLine(teaser: string | undefined): string {
  return teaser ? teaser.split('\n', 1)[0] : '';
}

/**
 * The teaser's preview block (everything below the subject line) — shown
 * under the summary so "what was written" is visible at a glance.
 * {@code null} for single-line teasers.
 */
function teaserBlock(event: ProcessProgressNotification): string | null {
  const teaser = event.status?.teaser;
  if (!teaser) return null;
  const rest = teaser.split('\n').slice(1).join('\n');
  return rest || null;
}
function tagOf(event: ProcessProgressNotification): string | null {
  if (kindOf(event) !== 'STATUS' || !event.status) return null;
  return event.status.tag as unknown as string;
}
</script>

<template>
  <div class="p-3 flex flex-col gap-3 min-h-0">
    <div class="text-xs uppercase tracking-wide opacity-60 font-semibold px-1">
      {{ $t('chat.progress.title') }}
    </div>

    <VEmptyState
      v-if="reversed.length === 0"
      :headline="$t('chat.progress.empty')"
      :body="$t('chat.progress.emptyBody')"
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
        <div
          v-if="teaserBlock(event)"
          class="mt-1 border-l-2 border-base-300 pl-2 font-mono text-xs opacity-70 whitespace-pre-line break-words"
        >{{ teaserBlock(event) }}</div>
      </li>
    </ol>
  </div>
</template>
