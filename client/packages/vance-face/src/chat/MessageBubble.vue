<script setup lang="ts">
import { computed } from 'vue';
import { MarkdownView } from '@components/index';

// Role on the wire is the Java enum *name* (`"USER"` / `"ASSISTANT"` /
// `"SYSTEM"`) — Jackson serialises enums by name. We don't import
// {@code ChatRole} from `@vance/generated` here because it is generated
// as a *numeric* enum, which makes Vue's runtime prop-type check
// expect a Number and warn on every incoming string. Treat the role
// as a plain string at the component boundary instead.
type RoleName = 'USER' | 'ASSISTANT' | 'SYSTEM';

const props = defineProps<{
  role: RoleName | string;
  content: string;
  /** ISO string or epoch-millis number — both rendered as relative/local. */
  createdAt?: string | number | Date;
  /** True if the bubble is still streaming (no canonical message yet). */
  streaming?: boolean;
}>();

const isUser = computed(() => props.role === 'USER');
const isAssistant = computed(() => props.role === 'ASSISTANT');
const isSystem = computed(() => props.role === 'SYSTEM');

const formatted = computed<string>(() => {
  if (!props.createdAt) return '';
  const d = props.createdAt instanceof Date ? props.createdAt : new Date(props.createdAt);
  if (isNaN(d.getTime())) return '';
  return d.toLocaleTimeString();
});
</script>

<template>
  <div
    class="flex"
    :class="isUser ? 'justify-end' : 'justify-start'"
  >
    <div
      class="max-w-[85%] rounded-2xl px-4 py-2.5 shadow-sm"
      :class="[
        isUser ? 'bg-primary text-primary-content' : '',
        isAssistant ? 'bg-base-100 border border-base-300' : '',
        isSystem ? 'bg-base-200 text-sm italic opacity-80' : '',
      ]"
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
