<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton, VEmptyState } from '@/components';
import { MaximegalonStatus, type MaximegalonDto } from '@vance/generated';
import { useInbox } from '@/composables/useInbox';
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
}>();

const { t } = useI18n();
const inbox = useInbox();
const busy = ref(false);

/**
 * Which thread is open, or {@code null} for the list. Cleared whenever the
 * document changes — a thread about the previous document has no business
 * staying on screen when the reader switched tabs.
 */
const openThreadId = ref<string | null>(null);

const documentId = computed<string | null>(() => props.activeDocument?.id ?? null);

watch(documentId, async (id) => {
  openThreadId.value = null;
  inbox.clearSelection();
  if (id) await inbox.loadForDocument(id);
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
      <span class="truncate">{{ activeDocument?.title || activeDocument?.path }}</span>
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
  </div>
</template>
