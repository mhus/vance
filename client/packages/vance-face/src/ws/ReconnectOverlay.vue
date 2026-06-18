<script setup lang="ts">
/**
 * Global reconnect overlay — shown whenever the tab-singleton
 * {@code wsConnectionStore} is in {@code 'reconnecting'} or
 * {@code 'down'} state. Blocks the rest of the UI via a fullscreen
 * native {@code &lt;dialog&gt;} so the user can't take an action
 * (compose, send, switch session, …) that would silently fail until
 * the connection comes back.
 *
 * <p>States rendered:
 * <ul>
 *   <li>{@code reconnecting} — Spinner + "Verbindung verloren — versuche
 *       Wiederherstellung… (Versuch X/Y)". No button — user just waits.</li>
 *   <li>{@code down} — Error text + manual "Erneut versuchen"-Button.
 *       The button resets the backoff and triggers another attempt.</li>
 * </ul>
 *
 * <p>The overlay also auto-closes (i.e. unmounts itself implicitly via
 * the {@code v-if}) once the store flips back to {@code 'connected'} —
 * no animation, the rest of the UI is back to normal immediately.
 */
import { useI18n } from 'vue-i18n';
import { manualReconnect, useWsConnection } from './wsConnectionStore';

const { t } = useI18n();
const { status, reconnectAttempts, maxReconnectAttempts, lastError } =
  useWsConnection();
</script>

<template>
  <Teleport to="body">
    <div
      v-if="status === 'reconnecting' || status === 'down'"
      class="fixed inset-0 z-[9999] flex items-center justify-center
             bg-base-300/70 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      :aria-label="t('reconnect.title')"
    >
      <div
        class="bg-base-100 border border-base-300 rounded-lg shadow-xl
               p-6 max-w-sm w-full mx-4 text-center"
      >
        <div class="text-base font-medium text-base-content mb-3">
          {{ t('reconnect.title') }}
        </div>

        <div
          v-if="status === 'reconnecting'"
          class="flex flex-col items-center gap-3 text-sm text-base-content/70"
        >
          <svg
            class="animate-spin h-6 w-6 text-primary"
            xmlns="http://www.w3.org/2000/svg"
            fill="none"
            viewBox="0 0 24 24"
            aria-hidden="true"
          >
            <circle
              class="opacity-25"
              cx="12"
              cy="12"
              r="10"
              stroke="currentColor"
              stroke-width="4"
            />
            <path
              class="opacity-75"
              fill="currentColor"
              d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z"
            />
          </svg>
          <div>
            {{ t('reconnect.attempting', {
              n: reconnectAttempts,
              total: maxReconnectAttempts,
            }) }}
          </div>
        </div>

        <div v-else class="flex flex-col items-center gap-4">
          <div class="text-sm text-base-content/70">
            {{ t('reconnect.giveUp') }}
          </div>
          <div
            v-if="lastError"
            class="text-xs text-base-content/50 font-mono break-all"
          >
            {{ lastError }}
          </div>
          <button
            type="button"
            class="btn btn-primary btn-sm"
            @click="manualReconnect"
          >
            {{ t('reconnect.tryAgain') }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
