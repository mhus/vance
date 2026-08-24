<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton, VEmptyState, VInput, VModal, VSelect, VTextarea } from '@/components';
import { MaximegalonStatus, type MaximegalonDto } from '@vance/generated';
import { useInbox } from '@/composables/useInbox';
import { useTeams } from '@/composables/useTeams';
import { getUsername } from '@vance/shared';
import InboxThreadPanel from '@/inbox/InboxThreadPanel.vue';
import InboxReactionBar from '@/inbox/InboxReactionBar.vue';
import MarkdownView from '@/components/MarkdownView.vue';
import type { CortexDocument } from '../types';

/**
 * The discussions about the document currently open — the third tab of the
 * right panel, beside Chat and Help.
 *
 * <p><b>Two levels, like the chat tab.</b> First every thread whose object is
 * this document, then one of them opened in place. The same shape for the same
 * reason: a list is what you need to choose, and a conversation is what you need
 * once you have chosen; showing both at once in a narrow column serves neither.
 *
 * <p><b>An empty list is not a statement about existence.</b> The server filters
 * by who may see a thread, and a document you may read can carry threads between
 * people you share nothing with — document access is deliberately not a back
 * door into conversations about it. So the empty state says "no discussions",
 * never "none you may see": the second phrasing would confirm what the filter
 * protects.
 */

const props = defineProps<{
  /** The document in the foreground, or {@code null} when no tab is open. */
  activeDocument: CortexDocument | null;
  /**
   * Whether this tab is the one on screen.
   *
   * <p>The panel is kept mounted across tab switches (its two-level position
   * would be lost otherwise), which means every request it makes on a document
   * change is made whether or not anybody is looking. The host therefore says
   * when it is visible, and the fetches follow: the neighbouring chat panel
   * makes the same distinction, only the other way round — it is expensive to
   * build, so it is mounted behind a condition and only *hidden* by v-show.
   */
  visible?: boolean;
}>();

const { t } = useI18n();
const inbox = useInbox();
const teamsState = useTeams();
const busy = ref(false);
const currentUser = getUsername() ?? '';

/**
 * Which thread is open, or {@code null} for the list. Cleared whenever the
 * document changes — a thread about the previous document has no business
 * staying on screen when the reader switched tabs.
 */
const openThreadId = ref<string | null>(null);

const documentId = computed<string | null>(() => props.activeDocument?.id ?? null);

/**
 * Set when the document changed while the tab was hidden. The list is then
 * fetched on the way in rather than on the way past — a reader who never opens
 * Discussion never pays for it, and one who does sees the current document's
 * threads, not the previous document's.
 */
const stale = ref(true);

watch(documentId, async (id) => {
  openThreadId.value = null;
  inbox.clearSelection();
  if (!id) {
    stale.value = false;
    return;
  }
  if (!props.visible) {
    stale.value = true;
    return;
  }
  stale.value = false;
  await inbox.loadForDocument(id);
}, { immediate: true });

// Becoming visible with a document whose threads were never fetched (or fetched
// for a different document) is the other half of the same rule.
watch(() => props.visible, async (isVisible) => {
  if (!isVisible || !stale.value) return;
  const id = documentId.value;
  if (!id) return;
  stale.value = false;
  await inbox.loadForDocument(id);
}, { immediate: true });

async function openThread(item: MaximegalonDto): Promise<void> {
  if (!item.id) return;
  openThreadId.value = item.id;
  await inbox.loadOne(item.id);
}

/**
 * Back to the list, and reload it: the visit just changed things the row shows —
 * the reply count if a contribution was added, the read state because opening a
 * thread reports it. A stale row after coming back would look like the write
 * failed.
 */
async function backToList(): Promise<void> {
  openThreadId.value = null;
  inbox.clearSelection();
  if (documentId.value) await inbox.loadForDocument(documentId.value);
}

// ── Opening a discussion ─────────────────────────────────────────────
//
// The "+" beside the heading, mirroring the session picker. Deliberately not a
// second share dialog: pointing somebody at a document is Actions → Share and
// lands in this same list. This is for the two cases that cannot — a thread
// addressed to yourself, and one whose point is a question.

const openOpen = ref(false);
const newTitle = ref('');
const newBody = ref('');
const newAssignee = ref('');
const creating = ref(false);

/**
 * Who it can be addressed to: yourself first (the case that exists because the
 * share path refuses it), then team-mates. The server has the final say — a
 * recipient whose inbox you may not write to is refused there.
 */
const assigneeOptions = computed(() => {
  const set = new Set<string>();
  for (const team of teamsState.teams.value) {
    for (const m of team.members) if (m && m !== currentUser) set.add(m);
  }
  return [
    { value: '', label: t('cortexThreads.assignSelf') },
    ...[...set].sort().map((u) => ({ value: u, label: u })),
  ];
});

/**
 * The team list has to be fetched, not just read: without it the recipient
 * dropdown offers only "mine" and the feature looks half-built.
 *
 * <p>Fetched when the dialog opens, not on mount. The panel is mounted for
 * every Cortex session whether or not anyone opens Discussion, and this list is
 * needed by one dropdown inside one modal — a request nobody waits for is still
 * a request every boot makes. Once is enough; the membership does not change
 * while a dialog is open.
 */
let teamsLoaded = false;
async function ensureTeams(): Promise<void> {
  if (teamsLoaded) return;
  await teamsState.reload();
  // Marked loaded only on success: a failed fetch leaves the dropdown with just
  // "Mine", and the next attempt at the dialog is the natural place to retry.
  teamsLoaded = teamsState.error.value === null;
}

function openDialog(): void {
  void ensureTeams();
  // Prefilled with the document's name: a discussion about a document almost
  // always starts by naming it, and an empty title field invites "Frage".
  newTitle.value = props.activeDocument?.title || props.activeDocument?.name || '';
  newBody.value = '';
  newAssignee.value = '';
  openOpen.value = true;
}

async function confirmOpen(): Promise<void> {
  const id = documentId.value;
  const title = newTitle.value.trim();
  if (!id || !title) return;
  creating.value = true;
  try {
    const created = await inbox.openDiscussion(
      id, title, newBody.value.trim() || null, newAssignee.value || null);
    if (!created) return;
    openOpen.value = false;
    await inbox.loadForDocument(id);
    // Straight into it: you just wrote the opening line, so the thread is where
    // you want to be — not back in a list looking for what you made.
    if (created.id) {
      openThreadId.value = created.id;
      await inbox.loadOne(created.id);
    }
  } finally {
    creating.value = false;
  }
}

// ── Thread actions ───────────────────────────────────────────────────
//
// Thin pass-throughs, as in the inbox editor: the composable owns the request
// and folds the server's answer back into `selected`.

async function onPost(body: string, parentId: string | null): Promise<void> {
  const sel = inbox.selected.value;
  if (!sel?.id) return;
  busy.value = true;
  try {
    await inbox.postMessage(sel.id, body, parentId);
  } finally {
    busy.value = false;
  }
}

async function onRead(): Promise<void> {
  const sel = inbox.selected.value;
  if (sel?.id) await inbox.markRead(sel.id);
}

async function onInvite(userId: string): Promise<void> {
  const sel = inbox.selected.value;
  if (!sel?.id) return;
  busy.value = true;
  try {
    await inbox.invite(sel.id, userId);
  } finally {
    busy.value = false;
  }
}

async function onRemove(userId: string): Promise<void> {
  const sel = inbox.selected.value;
  if (!sel?.id) return;
  busy.value = true;
  try {
    await inbox.removeParticipant(sel.id, userId);
  } finally {
    busy.value = false;
  }
}

async function onFollow(following: boolean): Promise<void> {
  const sel = inbox.selected.value;
  if (!sel?.id) return;
  busy.value = true;
  try {
    await inbox.setFollowing(sel.id, following);
  } finally {
    busy.value = false;
  }
}

async function onReact(key: string, on: boolean, messageId: string | null): Promise<void> {
  const sel = inbox.selected.value;
  if (sel?.id) await inbox.react(sel.id, key, on, messageId);
}

async function onListReact(item: MaximegalonDto, key: string, on: boolean): Promise<void> {
  if (item.id) await inbox.react(item.id, key, on, null);
}

/**
 * Deep link into the inbox editor. The panel deliberately does not carry the
 * answer buttons: deciding an ask is the inbox's job, where the item is shown
 * with its options and its effect warning. Here the reader reads and
 * contributes; to settle it they go where settling belongs.
 */
function inboxLink(id: string): string {
  return `/inbox.html?item=${encodeURIComponent(id)}`;
}

function when(at: Date | string | undefined): string {
  return at ? new Date(at).toLocaleDateString() : '';
}
</script>

<template>
  <div class="h-full min-h-0 flex flex-col">
    <div
      class="px-3 py-1.5 text-xs border-b border-base-300 bg-base-200/40 text-base-content/60
             flex items-center gap-2 shrink-0"
    >
      <VButton
        v-if="openThreadId"
        size="sm"
        variant="ghost"
        class="-ml-2"
        :title="t('cortexThreads.backToList')"
        :aria-label="t('cortexThreads.backToList')"
        @click="backToList"
      >‹</VButton>
      <span v-else class="uppercase tracking-wide opacity-70">
        {{ t('cortexThreads.heading') }}
      </span>
      <span class="truncate flex-1">{{ activeDocument?.title || activeDocument?.path }}</span>
      <VButton
        v-if="activeDocument && !openThreadId"
        size="sm"
        variant="ghost"
        class="shrink-0 -mr-2"
        :title="t('cortexThreads.newTitle')"
        @click="openDialog"
      >+</VButton>
    </div>

    <div v-if="!activeDocument" class="flex-1 flex items-center justify-center p-3">
      <p class="text-sm opacity-60">{{ t('cortexThreads.noDocument') }}</p>
    </div>

    <div v-else class="flex-1 min-h-0 overflow-y-auto">
      <VAlert v-if="inbox.error.value" variant="error" class="m-2">
        <span>{{ inbox.error.value }}</span>
      </VAlert>

      <div v-if="inbox.loading.value && !openThreadId" class="p-3 text-xs opacity-60">
        {{ t('cortexThreads.loading') }}
      </div>

      <!-- Level 1: the threads about this document. -->
      <template v-else-if="!openThreadId">
        <VEmptyState
          v-if="inbox.items.value.length === 0"
          :headline="t('cortexThreads.emptyHeadline')"
          :body="t('cortexThreads.emptyBody')"
        />
        <ul v-else class="flex flex-col">
          <li
            v-for="item in inbox.items.value"
            :key="item.id ?? ''"
            class="border-b border-base-300 px-3 py-2 cursor-pointer hover:bg-base-200
                   flex flex-col gap-1"
            @click="openThread(item)"
          >
            <div class="flex items-baseline gap-2 min-w-0">
              <span class="text-sm truncate flex-1">
                {{ item.title || t('cortexThreads.noTitle') }}
              </span>
              <span class="text-xs opacity-50 shrink-0">{{ when(item.createdAt) }}</span>
            </div>
            <div class="flex items-center gap-2 text-xs opacity-70 flex-wrap">
              <span>{{ item.type }}</span>
              <!-- The one thing worth colouring: something is waiting on a
                   person, and this panel cannot settle it. -->
              <span
                v-if="item.requiresAction && item.status === MaximegalonStatus.PENDING"
                class="text-warning"
              >{{ t('cortexThreads.awaitingAnswer') }}</span>
              <span v-else-if="item.status !== MaximegalonStatus.PENDING" class="opacity-60">
                {{ item.status }}
              </span>
              <span v-if="item.messageCount" class="opacity-60">
                {{ t('inbox.list.replies', item.messageCount) }}
              </span>
            </div>
            <div class="mt-0.5" @click.stop>
              <InboxReactionBar
                :reactions="item.reactions"
                @react="(key: string, on: boolean) => onListReact(item, key, on)"
              />
            </div>
          </li>
        </ul>
        <!-- The server stopped at its ceiling. Said explicitly: this column has
             no pagination, so a cut list would otherwise read as complete. -->
        <p
          v-if="inbox.itemsTruncated.value"
          class="px-3 py-2 text-xs opacity-60 border-t border-base-300"
        >{{ t('cortexThreads.truncated', { count: inbox.items.value.length }) }}</p>
      </template>

      <!-- Level 2: one thread. The question, then the clarification. -->
      <div v-else-if="inbox.selected.value" class="p-3 flex flex-col gap-2">
        <div class="flex items-baseline gap-2 flex-wrap">
          <span class="font-semibold">
            {{ inbox.selected.value.title || t('cortexThreads.noTitle') }}
          </span>
          <span class="text-xs opacity-60">{{ inbox.selected.value.type }}</span>
        </div>
        <MarkdownView
          v-if="inbox.selected.value.body"
          :source="inbox.selected.value.body"
        />
        <!-- Settling belongs to the inbox, where the item is rendered with its
             options and its effect warning. Offering the buttons here would put
             a decision behind a panel that cannot show its consequences. -->
        <a
          v-if="inbox.selected.value.requiresAction
            && inbox.selected.value.status === MaximegalonStatus.PENDING"
          class="text-xs underline opacity-80 hover:opacity-100"
          :href="inboxLink(inbox.selected.value.id ?? '')"
        >{{ t('cortexThreads.answerInInbox') }}</a>

        <InboxThreadPanel
          :item="inbox.selected.value"
          :busy="busy"
          :error="inbox.error.value"
          @post="onPost"
          @read="onRead"
          @invite="onInvite"
          @remove="onRemove"
          @follow="onFollow"
          @react="onReact"
        />
      </div>

      <div v-else class="p-3 text-xs opacity-60">{{ t('cortexThreads.loading') }}</div>
    </div>

    <VModal v-model="openOpen" :title="t('cortexThreads.newTitle')" :close-on-backdrop="!creating">
      <div class="flex flex-col gap-3">
        <p class="text-sm opacity-80">
          {{ t('cortexThreads.newIntro', { doc: activeDocument?.title || activeDocument?.path }) }}
        </p>
        <VAlert v-if="inbox.error.value" variant="error">
          <span>{{ inbox.error.value }}</span>
        </VAlert>
        <div class="w-full">
          <VInput
            v-model="newTitle"
            :label="t('cortexThreads.newTitleLabel')"
            :disabled="creating"
          />
        </div>
        <div class="w-full">
          <VSelect
            v-model="newAssignee"
            :label="t('cortexThreads.newAssignee')"
            :options="assigneeOptions"
            :disabled="creating"
          />
        </div>
        <VTextarea
          v-model="newBody"
          :label="t('cortexThreads.newBody')"
          :rows="4"
          :disabled="creating"
        />
      </div>
      <template #actions>
        <VButton variant="ghost" :disabled="creating" @click="openOpen = false">
          {{ t('cortexThreads.newCancel') }}
        </VButton>
        <VButton
          variant="primary"
          :loading="creating"
          :disabled="!newTitle.trim()"
          @click="confirmOpen"
        >{{ t('cortexThreads.newConfirm') }}</VButton>
      </template>
    </VModal>
  </div>
</template>
