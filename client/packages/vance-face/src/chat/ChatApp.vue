<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, computed, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  BrainWebSocket,
  WebSocketRequestError,
  getTenantId,
  getUsername,
  setActiveSessionId,
  getActiveSessionId,
} from '@vance/shared';
import type {
  SessionResumeRequest,
  SessionResumeResponse,
} from '@vance/generated';
import { EditorShell, VAlert, VButton } from '@components/index';
import PickerView from './PickerView.vue';
import ChatView from './ChatView.vue';

const { t } = useI18n();

const CLIENT_VERSION = '0.1.0';

type Mode = 'connecting' | 'picker' | 'live' | 'occupied' | 'failed';

const mode = ref<Mode>('connecting');
const errorMessage = ref<string | null>(null);
const socket = ref<BrainWebSocket | null>(null);
const activeSessionId = ref<string | null>(null);

const username = computed<string | null>(() => getUsername());

const connectionState = computed<'connected' | 'idle' | 'occupied' | undefined>(() => {
  if (mode.value === 'live') return 'connected';
  if (mode.value === 'occupied') return 'occupied';
  if (mode.value === 'picker' || mode.value === 'connecting') return 'idle';
  return undefined;
});

function urlSessionId(): string | null {
  const params = new URLSearchParams(window.location.search);
  const id = params.get('sessionId');
  return id && id.length > 0 ? id : null;
}

function pushSessionIdToUrl(sessionId: string | null): void {
  const url = new URL(window.location.href);
  if (sessionId) url.searchParams.set('sessionId', sessionId);
  else url.searchParams.delete('sessionId');
  window.history.replaceState(null, '', url.toString());
}

async function openSocket(): Promise<BrainWebSocket> {
  const tenant = getTenantId();
  if (!tenant) {
    throw new Error('Missing tenant — cannot open chat connection.');
  }
  // Same-origin upgrade ships the {@code vance_access} cookie
  // automatically. No JWT lookup in JS — that's the whole point of
  // the cookie-based auth flow.
  return BrainWebSocket.connect({
    tenant,
    profile: 'web',
    clientVersion: CLIENT_VERSION,
  });
}

async function resumeSessionId(sessionId: string): Promise<void> {
  if (!socket.value) return;
  try {
    await socket.value.send<SessionResumeRequest, SessionResumeResponse>(
      'session-resume', { sessionId });
    activeSessionId.value = sessionId;
    setActiveSessionId(sessionId);
    pushSessionIdToUrl(sessionId);
    mode.value = 'live';
  } catch (e) {
    if (e instanceof WebSocketRequestError) {
      switch (e.errorCode) {
        case 409:
          mode.value = 'occupied';
          errorMessage.value = t('chat.sessionOccupiedBy', { id: sessionId });
          return;
        case 404:
          // Stale sessionId (closed or never existed) — drop it and fall back to picker.
          setActiveSessionId(null);
          pushSessionIdToUrl(null);
          mode.value = 'picker';
          errorMessage.value = t('chat.sessionNotFound', { id: sessionId });
          return;
        case 403:
          mode.value = 'failed';
          errorMessage.value = t('chat.sessionForbidden', { id: sessionId });
          return;
      }
    }
    mode.value = 'failed';
    errorMessage.value = e instanceof Error ? e.message : t('chat.failedToResume');
  }
}

async function onSessionPicked(sessionId: string): Promise<void> {
  errorMessage.value = null;
  await resumeSessionId(sessionId);
}

async function onSessionBootstrapped(sessionId: string): Promise<void> {
  // session-bootstrap binds the socket as a side effect; no extra resume.
  errorMessage.value = null;
  activeSessionId.value = sessionId;
  setActiveSessionId(sessionId);
  pushSessionIdToUrl(sessionId);
  mode.value = 'live';
}

async function leaveLive(): Promise<void> {
  // Guard against accidental loss: the WS will be unbound and any
  // composer draft / unread progress disappears. Native confirm() is
  // intentional — a modal dialog buys polish but adds component
  // weight, and this is the only confirmation surface in the chat
  // editor for now.
  const ok = window.confirm(t('chat.confirmLeave'));
  if (!ok) return;
  if (socket.value && !socket.value.closed()) {
    socket.value.sendNoReply('session-unbind');
  }
  activeSessionId.value = null;
  pushSessionIdToUrl(null);
  // localStorage.activeSessionId stays — it's a hint for next visit, not state.
  mode.value = 'picker';
}

/**
 * Browser tab close / navigation guard. Active only while in live
 * mode — the picker has no state worth protecting. The actual prompt
 * text is browser-controlled (locked down for anti-spam reasons), so
 * we only need to set {@code returnValue} to a non-empty string.
 */
function beforeUnloadGuard(event: BeforeUnloadEvent): void {
  event.preventDefault();
  event.returnValue = '';
}

watch(mode, (next, prev) => {
  if (next === 'live' && prev !== 'live') {
    window.addEventListener('beforeunload', beforeUnloadGuard);
  } else if (prev === 'live' && next !== 'live') {
    window.removeEventListener('beforeunload', beforeUnloadGuard);
  }
});

function backToPicker(): void {
  errorMessage.value = null;
  pushSessionIdToUrl(null);
  mode.value = 'picker';
}

onMounted(async () => {
  try {
    socket.value = await openSocket();
  } catch (e) {
    mode.value = 'failed';
    errorMessage.value = e instanceof Error ? e.message : t('chat.failedToOpen');
    return;
  }

  socket.value.onClose(() => {
    if (mode.value === 'live') {
      mode.value = 'failed';
      errorMessage.value = t('chat.connectionLost');
    } else if (mode.value === 'picker' || mode.value === 'occupied') {
      mode.value = 'failed';
      errorMessage.value = t('chat.connectionClosed');
    }
  });

  // Resume hint: URL param wins over localStorage.
  const hinted = urlSessionId() ?? getActiveSessionId();
  if (hinted) {
    await resumeSessionId(hinted);
  } else {
    mode.value = 'picker';
  }
});

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', beforeUnloadGuard);
  socket.value?.close();
});
</script>

<template>
  <EditorShell
    :title="$t('chat.pageTitle')"
    :connection-state="connectionState"
    :full-height="true"
  >
    <div class="h-full min-h-0 flex flex-col">
      <div v-if="errorMessage" class="px-6 pt-4">
        <VAlert :variant="mode === 'occupied' ? 'warning' : 'error'">
          {{ errorMessage }}
          <template v-if="mode === 'occupied'">
            <div class="mt-2 flex gap-2">
              <VButton variant="secondary" @click="backToPicker">
                {{ $t('chat.pickAnotherSession') }}
              </VButton>
              <VButton variant="ghost" @click="resumeSessionId(activeSessionId ?? '')">
                {{ $t('chat.tryAgain') }}
              </VButton>
            </div>
          </template>
          <template v-else-if="mode === 'failed'">
            <div class="mt-2">
              <VButton variant="secondary" @click="backToPicker">
                {{ $t('chat.backToPicker') }}
              </VButton>
            </div>
          </template>
        </VAlert>
      </div>

      <div class="flex-1 min-h-0">
        <PickerView
          v-if="mode === 'picker' && socket"
          :socket="socket"
          :username="username"
          @session-picked="onSessionPicked"
          @session-bootstrapped="onSessionBootstrapped"
        />

        <ChatView
          v-else-if="mode === 'live' && socket && activeSessionId"
          :socket="socket"
          :session-id="activeSessionId"
          @leave="leaveLive"
        />

        <div v-else-if="mode === 'connecting'" class="p-6 text-sm opacity-60">
          {{ $t('chat.connecting') }}
        </div>
      </div>
    </div>
  </EditorShell>
</template>
