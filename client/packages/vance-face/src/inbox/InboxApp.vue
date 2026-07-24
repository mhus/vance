<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  EditorShell,
  type FocusZone,
  MarkdownView,
  VAlert,
  VButton,
  VCard,
  VCheckbox,
  VEmptyState,
  VInput,
  VModal,
  VSelect,
  VTextarea,
} from '@/components';
import { useInbox, type AssignedToFilter, type InboxFilter } from '@/composables/useInbox';
import { useTeams } from '@/composables/useTeams';
import { getUsername } from '@vance/shared';
import { VBadge } from '@/components';
import { setDocumentDraft } from '@/platform';
import {
  AnswerOutcome,
  Criticality,
  InboxItemStatus,
  InboxItemType,
  type InboxItemDto,
} from '@vance/generated';

const { t } = useI18n();
const inbox = useInbox();
const teamsState = useTeams();

const currentUser = getUsername() ?? 'unknown';

// ─────── Sidebar selection ───────
// One radio-like choice across the whole sidebar:
//   { kind: 'inbox', tag?: string }   — own pending/answered with optional tag filter
//   { kind: 'archive' }               — own archived items
//   { kind: 'team', teamName }        — items assigned to other team members
type SidebarSelection =
  | { kind: 'inbox'; tag: string | null }
  | { kind: 'archive' }
  | { kind: 'team'; teamName: string };

const selection = ref<SidebarSelection>({ kind: 'inbox', tag: null });

/**
 * Two-way bound focus zone for {@code <EditorShell>}'s focus model.
 * Local writes (e.g. on sidebar-nav click) shift the focus directly;
 * EditorShell still drives reads from its own pointer/focus/escape
 * listeners.
 */
const focusZone = ref<FocusZone>('main');

function isSelected(other: SidebarSelection): boolean {
  const s = selection.value;
  if (s.kind !== other.kind) return false;
  if (s.kind === 'inbox') return s.tag === (other as Extract<SidebarSelection, { kind: 'inbox' }>).tag;
  if (s.kind === 'team') return s.teamName === (other as Extract<SidebarSelection, { kind: 'team' }>).teamName;
  return true;
}

function selectInbox(tag: string | null = null): void {
  selection.value = { kind: 'inbox', tag };
}

function selectArchive(): void {
  selection.value = { kind: 'archive' };
}

function selectTeam(teamName: string): void {
  selection.value = { kind: 'team', teamName };
}

// Translate sidebar selection → backend filter.
function selectionToFilter(s: SidebarSelection): InboxFilter {
  if (s.kind === 'inbox') {
    return {
      assignedTo: { kind: 'self' },
      status: InboxItemStatus.PENDING,
      tag: s.tag,
    };
  }
  if (s.kind === 'archive') {
    return {
      assignedTo: { kind: 'self' },
      status: InboxItemStatus.ARCHIVED,
      tag: null,
    };
  }
  // team
  return {
    assignedTo: { kind: 'team', teamName: s.teamName } as AssignedToFilter,
    status: null,
    tag: null,
  };
}

// ─────── URL state ───────
//
// `?item=<id>` carries the master-detail mode: empty = list view,
// present = detail view. Push on selection change so the browser
// back-button takes the user from detail back to the list. Read on
// mount and on popstate to keep state and URL in sync.

function readItemFromUrl(): string | null {
  return new URLSearchParams(window.location.search).get('item');
}

function pushItemToUrl(id: string | null): void {
  const url = new URL(window.location.href);
  if (id) url.searchParams.set('item', id);
  else url.searchParams.delete('item');
  if (url.toString() !== window.location.href) {
    window.history.pushState(null, '', url.toString());
  }
}

async function onPopstate(): Promise<void> {
  const id = readItemFromUrl();
  if (id && inbox.selected.value?.id !== id) {
    await inbox.loadOne(id);
  } else if (!id && inbox.selected.value) {
    inbox.clearSelection();
  }
}

// Single source of truth: any time `inbox.selected` changes, mirror
// that into the URL. Includes both user-action paths (openItem, the
// close-button) and the filter-change watcher below that calls
// clearSelection.
watch(() => inbox.selected.value?.id ?? null, (id) => {
  pushItemToUrl(id);
});

// ─────── Lifecycle ───────
onMounted(async () => {
  await Promise.all([teamsState.reload(), inbox.loadTags()]);
  await inbox.loadList(selectionToFilter(selection.value));
  // Deep-link restore: if the URL points at an item, load it.
  const initial = readItemFromUrl();
  if (initial) {
    await inbox.loadOne(initial);
  }
  window.addEventListener('popstate', onPopstate);
});

onBeforeUnmount(() => {
  window.removeEventListener('popstate', onPopstate);
});

watch(selection, async (next) => {
  inbox.clearSelection();
  await inbox.loadList(selectionToFilter(next));
}, { deep: true });

// ─────── Item open / close ───────
async function openItem(item: InboxItemDto): Promise<void> {
  if (!item.id) return;
  await inbox.loadOne(item.id);
}

function closeItem(): void {
  inbox.clearSelection();
}

/**
 * Human label for the current list view — drives the sub-header.
 * Mirrors what {@link breadcrumbs} renders in the topbar, just
 * without the leading project name.
 */
const viewLabel = computed<string>(() => {
  const s = selection.value;
  if (s.kind === 'archive') return t('inbox.sidebar.archive');
  if (s.kind === 'team') return t('inbox.breadcrumbTeam', { team: s.teamName });
  if (s.kind === 'inbox' && s.tag) return '#' + s.tag;
  return t('inbox.sidebar.inbox');
});

function formatTimestamp(value?: Date | string | null): string {
  if (!value) return '';
  const d = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(d.getTime())) return String(value);
  return d.toLocaleString();
}

function shortPreview(text: string | null | undefined, max = 100): string {
  if (!text) return '';
  return text.length > max ? text.substring(0, max) + '…' : text;
}

// ─────── Answer flows ───────
const feedbackText = ref('');
const reasonText = ref('');
const submitting = ref(false);

watch(() => inbox.selected.value?.id, () => {
  feedbackText.value = '';
  reasonText.value = '';
});

async function submitApproval(approved: boolean): Promise<void> {
  const sel = inbox.selected.value;
  if (!sel?.id) return;
  submitting.value = true;
  try {
    await inbox.answer(sel.id, AnswerOutcome.DECIDED, { approved });
  } finally {
    submitting.value = false;
  }
}

async function submitDecision(chosen: unknown): Promise<void> {
  const sel = inbox.selected.value;
  if (!sel?.id) return;
  submitting.value = true;
  try {
    await inbox.answer(sel.id, AnswerOutcome.DECIDED, { chosen });
  } finally {
    submitting.value = false;
  }
}

async function submitFeedback(): Promise<void> {
  const sel = inbox.selected.value;
  if (!sel?.id) return;
  if (!feedbackText.value.trim()) return;
  submitting.value = true;
  try {
    await inbox.answer(sel.id, AnswerOutcome.DECIDED, { text: feedbackText.value.trim() });
  } finally {
    submitting.value = false;
  }
}

async function submitInsufficientInfo(): Promise<void> {
  const sel = inbox.selected.value;
  if (!sel?.id) return;
  submitting.value = true;
  try {
    await inbox.answer(sel.id, AnswerOutcome.INSUFFICIENT_INFO, null, reasonText.value.trim() || null);
  } finally {
    submitting.value = false;
  }
}

async function submitUndecidable(): Promise<void> {
  const sel = inbox.selected.value;
  if (!sel?.id) return;
  submitting.value = true;
  try {
    await inbox.answer(sel.id, AnswerOutcome.UNDECIDABLE, null, reasonText.value.trim() || null);
  } finally {
    submitting.value = false;
  }
}

async function archiveItem(): Promise<void> {
  const sel = inbox.selected.value;
  if (!sel?.id) return;
  submitting.value = true;
  try {
    await inbox.archive(sel.id);
  } finally {
    submitting.value = false;
  }
}

async function unarchiveItem(): Promise<void> {
  const sel = inbox.selected.value;
  if (!sel?.id) return;
  submitting.value = true;
  try {
    await inbox.unarchive(sel.id);
  } finally {
    submitting.value = false;
  }
}

// ─── Bulk archive by type ───────────────────────────────────────────────
//
// Lets the user clear out the noise items (output text, output image,
// feedback, …) in one go. Modal exposes a checkbox per type; submit
// loops through the visible list and archives each matching active
// item via the existing per-id endpoint. No new server route — keeps
// the change small.

const showBulkArchive = ref(false);
const bulkArchiveBusy = ref(false);
const bulkArchiveTypes = ref<Record<InboxItemType, boolean>>(
  Object.fromEntries(
    Object.values(InboxItemType).map((t) => [
      t,
      // Default-on for the loud output-only types; explicit user-facing
      // request types (approval, decision, ordering, structure-edit)
      // start off so they don't get archived by accident.
      t === InboxItemType.OUTPUT_TEXT
        || t === InboxItemType.OUTPUT_IMAGE
        || t === InboxItemType.OUTPUT_DOCUMENT
        || t === InboxItemType.FEEDBACK,
    ]),
  ) as Record<InboxItemType, boolean>,
);

const inboxItemTypeValues = Object.values(InboxItemType) as InboxItemType[];

/**
 * Items in the current list that match the selected types AND are
 * still archive-able (have an id, not yet archived). The user might
 * be on the archive view too — bulk-archive only operates on active
 * items there's nothing sensible to do otherwise.
 */
const bulkArchiveCandidates = computed(() => {
  return inbox.items.value.filter((item) => {
    if (!item.id) return false;
    if (item.status === InboxItemStatus.ARCHIVED) return false;
    return bulkArchiveTypes.value[item.type] === true;
  });
});

function openBulkArchive(): void {
  showBulkArchive.value = true;
}

async function submitBulkArchive(): Promise<void> {
  const candidates = bulkArchiveCandidates.value.slice();
  if (candidates.length === 0) {
    showBulkArchive.value = false;
    return;
  }
  bulkArchiveBusy.value = true;
  try {
    // Sequential to keep server load steady + give the user a
    // deterministic order. Failures don't stop the loop — the
    // composable surfaces the last error in {@code inbox.error}.
    for (const item of candidates) {
      if (item.id) await inbox.archive(item.id);
    }
  } finally {
    bulkArchiveBusy.value = false;
    showBulkArchive.value = false;
  }
}

/**
 * Hand the current item over to the document editor as a fresh
 * draft. The Document editor reads the draft on mount (one-shot)
 * and opens its create-modal prefilled with title / path / content
 * — see specification/web-ui.md §… (Inbox → Document handoff).
 */
async function toDocument(): Promise<void> {
  const sel = inbox.selected.value;
  if (!sel) return;
  const ts = sel.createdAt
    ? new Date(sel.createdAt).toISOString().slice(0, 10)
    : new Date().toISOString().slice(0, 10);
  // Slug for the suggested file path. Keep it conservative — the
  // user can edit before saving.
  const slug = (sel.title || sel.id || 'inbox-item')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60) || 'inbox-item';
  setDocumentDraft({
    title: sel.title ?? '',
    path: `inbox/${ts}-${slug}.md`,
    content: sel.body ?? '',
    mimeType: 'text/markdown',
    source: `Inbox item «${sel.title ?? '(no title)'}» from ${sel.originatorUserId}`,
  });
  // Archive the item — once it's been promoted to a document, it's
  // no longer pending in the inbox. Skip if already archived.
  if (sel.id && sel.status !== InboxItemStatus.ARCHIVED) {
    submitting.value = true;
    try {
      await inbox.archive(sel.id);
    } finally {
      submitting.value = false;
    }
  }
  // Same-tab navigation — the Document editor mounts fresh and
  // consumes the draft on its first onMounted.
  window.location.href = '/documents.html?createDraft=1';
}

async function dismissItem(): Promise<void> {
  const sel = inbox.selected.value;
  if (!sel?.id) return;
  submitting.value = true;
  try {
    await inbox.dismiss(sel.id);
  } finally {
    submitting.value = false;
  }
}

// ─────── Delegate modal ───────
const delegateOpen = ref(false);
const delegateTarget = ref('');
const delegateNote = ref('');
const delegating = ref(false);

const delegateOptions = computed(() => {
  // Build the union of all team-mates the current user can delegate
  // to, deduped, sorted, self excluded. Username strings only.
  const set = new Set<string>();
  for (const t of teamsState.teams.value) {
    for (const m of t.members) {
      if (m && m !== currentUser) set.add(m);
    }
  }
  return [...set].sort().map((u) => ({ value: u, label: u }));
});

function openDelegateModal(): void {
  delegateTarget.value = delegateOptions.value[0]?.value ?? '';
  delegateNote.value = '';
  delegateOpen.value = true;
}

async function confirmDelegate(): Promise<void> {
  const sel = inbox.selected.value;
  if (!sel?.id || !delegateTarget.value) return;
  delegating.value = true;
  try {
    const ok = await inbox.delegate(sel.id, delegateTarget.value, delegateNote.value || null);
    if (ok) delegateOpen.value = false;
  } finally {
    delegating.value = false;
  }
}

// ─────── Item-type detection ───────
function isAsk(item: InboxItemDto): boolean {
  return item.requiresAction === true && item.status === InboxItemStatus.PENDING;
}

function decisionOptions(item: InboxItemDto): string[] {
  const raw = item.payload?.options;
  if (Array.isArray(raw)) return raw.map((o) => String(o));
  return [];
}

const breadcrumbs = computed<string[]>(() => {
  // Breadcrumbs carry the path *within* the editor — the editor name
  // itself is in the topbar title and would otherwise read twice.
  const c: string[] = [];
  const s = selection.value;
  if (s.kind === 'inbox' && s.tag) c.push('#' + s.tag);
  if (s.kind === 'archive') c.push(t('inbox.breadcrumbArchive'));
  if (s.kind === 'team') c.push(t('inbox.breadcrumbTeam', { team: s.teamName }));
  if (inbox.selected.value) c.push(shortPreview(inbox.selected.value.title, 40));
  return c;
});
</script>

<template>
  <EditorShell
    v-model:focus-zone="focusZone"
    :title="$t('inbox.pageTitle')"
    :breadcrumbs="breadcrumbs"
    :full-height="true"
    :show-sidebar="true"
    focus-model="auto"
    title-clickable
    @title-click="focusZone = 'sidebar'"
  >
    <!-- ─── Sidebar ─── -->
    <!--
      Each nav button uses {@code @pointerdown.stop="focusZone='main'"}
      so that clicking a nav option jumps focus straight to the main
      zone without first flashing through 'sidebar' (which is what
      EditorShell's aside-level pointerdown listener would otherwise
      assign on the way to the click). The {@code .stop} swallows the
      bubble; click fires normally and runs selectXxx. Clicking the
      sidebar's empty space still focuses the sidebar.
    -->
    <template #sidebar>
      <nav class="flex flex-col gap-1 p-2">
        <button
          class="sidebar-item"
          :class="{ 'sidebar-item--active': isSelected({ kind: 'inbox', tag: null }) }"
          type="button"
          @pointerdown.stop="focusZone = 'main'"
          @click="selectInbox(null)"
        >{{ $t('inbox.sidebar.inbox') }}</button>

        <button
          v-for="tag in inbox.tags.value"
          :key="'t-' + tag"
          class="sidebar-item sidebar-item--child"
          :class="{ 'sidebar-item--active': isSelected({ kind: 'inbox', tag }) }"
          type="button"
          @pointerdown.stop="focusZone = 'main'"
          @click="selectInbox(tag)"
        >#{{ tag }}</button>

        <button
          class="sidebar-item mt-2"
          :class="{ 'sidebar-item--active': isSelected({ kind: 'archive' }) }"
          type="button"
          @pointerdown.stop="focusZone = 'main'"
          @click="selectArchive"
        >{{ $t('inbox.sidebar.archive') }}</button>

        <div class="mt-3 text-xs uppercase opacity-50 px-2">
          {{ $t('inbox.sidebar.teamInbox') }}
        </div>
        <div v-if="teamsState.loading.value" class="px-2 text-xs opacity-60">
          {{ $t('inbox.sidebar.loadingTeams') }}
        </div>
        <div v-else-if="teamsState.teams.value.length === 0" class="px-2 text-xs opacity-60">
          {{ $t('inbox.sidebar.noTeams') }}
        </div>
        <button
          v-for="team in teamsState.teams.value"
          :key="'team-' + team.id"
          class="sidebar-item sidebar-item--child"
          :class="{ 'sidebar-item--active': isSelected({ kind: 'team', teamName: team.name }) }"
          type="button"
          @pointerdown.stop="focusZone = 'main'"
          @click="selectTeam(team.name)"
        >{{ team.title || team.name }}</button>
      </nav>
    </template>

    <!-- ─── Main: list ↔ detail switcher ─── -->
    <div class="h-full min-h-0 flex flex-col">
      <!-- Sub-header A: list mode. Picker-style full-width strip with
           the current view label on the left and the bulk-archive
           escape hatch on the right. Hidden in archive view (nothing
           to archive there). Mirrors the documents picker sub-header. -->
      <div
        v-if="!inbox.selected.value"
        class="px-6 pt-4 pb-3 border-b border-base-300 bg-base-100 flex items-center gap-3"
      >
        <div class="flex-1 min-w-0 font-semibold truncate">{{ viewLabel }}</div>
        <VButton
          v-if="selection.kind !== 'archive' && inbox.items.value.length > 0"
          variant="ghost"
          size="sm"
          @click="openBulkArchive"
        >{{ $t('inbox.bulkArchive.button') }}</VButton>
      </div>

      <!-- Sub-header B: detail mode. Back-button on the left + the
           item title; type and criticality move to the right. -->
      <div
        v-else
        class="px-6 pt-4 pb-3 border-b border-base-300 bg-base-100 flex items-center gap-x-3 gap-y-1 flex-wrap"
      >
        <div class="flex items-center gap-3 min-w-0 flex-1 basis-[16rem]">
          <VButton
            variant="ghost"
            size="sm"
            :title="$t('inbox.detail.backToList')"
            @click="closeItem"
          >←</VButton>
          <span class="font-semibold truncate">
            {{ inbox.selected.value.title || $t('inbox.detail.noTitle') }}
          </span>
        </div>
        <div class="text-xs opacity-70 flex items-center gap-3 shrink-0">
          <span>{{ inbox.selected.value.type }}</span>
          <VBadge
            v-if="inbox.selected.value.criticality !== Criticality.NORMAL"
            variant="warning"
            size="sm"
          >{{ inbox.selected.value.criticality }}</VBadge>
        </div>
      </div>

      <!-- Scrollable content area. -->
      <div class="flex-1 min-h-0 overflow-y-auto">
        <div class="container mx-auto px-4 py-4 max-w-4xl">
    <section v-if="!inbox.selected.value" class="inbox-list p-2">
      <VAlert v-if="inbox.error.value" variant="error" class="mb-3">
        <span>{{ inbox.error.value }}</span>
      </VAlert>
      <VEmptyState
        v-if="!inbox.loading.value && inbox.items.value.length === 0"
        :headline="$t('inbox.list.emptyHeadline')"
        :body="$t('inbox.list.emptyBody')"
      />
      <ul class="flex flex-col gap-1">
        <li
          v-for="item in inbox.items.value"
          :key="item.id ?? ''"
          class="list-row"
          @click="openItem(item)"
        >
          <div class="flex items-center justify-between gap-2">
            <span class="font-medium truncate">
              {{ item.title || $t('inbox.list.noTitle') }}
            </span>
            <span class="text-xs opacity-60 shrink-0">{{ formatTimestamp(item.createdAt) }}</span>
          </div>
          <div class="flex items-center gap-2 text-xs opacity-70">
            <span>{{ item.type }}</span>
            <VBadge v-if="item.criticality !== Criticality.NORMAL" variant="warning" size="sm">{{ item.criticality }}</VBadge>
            <span v-if="item.assignedToUserId !== currentUser" class="opacity-60">
              → {{ item.assignedToUserId }}
            </span>
            <span v-if="item.status !== InboxItemStatus.PENDING" class="opacity-60">{{ item.status }}</span>
          </div>
          <div v-if="item.tags && item.tags.length" class="mt-1 flex gap-1 flex-wrap">
            <VBadge
              v-for="t in item.tags"
              :key="t"
              variant="ghost"
              size="sm"
            >{{ t }}</VBadge>
          </div>
        </li>
      </ul>
    </section>

    <section v-else class="inbox-detail p-2">
      <!-- Back to list now lives in the full-width sub-header above
           (mirrors the document detail view); the card jumps straight
           into the item content. -->
      <VCard>
          <template #header>
            <div class="flex items-center justify-between gap-2">
              <span class="font-semibold truncate">
                {{ inbox.selected.value.title || $t('inbox.detail.noTitle') }}
              </span>
              <span class="text-xs opacity-60">{{ inbox.selected.value.type }}</span>
            </div>
          </template>

          <div class="text-xs opacity-60 flex flex-wrap gap-3 mb-3">
            <span>{{ $t('inbox.detail.fromLabel', { user: inbox.selected.value.originatorUserId }) }}</span>
            <span>{{ $t('inbox.detail.toLabel', { user: inbox.selected.value.assignedToUserId }) }}</span>
            <span>{{ $t('inbox.detail.statusLabel', { status: inbox.selected.value.status }) }}</span>
            <span v-if="inbox.selected.value.criticality !== Criticality.NORMAL">
              {{ $t('inbox.detail.criticalityLabel', { criticality: inbox.selected.value.criticality }) }}
            </span>
            <span>{{ formatTimestamp(inbox.selected.value.createdAt) }}</span>
          </div>

          <MarkdownView
            v-if="inbox.selected.value.body"
            :source="inbox.selected.value.body"
          />
          <div v-else class="opacity-60 italic">{{ $t('inbox.detail.noBody') }}</div>

          <div
            v-if="inbox.selected.value.payload && Object.keys(inbox.selected.value.payload).length > 0"
            class="mt-3 text-xs"
          >
            <details>
              <summary class="cursor-pointer opacity-70">{{ $t('inbox.detail.payload') }}</summary>
              <pre class="text-xs bg-base-200 p-2 rounded mt-1 overflow-auto">{{ JSON.stringify(inbox.selected.value.payload, null, 2) }}</pre>
            </details>
          </div>

          <div
            v-if="inbox.selected.value.answer"
            class="mt-3 p-3 rounded bg-base-200 text-sm"
          >
            <div class="opacity-70 text-xs mb-1">{{ $t('inbox.detail.answer') }}</div>
            <div>{{ $t('inbox.detail.answerOutcome', { outcome: inbox.selected.value.answer.outcome }) }}</div>
            <div v-if="inbox.selected.value.answer.value">
              {{ $t('inbox.detail.answerValue') }}
              <code>{{ JSON.stringify(inbox.selected.value.answer.value) }}</code>
            </div>
            <div v-if="inbox.selected.value.answer.reason">
              {{ $t('inbox.detail.answerReason', { reason: inbox.selected.value.answer.reason }) }}
            </div>
            <div class="opacity-60 text-xs mt-1">
              {{ $t('inbox.detail.answerBy', { user: inbox.selected.value.answer.answeredBy }) }}
            </div>
          </div>

          <!-- ─── Action bar ───
               Stacks three logical groups vertically so the feedback
               textarea / approval buttons / decision options aren't
               glued to the fallback inputs and the secondary tray. -->
          <template #actions>
            <div class="flex flex-col gap-4 w-full">
              <!-- Group 1: primary answer (type-specific). -->
              <!-- APPROVAL: Yes / No -->
              <div
                v-if="isAsk(inbox.selected.value) && inbox.selected.value.type === InboxItemType.APPROVAL"
                class="flex flex-wrap gap-2"
              >
                <VButton variant="primary" :loading="submitting" @click="submitApproval(true)">
                  {{ $t('inbox.actions.yes') }}
                </VButton>
                <VButton variant="ghost" :loading="submitting" @click="submitApproval(false)">
                  {{ $t('inbox.actions.no') }}
                </VButton>
              </div>

              <!-- DECISION: option-buttons. Labels are domain text from
                   the server payload — not translated here. -->
              <div
                v-else-if="isAsk(inbox.selected.value) && inbox.selected.value.type === InboxItemType.DECISION"
                class="flex flex-wrap gap-2"
              >
                <VButton
                  v-for="opt in decisionOptions(inbox.selected.value)"
                  :key="opt"
                  variant="primary"
                  :loading="submitting"
                  @click="submitDecision(opt)"
                >{{ opt }}</VButton>
                <span v-if="decisionOptions(inbox.selected.value).length === 0" class="text-xs opacity-60">
                  {{ $t('inbox.actions.noOptionsHint') }}
                </span>
              </div>

              <!-- FEEDBACK: full-width textarea, Send button below
                   right-aligned. The textarea wants space; squeezing
                   it next to a button on one line ate that space. -->
              <div
                v-else-if="isAsk(inbox.selected.value) && inbox.selected.value.type === InboxItemType.FEEDBACK"
                class="flex flex-col gap-2"
              >
                <VTextarea
                  v-model="feedbackText"
                  label=""
                  :rows="4"
                  :disabled="submitting"
                />
                <div class="flex justify-end">
                  <VButton
                    variant="primary"
                    :loading="submitting"
                    :disabled="!feedbackText.trim()"
                    @click="submitFeedback"
                  >{{ $t('inbox.actions.send') }}</VButton>
                </div>
              </div>

              <!-- Group 2: fallback row — "I can't answer". Always
                   shown for ask items, sits visually under the primary
                   answer with a subtle separator so the user sees it
                   as a different intent. -->
              <div
                v-if="isAsk(inbox.selected.value)"
                class="flex flex-wrap items-center gap-2 pt-3 border-t border-base-300"
              >
                <VInput
                  v-model="reasonText"
                  :placeholder="$t('inbox.actions.reasonPlaceholder')"
                  :disabled="submitting"
                  class="flex-1 min-w-[14rem]"
                />
                <VButton variant="ghost" :loading="submitting" @click="submitInsufficientInfo">
                  {{ $t('inbox.actions.insufficientInfo') }}
                </VButton>
                <VButton variant="ghost" :loading="submitting" @click="submitUndecidable">
                  {{ $t('inbox.actions.undecidable') }}
                </VButton>
              </div>

              <!-- Group 3: secondary actions tray, right-aligned.
                   Always visible — even for non-ask items the user
                   may want to dismiss / archive / send to documents. -->
              <div
                class="flex flex-wrap gap-2 justify-end"
                :class="isAsk(inbox.selected.value) ? 'pt-3 border-t border-base-300' : ''"
              >
                <VButton
                  variant="ghost"
                  :disabled="submitting"
                  @click="toDocument"
                >{{ $t('inbox.actions.toDocument') }}</VButton>
                <VButton
                  variant="ghost"
                  :disabled="submitting"
                  @click="openDelegateModal"
                >{{ $t('inbox.actions.delegate') }}</VButton>
                <VButton
                  v-if="inbox.selected.value.status !== InboxItemStatus.DISMISSED"
                  variant="ghost"
                  :disabled="submitting"
                  @click="dismissItem"
                >{{ $t('inbox.actions.dismiss') }}</VButton>
                <VButton
                  v-if="inbox.selected.value.status === InboxItemStatus.ARCHIVED"
                  variant="primary"
                  :loading="submitting"
                  @click="unarchiveItem"
                >{{ $t('inbox.actions.unarchive') }}</VButton>
                <VButton
                  v-else
                  variant="primary"
                  :loading="submitting"
                  @click="archiveItem"
                >{{ $t('inbox.actions.archive') }}</VButton>
              </div>
            </div>
          </template>
        </VCard>
      </section>
        </div>
      </div>
    </div>

    <!-- ─── Bulk-archive modal: pick types + archive matching items ─── -->
    <VModal
      v-model="showBulkArchive"
      :title="$t('inbox.bulkArchive.title')"
      :close-on-backdrop="!bulkArchiveBusy"
    >
      <div class="flex flex-col gap-3">
        <p class="text-sm opacity-80">{{ $t('inbox.bulkArchive.body') }}</p>
        <fieldset class="flex flex-col gap-1.5">
          <legend class="text-xs uppercase opacity-60 mb-1">
            {{ $t('inbox.bulkArchive.typesLegend') }}
          </legend>
          <VCheckbox
            v-for="t in inboxItemTypeValues"
            :key="t"
            v-model="bulkArchiveTypes[t]"
            :label="t"
            :disabled="bulkArchiveBusy"
          />
        </fieldset>
        <div class="text-xs opacity-70">
          <span v-if="bulkArchiveCandidates.length === 0">
            {{ $t('inbox.bulkArchive.countNone') }}
          </span>
          <span v-else>
            {{ $t('inbox.bulkArchive.countLabel', { n: bulkArchiveCandidates.length }) }}
          </span>
        </div>
      </div>
      <template #actions>
        <VButton
          variant="ghost"
          :disabled="bulkArchiveBusy"
          @click="showBulkArchive = false"
        >{{ $t('inbox.bulkArchive.cancel') }}</VButton>
        <VButton
          variant="danger"
          :loading="bulkArchiveBusy"
          :disabled="bulkArchiveCandidates.length === 0"
          @click="submitBulkArchive"
        >{{ $t('inbox.bulkArchive.confirm', { n: bulkArchiveCandidates.length }) }}</VButton>
      </template>
    </VModal>

    <!-- Delegate modal -->
    <VModal
      v-model="delegateOpen"
      :title="$t('inbox.delegate.title')"
      :close-on-backdrop="!delegating"
    >
      <p class="text-sm opacity-80 mb-3">{{ $t('inbox.delegate.body') }}</p>
      <div class="flex flex-col gap-3">
        <VSelect
          v-model="delegateTarget"
          :options="delegateOptions"
          :label="$t('inbox.delegate.recipient')"
          :disabled="delegating || delegateOptions.length === 0"
        />
        <VTextarea
          v-model="delegateNote"
          :label="$t('inbox.delegate.note')"
          :rows="3"
          :disabled="delegating"
        />
      </div>
      <template #actions>
        <VButton variant="ghost" :disabled="delegating" @click="delegateOpen = false">
          {{ $t('inbox.delegate.cancel') }}
        </VButton>
        <VButton
          variant="primary"
          :loading="delegating"
          :disabled="!delegateTarget || delegateOptions.length === 0"
          @click="confirmDelegate"
        >{{ $t('inbox.delegate.confirm') }}</VButton>
      </template>
    </VModal>
  </EditorShell>
</template>

<style scoped>
/* Layout (sidebar / list / detail) now comes from {@code <EditorShell>}'s
 * focus-model — zone widths, borders, backgrounds, transitions all
 * live there. We only keep the inner-content styles for sidebar nav
 * items and list rows. */

.sidebar-item {
  display: block;
  text-align: left;
  padding: 0.4rem 0.6rem;
  border-radius: 0.375rem;
  font-size: 0.875rem;
  background: transparent;
  cursor: pointer;
  width: 100%;
}
.sidebar-item:hover {
  background: hsl(var(--bc) / 0.08);
}
.sidebar-item--active {
  background: hsl(var(--p) / 0.15);
  color: hsl(var(--pf));
  font-weight: 600;
}
.sidebar-item--child {
  padding-left: 1.5rem;
  font-size: 0.8125rem;
}

.list-row {
  padding: 0.6rem 0.75rem;
  border-radius: 0.5rem;
  cursor: pointer;
  border: 1px solid transparent;
}
.list-row:hover {
  background: hsl(var(--bc) / 0.04);
}
.list-row--active {
  background: hsl(var(--p) / 0.08);
  border-color: hsl(var(--p) / 0.4);
}
</style>
