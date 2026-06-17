<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  BrainWebSocket,
  WebSocketRequestError,
  type BrainWsApi,
  getTenantId,
  getUsername,
  setActiveSessionId,
} from '@vance/shared';
import type {
  ChatMessageDto,
  DocumentDto,
  ProcessProgressNotification,
  SessionListRequest,
  SessionListResponse,
  SessionResumeRequest,
  SessionResumeResponse,
  SwitchToNotification,
} from '@vance/generated';
import {
  type Crumb,
  EditorShell,
  type FocusZone,
  VAlert,
  VButton,
} from '@components/index';
import PickerView from './PickerView.vue';
import ChatView from './ChatView.vue';
import ChatComposer from './ChatComposer.vue';
import ChatRightPanel from './ChatRightPanel.vue';
import { useFollowUpSuggestion } from '@composables/useFollowUpSuggestion';
import { useNotificationSubscription } from '@/notification/useNotificationSubscription';

const { t } = useI18n();

const CLIENT_VERSION = '0.1.0';

type Mode = 'connecting' | 'picker' | 'live' | 'occupied' | 'failed';

const mode = ref<Mode>('connecting');
const errorMessage = ref<string | null>(null);
const socket = ref<BrainWebSocket | null>(null);
// Pipe `notify` frames into the global toast/beep store. The
// subscription follows reconnects (socket ref is swapped, the
// composable rebinds via watch).
useNotificationSubscription(socket);
const activeSessionId = ref<string | null>(null);
/**
 * True after the live WS has closed but before a reconnect has run.
 * In this state the editor stays mounted (composer text + ChatView
 * scroll position survive) and the next user-initiated send goes
 * through {@link ensureConnected} to transparently re-open the socket.
 * Set back to false on successful reconnect; on reconnect-failure we
 * fall through to {@code mode = 'failed'} so the existing Retry banner
 * picks it up.
 */
const socketDown = ref(false);

/**
 * Banner / UI state shown while the WS is bound to a session Eddie
 * switched us to (spec §8.5). Cleared when we switch back to the hub
 * or pick a different session through the picker.
 */
interface MediationState {
  workerProjectName: string;
}
const mediation = ref<MediationState | null>(null);

/**
 * Single-level back-stack: the session id we were bound to before the
 * current switch-to. `null` means we're at the hub already.
 */
const previousSessionId = ref<string | null>(null);

const username = computed<string | null>(() => getUsername());

const connectionState = computed<'connected' | 'idle' | 'occupied' | undefined>(() => {
  // socketDown wins over live: the session is still bound and the
  // editor stays mounted, but the WS is gone until the next send. Spec
  // §6.4 maps "disconnect with reconnect pending" to the grey idle dot.
  if (mode.value === 'live' && socketDown.value) return 'idle';
  if (mode.value === 'live') return 'connected';
  if (mode.value === 'occupied') return 'occupied';
  if (mode.value === 'picker' || mode.value === 'connecting') return 'idle';
  return undefined;
});

const connectionTooltip = computed<string | undefined>(() => {
  if (mode.value === 'live' && socketDown.value) {
    return t('header.connection.reconnecting');
  }
  return undefined;
});

// ──────────────── Session-resolved state ────────────────
//
// Filled by {@link resolveSessionAndProcess} once a session has been
// bound. Composer + RightPanel + ChatView all read these; they hang
// off ChatApp instead of any specific child because the WS lookup
// crosses component boundaries.

const chatProcessName = ref<string | null>(null);
const chatProjectId = ref<string>('');
/**
 * Display name of the bound session, fetched together with the
 * project id in {@link resolveSessionAndProcess}. Empty when no
 * session is bound or the session-list lookup failed. Drives the
 * second breadcrumb segment in live mode.
 */
const sessionDisplayName = ref<string | null>(null);

// ──────────────── Picker project selection ────────────────
//
// The picker's currently-selected project lives here so the URL,
// breadcrumb, and PickerView all agree on a single source of truth.
// User clicks in the project list flow upward via {@code project-pick},
// trigger a pushState, and become navigable through the browser
// back/forward stack. Title is resolved by PickerView (only it has the
// project list) and reported via {@code project-resolved}.

const pickerProjectName = ref<string | null>(null);
/**
 * Display title of the currently relevant project — picker selection
 * in picker mode, the bound session's owning project in live mode.
 * Both PickerView and ChatView feed this via {@code project-resolved}.
 */
const currentProjectTitle = ref<string | null>(null);

function urlProjectName(): string | null {
  const id = new URLSearchParams(window.location.search).get('project');
  return id && id.length > 0 ? id : null;
}

function pushProjectToUrl(name: string | null, mode: 'push' | 'replace' = 'push'): void {
  const url = new URL(window.location.href);
  if (name) url.searchParams.set('project', name);
  else url.searchParams.delete('project');
  if (url.toString() === window.location.href) return;
  if (mode === 'push') {
    window.history.pushState(null, '', url.toString());
  } else {
    window.history.replaceState(null, '', url.toString());
  }
}

function onPickerProjectPick(payload: { name: string; title: string }): void {
  pickerProjectName.value = payload.name;
  currentProjectTitle.value = payload.title;
  pushProjectToUrl(payload.name, 'push');
}

function onPickerProjectResolved(payload: { name: string; title: string }): void {
  if (payload.name === pickerProjectName.value) {
    currentProjectTitle.value = payload.title;
  }
}

function onChatViewProjectResolved(payload: { name: string; title: string }): void {
  if (payload.name === chatProjectId.value) {
    currentProjectTitle.value = payload.title;
  }
}

/**
 * Click handler for the project segment of the live-mode breadcrumb.
 * Unbinds the current session, transitions to picker mode with the
 * same project pre-selected, and rewrites the URL in one step (single
 * history entry rather than two).
 */
function goToPickerWithProject(projectName: string): void {
  if (!projectName) return;
  const url = new URL(window.location.href);
  url.searchParams.delete('sessionId');
  url.searchParams.set('project', projectName);
  if (url.toString() !== window.location.href) {
    window.history.pushState(null, '', url.toString());
  }
  pickerProjectName.value = projectName;
  // Land on the sessions list, not the project list — user just told
  // us they want to navigate this project's sessions.
  focusZone.value = 'main';
  void leaveLive();
}

const breadcrumbs = computed<Crumb[]>(() => {
  if (liveOk.value) {
    const projectText = currentProjectTitle.value || chatProjectId.value;
    const session = sessionDisplayName.value || t('chat.breadcrumb.unnamedSession');
    if (projectText && chatProjectId.value) {
      const projectId = chatProjectId.value;
      return [
        { text: projectText, onClick: () => goToPickerWithProject(projectId) },
        session,
      ];
    }
    return [session];
  }
  if (pickerMode.value && currentProjectTitle.value) {
    return [currentProjectTitle.value];
  }
  return [];
});

async function resolveSessionAndProcess(sessionId: string): Promise<void> {
  if (!socket.value) return;
  try {
    const resp = await socket.value.send<SessionListRequest, SessionListResponse>(
      'session-list', {});
    const summary = resp.sessions?.find((s) => s.sessionId === sessionId);
    chatProjectId.value = summary?.projectId ?? '';
    sessionDisplayName.value = summary?.displayName ?? null;
  } catch {
    chatProjectId.value = '';
    sessionDisplayName.value = null;
  }
  // The chat-process name is fixed by SessionChatBootstrapper to
  // CHAT_PROCESS_NAME = "chat" — exactly one per session.
  chatProcessName.value = 'chat';
}

// ──────────────── Progress events (right-panel feed) ────────────────

const PROGRESS_CAP = 50;
const progressEvents = ref<ProcessProgressNotification[]>([]);
let progressUnsubscribe: (() => void) | null = null;

function recordProgress(data: ProcessProgressNotification): void {
  progressEvents.value.push(data);
  if (progressEvents.value.length > PROGRESS_CAP) {
    progressEvents.value.splice(0, progressEvents.value.length - PROGRESS_CAP);
  }
}

// ──────────────── Focus model ────────────────

const focusZone = ref<FocusZone>('main');

// ──────────────── Child refs (for imperative cross-component calls) ────────────────

const chatViewRef = ref<InstanceType<typeof ChatView> | null>(null);
const composerRef = ref<InstanceType<typeof ChatComposer> | null>(null);
const rightPanelRef = ref<InstanceType<typeof ChatRightPanel> | null>(null);

// ──────────────── Cross-component event routing ────────────────

function onLocalEchoFromComposer(msg: ChatMessageDto): void {
  chatViewRef.value?.appendLocalEcho(msg);
}
function onRollbackEchoFromComposer(messageId: string): void {
  chatViewRef.value?.rollbackLocalEcho(messageId);
}
function onSpeakMessageFromView(text: string): void {
  composerRef.value?.speakMessage(text);
}
function onNoteActivityFromView(): void {
  composerRef.value?.noteTalkActivity();
}
function onHistoryLoadedFromView(): void {
  composerRef.value?.markSpeakerLive();
}
function onAskUserPickFromView(label: string): void {
  void composerRef.value?.setTextAndSend(label);
}
function onWizardDeepLinkFromView(detail: { name: string; prefill: Record<string, string> }): void {
  rightPanelRef.value?.openWizard(detail.name, detail.prefill);
}
function onPromptReadyFromRightPanel(prompt: string): void {
  composerRef.value?.setText(prompt);
}

// ──────────────── Follow-up ghost bubble ────────────────
//
// Reply-mode suggestion ({@code follow-up/{project}}) for the most-
// recent assistant message. Shown as a ghost bubble in {@link ChatView}
// whenever the composer is empty; Space/Tab/click in the composer
// accepts it into the input.

/** Mirrored from {@link ChatView}'s {@code last-assistant-changed} emit. */
const lastAssistantContent = ref<string | null>(null);
/** Mirrored from {@link ChatComposer}'s {@code text-changed} emit. */
const composerText = ref<string>('');
/** Mirrored from {@link ChatComposer}'s {@code focus-changed} emit.
 *  Gates the follow-up fetch — we only ask the LLM when the user is
 *  plausibly about to type. */
const composerFocused = ref<boolean>(false);

const followUpProjectId = computed<string | null>(() => chatProjectId.value || null);

/** Disable while the composer is sending — the suggestion would only
 *  cause UI noise during the send/stream window. */
const followUpEnabled = computed<boolean>(() => mode.value === 'live');

const {
  activeSuggestion: followUpSuggestion,
  acceptCurrent: acceptFollowUp,
} = useFollowUpSuggestion({
  lastAssistantContent,
  composerText,
  projectId: followUpProjectId,
  enabled: followUpEnabled,
  requestActive: composerFocused,
});

function onLastAssistantChangedFromView(content: string | null): void {
  lastAssistantContent.value = content;
}
function onComposerTextChanged(text: string): void {
  composerText.value = text;
}
function onComposerFocusChanged(focused: boolean): void {
  composerFocused.value = focused;
}
function onAcceptFollowUpFromView(): void {
  const suggestion = followUpSuggestion.value;
  if (!suggestion) return;
  composerRef.value?.setText(suggestion + ' ');
  acceptFollowUp();
}

/**
 * ChatView just persisted the conversation as a Markdown document.
 * chat.html has no in-place editor surface, so we open the document
 * in a new browser tab via documents.html — the user keeps their
 * chat session alive in the original tab and can move/rename the
 * export there. Cortex handles its own event by opening the document
 * as a tab inside the same window (see CortexChatPanel).
 */
function onConversationExportedFromView(payload: { documentId: string; document: DocumentDto }): void {
  const projectId = payload.document.projectId ?? chatProjectId.value;
  if (!projectId) return;
  const url = `/documents.html?projectId=${encodeURIComponent(projectId)}`
    + `&documentId=${encodeURIComponent(payload.documentId)}`;
  window.open(url, '_blank', 'noopener');
}
function onFollowUpAcceptedFromComposer(): void {
  acceptFollowUp();
}

// ──────────────── URL state ────────────────

function urlSessionId(): string | null {
  const params = new URLSearchParams(window.location.search);
  const id = params.get('sessionId');
  return id && id.length > 0 ? id : null;
}

function pushSessionIdToUrl(sessionId: string | null): void {
  const url = new URL(window.location.href);
  if (sessionId) url.searchParams.set('sessionId', sessionId);
  else url.searchParams.delete('sessionId');
  // pushState adds a new history entry — Browser-Back from chat goes
  // back to the picker. The check below guards against duplicate
  // entries when the URL didn't actually change (notably when this
  // is called via the popstate handler after the browser already
  // navigated).
  if (url.toString() !== window.location.href) {
    window.history.pushState(null, '', url.toString());
  }
}

// ──────────────── WS socket lifecycle ────────────────

async function openSocket(): Promise<BrainWebSocket> {
  const tenant = getTenantId();
  if (!tenant) {
    throw new Error('Missing tenant — cannot open chat connection.');
  }
  return BrainWebSocket.connect({
    tenant,
    profile: 'web',
    clientVersion: CLIENT_VERSION,
  });
}

async function openSocketForPicker(): Promise<boolean> {
  await teardownCurrentSocket();
  try {
    socket.value = await openSocket();
  } catch (e) {
    mode.value = 'failed';
    // e.message from WebSocketClosedError is hard-coded English (the
    // shared layer is platform-neutral). Use the localized string so
    // the banner doesn't mix languages with the buttons below.
    errorMessage.value = t('chat.failedToOpen');
    console.warn('[chat] openSocketForPicker failed:', e);
    return false;
  }
  attachSocketListeners(socket.value);
  return true;
}

async function teardownCurrentSocket(): Promise<void> {
  switchToUnsubscribe?.();
  switchToUnsubscribe = null;
  onCloseUnsubscribe?.();
  onCloseUnsubscribe = null;
  progressUnsubscribe?.();
  progressUnsubscribe = null;
  if (socket.value) {
    socket.value.close();
    socket.value = null;
  }
  // Reset session-bound state — these belong to the now-dead WS.
  progressEvents.value = [];
  chatProcessName.value = null;
  chatProjectId.value = '';
}

function attachSocketListeners(s: BrainWsApi): void {
  onCloseUnsubscribe = s.onClose(() => {
    if (mode.value === 'live') {
      // Keep ChatView/Composer mounted (their local state — composer
      // text, scroll position, in-flight uploads — would otherwise be
      // discarded). {@link ensureConnected} re-opens the socket lazily
      // on the next send.
      socketDown.value = true;
    } else if (mode.value === 'picker' || mode.value === 'occupied') {
      mode.value = 'failed';
      errorMessage.value = t('chat.connectionClosed');
    }
  });
  switchToUnsubscribe = s.on<SwitchToNotification>(
    'switch-to', (data) => { void onSwitchTo(data); });
  progressUnsubscribe = s.on<ProcessProgressNotification>(
    'process-progress', recordProgress);
}

/**
 * Coalesced reconnect entry-point handed to ChatComposer. Returns
 * {@code true} if the socket is (or becomes) usable, {@code false} if
 * the reconnect failed — in which case {@code mode} has been flipped
 * to {@code 'failed'} and the Retry banner is showing.
 *
 * <p>The composer calls this before each WS send when
 * {@code socket.closed()}. Concurrent calls share the same in-flight
 * promise so two near-simultaneous sends produce one reconnect.
 */
let reconnectInFlight: Promise<boolean> | null = null;

async function ensureConnected(): Promise<boolean> {
  if (!socketDown.value && socket.value && !socket.value.closed()) {
    return true;
  }
  if (reconnectInFlight) return reconnectInFlight;
  reconnectInFlight = doReconnect();
  try {
    return await reconnectInFlight;
  } finally {
    reconnectInFlight = null;
  }
}

async function doReconnect(): Promise<boolean> {
  const sessionId = activeSessionId.value;
  if (!sessionId) {
    mode.value = 'failed';
    errorMessage.value = t('chat.connectionLost');
    return false;
  }
  let newSocket: BrainWebSocket;
  try {
    newSocket = await openSocket();
  } catch (e) {
    mode.value = 'failed';
    errorMessage.value = t('chat.connectionLost');
    console.warn('[chat] reconnect openSocket failed:', e);
    return false;
  }
  try {
    await newSocket.send<SessionResumeRequest, SessionResumeResponse>(
      'session-resume', { sessionId });
  } catch (e) {
    newSocket.close();
    if (e instanceof WebSocketRequestError && e.errorCode === 409) {
      mode.value = 'occupied';
      errorMessage.value = t('chat.sessionOccupiedBy', { id: sessionId });
    } else {
      mode.value = 'failed';
      errorMessage.value = t('chat.connectionLost');
      console.warn('[chat] reconnect session-resume failed:', e);
    }
    return false;
  }
  // Drop subscribers bound to the dead socket; attach fresh ones to the
  // new socket before swapping it into the reactive ref so ChatView's
  // socket-watch sees the replacement and re-attaches its own.
  const oldSocket = socket.value;
  switchToUnsubscribe?.(); switchToUnsubscribe = null;
  onCloseUnsubscribe?.(); onCloseUnsubscribe = null;
  progressUnsubscribe?.(); progressUnsubscribe = null;
  attachSocketListeners(newSocket);
  socket.value = newSocket;
  socketDown.value = false;
  oldSocket?.close();
  return true;
}

async function openAndBind(sessionId: string): Promise<boolean> {
  await teardownCurrentSocket();
  try {
    socket.value = await openSocket();
  } catch (e) {
    mode.value = 'failed';
    errorMessage.value = t('chat.failedToOpen');
    console.warn('[chat] openAndBind openSocket failed:', e);
    return false;
  }
  attachSocketListeners(socket.value);

  try {
    await socket.value.send<SessionResumeRequest, SessionResumeResponse>(
      'session-resume', { sessionId });
    activeSessionId.value = sessionId;
    setActiveSessionId(sessionId);
    pushSessionIdToUrl(sessionId);
    errorMessage.value = null;
    mode.value = 'live';
    await resolveSessionAndProcess(sessionId);
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
    errorMessage.value = t('chat.failedToResume');
    console.warn('[chat] openAndBind session-resume failed:', e);
    return false;
  }
}

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
  errorMessage.value = null;
  activeSessionId.value = sessionId;
  setActiveSessionId(sessionId);
  pushSessionIdToUrl(sessionId);
  mode.value = 'live';
  await resolveSessionAndProcess(sessionId);
}

async function leaveLive(): Promise<void> {
  if (socket.value && !socket.value.closed()) {
    socket.value.sendNoReply('session-unbind');
  }
  // The post-unbind socket can land in a half-dead state — browser tab
  // throttling, server-side connection cleanup, or a stray frame in
  // flight have all been observed to break the next session-bootstrap
  // with WebSocketClosedError. Tear it down and open a fresh one so the
  // picker starts from the same clean state a URL-less page load would.
  // openSocketForPicker handles teardown + resets the session-scoped
  // state (chatProjectId, chatProcessName, progressEvents).
  activeSessionId.value = null;
  pushSessionIdToUrl(null);
  sessionDisplayName.value = null;
  mediation.value = null;
  previousSessionId.value = null;
  const ok = await openSocketForPicker();
  if (!ok) return; // mode flipped to 'failed' inside openSocketForPicker
  mode.value = 'picker';
}

function backToPicker(): void {
  errorMessage.value = null;
  pushSessionIdToUrl(null);
  mode.value = 'picker';
}

/**
 * Retry-banner entry-point. Mirrors {@code onMounted}: if there's an
 * active or URL-hinted session, bind to it; otherwise re-open the
 * picker socket. Needed because the initial handshake can fail before
 * any session is bound — without this, the failed banner would only
 * show "Back to picker" (which can't render without a socket).
 */
async function retryConnect(): Promise<void> {
  errorMessage.value = null;
  const target = activeSessionId.value ?? urlSessionId();
  if (target) {
    await openAndBind(target);
  } else {
    const ok = await openSocketForPicker();
    if (ok) mode.value = 'picker';
  }
}

let switchToUnsubscribe: (() => void) | null = null;
let onCloseUnsubscribe: (() => void) | null = null;

/**
 * Browser back/forward routes through here. Reads the current URL
 * and either binds to the new session id, or — if the URL no longer
 * carries a sessionId — drops back to the picker. We deliberately
 * compare against {@link activeSessionId} so popstate events that
 * land on the same URL (e.g. a programmatic replaceState elsewhere)
 * become no-ops. The {@code ?project=} parameter steers the picker's
 * project selection along the same axis.
 */
async function onPopstate(): Promise<void> {
  const id = urlSessionId();
  if (id) {
    if (id !== activeSessionId.value) {
      await openAndBind(id);
    }
  } else if (mode.value === 'live') {
    await leaveLive();
  }
  // Project changes are independent of session — both can shift in
  // a single popstate when the user navigates directly across
  // different URLs. Sync the picker selection too.
  const project = urlProjectName();
  if (project !== pickerProjectName.value) {
    pickerProjectName.value = project;
    if (!project) currentProjectTitle.value = null;
  }
}

onMounted(async () => {
  // Only URL-hint triggers auto-bind. Stale localStorage sessionId is
  // intentionally ignored — opening chat.html with no params lands
  // in the picker so the user explicitly picks a session.
  const hinted = urlSessionId();
  // Project from URL is seeded before mount completes so PickerView
  // sees it via the v-model prop on its first render.
  pickerProjectName.value = urlProjectName();
  if (hinted) {
    await openAndBind(hinted);
  } else {
    const ok = await openSocketForPicker();
    if (ok) mode.value = 'picker';
  }
  window.addEventListener('popstate', onPopstate);
});

onBeforeUnmount(() => {
  window.removeEventListener('popstate', onPopstate);
  switchToUnsubscribe?.();
  onCloseUnsubscribe?.();
  progressUnsubscribe?.();
  socket.value?.close();
});

// Mode-driven cleanup: when we leave live mode (back to picker / failed),
// the composer is unmounted, but if a user is staring at the picker with
// stale chatProcessName around the watch is harmless. Tracked here for
// clarity rather than as a side-effect of leaveLive alone.
watch(mode, (next) => {
  if (next !== 'live') {
    progressEvents.value = [];
  }
});

const liveOk = computed<boolean>(() =>
  mode.value === 'live' && socket.value !== null && activeSessionId.value !== null);

const pickerMode = computed<boolean>(() =>
  mode.value === 'picker' && socket.value !== null);

/**
 * Title-click in the topbar — same affordance, different action per
 * mode. In picker mode it focuses the project-list sidebar; in live
 * mode it leaves the session back to the picker (which is why the
 * old "← Sessions" button inside ChatView's header is gone).
 */
function onTitleClick(): void {
  if (pickerMode.value) {
    focusZone.value = 'sidebar';
  } else if (liveOk.value) {
    void leaveLive();
  }
}

/**
 * Switch from the plain chat view to the Cortex view for this session.
 * Cortex picks up the same sessionId from the URL and restores its own
 * state (open tabs, chat-bound document) from the chat session record.
 *
 * <p>Implemented as a hard navigation rather than an SPA route because
 * the two views live in separate Vite entries (chat.html, cortex.html);
 * the user's reverse path is the {@code ← Chat} button in Cortex.
 */
function openInCortex(): void {
  const id = activeSessionId.value;
  if (!id) return;
  window.location.href = `/cortex.html?sessionId=${encodeURIComponent(id)}`;
}
</script>

<template>
  <EditorShell
    v-model:focus-zone="focusZone"
    :title="$t('chat.pageTitle')"
    :breadcrumbs="breadcrumbs"
    :connection-state="connectionState"
    :connection-tooltip="connectionTooltip"
    :full-height="true"
    focus-model="auto"
    :show-sidebar="pickerMode"
    :show-right-panel="liveOk"
    :show-footer="liveOk"
    :title-clickable="pickerMode || liveOk"
    @title-click="onTitleClick"
  >
    <!-- Topbar action: jump from the plain chat view into Cortex for
         the current session. Hidden in picker mode where there's no
         session to anchor Cortex to. The session's projectId is the
         one Cortex will scope its file tree to.

         Also hidden on phone-class viewports — Cortex's three-zone
         layout (file tree + editor + chat) is unusable below ~768px,
         so we don't tempt the user into a broken view. iPad portrait
         (768px) is the smallest size where Cortex still works. -->
    <template v-if="liveOk" #topbar-extra>
      <VButton
        size="sm"
        variant="ghost"
        class="hidden md:inline-flex"
        @click="openInCortex"
      >
        Open Cortex →
      </VButton>
    </template>

    <!-- Sidebar slot — picker mode only. PickerView teleports its
         project-list aside into the target div below so the project
         nav lives in the EditorShell sidebar zone (proper focus +
         reclaim handle) rather than as a private sub-aside in main. -->
    <template #sidebar>
      <div
        v-if="pickerMode"
        id="vance-picker-projects-target"
        class="h-full"
      />
    </template>
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
            <div class="mt-2 flex gap-2">
              <!-- "Back to picker" only makes sense once we've bound to
                   a session — before that, there's nothing to go back to
                   and the picker can't render without a socket anyway. -->
              <VButton
                v-if="activeSessionId"
                variant="secondary"
                @click="backToPicker"
              >
                {{ $t('chat.backToPicker') }}
              </VButton>
              <VButton variant="ghost" @click="retryConnect">
                {{ $t('chat.tryAgain') }}
              </VButton>
            </div>
          </template>
        </VAlert>
      </div>

      <div class="flex-1 min-h-0">
        <PickerView
          v-if="mode === 'picker' && socket"
          v-model:selected-project="pickerProjectName"
          :socket="socket"
          :username="username"
          @session-picked="onSessionPicked"
          @session-bootstrapped="onSessionBootstrapped"
          @focus-main="focusZone = 'main'"
          @project-pick="onPickerProjectPick"
          @project-resolved="onPickerProjectResolved"
        />

        <ChatView
          v-else-if="liveOk"
          ref="chatViewRef"
          :key="activeSessionId ?? ''"
          :socket="socket!"
          :session-id="activeSessionId!"
          :mediation="mediation"
          :chat-process-name="chatProcessName"
          :chat-project-id="chatProjectId"
          :follow-up-suggestion="followUpSuggestion"
          @leave="leaveLive"
          @hub="backToHub"
          @speak-message="onSpeakMessageFromView"
          @note-activity="onNoteActivityFromView"
          @history-loaded="onHistoryLoadedFromView"
          @ask-user-pick="onAskUserPickFromView"
          @wizard-deep-link="onWizardDeepLinkFromView"
          @project-resolved="onChatViewProjectResolved"
          @last-assistant-changed="onLastAssistantChangedFromView"
          @accept-follow-up="onAcceptFollowUpFromView"
          @conversation-exported="onConversationExportedFromView"
        />

        <div v-else-if="mode === 'connecting'" class="p-6 text-sm opacity-60">
          {{ $t('chat.connecting') }}
        </div>
      </div>
    </div>

    <!-- Slots are registered unconditionally so EditorShell's
         {@code hasRightCell} / {@code hasFooterSlot} computeds pick
         them up reactively. The inner v-if hides the actual components
         in picker/connecting/failed modes — picker briefly shows two
         empty rails until the user picks a session. -->
    <template #right-panel>
      <ChatRightPanel
        v-if="liveOk"
        ref="rightPanelRef"
        :events="progressEvents"
        :project-id="chatProjectId || undefined"
        :session-key="chatProcessName ?? undefined"
        @prompt-ready="onPromptReadyFromRightPanel"
      />
    </template>

    <template #footer>
      <ChatComposer
        v-if="liveOk"
        ref="composerRef"
        :key="activeSessionId ?? ''"
        :socket="socket!"
        :chat-process-name="chatProcessName"
        :chat-project-id="chatProjectId"
        :mediation="mediation"
        :follow-up-suggestion="followUpSuggestion"
        :ensure-connected="ensureConnected"
        @hub="backToHub"
        @local-echo="onLocalEchoFromComposer"
        @rollback-echo="onRollbackEchoFromComposer"
        @text-changed="onComposerTextChanged"
        @follow-up-accepted="onFollowUpAcceptedFromComposer"
        @focus-changed="onComposerFocusChanged"
      />
    </template>
  </EditorShell>
</template>
