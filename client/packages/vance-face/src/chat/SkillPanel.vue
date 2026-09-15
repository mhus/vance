<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { BrainWsApi } from '@vance/shared';
import { ProcessSkillCommand } from '@vance/generated';
import type {
  ActiveSkillRefDto,
  ProcessSkillRequest,
  ProcessSkillResponse,
  SkillSummaryDto,
} from '@vance/generated';
import { VAlert, VBadge, VButton, VEmptyState } from '@components/index';

interface Props {
  /** Live socket — the listing is a read-only {@code process-skill} LIST round-trip. */
  socket: BrainWsApi;
  /** Process name of the active chat session — skills are process-scoped. */
  sessionKey?: string;
}

const props = withDefaults(defineProps<Props>(), {
  sessionKey: undefined,
});

const emit = defineEmits<{
  /**
   * Fired when the user hits play — the host writes {@code /skill <name> }
   * into the composer. Not sent directly: the user may append skill
   * arguments (skills.md §2b) before submitting, and the composer stays
   * the single activation path.
   */
  (e: 'promptReady', prompt: string): void;
}>();

const { t } = useI18n();

const available = ref<SkillSummaryDto[]>([]);
const active = ref<ActiveSkillRefDto[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
/** Distinguishes "not loaded yet" from "loaded, nothing there". */
const loaded = ref(false);

/**
 * Fetches active + available skills via the same {@code process-skill}
 * WS round-trip the composer's {@code /skill list} uses. Pull-based by
 * design — active skills mutate during turns (triggers, one-shot
 * expiry) and there is no push event for it, so the refresh button and
 * session/tab switches are the invalidation points.
 */
async function refresh(): Promise<void> {
  if (!props.sessionKey) return;
  loading.value = true;
  error.value = null;
  try {
    const reply = await props.socket.send<ProcessSkillRequest, ProcessSkillResponse>(
      'process-skill',
      { processName: props.sessionKey, command: ProcessSkillCommand.LIST, oneShot: false },
    );
    available.value = reply.availableSkills ?? [];
    active.value = reply.activeSkills ?? [];
    loaded.value = true;
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err);
  } finally {
    loading.value = false;
  }
}

/** One flat, sorted row set — active ones flagged, active-but-unlisted kept. */
const rows = computed(() => {
  const activeByName = new Map(active.value.map((a) => [a.name, a]));
  const seen = new Set<string>();
  const out: { summary: SkillSummaryDto; ref: ActiveSkillRefDto | null }[] = [];
  for (const s of available.value) {
    seen.add(s.name);
    out.push({ summary: s, ref: activeByName.get(s.name) ?? null });
  }
  // An active skill can drop out of the available list (e.g. disabled in
  // its source while still running) — keep it visible.
  for (const a of active.value) {
    if (!seen.has(a.name)) {
      out.push({
        summary: {
          name: a.name,
          title: a.name,
          description: '',
          version: '',
          tags: [],
          enabled: true,
          source: a.resolvedFromScope,
        },
        ref: a,
      });
    }
  }
  out.sort((a, b) => a.summary.title.localeCompare(b.summary.title));
  return out;
});

function play(name: string): void {
  // Trailing space: invite skill arguments without a second click.
  emit('promptReady', `/skill ${name} `);
}

onMounted(refresh);
// The listing is bound to one process — a session switch invalidates it.
watch(() => props.sessionKey, refresh);
</script>

<template>
  <div class="p-3 flex flex-col gap-3 min-h-0">
    <div class="flex items-center justify-between px-1">
      <div class="text-xs uppercase tracking-wide opacity-60 font-semibold">
        {{ t('chat.skills.title') }}
      </div>
      <VButton
        variant="ghost"
        size="sm"
        :loading="loading"
        :disabled="!sessionKey"
        :title="t('chat.skills.refresh')"
        @click="refresh"
      >
        ⟳
      </VButton>
    </div>

    <VAlert v-if="error" variant="error">
      {{ error }}
    </VAlert>

    <VEmptyState
      v-else-if="!sessionKey"
      :headline="t('chat.skills.noProcessHeadline')"
      :body="t('chat.skills.noProcessBody')"
    />

    <VEmptyState
      v-else-if="loaded && rows.length === 0"
      :headline="t('chat.skills.emptyHeadline')"
      :body="t('chat.skills.emptyBody')"
    />

    <div v-else class="flex flex-col gap-1.5">
      <div
        v-for="row in rows"
        :key="row.summary.name"
        class="bg-base-200 rounded px-2.5 py-2 text-left text-sm"
        :class="{ 'opacity-50': !row.summary.enabled }"
      >
        <div class="flex items-center gap-2">
          <div class="flex-1 min-w-0 flex flex-col gap-1">
            <div class="flex items-center gap-1.5 flex-wrap">
              <span class="font-semibold truncate">{{ row.summary.title }}</span>
              <VBadge v-if="row.ref" variant="success" size="xs" outline>
                {{ t('chat.skills.activeBadge') }}
              </VBadge>
              <VBadge v-if="row.ref?.oneShot" variant="info" size="xs" outline>
                {{ t('chat.skills.oneShotBadge') }}
              </VBadge>
              <VBadge variant="neutral" size="xs" outline>
                {{ row.summary.source }}
              </VBadge>
            </div>
            <div class="text-[11px] opacity-50 font-mono truncate">{{ row.summary.name }}</div>
            <div v-if="row.summary.description" class="text-xs opacity-70 line-clamp-2">
              {{ row.summary.description }}
            </div>
          </div>
          <VButton
            v-if="row.summary.enabled"
            variant="ghost"
            size="sm"
            :title="t('chat.skills.play')"
            @click="play(row.summary.name)"
          >
            ▶
          </VButton>
        </div>
      </div>
    </div>
  </div>
</template>
