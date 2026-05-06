<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import type { TodoItem } from '@vance/generated';

// ProcessMode and TodoStatus arrive on the wire as Java enum *names*
// (Jackson default). The generated TS enums are numeric, so importing
// them and comparing with `===` against the wire value would always
// miss. Treat both as string-literal types at the boundary — same
// pattern as MessageBubble's RoleName / ProgressFeed's Kind.
type ProcessModeName = 'NORMAL' | 'EXPLORING' | 'PLANNING' | 'EXECUTING';
type TodoStatusName = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED';

const props = defineProps<{
  mode: ProcessModeName;
  todos: TodoItem[];
  planMeta: { version: number; summary?: string } | null;
}>();

const { t } = useI18n();

const showPending = computed(() => props.mode === 'PLANNING' && props.planMeta !== null);
const showTodos = computed(() => props.todos.length > 0);
const visible = computed(() => showPending.value || showTodos.value);

const planTitle = computed<string>(() => {
  const v = props.planMeta?.version ?? 1;
  return v > 1
    ? t('chat.planMode.planTitleVersioned', { version: v })
    : t('chat.planMode.planTitle');
});

function statusOf(item: TodoItem): TodoStatusName {
  return (item.status as unknown as TodoStatusName) ?? 'PENDING';
}

function markerOf(item: TodoItem): string {
  switch (statusOf(item)) {
    case 'IN_PROGRESS': return '[~]';
    case 'COMPLETED': return '[✓]';
    case 'PENDING':
    default: return '[ ]';
  }
}

function labelOf(item: TodoItem): string {
  if (statusOf(item) === 'IN_PROGRESS' && item.activeForm && item.activeForm.trim()) {
    return item.activeForm;
  }
  return item.content;
}

function rowClassOf(item: TodoItem): string {
  switch (statusOf(item)) {
    case 'IN_PROGRESS': return 'text-warning font-medium';
    case 'COMPLETED': return 'opacity-50 line-through';
    case 'PENDING':
    default: return '';
  }
}
</script>

<template>
  <div
    v-if="visible"
    class="border-t border-base-300 bg-base-200/60 px-6 py-3 flex flex-col gap-3"
  >
    <div
      v-if="showPending"
      class="rounded-lg border border-info/40 bg-info/10 px-3 py-2 text-sm flex flex-col gap-1"
    >
      <div class="flex items-center gap-2">
        <span class="font-semibold text-info">{{ planTitle }}</span>
        <span class="text-xs uppercase tracking-wide opacity-60">
          {{ $t('chat.planMode.awaitingApproval') }}
        </span>
      </div>
      <div v-if="planMeta?.summary" class="text-xs opacity-80 break-words">
        {{ planMeta.summary }}
      </div>
      <div class="text-xs opacity-60">
        {{ $t('chat.planMode.approvalHint') }}
      </div>
    </div>

    <div v-if="showTodos" class="text-xs">
      <div class="uppercase tracking-wide opacity-60 font-semibold mb-1.5">
        {{ $t('chat.planMode.todosTitle') }}
      </div>
      <ul class="flex flex-col gap-0.5 font-mono">
        <li
          v-for="item in todos"
          :key="item.id || item.content"
          :class="rowClassOf(item)"
        >
          <span>{{ markerOf(item) }}</span>
          <span class="ml-2">{{ labelOf(item) }}</span>
        </li>
      </ul>
    </div>
  </div>
</template>
