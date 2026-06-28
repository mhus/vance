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
  type SessionSummaryRichDto,
} from '@vance/generated';
import { VAlert, VButton, VInput } from '@/components';

const { t } = useI18n();

const props = defineProps<{
  projectId: string;
}>();

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
});

watch(() => props.projectId, () => {
  void reload();
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

      <ul v-else class="flex flex-col">
        <li
          v-for="session in filtered"
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
  </div>
</template>
