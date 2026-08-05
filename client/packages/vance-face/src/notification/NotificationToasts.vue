<script setup lang="ts">
/**
 * Toast overlay for the user-notification side-channel — fixed in the
 * top-right corner above the EditorShell grid. Mounted globally by
 * EditorShell so every editor surface gets the same UI without
 * per-page wiring.
 *
 * <p>Spec: specification/user-notification-channel.md
 *
 * <p>Severity rendering: solid card on the base-100 color, severity
 * communicated through a 4px left-edge strip + the badge color. Avoid
 * tinting the background itself — DaisyUI's {@code bg-{color}/{alpha}}
 * modifier makes the card half-transparent, which reads as "broken
 * CSS" on most user setups.
 */
import { useNotificationStore } from './notificationStore';

const { toasts, dismiss } = useNotificationStore();

function severityClass(severity: string): string {
  switch (severity) {
    case 'WARN': return 'notify-toast--warn';
    case 'ERROR': return 'notify-toast--error';
    case 'INFO':
    default: return 'notify-toast--info';
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
        v-for="t in toasts"
        :key="t.id"
        class="notify-toast"
        :class="severityClass(t.notification.severity)"
        @click="dismiss(t.id)"
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
        <div class="mt-1 text-sm break-words text-base-content">{{ t.notification.text }}</div>
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
  border: 1px solid color-mix(in oklab, var(--color-base-content) 18%, transparent);
  border-left: 4px solid var(--color-info);
  border-radius: 0.5rem;
  padding: 0.6rem 0.75rem;
  /* Solid base-100 background — the toast must never be see-through. */
  background-color: var(--color-base-100);
  color: var(--color-base-content);
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.18);
}
.notify-toast--info  { border-left-color: var(--color-info); }
.notify-toast--warn  { border-left-color: var(--color-warning); }
.notify-toast--error { border-left-color: var(--color-error); }
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
