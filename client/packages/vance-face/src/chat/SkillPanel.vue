<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { BrainWsApi } from '@vance/shared';
import { ProcessSkillCommand, SkillTriggerType } from '@vance/generated';
import type {
  ActiveSkillRefDto,
  ProcessSkillRequest,
  ProcessSkillResponse,
  SkillCategoryDto,
  SkillSummaryDto,
} from '@vance/generated';
import { VAlert, VBadge, VButton, VEmptyState, VModal } from '@components/index';
import { groupCategorized } from '@/util/categoryGroups';

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

const { t, locale } = useI18n();

const available = ref<SkillSummaryDto[]>([]);
/** Category metadata from the LIST reply — order + localised labels (skills.md §4f). */
const categories = ref<SkillCategoryDto[]>([]);
const active = ref<ActiveSkillRefDto[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
/** Name of the skill whose CLEAR round-trip is in flight — drives the button spinner. */
const clearing = ref<string | null>(null);
/** Distinguishes "not loaded yet" from "loaded, nothing there". */
const loaded = ref(false);
/** Skill whose detail modal is open — carries the full LIST metadata (skills.md §2). */
const detail = ref<SkillSummaryDto | null>(null);

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
    categories.value = reply.categories ?? [];
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
          triggers: [],
          lifecycle: '',
          tools: [],
          manualPaths: [],
          arguments: [],
          referenceDocs: [],
          scripts: [],
          activate: [],
          deactivate: [],
          enabled: true,
          source: a.resolvedFromScope,
        },
        ref: a,
      });
    }
  }
  // No local sort: the server order is category-group-then-title
  // (SkillCategoriesService) and grouping trusts it (skills.md §4f).
  return out;
});

/**
 * Rows grouped by category for the panel rendering: first-occurrence
 * order over the server-sorted list (skills.md §4f). The category key
 * lives on the summary; the synthetic active-but-unlisted rows carry
 * none and land in the trailing "no category" group.
 */
const groups = computed(() => groupCategorized(
  rows.value,
  (row) => row.summary.category,
  categories.value,
  locale.value,
  t('chat.skills.categoryOther'),
));

function play(name: string): void {
  // Trailing space: invite skill arguments without a second click.
  emit('promptReady', `/skill ${name} `);
}

/**
 * Deactivates one active skill directly via a {@code process-skill} CLEAR
 * round-trip. Unlike activation this never goes through the composer:
 * CLEAR takes no arguments to append, and the composer echo would be pure
 * noise. The reply carries the post-mutation {@code activeSkills}, so the
 * badge state updates without a follow-up LIST.
 */
async function clear(name: string): Promise<void> {
  if (!props.sessionKey) return;
  clearing.value = name;
  error.value = null;
  try {
    const reply = await props.socket.send<ProcessSkillRequest, ProcessSkillResponse>(
      'process-skill',
      { processName: props.sessionKey, command: ProcessSkillCommand.CLEAR, skillName: name, oneShot: false },
    );
    active.value = reply.activeSkills ?? [];
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err);
  } finally {
    clearing.value = null;
  }
}

/**
 * A {@code lifecycle: shot} skill never becomes active — activation
 * fires it once as a prompt/config macro (skills.md §2a). The badge is
 * the row's only signal that ▶ behaves differently here.
 */
function isMacro(summary: SkillSummaryDto): boolean {
  return summary.lifecycle === 'shot';
}

/**
 * Flattens a skill's auto-triggers into display tokens — keywords as-is,
 * patterns as the raw regex. What Arthur matches against when it picks a
 * skill implicitly (skills.md §4c), so the user can predict the auto
 * activation before it happens.
 */
function triggerTokens(summary: SkillSummaryDto): string[] {
  const out: string[] = [];
  for (const trigger of summary.triggers ?? []) {
    if (trigger.type === SkillTriggerType.KEYWORDS) {
      out.push(...(trigger.keywords ?? []));
    } else if (trigger.pattern) {
      out.push(trigger.pattern);
    }
  }
  return out;
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
      <template
        v-for="group in groups"
        :key="group.key ?? '__other__'"
      >
        <div
          v-if="group.label"
          class="text-xs font-semibold uppercase tracking-wide opacity-60 px-1 pt-2"
        >
          {{ group.label }}
        </div>
        <div
          v-for="row in group.items"
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
              <VBadge v-if="isMacro(row.summary)" variant="warning" size="xs" outline>
                {{ t('chat.skills.macroBadge') }}
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
            v-if="row.summary.enabled && !row.ref"
            variant="ghost"
            size="sm"
            :title="t('chat.skills.play')"
            @click="play(row.summary.name)"
          >
            ▶
          </VButton>
          <VButton
            v-if="row.ref"
            variant="ghost"
            size="sm"
            :loading="clearing === row.summary.name"
            :disabled="row.ref.fromRecipe"
            :title="row.ref.fromRecipe ? t('chat.skills.recipeBoundTitle') : t('chat.skills.clear')"
            @click="clear(row.summary.name)"
          >
            ✕
          </VButton>
          <VButton
            variant="ghost"
            size="sm"
            :title="t('chat.skills.info')"
            @click="detail = row.summary"
          >
            ℹ
          </VButton>
        </div>
      </div>
      </template>
    </div>

    <VModal
      :model-value="detail !== null"
      :title="detail ? `${detail.title}${detail.version ? ` v${detail.version}` : ''}` : ''"
      @update:model-value="(v: boolean) => { if (!v) detail = null; }"
    >
      <div v-if="detail" class="flex flex-col gap-4 text-sm">
        <div class="flex items-center gap-1.5 flex-wrap">
          <VBadge variant="neutral" size="xs" outline>{{ detail.name }}</VBadge>
          <VBadge variant="neutral" size="xs" outline>{{ detail.source }}</VBadge>
          <VBadge :variant="isMacro(detail) ? 'warning' : 'info'" size="xs" outline>
            {{ isMacro(detail) ? t('chat.skills.macroBadge') : t('chat.skills.stickyBadge') }}
          </VBadge>
        </div>
        <div v-if="detail.description" class="opacity-80">{{ detail.description }}</div>
        <div class="text-xs opacity-60">
          {{ isMacro(detail) ? t('chat.skills.lifecycleShotHint') : t('chat.skills.lifecycleStickyHint') }}
        </div>

        <div v-if="triggerTokens(detail).length">
          <div class="text-xs uppercase tracking-wide opacity-60 font-semibold mb-1">
            {{ t('chat.skills.autoTrigger') }}
          </div>
          <div class="flex flex-wrap gap-1">
            <template v-for="(trigger, ti) in detail.triggers ?? []" :key="ti">
              <VBadge
                v-for="keyword in trigger.keywords ?? []"
                :key="keyword"
                variant="neutral"
                size="xs"
                outline
              >
                {{ keyword }}
              </VBadge>
              <span v-if="trigger.pattern" class="font-mono text-xs opacity-70">
                /{{ trigger.pattern }}/
              </span>
            </template>
          </div>
          <div class="text-[11px] opacity-50 mt-1">{{ t('chat.skills.autoTriggerTitle') }}</div>
        </div>

        <div v-if="(detail.arguments ?? []).length">
          <div class="text-xs uppercase tracking-wide opacity-60 font-semibold mb-1">
            {{ t('chat.skills.argumentsLabel') }}
          </div>
          <div class="flex flex-col gap-1.5">
            <div v-for="arg in detail.arguments" :key="arg.name" class="flex flex-col gap-0.5">
              <div class="flex items-center gap-1.5 flex-wrap">
                <span class="font-mono text-xs">{{ arg.name }}</span>
                <VBadge variant="neutral" size="xs" outline>{{ arg.type }}</VBadge>
                <VBadge v-if="arg.required" variant="warning" size="xs" outline>
                  {{ t('chat.skills.requiredLabel') }}
                </VBadge>
              </div>
              <div v-if="arg.description" class="text-xs opacity-60">{{ arg.description }}</div>
            </div>
          </div>
          <div class="text-[11px] opacity-50 mt-1">{{ t('chat.skills.argumentsHint') }}</div>
        </div>

        <div v-if="(detail.tools ?? []).length">
          <div class="text-xs uppercase tracking-wide opacity-60 font-semibold mb-1">
            {{ t('chat.skills.toolsLabel') }}
          </div>
          <div class="flex flex-wrap gap-1">
            <span v-for="tool in detail.tools" :key="tool" class="font-mono text-xs opacity-70">
              {{ tool }}
            </span>
          </div>
        </div>

        <div v-if="(detail.manualPaths ?? []).length">
          <div class="text-xs uppercase tracking-wide opacity-60 font-semibold mb-1">
            {{ t('chat.skills.manualPathsLabel') }}
          </div>
          <div class="flex flex-col gap-0.5">
            <span v-for="path in detail.manualPaths" :key="path" class="font-mono text-xs opacity-70">
              {{ path }}
            </span>
          </div>
        </div>

        <div v-if="(detail.referenceDocs ?? []).length">
          <div class="text-xs uppercase tracking-wide opacity-60 font-semibold mb-1">
            {{ t('chat.skills.referenceDocsLabel') }}
          </div>
          <div class="flex flex-col gap-1">
            <div v-for="doc in detail.referenceDocs" :key="doc.title" class="flex flex-col gap-0.5">
              <div class="flex items-center gap-1.5 flex-wrap">
                <span>{{ doc.title }}</span>
                <VBadge variant="neutral" size="xs" outline>
                  {{ doc.loadMode === 'ON_DEMAND' ? t('chat.skills.onDemandLabel') : t('chat.skills.inlineLabel') }}
                </VBadge>
              </div>
              <div v-if="doc.summary" class="text-xs opacity-60">{{ doc.summary }}</div>
            </div>
          </div>
        </div>

        <div v-if="(detail.scripts ?? []).length">
          <div class="text-xs uppercase tracking-wide opacity-60 font-semibold mb-1">
            {{ t('chat.skills.scriptsLabel') }}
          </div>
          <div class="flex flex-col gap-1">
            <div v-for="script in detail.scripts" :key="script.name" class="flex flex-col gap-0.5">
              <div class="flex items-center gap-1.5 flex-wrap">
                <span class="font-mono text-xs">skill_{{ detail.name }}__{{ script.name }}</span>
                <VBadge variant="neutral" size="xs" outline>{{ script.target }}</VBadge>
              </div>
              <div v-if="script.description" class="text-xs opacity-60">{{ script.description }}</div>
            </div>
          </div>
        </div>

        <div v-if="(detail.activate ?? []).length">
          <div class="text-xs uppercase tracking-wide opacity-60 font-semibold mb-1">
            {{ t('chat.skills.activateLabel') }}
          </div>
          <div class="flex flex-col gap-0.5">
            <span v-for="(cmd, i) in detail.activate" :key="i" class="font-mono text-xs opacity-70">
              {{ cmd }}
            </span>
          </div>
        </div>

        <div v-if="(detail.deactivate ?? []).length">
          <div class="text-xs uppercase tracking-wide opacity-60 font-semibold mb-1">
            {{ t('chat.skills.deactivateLabel') }}
          </div>
          <div class="flex flex-col gap-0.5">
            <span v-for="(cmd, i) in detail.deactivate" :key="i" class="font-mono text-xs opacity-70">
              {{ cmd }}
            </span>
          </div>
        </div>
      </div>
    </VModal>
  </div>
</template>
