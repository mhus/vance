<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VButton, VInput, VTextarea, VToggle } from '@vance/components';
import { getUsername } from '@vance/shared';
import type { MaximegalonDto, MaximegalonMessageDto } from '@vance/generated';

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
}>();

const emit = defineEmits<{
  (e: 'post', body: string, parentId: string | null): void;
  (e: 'read'): void;
  (e: 'invite', userId: string): void;
  (e: 'follow', following: boolean): void;
  (e: 'react', key: string, on: boolean, messageId: string | null): void;
}>();

const { t } = useI18n();
const me = computed<string>(() => getUsername() ?? '');

/**
 * The reaction palette, as explicit shortcode/character pairs.
 *
 * <p>Not {@code VEmojiPicker}: that one emits the unicode character and offers
 * a topic set built for document icons. The wire format wants shortcodes on
 * purpose — 👍 and 👍🏽 are different codepoints and would file the same
 * reaction twice. A fixed palette sidesteps the mapping entirely, and these six
 * are the ones that do work in a clarification: agree, on it, done, unclear,
 * thanks, celebrate.
 */
const PALETTE: ReadonlyArray<{ key: string; char: string }> = [
  { key: 'thumbsup', char: '👍' },
  { key: 'eyes', char: '👀' },
  { key: 'white_check_mark', char: '✅' },
  { key: 'question', char: '❓' },
  { key: 'pray', char: '🙏' },
  { key: 'tada', char: '🎉' },
];

// ── Read reporting ───────────────────────────────────────────────────
//
// Once per thread id: re-reporting on every re-render would be a write per
// keystroke in the composer.
const reportedFor = ref<string | null>(null);

watch(() => props.item.id, (id) => {
  if (!id || reportedFor.value === id) return;
  reportedFor.value = id;
  if (hasUnreadForMe.value) emit('read');
}, { immediate: true });

/** Mirrors the server's rule, so we only report when there is something to report. */
const hasUnreadForMe = computed<boolean>(() => {
  if (!props.item.readBy?.includes(me.value)) return true;
  return (props.item.messages ?? []).some((m) => !m.readBy?.includes(me.value));
});

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

/**
 * Where the "new from here" line goes: the first message this user has not
 * read. Derived from what is on screen and not persisted — it only has to
 * survive looking at the thread, not the session.
 */
const firstUnreadId = computed<string | null>(() =>
  (props.item.messages ?? []).find((m) => !m.readBy?.includes(me.value))?.id ?? null);

const following = computed<boolean>(() => props.item.participants?.includes(me.value) ?? false);

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

function submitInvite(): void {
  const name = inviteName.value.trim();
  if (!name || props.busy) return;
  emit('invite', name);
  inviteName.value = '';
}

function toggleReaction(message: MaximegalonMessageDto | null, key: string): void {
  const source = message ? message.reactions : props.item.reactions;
  const mine = source?.find((r) => r.key === key)?.userIds?.includes(me.value) ?? false;
  emit('react', key, !mine, message?.id ?? null);
}

function reactionCount(message: MaximegalonMessageDto | null, key: string): number {
  const source = message ? message.reactions : props.item.reactions;
  return source?.find((r) => r.key === key)?.userIds?.length ?? 0;
}

function reactedByMe(message: MaximegalonMessageDto | null, key: string): boolean {
  const source = message ? message.reactions : props.item.reactions;
  return source?.find((r) => r.key === key)?.userIds?.includes(me.value) ?? false;
}

/** The generator maps Instant to Date; a string still arrives over the wire. */
function when(at: Date | string | undefined): string {
  return at ? new Date(at).toLocaleString() : '';
}
</script>

<template>
  <section class="flex flex-col gap-4 border-t pt-4 mt-4">
    <!-- Participants + follow. The list is who gets updates; the toggle is
         how you leave. An assignee with an open ask is refused by the server
         (a process waits on them) — the error surfaces above. -->
    <header class="flex flex-wrap items-center justify-between gap-2">
      <div class="flex flex-wrap items-center gap-2">
        <span class="text-sm opacity-70">{{ t('inboxThread.participants') }}</span>
        <span
          v-for="p in item.participants"
          :key="p"
          class="text-sm px-2 py-0.5 rounded bg-black/5 dark:bg-white/10"
        >{{ p }}</span>
        <span v-if="item.teamId" class="text-sm opacity-70">
          {{ t('inboxThread.team', { team: item.teamId }) }}
        </span>
      </div>
      <VToggle
        :model-value="following"
        :title="t('inboxThread.followTitle')"
        :label="t('inboxThread.follow')"
        @update:model-value="(v: boolean) => emit('follow', v)"
      />
    </header>

    <!-- Reactions on the thread's own question. -->
    <div class="flex flex-wrap items-center gap-1">
      <VButton
        v-for="r in PALETTE"
        :key="r.key"
        size="sm"
        :variant="reactedByMe(null, r.key) ? 'primary' : 'ghost'"
        :title="r.key"
        @click="toggleReaction(null, r.key)"
      >
        {{ r.char }}<span v-if="reactionCount(null, r.key)" class="ml-1 text-xs">{{
          reactionCount(null, r.key)
        }}</span>
      </VButton>
    </div>

    <!-- The clarification. -->
    <ol v-if="tree.length" class="flex flex-col gap-3">
      <li v-for="node in tree" :key="node.message.id" class="flex flex-col gap-2">
        <div
          v-if="node.message.id === firstUnreadId"
          class="text-xs uppercase tracking-wide opacity-60"
        >{{ t('inboxThread.newFromHere') }}</div>

        <article class="flex flex-col gap-1">
          <div class="flex items-baseline gap-2">
            <span class="font-medium text-sm">{{ node.message.authorUserId }}</span>
            <span class="text-xs opacity-60">{{ when(node.message.createdAt) }}</span>
          </div>
          <p class="whitespace-pre-wrap text-sm">{{ node.message.body }}</p>
          <div class="flex flex-wrap items-center gap-1">
            <VButton
              v-for="r in PALETTE"
              :key="r.key"
              size="sm"
              :variant="reactedByMe(node.message, r.key) ? 'primary' : 'ghost'"
              :title="r.key"
              @click="toggleReaction(node.message, r.key)"
            >
              {{ r.char }}<span
                v-if="reactionCount(node.message, r.key)"
                class="ml-1 text-xs"
              >{{ reactionCount(node.message, r.key) }}</span>
            </VButton>
            <VButton size="sm" variant="ghost" @click="startReply(node.message.id)">
              {{ t('inboxThread.reply') }}
            </VButton>
          </div>
        </article>

        <!-- One level of replies; deeper nesting is refused by the server. -->
        <ol v-if="node.replies.length" class="flex flex-col gap-2 pl-6 border-l">
          <li v-for="reply in node.replies" :key="reply.id" class="flex flex-col gap-1">
            <div
              v-if="reply.id === firstUnreadId"
              class="text-xs uppercase tracking-wide opacity-60"
            >{{ t('inboxThread.newFromHere') }}</div>
            <div class="flex items-baseline gap-2">
              <span class="font-medium text-sm">{{ reply.authorUserId }}</span>
              <span class="text-xs opacity-60">{{ when(reply.createdAt) }}</span>
            </div>
            <p class="whitespace-pre-wrap text-sm">{{ reply.body }}</p>
            <div class="flex flex-wrap items-center gap-1">
              <VButton
                v-for="r in PALETTE"
                :key="r.key"
                size="sm"
                :variant="reactedByMe(reply, r.key) ? 'primary' : 'ghost'"
                :title="r.key"
                @click="toggleReaction(reply, r.key)"
              >
                {{ r.char }}<span
                  v-if="reactionCount(reply, r.key)"
                  class="ml-1 text-xs"
                >{{ reactionCount(reply, r.key) }}</span>
              </VButton>
            </div>
          </li>
        </ol>
      </li>
    </ol>
    <p v-else class="text-sm opacity-60">{{ t('inboxThread.empty') }}</p>

    <!-- Composer. Contributing is not deciding: this posts a message and
         leaves the answer buttons above untouched. -->
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
