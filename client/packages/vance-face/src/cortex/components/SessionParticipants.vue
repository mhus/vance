<script setup lang="ts">
import { computed } from 'vue';
import type { SessionParticipantDto } from '@vance/generated';

/**
 * Avatar stack for a multi-user session — drop into the Cortex
 * top-bar or chat header. Shows initials + a colour derived
 * deterministically from the user id so the same participant keeps
 * the same chip across the session. Tooltip on each avatar reveals
 * the full display name.
 *
 * <p>Hidden when only one participant is present — there is no
 * collaboration to display in that case.
 *
 * <p>See {@code planning/multi-user-sessions.md} §6 / §7.
 */
interface Props {
  participants: SessionParticipantDto[];
  /** How many avatars to render before collapsing into a +N badge. */
  max?: number;
}

const props = withDefaults(defineProps<Props>(), {
  max: 4,
});

const visible = computed(() => props.participants.slice(0, props.max));
const overflow = computed(() =>
  Math.max(0, props.participants.length - props.max),
);
const showStack = computed(() => props.participants.length > 1);

function initialsFor(p: SessionParticipantDto): string {
  const source = p.displayName?.trim() || p.userId;
  if (!source) return '?';
  const parts = source.split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

const PALETTE = [
  '#ef4444', '#f97316', '#f59e0b', '#84cc16',
  '#22c55e', '#14b8a6', '#06b6d4', '#3b82f6',
  '#6366f1', '#8b5cf6', '#a855f7', '#ec4899',
];

function colourFor(userId: string): string {
  let hash = 0;
  for (let i = 0; i < userId.length; i++) {
    hash = ((hash << 5) - hash + userId.charCodeAt(i)) | 0;
  }
  return PALETTE[Math.abs(hash) % PALETTE.length];
}

function tooltip(p: SessionParticipantDto): string {
  return p.displayName?.trim() || p.userId;
}
</script>

<template>
  <div v-if="showStack" class="flex items-center -space-x-2">
    <span
      v-for="p in visible"
      :key="p.editorId"
      class="inline-flex h-7 w-7 items-center justify-center rounded-full
             border-2 border-white dark:border-gray-900 text-xs font-semibold text-white shadow"
      :style="{ backgroundColor: colourFor(p.userId) }"
      :title="tooltip(p)"
    >
      {{ initialsFor(p) }}
    </span>
    <span
      v-if="overflow > 0"
      class="inline-flex h-7 w-7 items-center justify-center rounded-full
             border-2 border-white dark:border-gray-900 bg-gray-300 dark:bg-gray-700
             text-xs font-semibold text-gray-800 dark:text-gray-100 shadow"
      :title="participants
        .slice(max)
        .map((p) => p.displayName || p.userId)
        .join(', ')"
    >
      +{{ overflow }}
    </span>
  </div>
</template>
