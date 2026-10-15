<script setup lang="ts">
/**
 * Compact session picker for a right-panel slot: lists one project's
 * sessions, and a click emits {@code open-session} so the host decides what
 * opening one means (Cortex navigates carrying its open tabs, the inbox
 * swaps the panel in place).
 *
 * <p>The "New session" button opens a recipe-selection modal inline and
 * bootstraps via WebSocket rather than sending the user to /chat —
 * whatever the host had on screen survives.
 */
import { computed, onMounted, ref, watch } from 'vue';
import {
  listProjectRecipes,
  listSessions,
  WebSocketRequestError,
} from '@vance/shared';
import {
  AccentColor,
  SessionStatus,
  type RecipeCategoryDto,
  type RecipeListedDto,
  type SessionBootstrapRequest,
  type SessionGroupDto,
  type SessionSummaryRichDto,
} from '@vance/generated';
import {
  VAlert,
  VButton,
  VCheckbox,
  VGroup,
  VGroupRow,
  VIcon,
  VIconButton,
  VInput,
  VModal,
} from '@/components';
import { useSessionGroups } from '@/composables/useSessionGroups';
import { useSessionGroupCollapse } from '@/composables/useSessionGroupCollapse';
import { filterListedRecipes, groupListedRecipes } from '@/recipes/recipePickerGroups';
import { markBound, sendSessionBootstrap, useWsConnection } from '@/ws/wsConnectionStore';
import { navigateTo } from '@/platform/navigate';
import { useI18n } from 'vue-i18n';

const { t, locale } = useI18n();

const props = defineProps<{
  projectId: string;
}>();

const emit = defineEmits<{
  (e: 'open-session', sessionId: string): void;
}>();

const projectIdRef = computed(() => props.projectId);

const sessions = ref<SessionSummaryRichDto[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const filter = ref('');
const showArchived = ref(false);

// ─── Inline recipe picker + session bootstrap ───
// Mirrors PickerView.vue's recipe modal so the add button stays in
// /cortex instead of redirecting to /chat (which would wipe
// the user's open tabs). On success we emit `open-session` so
// EditorApp's enterSession() navigates to /cortex?sessionId=…
// while carrying the open tabs across.
const { socket } = useWsConnection();

const recipeModalOpen = ref(false);
const recipeOptions = ref<RecipeListedDto[]>([]);
const recipeCategories = ref<RecipeCategoryDto[]>([]);
const recipeFilter = ref('');
const recipesLoading = ref(false);
const recipesError = ref<string | null>(null);
const bootstrapping = ref(false);
const bootstrapError = ref<string | null>(null);

/**
 * Recipe entries filtered by the modal's search field, then grouped:
 * category groups with resolved labels, "no category" entries trailing.
 * See {@link filterListedRecipes} / {@link groupListedRecipes}.
 */
const recipeGroups = computed(() => groupListedRecipes(
  filterListedRecipes(recipeOptions.value, recipeFilter.value),
  recipeCategories.value,
  locale.value,
  t('chat.picker.recipeCategoryOther'),
));

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
  [AccentColor.SLATE]: 'border-l-(--v-content-grey-fg)',
  [AccentColor.RED]: 'border-l-(--v-content-pink-fg)',
  [AccentColor.ORANGE]: 'border-l-(--v-content-orange-fg)',
  [AccentColor.AMBER]: 'border-l-(--v-content-yellow-fg)',
  [AccentColor.GREEN]: 'border-l-(--v-content-green-fg)',
  [AccentColor.TEAL]: 'border-l-(--v-content-teal-fg)',
  [AccentColor.CYAN]: 'border-l-(--v-content-teal-fg)',
  [AccentColor.BLUE]: 'border-l-(--v-content-purple-fg)',
  [AccentColor.INDIGO]: 'border-l-(--v-content-purple-fg)',
  [AccentColor.PURPLE]: 'border-l-(--v-content-purple-fg)',
  [AccentColor.PINK]: 'border-l-(--v-content-pink-fg)',
  [AccentColor.ROSE]: 'border-l-(--v-content-pink-fg)',
};

function colorBorderClass(session: SessionSummaryRichDto): string {
  if (session.color === undefined) return 'border-l-(--v-separator)';
  return COLOR_BORDER[session.color] ?? 'border-l-(--v-separator)';
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
  // Let the host build the target URL — it carries the open tabs across.
  emit('open-session', session.sessionId);
}

/**
 * Open the recipe-selection modal inline. Fetches the project's listed
 * recipes (same call as PickerView). The user picks a recipe (or
 * "Default") to trigger {@link bootstrapNew}.
 */
async function openRecipeModal(): Promise<void> {
  bootstrapError.value = null;
  recipesError.value = null;
  // Fresh open, fresh filter — a stale needle from the last session would
  // look like missing recipes.
  recipeFilter.value = '';
  recipeModalOpen.value = true;
  recipesLoading.value = true;
  try {
    const response = await listProjectRecipes(props.projectId);
    recipeOptions.value = response.recipes;
    recipeCategories.value = response.categories;
  } catch (e) {
    recipesError.value = describeError(e, t('chat.picker.recipeLoadFailed'));
    recipeOptions.value = [];
    recipeCategories.value = [];
  } finally {
    recipesLoading.value = false;
  }
}

/**
 * Bootstrap a fresh session via the `session-bootstrap` WebSocket call.
 * On success, emit `open-session` with the new sessionId so EditorApp's
 * {@code enterSession()} navigates to `/cortex?sessionId=…` (carrying
 * the open tabs). On failure, surface the error inline — only fall back
 * to the /chat redirect if the socket itself is unavailable.
 */
async function bootstrapNew(chatRecipe: string | null): Promise<void> {
  if (bootstrapping.value) return;
  const sock = socket.value;
  if (!sock) {
    // Socket not ready — fall back to the legacy /chat flow rather
    // than silently failing. This is the only redirect path left.
    const params = new URLSearchParams();
    params.set('project', props.projectId);
    navigateTo(`/chat?${params.toString()}`);
    return;
  }
  bootstrapping.value = true;
  bootstrapError.value = null;
  try {
    const payload: SessionBootstrapRequest = {
      projectId: props.projectId,
      processes: [],
      // Creating a fresh session — takeover only applies to resume.
      takeover: false,
    };
    if (chatRecipe) payload.chatRecipe = chatRecipe;
    const response = await sendSessionBootstrap(sock, payload);
    recipeModalOpen.value = false;
    // The bootstrap call bound the session to this WebSocket on the
    // server. Tell the WS store so it knows the session is already
    // bound — without this, the store would send a fresh session-resume
    // after the page switches, which collides with the bootstrap-time
    // bind and triggers the "session already open elsewhere" conflict
    // dialog (the very bug we're fixing).
    markBound(response.sessionId);
    emit('open-session', response.sessionId);
  } catch (e) {
    bootstrapError.value = describeError(e, t('chat.picker.failedToStartSession'));
  } finally {
    bootstrapping.value = false;
  }
}

function describeError(e: unknown, fallback: string): string {
  if (e instanceof WebSocketRequestError) {
    return `${e.message} (code ${e.errorCode})`;
  }
  return e instanceof Error ? e.message : fallback;
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
    <div class="flex items-center gap-1 px-2 py-1.5 border-b border-(--v-separator) shrink-0">
      <span class="font-semibold flex-1">{{ $t('shell.sessions') }}</span>
      <span class="text-xs text-(--v-label-tertiary)">{{ filtered.length }}</span>
      <VIconButton
        icon="add"
        :label="$t('chat.picker.newSession')"
        size="sm"
        @click="openRecipeModal"
      />
    </div>

    <div class="px-2 py-1.5 border-b border-(--v-separator) shrink-0 flex flex-col gap-1.5">
      <VInput
        v-model="filter"
        :placeholder="$t('chat.picker.sessionFilterPlaceholder')"
      />
      <VCheckbox v-model="showArchived" :label="$t('chat.picker.showArchived')" />
    </div>

    <div class="flex-1 min-h-0 overflow-y-auto">
      <VAlert v-if="error" variant="error" class="m-2">{{ error }}</VAlert>

      <div v-if="loading" class="p-3 text-xs text-(--v-label-secondary)">
        {{ $t('chat.picker.sessionsLoading') }}
      </div>

      <div
        v-else-if="sessions.length === 0"
        class="p-3 text-xs text-(--v-label-secondary)"
      >
        {{ $t('chat.picker.noSessions') }}
      </div>

      <div
        v-else-if="filtered.length === 0"
        class="p-3 text-xs text-(--v-label-secondary)"
      >
        {{ $t('chat.picker.sessionFilterNoMatch', { filter }) }}
      </div>

      <template v-else>
        <div v-for="block in sessionBlocks" :key="block.group ? block.group.name : '__ungrouped__'">
          <!-- Group header — only when the project has groups (read-only). -->
          <div
            v-if="hasGroups"
            class="flex items-center gap-1.5 px-2 py-1 bg-(--v-bg) border-b border-(--v-separator)
                   cursor-pointer select-none sticky top-0 z-10"
            @click="toggleCollapsed(block.group)"
          >
            <VIcon
              :name="isCollapsed(block.group) ? 'forward' : 'expand'"
              class="text-(--v-label-secondary)"
            />
            <span class="font-semibold text-xs truncate flex-1">
              {{ block.group
                ? (block.group.title || block.group.name)
                : $t('chat.picker.groups.ungrouped') }}
            </span>
            <span class="text-xs text-(--v-label-tertiary)">{{ block.sessions.length }}</span>
          </div>

          <ul v-show="!isCollapsed(block.group)" class="flex flex-col">
            <li
              v-for="session in block.sessions"
              :key="session.sessionId"
              class="border-b border-(--v-separator) border-l-2 px-2 py-1.5 cursor-pointer
                     hover:bg-(--v-fill) flex items-start gap-2"
              :class="[
                colorBorderClass(session),
                session.status === SessionStatus.ARCHIVED ? 'text-(--v-label-secondary)' : '',
              ]"
              @click="openSession(session)"
            >
              <span class="shrink-0 w-5 text-center leading-none mt-0.5">
                <span v-if="session.icon">{{ session.icon }}</span>
                <VIcon v-else name="chat" class="inline-block text-(--v-label-tertiary)" />
              </span>
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-1 min-w-0">
                  <span
                    v-if="session.pinned"
                    class="shrink-0 inline-flex text-(--v-label-secondary)"
                    :title="$t('chat.sessionHeader.pinTooltip')"
                  ><VIcon name="pin" /></span>
                  <span class="truncate">{{ sessionTitle(session) }}</span>
                </div>
                <div class="text-xs text-(--v-label-tertiary) truncate">
                  {{ formatRelativeTime(session.lastActivityAt) }}
                </div>
              </div>
            </li>
          </ul>
        </div>
      </template>
    </div>

    <!-- Recipe picker — inline modal (mirrors PickerView.vue) -->
    <VModal
      v-model="recipeModalOpen"
      :title="$t('chat.picker.recipeModalTitle')"
      :description="$t('chat.picker.recipeModalIntro')"
    >
      <div class="flex flex-col gap-4">
        <VInput
          v-model="recipeFilter"
          :placeholder="$t('chat.picker.recipeFilterPlaceholder')"
        />

        <VAlert v-if="recipesError" variant="error">{{ recipesError }}</VAlert>
        <VAlert v-if="bootstrapError" variant="error">{{ bootstrapError }}</VAlert>

        <VGroup>
          <VGroupRow
            navigates
            :label="$t('chat.picker.recipeDefaultName')"
            :description="$t('chat.picker.recipeDefaultDescription')"
            @click="bootstrapNew(null)"
          />
        </VGroup>

        <p v-if="recipesLoading" class="text-sm text-(--v-label-secondary)">
          {{ $t('chat.picker.sessionsLoading') }}
        </p>

        <p
          v-if="recipeFilter.trim() && !recipesLoading && recipeGroups.length === 0"
          class="text-sm text-(--v-label-secondary)"
        >
          {{ $t('chat.picker.recipeFilterNoMatch', { filter: recipeFilter.trim() }) }}
        </p>

        <VGroup
          v-for="group in recipeGroups"
          :key="group.key ?? '__other__'"
          :label="group.label || undefined"
        >
          <VGroupRow
            v-for="recipe in group.recipes"
            :key="recipe.name"
            navigates
            :label="recipe.title || recipe.name"
            :meta="recipe.title ? recipe.name : undefined"
            :description="recipe.description ?? undefined"
            @click="bootstrapNew(recipe.name)"
          />
        </VGroup>
      </div>
      <template #actions>
        <VButton variant="secondary" :disabled="bootstrapping" @click="recipeModalOpen = false">
          {{ $t('common.cancel') }}
        </VButton>
      </template>
    </VModal>
  </div>
</template>
