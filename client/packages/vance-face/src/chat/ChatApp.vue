<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, computed, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  BrainWebSocket,
  WebSocketRequestError,
  type BrainWsApi,
  getTenantId,
  getUsername,
  setActiveSessionId,
  getActiveSessionId,
} from '@vance/shared';
import type {
  SessionResumeRequest,
  SessionResumeResponse,
  SwitchToNotification,
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

/**
 * Banner / UI state shown while the WS is bound to a session Eddie
 * switched us to (spec §8.5). Cleared when we switch back to the hub
 * or pick a different session through the picker. v1 carries only the
 * label needed for the banner — the back-stack lives separately in
 * {@link previousSessionId}.
 */
interface MediationState {
  workerProjectName: string;
}
const mediation = ref<MediationState | null>(null);

/**
 * Single-level back-stack: the session id we were bound to before the
 * current switch-to. {@code null} means we're at the hub already.
 * {@code /hub} closes the current WS and reopens one bound to this
 * id. v2 can grow this into a real stack for multi-hop project
 * context switching.
 */
const previousSessionId = ref<string | null>(null);

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

/**
 * Open a session-less socket and attach the global listeners. Used
 * by the picker, which then drives session-bootstrap on this same
 * socket. The picker's success path goes through
 * {@link onSessionBootstrapped} without re-opening the socket.
 */
async function openSocketForPicker(): Promise<boolean> {
  if (socket.value) {
    switchToUnsubscribe?.();
    switchToUnsubscribe = null;
    socket.value.close();
    socket.value = null;
  }
  try {
    socket.value = await openSocket();
  } catch (e) {
    mode.value = 'failed';
    errorMessage.value = e instanceof Error ? e.message : t('chat.failedToOpen');
    return false;
  }
  attachSocketListeners(socket.value);
  return true;
}

/**
 * Attach onClose + switch-to listeners on the given socket. Pulled
 * out so both paths (session-bound via {@link openAndBind} and
 * session-less via {@link openSocketForPicker}) share the same
 * wiring.
 */
function attachSocketListeners(s: BrainWsApi): void {
  s.onClose(() => {
    if (mode.value === 'live') {
      mode.value = 'failed';
      errorMessage.value = t('chat.connectionLost');
    } else if (mode.value === 'picker' || mode.value === 'occupied') {
      mode.value = 'failed';
      errorMessage.value = t('chat.connectionClosed');
    }
  });
  switchToUnsubscribe = s.on<SwitchToNotification>(
    'switch-to', (data) => { void onSwitchTo(data); });
}

/**
 * Open a fresh WS and bind it to {@code sessionId}. Closes any existing
 * WS first. This is the unified switch path used by both the initial
 * connect (from picker / URL hint) and the server-pushed switch-to
 * frame. Re-registers the switch-to listener on the new socket so a
 * subsequent switch still finds us.
 *
 * @returns {@code true} on a clean bind, {@code false} when resume
 *          failed (mode + errorMessage are populated as a side effect).
 */
async function openAndBind(sessionId: string): Promise<boolean> {
  // Tear down the old socket. close() is idempotent and harmless when
  // there's nothing to close (first connect).
  if (socket.value) {
    switchToUnsubscribe?.();
    switchToUnsubscribe = null;
    socket.value.close();
    socket.value = null;
  }
  try {
    socket.value = await openSocket();
  } catch (e) {
    mode.value = 'failed';
    errorMessage.value = e instanceof Error ? e.message : t('chat.failedToOpen');
    return false;
  }
  attachSocketListeners(socket.value);

  try {
    await socket.value.send<SessionResumeRequest, SessionResumeResponse>(
      'session-resume', { sessionId });
    activeSessionId.value = sessionId;
    setActiveSessionId(sessionId);
    pushSessionIdToUrl(sessionId);
    mode.value = 'live';
    return true;
  } catch (e) {
    if (e instanceof WebSocketRequestError) {
      switch (e.errorCode) {
        case 409:
          mode.value = 'occupied';
          errorMessage.value = t('chat.sessionOccupiedBy', { id: sessionId });
          return false;
        case 404:
          setActiveSessionId(null);
          pushSessionIdToUrl(null);
          mode.value = 'picker';
          errorMessage.value = t('chat.sessionNotFound', { id: sessionId });
          return false;
        case 403:
          mode.value = 'failed';
          errorMessage.value = t('chat.sessionForbidden', { id: sessionId });
          return false;
      }
    }
    mode.value = 'failed';
    errorMessage.value = e instanceof Error ? e.message : t('chat.failedToResume');
    return false;
  }
}

/**
 * Server-pushed {@code switch-to} frame — Eddie's MEDIATE action (or a
 * future flow like project-tab switching) asks the client to drop the
 * current WS and open a new one bound to {@code targetSessionId}. The
 * previous session id is pushed onto our single-level back-stack so
 * {@code /hub} can return.
 */
async function onSwitchTo(data: SwitchToNotification): Promise<void> {
  const target = data.targetSessionId;
  if (!target) return;
  const fromSessionId = activeSessionId.value;
  const ok = await openAndBind(target);
  if (!ok) return;
  previousSessionId.value = fromSessionId;
  mediation.value = {
    workerProjectName: data.targetProjectId || data.targetProcessName || target,
  };
}

/**
 * User-triggered {@code /hub} — purely client-side. Close the current
 * WS, open a new one bound to the remembered previous session, drop
 * the mediation banner. No server round-trip; the server doesn't know
 * (or care) about the back-stack — workers are regular sessions, the
 * hub is a regular session, switching between them is local.
 *
 * No-op when we're already at the hub (no previous session remembered).
 */
async function backToHub(): Promise<void> {
  const target = previousSessionId.value;
  if (!target) return;
  const ok = await openAndBind(target);
  if (!ok) return;
  previousSessionId.value = null;
  mediation.value = null;
}

async function onSessionPicked(sessionId: string): Promise<void> {
  errorMessage.value = null;
  await openAndBind(sessionId);
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

let switchToUnsubscribe: (() => void) | null = null;

onMounted(async () => {
  // Resume hint: URL param wins over localStorage. With a hint we go
  // straight to a session-bound socket via openAndBind; without one
  // we open a session-less socket for the picker.
  const hinted = urlSessionId() ?? getActiveSessionId();
  if (hinted) {
    await openAndBind(hinted);
  } else {
    const ok = await openSocketForPicker();
    if (ok) mode.value = 'picker';
  }
});

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', beforeUnloadGuard);
  switchToUnsubscribe?.();
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
              <VButton variant="ghost" @click="openAndBind(activeSessionId ?? '')">
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
          :key="activeSessionId"
          :socket="socket"
          :session-id="activeSessionId"
          :mediation="mediation"
          @leave="leaveLive"
          @hub="backToHub"
        />

        <div v-else-if="mode === 'connecting'" class="p-6 text-sm opacity-60">
          {{ $t('chat.connecting') }}
        </div>
      </div>
    </div>
  </EditorShell>
</template>
