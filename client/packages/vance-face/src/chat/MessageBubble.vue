<script setup lang="ts">
import { computed } from 'vue';
import { MarkdownView } from '@components/index';
import { uiTheme, paletteStyle } from '@composables/useUiTheme';

// Role on the wire is the Java enum *name* (`"USER"` / `"ASSISTANT"` /
// `"SYSTEM"`) — Jackson serialises enums by name. We don't import
// {@code ChatRole} from `@vance/generated` here because it is generated
// as a *numeric* enum, which makes Vue's runtime prop-type check
// expect a Number and warn on every incoming string. Treat the role
// as a plain string at the component boundary instead.
type RoleName = 'USER' | 'ASSISTANT' | 'SYSTEM';

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
}>(), {
  worker: false,
  lineMaxChars: () => uiTheme.lineMaxChars,
});

const isUser = computed(() => props.role === 'USER');
const isAssistant = computed(() => props.role === 'ASSISTANT');
const isSystem = computed(() => props.role === 'SYSTEM');

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
    :class="isUser ? 'justify-end' : 'justify-start'"
  >
    <div
      class="max-w-[85%] rounded-2xl px-4 py-2.5 shadow-sm"
      :class="[
        bubbleStyle ? '' : (isUser ? 'bg-primary text-primary-content' : ''),
        bubbleStyle ? '' : (isAssistant ? 'bg-base-100 border border-base-300' : ''),
        bubbleStyle ? '' : (isSystem ? 'bg-base-200 text-sm italic opacity-80' : ''),
      ]"
      :style="bubbleStyle ?? undefined"
    >
      <div
        v-if="!isUser"
        class="text-xs opacity-60 mb-1 flex items-center gap-2"
      >
        <span>{{ String(role).toLowerCase() }}</span>
        <span v-if="streaming" class="inline-block w-1.5 h-1.5 rounded-full bg-success animate-pulse" />
        <span v-if="formatted" class="opacity-60">· {{ formatted }}</span>
      </div>
      <MarkdownView :source="content" />
    </div>
  </div>
</template>
