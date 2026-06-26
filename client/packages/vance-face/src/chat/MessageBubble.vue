<script setup lang="ts">
import { computed, ref } from 'vue';
import { MarkdownView } from '@components/index';
import { uiTheme, paletteStyle } from '@composables/useUiTheme';
import { useI18n } from 'vue-i18n';
import QuestionCanvas, { type QuestionOption } from './QuestionCanvas.vue';

// Action-type values mirror constants in
// {@code ChatMessageDocument.ACTION_TYPE_*}.
const ACTION_TYPE_ASK_USER = 'ASK_USER';
const ACTION_TYPE_REJECT = 'REJECT';
const ACTION_TYPE_WAIT = 'WAIT';

// Role on the wire is the Java enum *name* (`"USER"` / `"ASSISTANT"` /
// `"SYSTEM"`) — Jackson serialises enums by name. We don't import
// {@code ChatRole} from `@vance/generated` here because it is generated
// as a *numeric* enum, which makes Vue's runtime prop-type check
// expect a Number and warn on every incoming string. Treat the role
// as a plain string at the component boundary instead.
type RoleName = 'USER' | 'ASSISTANT' | 'SYSTEM';

/** ASK_USER picker option (mirrors the {@code label/description} schema
 *  defined in {@code ArthurActionSchema} / {@code EddieActionSchema}).
 *  Kept as a re-export so existing call-sites stay valid; the new
 *  canonical name is {@link QuestionOption} in QuestionCanvas. */
export type AskUserOption = QuestionOption;

const props = withDefaults(defineProps<{
  role: RoleName | string;
  content: string;
  /** ISO string or epoch-millis number — both rendered as relative/local. */
  createdAt?: string | number | Date;
  /** True if the bubble is still streaming (no canonical message yet). */
  streaming?: boolean;
  /**
   * True for sub-process (worker) chat echoes — recipes spawned by the
   * main chat (e.g. {@code rezept-suche}). Renders compact, green, and
   * truncated to {@link #lineMaxChars} so the worker chatter doesn't
   * compete with the main thread for visual weight. Mirrors the foot
   * client's {@code worker()} channel.
   */
  worker?: boolean;
  /** Optional sub-process name shown as a prefix in worker mode. */
  processName?: string;
  /** Max characters for worker truncation (0 = disabled). Defaults to
   *  the env-configured {@code uiTheme.lineMaxChars}. */
  lineMaxChars?: number;
  /**
   * Optional structured metadata mirroring
   * {@code ChatMessageDto.meta}. Today we only consume
   * {@code askUserOptions}; future keys (typed side-channels) are
   * ignored gracefully.
   */
  meta?: Record<string, unknown>;
  /**
   * When the meta carries picker options, this flag controls whether
   * the buttons are still actionable. Set false once the user has
   * answered (a later USER message landed) so stale buttons grey out
   * instead of double-firing the same answer.
   */
  optionsActionable?: boolean;
  /**
   * UserId of the message author for USER turns (from
   * {@code ChatMessageDto.senderUserId}). {@code null} for legacy rows
   * and for non-USER roles — those render the way they always have.
   * See planning/multi-user-sessions.md §6.
   */
  senderUserId?: string | null;
  /** Display-name of the message author (from senderDisplayName). */
  senderDisplayName?: string | null;
  /**
   * Authenticated user of the current tab — used to decide whether a
   * USER bubble is "mine" (right-side / primary colour) or "someone
   * else's" (left-side / accent colour, with display-name header).
   */
  currentUserId?: string | null;
}>(), {
  worker: false,
  lineMaxChars: () => uiTheme.lineMaxChars,
  optionsActionable: true,
});

const emit = defineEmits<{
  (e: 'pickOption', label: string): void;
}>();

const askUserOptions = computed<AskUserOption[]>(() => {
  const raw = props.meta?.['askUserOptions'];
  if (!Array.isArray(raw)) return [];
  const out: AskUserOption[] = [];
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue;
    const obj = item as Record<string, unknown>;
    const label = obj['label'];
    if (typeof label !== 'string' || !label.trim()) continue;
    const desc = obj['description'];
    out.push({
      label: label.trim(),
      description: typeof desc === 'string' && desc.trim() ? desc.trim() : undefined,
    });
  }
  return out;
});

/**
 * Engine-action type from {@code meta.actionType}. Drives the
 * render-mode dispatch — see spec §11. Absent on USER messages,
 * fallback-text replies (LLM emitted raw text instead of an
 * arthur_action / eddie_action tool call), and legacy messages
 * persisted before the actionType tagging landed.
 */
const actionType = computed<string | null>(() => {
  const v = props.meta?.['actionType'];
  return typeof v === 'string' && v.trim() ? v.trim().toUpperCase() : null;
});

const isAskUser = computed(() =>
  actionType.value === ACTION_TYPE_ASK_USER || askUserOptions.value.length > 0,
);
const isReject = computed(() => actionType.value === ACTION_TYPE_REJECT);
const isWait   = computed(() => actionType.value === ACTION_TYPE_WAIT);

function onPick(label: string): void {
  if (!props.optionsActionable) return;
  emit('pickOption', label);
}

const isUser = computed(() => props.role === 'USER');
const isAssistant = computed(() => props.role === 'ASSISTANT');
const isSystem = computed(() => props.role === 'SYSTEM');

/**
 * Multi-user awareness — see planning/multi-user-sessions.md §6.
 *
 * - {@code isOtherUser} is true for USER bubbles that another
 *   participant wrote (their {@code senderUserId} differs from the
 *   current tab's authenticated user). Legacy rows without
 *   {@code senderUserId} stay treated as "mine" for backward
 *   compatibility — they predate the multi-user wiring.
 * - {@code otherDisplayName} is the label rendered above the
 *   foreign-user bubble.
 */
const isOtherUser = computed(() => {
  if (!isUser.value) return false;
  const sender = props.senderUserId;
  if (!sender) return false;
  const me = props.currentUserId;
  if (!me) return false;
  return sender !== me;
});

const otherDisplayName = computed<string>(() => {
  const name = props.senderDisplayName;
  if (name && name.trim()) return name;
  return props.senderUserId ?? '';
});

/**
 * Deterministic accent colour per author so the same participant
 * keeps the same chip across the session. Mirrors the palette used
 * by {@code SessionParticipants.vue}.
 */
const PALETTE = [
  '#ef4444', '#f97316', '#f59e0b', '#84cc16',
  '#22c55e', '#14b8a6', '#06b6d4', '#3b82f6',
  '#6366f1', '#8b5cf6', '#a855f7', '#ec4899',
];

const otherUserColour = computed<string>(() => {
  const userId = props.senderUserId ?? '';
  let hash = 0;
  for (let i = 0; i < userId.length; i++) {
    hash = ((hash << 5) - hash + userId.charCodeAt(i)) | 0;
  }
  return PALETTE[Math.abs(hash) % PALETTE.length];
});

/**
 * True when the message contains rich-content artifacts (fenced code
 * blocks with a kind tag, or {@code vance:} Markdown links). Such
 * messages get a full-width bubble so the {@code <KindBox>} canvas
 * (mindmap, table, graph, PDF preview, …) is readable rather than
 * squeezed into the chat's default {@code max-w-[85%]}. See
 * specification/inline-and-embedded-content.md §11.6.
 */
const hasRichContent = computed<boolean>(() => {
  const src = props.content;
  if (!src) return false;
  // Fenced block with non-empty lang tag (the kind discriminator).
  if (/^ {0,3}```[A-Za-z][\w-]*/m.test(src)) return true;
  // Markdown link or image with vance: URI.
  if (/!?\[[^\]]*\]\(vance:/.test(src)) return true;
  return false;
});

/**
 * Display-form of the message body. Replaces the engine-internal
 * "--- BEGIN/END CHILD REPLY ---" framing that
 * {@code ParentNotificationListener.enrichWithLastReply} emits in
 * {@code <process-event>} markers with compact visual chevrons
 * (>>>> / <<<<). Also drops the diagnostic lead-in line that
 * accompanies the BEGIN marker. New RELAY-output is already stripped
 * server-side by Arthur/Eddie's {@code unwrapChildReply}; this is
 * the safety net for historical chat-messages and for any fallback
 * paths where the LLM regurgitates the marker as content.
 */
const displayContent = computed<string>(() => {
  const src = props.content;
  if (!src) return '';
  return src
    .replace(/Last assistant reply from this child \(verbatim\):\s*\n?/g, '')
    .replace(/\n?-{3,}\s*BEGIN CHILD REPLY\s*-{3,}\n?/g, '\n\n>>>>\n')
    .replace(/\n?-{3,}\s*END CHILD REPLY\s*-{3,}\n?/g, '\n<<<<\n\n');
});

const workerText = computed<string>(() => {
  if (!props.worker) return '';
  const max = props.lineMaxChars;
  // Collapse newlines so a long multi-line worker reply stays one
  // visual row — the user only needs the gist; full content is in
  // the engine's own log if they want detail.
  const flat = props.content.replace(/\s+/g, ' ').trim();
  if (max <= 0 || flat.length <= max) return flat;
  return flat.slice(0, Math.max(0, max - 3)) + '...';
});

const formatted = computed<string>(() => {
  if (!props.createdAt) return '';
  const d = props.createdAt instanceof Date ? props.createdAt : new Date(props.createdAt);
  if (isNaN(d.getTime())) return '';
  return d.toLocaleTimeString();
});

// Inline-style overrides from env. Resolved at module load (Vite
// inlines `import.meta.env` at build time), so these don't need to
// be reactive.
const workerStyle = computed(() => paletteStyle(uiTheme.worker));
const userStyle = computed(() => paletteStyle(uiTheme.user));
const assistantStyle = computed(() => paletteStyle(uiTheme.assistant));
const systemStyle = computed(() => paletteStyle(uiTheme.system));

const bubbleStyle = computed(() => {
  if (isUser.value) return userStyle.value;
  if (isAssistant.value) return assistantStyle.value;
  if (isSystem.value) return systemStyle.value;
  return null;
});

const { t: _ } = useI18n();

/**
 * Show the hover-Copy affordance on regular USER/ASSISTANT bubbles with
 * non-empty content. ASK_USER, WAIT and REJECT bubbles render their own
 * interactive surface (option buttons / progress dots) — adding a copy
 * shortcut on top of those would muddy the visual contract.
 */
const canCopyContent = computed(() =>
  !props.worker
  && (isUser.value || isAssistant.value)
  && !isAskUser.value
  && !isWait.value
  && !isReject.value
  && (props.content?.trim().length ?? 0) > 0,
);

const copyJustHappened = ref(false);
let copyFeedbackTimer: ReturnType<typeof setTimeout> | null = null;

async function onCopyMarkdown(): Promise<void> {
  if (!props.content) return;
  if (typeof navigator === 'undefined' || !navigator.clipboard) return;
  try {
    await navigator.clipboard.writeText(props.content);
    copyJustHappened.value = true;
    if (copyFeedbackTimer) clearTimeout(copyFeedbackTimer);
    copyFeedbackTimer = setTimeout(() => {
      copyJustHappened.value = false;
      copyFeedbackTimer = null;
    }, 1500);
  } catch {
    // Browser blocked clipboard access — fail silently; the user gets
    // no badge and can fall back to manual selection.
  }
}
</script>

<template>
  <!-- Worker-channel echo: compact one-liner, no markdown render.
       Default colour is DaisyUI's `text-success`; an env-defined fg
       override (`VITE_VANCE_COLOR_WORKER`) wins via inline style. -->
  <div v-if="worker" class="flex justify-start">
    <div
      class="max-w-[85%] text-xs truncate flex items-center gap-2"
      :class="workerStyle ? '' : 'text-success/80'"
      :style="workerStyle ?? undefined"
    >
      <span class="font-mono opacity-70">[{{ processName ?? '?' }} · {{ String(role).toLowerCase() }}]</span>
      <span class="truncate">{{ workerText }}</span>
      <span v-if="streaming" class="inline-block w-1.5 h-1.5 rounded-full bg-success animate-pulse shrink-0" />
    </div>
  </div>

  <div
    v-else
    class="flex"
    :class="(isUser && !isOtherUser) ? 'justify-end' : 'justify-start'"
  >
    <div
      class="rounded-2xl px-4 py-2.5 shadow-sm relative group"
      :class="[
        hasRichContent ? 'w-full' : 'max-w-[85%]',
        bubbleStyle ? '' : (isOtherUser ? 'text-white' : ''),
        bubbleStyle ? '' : (isUser && !isOtherUser ? 'bg-primary text-primary-content' : ''),
        bubbleStyle ? '' : (isAssistant ? 'bg-base-100 border border-base-300' : ''),
        bubbleStyle ? '' : (isSystem ? 'bg-base-200 text-sm italic opacity-80' : ''),
      ]"
      :style="isOtherUser && !bubbleStyle
        ? { backgroundColor: otherUserColour }
        : (bubbleStyle ?? undefined)"
    >
      <button
        v-if="canCopyContent"
        type="button"
        class="mb-copy-btn"
        :class="copyJustHappened ? 'mb-copy-btn--done' : ''"
        :title="copyJustHappened
          ? (_?.('chat.bubble.copyDone') ?? 'Copied')
          : (_?.('chat.bubble.copy') ?? 'Copy as Markdown')"
        @click="onCopyMarkdown"
      >{{ copyJustHappened ? '✓' : '⧉' }}</button>
      <div
        v-if="isOtherUser"
        class="text-xs font-semibold mb-1 flex items-center gap-2 opacity-95"
      >
        <span>{{ otherDisplayName }}</span>
        <span v-if="formatted" class="opacity-70 font-normal">· {{ formatted }}</span>
      </div>
      <div
        v-else-if="!isUser"
        class="text-xs opacity-60 mb-1 flex items-center gap-2"
      >
        <span>{{ String(role).toLowerCase() }}</span>
        <span v-if="streaming" class="inline-block w-1.5 h-1.5 rounded-full bg-success animate-pulse" />
        <span v-if="formatted" class="opacity-60">· {{ formatted }}</span>
      </div>
      <!-- ASK_USER question canvas — question text + structured option
           buttons as one closed unit. See spec §11 (meta.actionType
           dispatch). Falls back to legacy showOptions check so old
           messages without actionType still render correctly. -->
      <QuestionCanvas
        v-if="isAskUser"
        :content="displayContent"
        :options="askUserOptions"
        :actionable="optionsActionable"
        @pick="onPick"
      />
      <!-- WAIT — async work in flight; show only a thin status line, no
           full bubble payload. -->
      <div v-else-if="isWait" class="text-xs italic opacity-70">
        <span class="inline-block w-1.5 h-1.5 rounded-full bg-warning animate-pulse mr-2" />
        {{ displayContent }}
      </div>
      <!-- REJECT — out-of-scope refusal; render in muted/warning tone. -->
      <div v-else-if="isReject" class="text-sm italic opacity-80">
        <MarkdownView :source="displayContent" />
      </div>
      <!-- ANSWER / RELAY / untagged: default Markdown rendering. -->
      <MarkdownView v-else :source="displayContent" />
    </div>
  </div>
</template>

<style scoped>
.mb-copy-btn {
  position: absolute;
  top: 0.25rem;
  right: 0.25rem;
  border: none;
  background: hsl(var(--b1) / 0.8);
  backdrop-filter: blur(2px);
  cursor: pointer;
  font-size: 0.85rem;
  line-height: 1;
  padding: 0.2rem 0.4rem;
  border-radius: 0.3rem;
  opacity: 0;
  transition: opacity 120ms ease-out, background 120ms ease-out;
  color: hsl(var(--bc));
  z-index: 1;
}
.group:hover .mb-copy-btn,
.mb-copy-btn:focus-visible {
  opacity: 0.85;
}
.mb-copy-btn:hover {
  opacity: 1;
  background: hsl(var(--b1));
}
.mb-copy-btn--done {
  opacity: 1 !important;
  color: hsl(var(--su));
}
</style>
