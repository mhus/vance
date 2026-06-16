<script setup lang="ts">
/**
 * Toast overlay for the user-notification side-channel — fixed in the
 * top-right corner above the EditorShell grid. Mounted globally by
 * EditorShell so every editor surface gets the same UI without
 * per-page wiring.
 *
 * <p>Spec: specification/user-notification-channel.md
 */
import { useNotificationStore } from './notificationStore';

const store = useNotificationStore();

function colorClass(severity: string): string {
  switch (severity) {
    case 'WARN': return 'bg-warning/15 text-warning border-warning/40';
    case 'ERROR': return 'bg-error/15 text-error border-error/40';
    case 'INFO':
    default: return 'bg-info/15 text-info border-info/40';
  }
}

function badgeClass(severity: string): string {
  switch (severity) {
    case 'WARN': return 'bg-warning text-warning-content';
    case 'ERROR': return 'bg-error text-error-content';
    case 'INFO':
    default: return 'bg-info text-info-content';
  }
}

function sourceLine(n: { sourceProcessTitle?: string; sourceProcessName?: string }): string {
  return n.sourceProcessTitle ?? n.sourceProcessName ?? '';
}
</script>

<template>
  <div class="notify-toast-stack" aria-live="polite" aria-atomic="false">
    <TransitionGroup name="notify-toast">
      <div
        v-for="t in store.toasts"
        :key="t.id"
        class="notify-toast"
        :class="colorClass(t.notification.severity)"
        @click="store.dismiss(t.id)"
      >
        <div class="flex items-center gap-2 text-xs font-semibold uppercase tracking-wide">
          <span
            class="px-1.5 py-0.5 rounded text-[10px]"
            :class="badgeClass(t.notification.severity)"
          >{{ t.notification.severity }}</span>
          <span v-if="sourceLine(t.notification)" class="opacity-70 truncate">
            {{ sourceLine(t.notification) }}
          </span>
        </div>
        <div class="mt-1 text-sm break-words">{{ t.notification.text }}</div>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.notify-toast-stack {
  position: fixed;
  top: 4rem;
  right: 1rem;
  z-index: 100;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  width: 22rem;
  max-width: calc(100vw - 2rem);
  pointer-events: none;
}
.notify-toast {
  pointer-events: auto;
  cursor: pointer;
  border: 1px solid;
  border-radius: 0.5rem;
  padding: 0.6rem 0.75rem;
  background-color: hsl(var(--b1));
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.15);
}
.notify-toast-enter-from {
  opacity: 0;
  transform: translateX(20px);
}
.notify-toast-leave-to {
  opacity: 0;
  transform: translateX(20px);
}
.notify-toast-enter-active,
.notify-toast-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}
</style>
