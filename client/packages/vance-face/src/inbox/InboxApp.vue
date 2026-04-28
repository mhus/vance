<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  EditorShell,
  MarkdownView,
  VAlert,
  VButton,
  VCard,
  VEmptyState,
  VInput,
  VModal,
  VSelect,
  VTextarea,
} from '@/components';
import { useInbox, type AssignedToFilter, type InboxFilter } from '@/composables/useInbox';
import { useTeams } from '@/composables/useTeams';
import { getUsername, setDocumentDraft } from '@vance/shared';
import {
  AnswerOutcome,
  Criticality,
  InboxItemStatus,
  InboxItemType,
  type InboxItemDto,
} from '@vance/generated';

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

// ─────── Lifecycle ───────
onMounted(async () => {
  await Promise.all([teamsState.reload(), inbox.loadTags()]);
  await inbox.loadList(selectionToFilter(selection.value));
});

watch(selection, async (next) => {
  inbox.clearSelection();
  await inbox.loadList(selectionToFilter(next));
}, { deep: true });

// ─────── Item open ───────
async function openItem(item: InboxItemDto): Promise<void> {
  if (!item.id) return;
  await inbox.loadOne(item.id);
}

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
  window.location.href = '/document-editor.html?createDraft=1';
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
  const c: string[] = ['Inbox'];
  const s = selection.value;
  if (s.kind === 'inbox' && s.tag) c.push('#' + s.tag);
  if (s.kind === 'archive') c.push('Archive');
  if (s.kind === 'team') c.push('Team: ' + s.teamName);
  if (inbox.selected.value) c.push(shortPreview(inbox.selected.value.title, 40));
  return c;
});
</script>

<template>
  <EditorShell title="Inbox" :breadcrumbs="breadcrumbs">
    <div class="inbox-grid">
      <!-- ─── Sidebar ─── -->
      <aside class="inbox-sidebar">
        <nav class="flex flex-col gap-1">
          <button
            class="sidebar-item"
            :class="{ 'sidebar-item--active': isSelected({ kind: 'inbox', tag: null }) }"
            type="button"
            @click="selectInbox(null)"
          >Inbox</button>

          <button
            v-for="tag in inbox.tags.value"
            :key="'t-' + tag"
            class="sidebar-item sidebar-item--child"
            :class="{ 'sidebar-item--active': isSelected({ kind: 'inbox', tag }) }"
            type="button"
            @click="selectInbox(tag)"
          >#{{ tag }}</button>

          <button
            class="sidebar-item mt-2"
            :class="{ 'sidebar-item--active': isSelected({ kind: 'archive' }) }"
            type="button"
            @click="selectArchive"
          >Archive</button>

          <div class="mt-3 text-xs uppercase opacity-50 px-2">Team Inbox</div>
          <div v-if="teamsState.loading.value" class="px-2 text-xs opacity-60">
            Loading teams…
          </div>
          <div v-else-if="teamsState.teams.value.length === 0" class="px-2 text-xs opacity-60">
            No teams
          </div>
          <button
            v-for="team in teamsState.teams.value"
            :key="'team-' + team.id"
            class="sidebar-item sidebar-item--child"
            :class="{ 'sidebar-item--active': isSelected({ kind: 'team', teamName: team.name }) }"
            type="button"
            @click="selectTeam(team.name)"
          >{{ team.title || team.name }}</button>
        </nav>
      </aside>

      <!-- ─── Item list ─── -->
      <section class="inbox-list">
        <VAlert v-if="inbox.error.value" variant="error" class="mb-3">
          <span>{{ inbox.error.value }}</span>
        </VAlert>
        <VEmptyState
          v-if="!inbox.loading.value && inbox.items.value.length === 0"
          headline="Empty"
          body="No items in this view."
        />
        <ul class="flex flex-col gap-1">
          <li
            v-for="item in inbox.items.value"
            :key="item.id ?? ''"
            class="list-row"
            :class="{ 'list-row--active': inbox.selected.value?.id === item.id }"
            @click="openItem(item)"
          >
            <div class="flex items-center justify-between gap-2">
              <span class="font-medium truncate">{{ item.title || '(no title)' }}</span>
              <span class="text-xs opacity-60 shrink-0">{{ formatTimestamp(item.createdAt) }}</span>
            </div>
            <div class="flex items-center gap-2 text-xs opacity-70">
              <span>{{ item.type }}</span>
              <span v-if="item.criticality !== Criticality.NORMAL" class="badge badge-warning badge-sm">{{ item.criticality }}</span>
              <span v-if="item.assignedToUserId !== currentUser" class="opacity-60">
                → {{ item.assignedToUserId }}
              </span>
              <span v-if="item.status !== InboxItemStatus.PENDING" class="opacity-60">{{ item.status }}</span>
            </div>
            <div v-if="item.tags && item.tags.length" class="mt-1 flex gap-1 flex-wrap">
              <span
                v-for="t in item.tags"
                :key="t"
                class="badge badge-ghost badge-sm"
              >{{ t }}</span>
            </div>
          </li>
        </ul>
      </section>

      <!-- ─── Detail ─── -->
      <section class="inbox-detail">
        <VEmptyState
          v-if="!inbox.selected.value"
          headline="Pick an item"
          body="Select an inbox item from the list to see its content and reply."
        />
        <VCard v-else>
          <template #header>
            <div class="flex items-center justify-between gap-2">
              <span class="font-semibold truncate">{{ inbox.selected.value.title || '(no title)' }}</span>
              <span class="text-xs opacity-60">{{ inbox.selected.value.type }}</span>
            </div>
          </template>

          <div class="text-xs opacity-60 flex flex-wrap gap-3 mb-3">
            <span>From: {{ inbox.selected.value.originatorUserId }}</span>
            <span>To: {{ inbox.selected.value.assignedToUserId }}</span>
            <span>Status: {{ inbox.selected.value.status }}</span>
            <span v-if="inbox.selected.value.criticality !== Criticality.NORMAL">
              Criticality: {{ inbox.selected.value.criticality }}
            </span>
            <span>{{ formatTimestamp(inbox.selected.value.createdAt) }}</span>
          </div>

          <MarkdownView
            v-if="inbox.selected.value.body"
            :source="inbox.selected.value.body"
          />
          <div v-else class="opacity-60 italic">(no body)</div>

          <div
            v-if="inbox.selected.value.payload && Object.keys(inbox.selected.value.payload).length > 0"
            class="mt-3 text-xs"
          >
            <details>
              <summary class="cursor-pointer opacity-70">Payload</summary>
              <pre class="text-xs bg-base-200 p-2 rounded mt-1 overflow-auto">{{ JSON.stringify(inbox.selected.value.payload, null, 2) }}</pre>
            </details>
          </div>

          <div
            v-if="inbox.selected.value.answer"
            class="mt-3 p-3 rounded bg-base-200 text-sm"
          >
            <div class="opacity-70 text-xs mb-1">Answer</div>
            <div>Outcome: {{ inbox.selected.value.answer.outcome }}</div>
            <div v-if="inbox.selected.value.answer.value">
              Value: <code>{{ JSON.stringify(inbox.selected.value.answer.value) }}</code>
            </div>
            <div v-if="inbox.selected.value.answer.reason">
              Reason: {{ inbox.selected.value.answer.reason }}
            </div>
            <div class="opacity-60 text-xs mt-1">
              by {{ inbox.selected.value.answer.answeredBy }}
            </div>
          </div>

          <!-- ─── Action bar ─── -->
          <template #actions>
            <div class="flex flex-wrap gap-2 w-full">
              <!-- APPROVAL: Yes / No / Cancel -->
              <template v-if="isAsk(inbox.selected.value) && inbox.selected.value.type === InboxItemType.APPROVAL">
                <VButton variant="primary" :loading="submitting" @click="submitApproval(true)">Yes</VButton>
                <VButton variant="ghost" :loading="submitting" @click="submitApproval(false)">No</VButton>
              </template>

              <!-- DECISION: option-buttons -->
              <template v-else-if="isAsk(inbox.selected.value) && inbox.selected.value.type === InboxItemType.DECISION">
                <VButton
                  v-for="opt in decisionOptions(inbox.selected.value)"
                  :key="opt"
                  variant="primary"
                  :loading="submitting"
                  @click="submitDecision(opt)"
                >{{ opt }}</VButton>
                <span v-if="decisionOptions(inbox.selected.value).length === 0" class="text-xs opacity-60">
                  (no options in payload — use the text reply below)
                </span>
              </template>

              <!-- FEEDBACK: text input + Send -->
              <template v-else-if="isAsk(inbox.selected.value) && inbox.selected.value.type === InboxItemType.FEEDBACK">
                <div class="flex-1 min-w-0">
                  <VTextarea
                    v-model="feedbackText"
                    label=""
                    :rows="3"
                    :disabled="submitting"
                  />
                </div>
                <VButton
                  variant="primary"
                  :loading="submitting"
                  :disabled="!feedbackText.trim()"
                  @click="submitFeedback"
                >Send</VButton>
              </template>

              <!-- Always available when asking: insufficient / undecidable -->
              <template v-if="isAsk(inbox.selected.value)">
                <VInput
                  v-model="reasonText"
                  placeholder="Reason (optional, used by 'insufficient' / 'undecidable')"
                  :disabled="submitting"
                  class="flex-1 min-w-[14rem]"
                />
                <VButton variant="ghost" :loading="submitting" @click="submitInsufficientInfo">
                  Insufficient info
                </VButton>
                <VButton variant="ghost" :loading="submitting" @click="submitUndecidable">
                  Undecidable
                </VButton>
              </template>

              <span class="grow" />

              <VButton
                variant="ghost"
                :disabled="submitting"
                @click="toDocument"
              >To Document</VButton>
              <VButton
                variant="ghost"
                :disabled="submitting"
                @click="openDelegateModal"
              >Delegate</VButton>
              <VButton
                v-if="inbox.selected.value.status !== InboxItemStatus.DISMISSED"
                variant="ghost"
                :disabled="submitting"
                @click="dismissItem"
              >Dismiss</VButton>
              <VButton
                v-if="inbox.selected.value.status === InboxItemStatus.ARCHIVED"
                variant="primary"
                :loading="submitting"
                @click="unarchiveItem"
              >Unarchive</VButton>
              <VButton
                v-else
                variant="primary"
                :loading="submitting"
                @click="archiveItem"
              >Archive</VButton>
            </div>
          </template>
        </VCard>
      </section>
    </div>

    <!-- Delegate modal -->
    <VModal
      v-model="delegateOpen"
      title="Delegate to teammate"
      :close-on-backdrop="!delegating"
    >
      <p class="text-sm opacity-80 mb-3">
        Hand this item to another team member. They will see it in their inbox and
        can answer / archive on your behalf.
      </p>
      <div class="flex flex-col gap-3">
        <VSelect
          v-model="delegateTarget"
          :options="delegateOptions"
          label="Recipient"
          :disabled="delegating || delegateOptions.length === 0"
        />
        <VTextarea
          v-model="delegateNote"
          label="Note (optional)"
          :rows="3"
          :disabled="delegating"
        />
      </div>
      <template #actions>
        <VButton variant="ghost" :disabled="delegating" @click="delegateOpen = false">Cancel</VButton>
        <VButton
          variant="primary"
          :loading="delegating"
          :disabled="!delegateTarget || delegateOptions.length === 0"
          @click="confirmDelegate"
        >Delegate</VButton>
      </template>
    </VModal>
  </EditorShell>
</template>

<style scoped>
.inbox-grid {
  display: grid;
  grid-template-columns: 14rem 22rem 1fr;
  gap: 1rem;
  min-height: 70vh;
}

@media (max-width: 1024px) {
  .inbox-grid {
    /* Stack to a single column on tablets / phones. The list and
       detail then scroll independently below the sidebar. */
    grid-template-columns: 1fr;
  }
}

.inbox-sidebar {
  border-right: 1px solid hsl(var(--bc) / 0.12);
  padding-right: 0.5rem;
}

.inbox-list {
  border-right: 1px solid hsl(var(--bc) / 0.12);
  padding-right: 0.75rem;
  overflow-y: auto;
}

.inbox-detail {
  overflow-y: auto;
}

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
