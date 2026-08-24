<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton, VInput, VTextarea, VToggle } from '@vance/components';
import { getUsername } from '@vance/shared';
import type { MaximegalonDto, MaximegalonMessageDto } from '@vance/generated';
import InboxReactionBar from './InboxReactionBar.vue';

/**
 * The discussion below an inbox thread: contributions, reactions, who takes
 * part, and the follow toggle.
 *
 * <p>Its own component rather than more lines in {@code InboxApp.vue}, which is
 * already a thousand of them.
 *
 * <p><b>Reading is reported, not assumed.</b> When a thread with something
 * unopened becomes visible here, the panel tells the server once — which is
 * client policy (see {@code InboxReadRequest}). It never touches the thread's
 * status: looking at a decision is not making it, so the answer buttons above
 * stay exactly as they were.
 */

const props = defineProps<{
  item: MaximegalonDto;
  busy?: boolean;
  /**
   * Last failure from a thread action. Rendered here rather than relying on the
   * page-level alert, which lives inside the list view and is therefore
   * invisible while a thread is open — the exact situation in which these
   * actions happen.
   */
  error?: string | null;
  /**
   * The contribution the reader has picked, or {@code null}. Owned by the host
   * rather than local state because the chat panel beside the list has to send
   * it with every turn — two copies would drift the moment one of them
   * re-rendered.
   */
  selectedMessageId?: string | null;
}>();

const emit = defineEmits<{
  (e: 'post', body: string, parentId: string | null): void;
  (e: 'read'): void;
  (e: 'invite', userId: string): void;
  (e: 'remove', userId: string): void;
  (e: 'follow', following: boolean): void;
  (e: 'react', key: string, on: boolean, messageId: string | null): void;
  /** A contribution was picked, or un-picked (null). */
  (e: 'select-message', messageId: string | null): void;
}>();

const { t } = useI18n();
const me = computed<string>(() => getUsername() ?? '');

/**
 * Where the "new from here" line goes: the first message this user had not read
 * when they opened the thread. A snapshot, not persisted — it only has to
 * survive this visit, not the session.
 */
const firstUnreadId = ref<string | null>(null);

function firstUnreadFor(item: MaximegalonDto): string | null {
  return (item.messages ?? []).find((m) => !m.readBy?.includes(me.value))?.id ?? null;
}

// ── Read reporting ───────────────────────────────────────────────────

/** Mirrors the server's rule, so we only report when there is something to report. */
const hasUnreadForMe = computed<boolean>(() => {
  if (!props.item.readBy?.includes(me.value)) return true;
  return (props.item.messages ?? []).some((m) => !m.readBy?.includes(me.value));
});

// Once per thread id: re-reporting on every re-render would be a write per
// keystroke in the composer.
//
// Declared *after* hasUnreadForMe on purpose — an immediate watcher runs during
// setup, so a const declared below it is still in its temporal dead zone and the
// component throws on first open. Neither the build nor vue-tsc catches that.
const reportedFor = ref<string | null>(null);

watch(() => props.item.id, (id) => {
  if (!id || reportedFor.value === id) return;
  reportedFor.value = id;
  // Freeze the divider *before* reporting. Reporting the read replaces the
  // item with the server's answer, in which nothing is unread any more — so a
  // computed over the current readBy would always be empty and the line could
  // never appear. It marks where this visit started, which is why it is a
  // snapshot and not derived state.
  firstUnreadId.value = firstUnreadFor(props.item);
  if (hasUnreadForMe.value) emit('read');
}, { immediate: true });

// ── Tree ─────────────────────────────────────────────────────────────
//
// Depth is one level, so the shape is roots with replies. Built here rather
// than sent nested: the server keeps a flat array to have a single update path.

interface Node { message: MaximegalonMessageDto; replies: MaximegalonMessageDto[] }

const tree = computed<Node[]>(() => {
  const messages = props.item.messages ?? [];
  const roots = messages.filter((m) => !m.parentId);
  return roots.map((message) => ({
    message,
    replies: messages.filter((m) => m.parentId === message.id),
  }));
});


const following = computed<boolean>(() => props.item.participants?.includes(me.value) ?? false);

/**
 * Whether to offer removing a participant.
 *
 * The server decides — this only avoids showing a button that would 409. The
 * approximation is the assignee, which is the part of "may decide" a client
 * can see: shared-team membership is not on the DTO, so a colleague of the
 * assignee gets no button and still has the endpoint. Erring towards too few
 * buttons is the right side to err on here.
 */
const canRemove = computed<boolean>(() => props.item.assignedToUserId === me.value);

/** The originator and the assignee of an open ask are refused by the server. */
function removable(user: string): boolean {
  if (!canRemove.value) return false;
  if (user === props.item.originatorUserId) return false;
  return !(user === props.item.assignedToUserId
    && props.item.requiresAction
    && props.item.status === 'PENDING');
}

// ── Composer ─────────────────────────────────────────────────────────

const draft = ref('');
const replyTo = ref<string | null>(null);
const inviteName = ref('');

function submit(): void {
  const body = draft.value.trim();
  if (!body || props.busy) return;
  emit('post', body, replyTo.value);
  draft.value = '';
  replyTo.value = null;
}

function startReply(id: string): void {
  replyTo.value = id;
}

/**
 * Picking is a toggle: clicking the picked one again lets go. Without that the
 * reader could never tell the agent "never mind that one" except by opening a
 * different thread.
 */
function toggleSelected(messageId: string | undefined): void {
  if (!messageId) return;
  emit('select-message', props.selectedMessageId === messageId ? null : messageId);
}

function submitInvite(): void {
  const name = inviteName.value.trim();
  if (!name || props.busy) return;
  emit('invite', name);
  inviteName.value = '';
}

/** The generator maps Instant to Date; a string still arrives over the wire. */
function when(at: Date | string | undefined): string {
  return at ? new Date(at).toLocaleString() : '';
}

/**
 * The server answers a refused invariant with a stable code. Turning it into a
 * sentence is the client's job: "assignee_must_stay" says nothing to a reader,
 * "you are the assignee of an open ask — delegate instead" says what to do.
 * Unknown text is passed through rather than swallowed.
 */
const REASONS = new Set(['assignee_must_stay', 'message_limit_reached', 'invalid_parent']);

const errorText = computed<string | null>(() => {
  const raw = props.error;
  if (!raw) return null;
  return REASONS.has(raw) ? t(`inboxThread.reason.${raw}`) : raw;
});

/**
 * Remount counter for the toggle. A checkbox is set by the browser on click; if
 * the server then refuses and `following` is unchanged, Vue has nothing to
 * re-render and the DOM keeps the value the click produced — the switch would
 * claim "not following" while the participant list says otherwise. Bumping the
 * key forces the input to be rebuilt from state.
 */
const toggleKey = ref(0);

watch(() => [props.item.participants, props.error], () => {
  toggleKey.value += 1;
});

</script>

<template>
  <section class="flex flex-col gap-4 border-t pt-4 mt-4">
    <VAlert v-if="errorText" variant="error">
      <span>{{ errorText }}</span>
    </VAlert>

    <!-- Participants + follow. The list is who gets updates; the toggle is how
         you leave. An assignee with an open ask is refused by the server (a
         process waits on them) and the reason appears in the alert above. -->
    <header class="flex flex-wrap items-center justify-between gap-2">
      <div class="flex flex-wrap items-center gap-2">
        <span class="text-sm opacity-70">{{ t('inboxThread.participants') }}</span>
        <span
          v-for="p in item.participants"
          :key="p"
          class="text-sm px-2 py-0.5 rounded bg-black/5 dark:bg-white/10 inline-flex items-center gap-1"
        >
          {{ p }}
          <button
            v-if="removable(p)"
            type="button"
            class="opacity-50 hover:opacity-100 leading-none"
            :title="t('inboxThread.removeTitle', { name: p })"
            :disabled="busy"
            @click="emit('remove', p)"
          >×</button>
        </span>
        <span v-if="item.teamId" class="text-sm opacity-70">
          {{ t('inboxThread.team', { team: item.teamId }) }}
        </span>
      </div>
      <VToggle
        :key="toggleKey"
        :model-value="following"
        :title="t('inboxThread.followTitle')"
        :label="t('inboxThread.follow')"
        @update:model-value="(v: boolean) => emit('follow', v)"
      />
    </header>

    <!-- Reactions on the thread's own question. The same bar the list row
         carries, so a reaction given there is the one seen here. -->
    <InboxReactionBar
      :reactions="item.reactions"
      :busy="busy"
      @react="(key: string, on: boolean) => emit('react', key, on, null)"
    />

    <!-- The clarification. Roots with one level of replies; deeper nesting is
         refused by the server. -->
    <ol v-if="tree.length" class="flex flex-col gap-3">
      <li v-for="node in tree" :key="node.message.id" class="flex flex-col gap-2">
        <div
          v-if="node.message.id === firstUnreadId"
          class="text-xs uppercase tracking-wide opacity-60"
        >{{ t('inboxThread.newFromHere') }}</div>

        <!-- Clicking a contribution picks it: the chat beside the list then
             sends its id with every turn, so "this one" has a referent. The
             ring is the only feedback, so it has to be unmistakable. The id
             is the scroll anchor for inbox_show_thread. -->
        <article
          :id="`inbox-msg-${node.message.id}`"
          class="flex flex-col gap-1 rounded px-2 py-1 -mx-2 cursor-pointer transition-colors"
          :class="selectedMessageId === node.message.id
            ? 'ring-2 ring-primary bg-primary/5'
            : 'hover:bg-black/5 dark:hover:bg-white/5'"
          @click="toggleSelected(node.message.id)"
        >
          <div class="flex items-baseline gap-2">
            <span class="font-medium text-sm">{{ node.message.authorUserId }}</span>
            <span class="text-xs opacity-60">{{ when(node.message.createdAt) }}</span>
            <span
              v-if="selectedMessageId === node.message.id"
              class="text-xs opacity-70"
            >{{ t('inboxThread.picked') }}</span>
          </div>
          <p class="whitespace-pre-wrap text-sm">{{ node.message.body }}</p>
          <InboxReactionBar
            :reactions="node.message.reactions"
            :busy="busy"
            @click.stop
            @react="(key: string, on: boolean) => emit('react', key, on, node.message.id)"
          >
            <VButton size="sm" variant="ghost" @click="startReply(node.message.id)">
              {{ t('inboxThread.reply') }}
            </VButton>
          </InboxReactionBar>
        </article>

        <ol v-if="node.replies.length" class="flex flex-col gap-2 pl-6 border-l">
          <li
            v-for="reply in node.replies"
            :id="`inbox-msg-${reply.id}`"
            :key="reply.id"
            class="flex flex-col gap-1 rounded px-2 py-1 -mx-2 cursor-pointer transition-colors"
            :class="selectedMessageId === reply.id
              ? 'ring-2 ring-primary bg-primary/5'
              : 'hover:bg-black/5 dark:hover:bg-white/5'"
            @click="toggleSelected(reply.id)"
          >
            <div
              v-if="reply.id === firstUnreadId"
              class="text-xs uppercase tracking-wide opacity-60"
            >{{ t('inboxThread.newFromHere') }}</div>
            <div class="flex items-baseline gap-2">
              <span class="font-medium text-sm">{{ reply.authorUserId }}</span>
              <span class="text-xs opacity-60">{{ when(reply.createdAt) }}</span>
            </div>
            <p class="whitespace-pre-wrap text-sm">{{ reply.body }}</p>
            <InboxReactionBar
              :reactions="reply.reactions"
              :busy="busy"
              @click.stop
              @react="(key: string, on: boolean) => emit('react', key, on, reply.id)"
            />
          </li>
        </ol>
      </li>
    </ol>
    <p v-else class="text-sm opacity-60">{{ t('inboxThread.empty') }}</p>

    <!-- Composer. Contributing is not deciding: this posts a message and leaves
         the answer buttons above untouched. -->
    <div class="flex flex-col gap-2">
      <div v-if="replyTo" class="flex items-center gap-2 text-xs opacity-70">
        <span>{{ t('inboxThread.replyingTo') }}</span>
        <VButton size="sm" variant="ghost" @click="replyTo = null">
          {{ t('inboxThread.cancelReply') }}
        </VButton>
      </div>
      <VTextarea
        v-model="draft"
        :rows="3"
        :placeholder="t('inboxThread.placeholder')"
        :disabled="busy"
      />
      <div class="flex items-center justify-between gap-2">
        <div class="flex items-center gap-2">
          <div class="w-48">
            <VInput
              v-model="inviteName"
              :placeholder="t('inboxThread.invitePlaceholder')"
              :disabled="busy"
            />
          </div>
          <VButton size="sm" variant="ghost" :disabled="busy" @click="submitInvite">
            {{ t('inboxThread.invite') }}
          </VButton>
        </div>
        <VButton :disabled="busy || !draft.trim()" @click="submit">
          {{ t('inboxThread.send') }}
        </VButton>
      </div>
    </div>
  </section>
</template>
