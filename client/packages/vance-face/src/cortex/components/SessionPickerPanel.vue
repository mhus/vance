<script setup lang="ts">
/**
 * Compact session picker that mounts in the right-panel slot of
 * Cortex when no sessionId is bound. Lists the current project's
 * sessions; click on a row navigates to {@code cortex.html?sessionId=…}
 * with the project preserved. "+ New session" hands off to
 * {@code chat.html?project=…} where the recipe-modal lives — the new
 * session jumps back into Cortex via the existing chat → cortex link.
 */
import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { listSessions } from '@vance/shared';
import {
  AccentColor,
  SessionStatus,
  type SessionGroupDto,
  type SessionSummaryRichDto,
} from '@vance/generated';
import { VAlert, VButton, VInput } from '@/components';
import { useSessionGroups } from '@/composables/useSessionGroups';
import { useSessionGroupCollapse } from '@/composables/useSessionGroupCollapse';

const { t } = useI18n();

const props = defineProps<{
  projectId: string;
}>();

const projectIdRef = computed(() => props.projectId);

const sessions = ref<SessionSummaryRichDto[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const filter = ref('');
const showArchived = ref(false);

const filtered = computed<SessionSummaryRichDto[]>(() => {
  const needle = filter.value.trim().toLowerCase();
  if (!needle) return sessions.value;
  return sessions.value.filter((s) =>
    sessionTitle(s).toLowerCase().includes(needle),
  );
});

async function reload(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    sessions.value = await listSessions({
      projectId: props.projectId,
      includeArchived: showArchived.value,
    });
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('chat.picker.failedToLoadSessions');
    sessions.value = [];
  } finally {
    loading.value = false;
  }
}

// ─── Session groups (read-only grouped view) ───
// Membership lives on the groups, not the sessions; grouping is computed by
// joining `filtered` against each group's sessionIds. See planning/session-groups.md.

const sessionGroupsState = useSessionGroups();
const hasGroups = computed(() => sessionGroupsState.groups.value.length > 0);

const UNGROUPED_KEY = 'ungrouped';
const collapse = useSessionGroupCollapse(projectIdRef);

function groupKey(g: SessionGroupDto | null): string {
  return g ? g.name : UNGROUPED_KEY;
}

function isCollapsed(g: SessionGroupDto | null): boolean {
  if (filter.value.trim()) return false;
  return collapse.has(groupKey(g));
}

function toggleCollapsed(g: SessionGroupDto | null): void {
  collapse.toggle(groupKey(g));
}

interface SessionBlock {
  group: SessionGroupDto | null;
  sessions: SessionSummaryRichDto[];
}

const sessionBlocks = computed<SessionBlock[]>(() => {
  const groups = sessionGroupsState.groups.value;
  if (groups.length === 0) {
    return [{ group: null, sessions: filtered.value }];
  }
  const byId = new Map<string, SessionSummaryRichDto>();
  for (const s of filtered.value) byId.set(s.sessionId, s);

  const grouped = new Set<string>();
  const groupBlocks: SessionBlock[] = [];
  for (const g of groups) {
    const members: SessionSummaryRichDto[] = [];
    for (const sid of g.sessionIds) {
      const s = byId.get(sid);
      if (s) {
        members.push(s);
        grouped.add(sid);
      }
    }
    groupBlocks.push({ group: g, sessions: members });
  }
  const ungrouped = filtered.value.filter((s) => !grouped.has(s.sessionId));
  // "Ungrouped" leads, named groups follow (by sortIndex) — mirrors chat picker.
  return [{ group: null, sessions: ungrouped }, ...groupBlocks];
});

function sessionTitle(session: SessionSummaryRichDto): string {
  if (session.title && session.title.trim().length > 0) return session.title;
  if (session.firstUserMessage && session.firstUserMessage.trim().length > 0) {
    return session.firstUserMessage;
  }
  return t('chat.sessionHeader.untitled');
}

const COLOR_BORDER: Record<AccentColor, string> = {
  [AccentColor.SLATE]: 'border-l-slate-500',
  [AccentColor.RED]: 'border-l-red-500',
  [AccentColor.ORANGE]: 'border-l-orange-500',
  [AccentColor.AMBER]: 'border-l-amber-500',
  [AccentColor.GREEN]: 'border-l-green-500',
  [AccentColor.TEAL]: 'border-l-teal-500',
  [AccentColor.CYAN]: 'border-l-cyan-500',
  [AccentColor.BLUE]: 'border-l-blue-500',
  [AccentColor.INDIGO]: 'border-l-indigo-500',
  [AccentColor.PURPLE]: 'border-l-purple-500',
  [AccentColor.PINK]: 'border-l-pink-500',
  [AccentColor.ROSE]: 'border-l-rose-500',
};

function colorBorderClass(session: SessionSummaryRichDto): string {
  if (session.color === undefined) return 'border-l-base-300';
  return COLOR_BORDER[session.color] ?? 'border-l-base-300';
}

function toEpochMillis(d: Date | string | number | undefined): number {
  if (d === undefined || d === null) return 0;
  if (d instanceof Date) return d.getTime();
  if (typeof d === 'number') return d;
  const parsed = new Date(d).getTime();
  return Number.isFinite(parsed) ? parsed : 0;
}

function formatRelativeTime(value: Date | string | number | undefined): string {
  const epochMillis = toEpochMillis(value);
  if (!epochMillis) return '';
  const diffMs = Date.now() - epochMillis;
  const seconds = Math.floor(diffMs / 1000);
  if (seconds < 60) return t('chat.picker.relativeJustNow');
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return t('chat.picker.relativeMinutes', { n: minutes });
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return t('chat.picker.relativeHours', { n: hours });
  const days = Math.floor(hours / 24);
  if (days < 7) return t('chat.picker.relativeDays', { n: days });
  return new Date(epochMillis).toLocaleDateString();
}

function openSession(session: SessionSummaryRichDto): void {
  if (session.status === SessionStatus.ARCHIVED) return;
  const params = new URLSearchParams();
  params.set('sessionId', session.sessionId);
  params.set('project', props.projectId);
  window.location.href = `/cortex.html?${params.toString()}`;
}

function newSession(): void {
  const params = new URLSearchParams();
  params.set('project', props.projectId);
  window.location.href = `/chat.html?${params.toString()}`;
}

onMounted(() => {
  void reload();
  void sessionGroupsState.reload(props.projectId);
});

watch(() => props.projectId, (id) => {
  void reload();
  void sessionGroupsState.reload(id);
});

watch(showArchived, () => {
  void reload();
});
</script>

<template>
  <div class="h-full flex flex-col min-h-0 text-sm">
    <div class="flex items-center gap-1 px-2 py-1.5 border-b border-base-300 shrink-0">
      <span class="font-semibold flex-1">Sessions</span>
      <span class="opacity-50 text-xs">{{ filtered.length }}</span>
      <VButton
        size="sm"
        variant="ghost"
        :title="$t('chat.picker.newSession')"
        @click="newSession"
      >
        +
      </VButton>
    </div>

    <div class="px-2 py-1.5 border-b border-base-300 shrink-0 flex flex-col gap-1.5">
      <VInput
        v-model="filter"
        :placeholder="$t('chat.picker.sessionFilterPlaceholder')"
      />
      <label class="flex items-center gap-1.5 text-xs opacity-70 cursor-pointer">
        <input
          v-model="showArchived"
          type="checkbox"
          class="checkbox checkbox-xs"
        />
        {{ $t('chat.picker.showArchived') }}
      </label>
    </div>

    <div class="flex-1 min-h-0 overflow-y-auto">
      <VAlert v-if="error" variant="error" class="m-2">{{ error }}</VAlert>

      <div v-if="loading" class="p-3 text-xs opacity-60">
        {{ $t('chat.picker.sessionsLoading') }}
      </div>

      <div
        v-else-if="sessions.length === 0"
        class="p-3 text-xs opacity-60"
      >
        {{ $t('chat.picker.noSessions') }}
      </div>

      <div
        v-else-if="filtered.length === 0"
        class="p-3 text-xs opacity-60"
      >
        {{ $t('chat.picker.sessionFilterNoMatch', { filter }) }}
      </div>

      <template v-else>
        <div v-for="block in sessionBlocks" :key="block.group ? block.group.name : '__ungrouped__'">
          <!-- Group header — only when the project has groups (read-only). -->
          <div
            v-if="hasGroups"
            class="flex items-center gap-1.5 px-2 py-1 bg-base-200/50 border-b border-base-300
                   cursor-pointer select-none sticky top-0 z-10"
            @click="toggleCollapsed(block.group)"
          >
            <span class="opacity-60 w-3 text-center text-xs">
              {{ isCollapsed(block.group) ? '▸' : '▾' }}
            </span>
            <span class="font-semibold text-xs truncate flex-1">
              {{ block.group
                ? (block.group.title || block.group.name)
                : $t('chat.picker.groups.ungrouped') }}
            </span>
            <span class="opacity-50 text-xs">{{ block.sessions.length }}</span>
          </div>

          <ul v-show="!isCollapsed(block.group)" class="flex flex-col">
            <li
              v-for="session in block.sessions"
              :key="session.sessionId"
              class="border-b border-base-300 border-l-2 px-2 py-1.5 cursor-pointer
                     hover:bg-base-200 flex items-start gap-2"
              :class="[
                colorBorderClass(session),
                session.status === SessionStatus.ARCHIVED ? 'opacity-60' : '',
              ]"
              @click="openSession(session)"
            >
          <span class="shrink-0 w-5 text-center leading-none mt-0.5">
            <span v-if="session.icon">{{ session.icon }}</span>
            <span v-else class="opacity-30">💬</span>
          </span>
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-1 min-w-0">
              <span
                v-if="session.pinned"
                class="shrink-0 opacity-60"
                :title="$t('chat.sessionHeader.pinTooltip')"
              >📌</span>
              <span class="truncate">{{ sessionTitle(session) }}</span>
            </div>
            <div class="text-xs opacity-50 truncate">
              {{ formatRelativeTime(session.lastActivityAt) }}
            </div>
          </div>
            </li>
          </ul>
        </div>
      </template>
    </div>
  </div>
</template>
