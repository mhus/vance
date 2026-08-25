<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  activityView,
  formatDuration,
  opElapsedMs,
  opsNewestFirst,
  type ActivityOp,
  type ActivityState,
} from './chatActivity';

/**
 * One live line between the transcript and the composer: what the agent is
 * doing right now, and how long it has been at it.
 *
 * <p>Deliberately not a chat message. Tool calls arrive on the ephemeral
 * progress channel (never persisted, never replayed), so rendering them as
 * bubbles would leave holes in the transcript after every reload. A strip
 * that collapses to a one-line summary answers the only question the user
 * actually has — "is anything still happening?" — without touching history.
 *
 * <p>The elapsed time ticks here rather than in the reducer: it is the one
 * piece of state that changes without a frame arriving.
 */

const props = defineProps<{
  state: ActivityState;
  /** Hides the strip entirely — used while the transcript is still loading. */
  suppressed?: boolean;
}>();

const { t, locale } = useI18n();

/** Ticking clock, one second — the resolution the strip displays. */
const now = ref(Date.now());
let timer: ReturnType<typeof setInterval> | null = null;

function stopTimer(): void {
  if (timer !== null) {
    clearInterval(timer);
    timer = null;
  }
}

const view = computed(() => activityView(props.state, now.value));

/**
 * The timer only runs while something is actually pending — running, parked,
 * or mid-turn. An idle chat showing a static summary must not wake the tab
 * every second.
 */
watch(
  () => view.value.current !== null
    || view.value.waiting !== null
    || props.state.turnActive,
  (live) => {
    if (live && timer === null) {
      now.value = Date.now();
      timer = setInterval(() => { now.value = Date.now(); }, 1_000);
    } else if (!live) {
      stopTimer();
      // One final read so the frozen summary shows the true end time.
      now.value = Date.now();
    }
  },
  { immediate: true },
);

onBeforeUnmount(stopTimer);

const expanded = ref(false);
// A new turn closes the drawer: keeping it open would leave the user
// staring at a list that empties under them.
watch(() => props.state.turnStartedAt, () => { expanded.value = false; });

const visible = computed(() => !props.suppressed && view.value.visible);

const ops = computed(() => opsNewestFirst(props.state));

const headline = computed<string>(() => {
  const current = view.value.current;
  if (current) {
    return current.worker ? `${current.worker} · ${current.label}` : current.label;
  }
  // Parked with nothing running — the wait IS the headline. This is the gate
  // case (Vogon goes BLOCKED and yields), where no op will ever follow to
  // explain the silence.
  const waiting = view.value.waiting;
  if (waiting) {
    return waiting.worker ? `${waiting.worker} · ${waiting.label}` : waiting.label;
  }
  if (view.value.failedCount > 0) {
    return t('chat.activityStrip.doneWithFailures', {
      tools: view.value.toolCount,
      failed: view.value.failedCount,
    });
  }
  if (view.value.toolCount > 0) {
    return t('chat.activityStrip.doneTools', { tools: view.value.toolCount });
  }
  return t('chat.activityStrip.working');
});

const marker = computed<string>(() => {
  if (view.value.current) return '⟳';
  // Waiting is not progress — an hourglass says "blocked", a spinner would
  // claim work is being done.
  if (view.value.waiting) return '⏳';
  return view.value.failedCount > 0 ? '⚠' : '✓';
});

/**
 * The wait as a second line, shown only when an op is already holding the
 * headline. Otherwise the wait is the headline and this would repeat it.
 */
const waitingSubline = computed<string | null>(() => {
  const waiting = view.value.waiting;
  if (!waiting || !view.value.current) return null;
  return waiting.label;
});

/** Tool count is only worth its width while more than one has run. */
const showCount = computed(
  () => view.value.current !== null && view.value.toolCount > 1,
);

function elapsedLabel(): string {
  return formatDuration(view.value.elapsedMs, locale.value);
}

function opLabel(op: ActivityOp): string {
  return op.worker ? `${op.worker} · ${op.label}` : op.label;
}

function opMarker(op: ActivityOp): string {
  if (op.endedAt === undefined) return '⟳';
  if (op.failed) return '⚠';
  switch (op.kind) {
    case 'provider': return '↻';
    case 'compaction': return '⤵';
    case 'script': return '›';
    case 'delegate': return '⑃';
    case 'search': return '⌕';
    case 'fetch': return '↓';
    case 'file': return '▤';
    case 'milestone': return '◆';
    case 'info': return '·';
    case 'tool':
    default: return '✓';
  }
}

function opElapsedLabel(op: ActivityOp): string {
  return formatDuration(opElapsedMs(op, now.value), locale.value);
}
</script>

<template>
  <div v-if="visible" class="border-t border-base-300 bg-base-200/60 text-xs">
    <!-- Drawer sits above the summary line so the line itself never moves
         when it opens — the eye keeps its anchor. -->
    <ol
      v-if="expanded && ops.length > 0"
      class="max-h-48 overflow-y-auto border-b border-base-300 px-3 py-1.5 flex flex-col gap-0.5"
    >
      <li v-for="op in ops" :key="op.id" :class="op.failed ? 'text-error' : ''">
        <div class="flex items-baseline gap-2">
          <span class="opacity-60 w-3 shrink-0" aria-hidden="true">{{ opMarker(op) }}</span>
          <span class="font-mono truncate">{{ opLabel(op) }}</span>
          <span class="ml-auto shrink-0 opacity-60 tabular-nums">{{ opElapsedLabel(op) }}</span>
        </div>
        <div v-if="op.detail" class="pl-5 opacity-60 break-words">{{ op.detail }}</div>
      </li>
    </ol>

    <div class="px-3 py-1.5">
      <div class="flex items-center gap-2">
        <span
          class="shrink-0 opacity-70"
          :class="view.current ? 'animate-spin inline-block' : ''"
          aria-hidden="true"
        >{{ marker }}</span>

        <span class="font-mono truncate" :title="headline">{{ headline }}</span>

        <span class="shrink-0 opacity-60 tabular-nums">· {{ elapsedLabel() }}</span>

        <span v-if="showCount" class="shrink-0 opacity-60">
          · {{ $t('chat.activityStrip.toolCount', { count: view.toolCount }) }}
        </span>

        <button
          v-if="ops.length > 0"
          type="button"
          class="ml-auto shrink-0 px-1 opacity-60 hover:opacity-100 transition-opacity"
          :aria-expanded="expanded"
          :title="expanded
            ? $t('chat.activityStrip.collapse')
            : $t('chat.activityStrip.expand')"
          :aria-label="expanded
            ? $t('chat.activityStrip.collapse')
            : $t('chat.activityStrip.expand')"
          @click="expanded = !expanded"
        >
          <span aria-hidden="true">{{ expanded ? '▾' : '▸' }}</span>
        </button>
      </div>

      <!-- Why the running op is slow, when there is one holding the
           headline. Own line rather than an appended clause: the wait text
           carries its own elapsed counter and would otherwise collide with
           the op's. -->
      <div
        v-if="waitingSubline"
        class="pl-5 opacity-60 truncate"
        :title="waitingSubline"
      >
        <span aria-hidden="true">⏳</span>
        <span class="ml-1">{{ waitingSubline }}</span>
      </div>
    </div>
  </div>
</template>
