import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { BrainWebSocket, WebSocketRequestError, getTenantId, getUsername, setActiveSessionId, } from '@vance/shared';
import { EditorShell, VAlert, VButton, } from '@components/index';
import PickerView from './PickerView.vue';
import ChatView from './ChatView.vue';
import ChatComposer from './ChatComposer.vue';
import ChatRightPanel from './ChatRightPanel.vue';
import { useFollowUpSuggestion } from '@composables/useFollowUpSuggestion';
const { t } = useI18n();
const CLIENT_VERSION = '0.1.0';
const mode = ref('connecting');
const errorMessage = ref(null);
const socket = ref(null);
const activeSessionId = ref(null);
const mediation = ref(null);
/**
 * Single-level back-stack: the session id we were bound to before the
 * current switch-to. `null` means we're at the hub already.
 */
const previousSessionId = ref(null);
const username = computed(() => getUsername());
const connectionState = computed(() => {
    if (mode.value === 'live')
        return 'connected';
    if (mode.value === 'occupied')
        return 'occupied';
    if (mode.value === 'picker' || mode.value === 'connecting')
        return 'idle';
    return undefined;
});
// ──────────────── Session-resolved state ────────────────
//
// Filled by {@link resolveSessionAndProcess} once a session has been
// bound. Composer + RightPanel + ChatView all read these; they hang
// off ChatApp instead of any specific child because the WS lookup
// crosses component boundaries.
const chatProcessName = ref(null);
const chatProjectId = ref('');
/**
 * Display name of the bound session, fetched together with the
 * project id in {@link resolveSessionAndProcess}. Empty when no
 * session is bound or the session-list lookup failed. Drives the
 * second breadcrumb segment in live mode.
 */
const sessionDisplayName = ref(null);
// ──────────────── Picker project selection ────────────────
//
// The picker's currently-selected project lives here so the URL,
// breadcrumb, and PickerView all agree on a single source of truth.
// User clicks in the project list flow upward via {@code project-pick},
// trigger a pushState, and become navigable through the browser
// back/forward stack. Title is resolved by PickerView (only it has the
// project list) and reported via {@code project-resolved}.
const pickerProjectName = ref(null);
/**
 * Display title of the currently relevant project — picker selection
 * in picker mode, the bound session's owning project in live mode.
 * Both PickerView and ChatView feed this via {@code project-resolved}.
 */
const currentProjectTitle = ref(null);
function urlProjectName() {
    const id = new URLSearchParams(window.location.search).get('project');
    return id && id.length > 0 ? id : null;
}
function pushProjectToUrl(name, mode = 'push') {
    const url = new URL(window.location.href);
    if (name)
        url.searchParams.set('project', name);
    else
        url.searchParams.delete('project');
    if (url.toString() === window.location.href)
        return;
    if (mode === 'push') {
        window.history.pushState(null, '', url.toString());
    }
    else {
        window.history.replaceState(null, '', url.toString());
    }
}
function onPickerProjectPick(payload) {
    pickerProjectName.value = payload.name;
    currentProjectTitle.value = payload.title;
    pushProjectToUrl(payload.name, 'push');
}
function onPickerProjectResolved(payload) {
    if (payload.name === pickerProjectName.value) {
        currentProjectTitle.value = payload.title;
    }
}
function onChatViewProjectResolved(payload) {
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
function goToPickerWithProject(projectName) {
    if (!projectName)
        return;
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
const breadcrumbs = computed(() => {
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
async function resolveSessionAndProcess(sessionId) {
    if (!socket.value)
        return;
    try {
        const resp = await socket.value.send('session-list', {});
        const summary = resp.sessions?.find((s) => s.sessionId === sessionId);
        chatProjectId.value = summary?.projectId ?? '';
        sessionDisplayName.value = summary?.displayName ?? null;
    }
    catch {
        chatProjectId.value = '';
        sessionDisplayName.value = null;
    }
    // The chat-process name is fixed by SessionChatBootstrapper to
    // CHAT_PROCESS_NAME = "chat" — exactly one per session.
    chatProcessName.value = 'chat';
}
// ──────────────── Progress events (right-panel feed) ────────────────
const PROGRESS_CAP = 50;
const progressEvents = ref([]);
let progressUnsubscribe = null;
function recordProgress(data) {
    progressEvents.value.push(data);
    if (progressEvents.value.length > PROGRESS_CAP) {
        progressEvents.value.splice(0, progressEvents.value.length - PROGRESS_CAP);
    }
}
// ──────────────── Focus model ────────────────
const focusZone = ref('main');
// ──────────────── Child refs (for imperative cross-component calls) ────────────────
const chatViewRef = ref(null);
const composerRef = ref(null);
const rightPanelRef = ref(null);
// ──────────────── Cross-component event routing ────────────────
function onLocalEchoFromComposer(msg) {
    chatViewRef.value?.appendLocalEcho(msg);
}
function onRollbackEchoFromComposer(messageId) {
    chatViewRef.value?.rollbackLocalEcho(messageId);
}
function onSpeakMessageFromView(text) {
    composerRef.value?.speakMessage(text);
}
function onNoteActivityFromView() {
    composerRef.value?.noteTalkActivity();
}
function onHistoryLoadedFromView() {
    composerRef.value?.markSpeakerLive();
}
function onAskUserPickFromView(label) {
    void composerRef.value?.setTextAndSend(label);
}
function onWizardDeepLinkFromView(detail) {
    rightPanelRef.value?.openWizard(detail.name, detail.prefill);
}
function onPromptReadyFromRightPanel(prompt) {
    composerRef.value?.setText(prompt);
}
// ──────────────── Follow-up ghost bubble ────────────────
//
// Reply-mode suggestion ({@code follow-up/{project}}) for the most-
// recent assistant message. Shown as a ghost bubble in {@link ChatView}
// whenever the composer is empty; Space/Tab/click in the composer
// accepts it into the input.
/** Mirrored from {@link ChatView}'s {@code last-assistant-changed} emit. */
const lastAssistantContent = ref(null);
/** Mirrored from {@link ChatComposer}'s {@code text-changed} emit. */
const composerText = ref('');
/** Mirrored from {@link ChatComposer}'s {@code focus-changed} emit.
 *  Gates the follow-up fetch — we only ask the LLM when the user is
 *  plausibly about to type. */
const composerFocused = ref(false);
const followUpProjectId = computed(() => chatProjectId.value || null);
/** Disable while the composer is sending — the suggestion would only
 *  cause UI noise during the send/stream window. */
const followUpEnabled = computed(() => mode.value === 'live');
const { activeSuggestion: followUpSuggestion, acceptCurrent: acceptFollowUp, } = useFollowUpSuggestion({
    lastAssistantContent,
    composerText,
    projectId: followUpProjectId,
    enabled: followUpEnabled,
    requestActive: composerFocused,
});
function onLastAssistantChangedFromView(content) {
    lastAssistantContent.value = content;
}
function onComposerTextChanged(text) {
    composerText.value = text;
}
function onComposerFocusChanged(focused) {
    composerFocused.value = focused;
}
function onAcceptFollowUpFromView() {
    const suggestion = followUpSuggestion.value;
    if (!suggestion)
        return;
    composerRef.value?.setText(suggestion + ' ');
    acceptFollowUp();
}
function onFollowUpAcceptedFromComposer() {
    acceptFollowUp();
}
// ──────────────── URL state ────────────────
function urlSessionId() {
    const params = new URLSearchParams(window.location.search);
    const id = params.get('sessionId');
    return id && id.length > 0 ? id : null;
}
function pushSessionIdToUrl(sessionId) {
    const url = new URL(window.location.href);
    if (sessionId)
        url.searchParams.set('sessionId', sessionId);
    else
        url.searchParams.delete('sessionId');
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
async function openSocket() {
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
async function openSocketForPicker() {
    await teardownCurrentSocket();
    try {
        socket.value = await openSocket();
    }
    catch (e) {
        mode.value = 'failed';
        errorMessage.value = e instanceof Error ? e.message : t('chat.failedToOpen');
        return false;
    }
    attachSocketListeners(socket.value);
    return true;
}
async function teardownCurrentSocket() {
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
function attachSocketListeners(s) {
    onCloseUnsubscribe = s.onClose(() => {
        if (mode.value === 'live') {
            mode.value = 'failed';
            errorMessage.value = t('chat.connectionLost');
        }
        else if (mode.value === 'picker' || mode.value === 'occupied') {
            mode.value = 'failed';
            errorMessage.value = t('chat.connectionClosed');
        }
    });
    switchToUnsubscribe = s.on('switch-to', (data) => { void onSwitchTo(data); });
    progressUnsubscribe = s.on('process-progress', recordProgress);
}
async function openAndBind(sessionId) {
    await teardownCurrentSocket();
    try {
        socket.value = await openSocket();
    }
    catch (e) {
        mode.value = 'failed';
        errorMessage.value = e instanceof Error ? e.message : t('chat.failedToOpen');
        return false;
    }
    attachSocketListeners(socket.value);
    try {
        await socket.value.send('session-resume', { sessionId });
        activeSessionId.value = sessionId;
        setActiveSessionId(sessionId);
        pushSessionIdToUrl(sessionId);
        errorMessage.value = null;
        mode.value = 'live';
        await resolveSessionAndProcess(sessionId);
        return true;
    }
    catch (e) {
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
async function onSwitchTo(data) {
    const target = data.targetSessionId;
    if (!target)
        return;
    const fromSessionId = activeSessionId.value;
    const ok = await openAndBind(target);
    if (!ok)
        return;
    previousSessionId.value = fromSessionId;
    mediation.value = {
        workerProjectName: data.targetProjectId || data.targetProcessName || target,
    };
}
async function backToHub() {
    const target = previousSessionId.value;
    if (!target)
        return;
    const ok = await openAndBind(target);
    if (!ok)
        return;
    previousSessionId.value = null;
    mediation.value = null;
}
async function onSessionPicked(sessionId) {
    errorMessage.value = null;
    await openAndBind(sessionId);
}
async function onSessionBootstrapped(sessionId) {
    errorMessage.value = null;
    activeSessionId.value = sessionId;
    setActiveSessionId(sessionId);
    pushSessionIdToUrl(sessionId);
    mode.value = 'live';
    await resolveSessionAndProcess(sessionId);
}
async function leaveLive() {
    if (socket.value && !socket.value.closed()) {
        socket.value.sendNoReply('session-unbind');
    }
    activeSessionId.value = null;
    pushSessionIdToUrl(null);
    chatProcessName.value = null;
    chatProjectId.value = '';
    sessionDisplayName.value = null;
    progressEvents.value = [];
    mode.value = 'picker';
}
function backToPicker() {
    errorMessage.value = null;
    pushSessionIdToUrl(null);
    mode.value = 'picker';
}
let switchToUnsubscribe = null;
let onCloseUnsubscribe = null;
/**
 * Browser back/forward routes through here. Reads the current URL
 * and either binds to the new session id, or — if the URL no longer
 * carries a sessionId — drops back to the picker. We deliberately
 * compare against {@link activeSessionId} so popstate events that
 * land on the same URL (e.g. a programmatic replaceState elsewhere)
 * become no-ops. The {@code ?project=} parameter steers the picker's
 * project selection along the same axis.
 */
async function onPopstate() {
    const id = urlSessionId();
    if (id) {
        if (id !== activeSessionId.value) {
            await openAndBind(id);
        }
    }
    else if (mode.value === 'live') {
        await leaveLive();
    }
    // Project changes are independent of session — both can shift in
    // a single popstate when the user navigates directly across
    // different URLs. Sync the picker selection too.
    const project = urlProjectName();
    if (project !== pickerProjectName.value) {
        pickerProjectName.value = project;
        if (!project)
            currentProjectTitle.value = null;
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
    }
    else {
        const ok = await openSocketForPicker();
        if (ok)
            mode.value = 'picker';
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
const liveOk = computed(() => mode.value === 'live' && socket.value !== null && activeSessionId.value !== null);
const pickerMode = computed(() => mode.value === 'picker' && socket.value !== null);
/**
 * Title-click in the topbar — same affordance, different action per
 * mode. In picker mode it focuses the project-list sidebar; in live
 * mode it leaves the session back to the picker (which is why the
 * old "← Sessions" button inside ChatView's header is gone).
 */
function onTitleClick() {
    if (pickerMode.value) {
        focusZone.value = 'sidebar';
    }
    else if (liveOk.value) {
        void leaveLive();
    }
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ 'onTitleClick': {} },
    focusZone: (__VLS_ctx.focusZone),
    title: (__VLS_ctx.$t('chat.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    connectionState: (__VLS_ctx.connectionState),
    fullHeight: (true),
    focusModel: "auto",
    showSidebar: (__VLS_ctx.pickerMode),
    showRightPanel: (__VLS_ctx.liveOk),
    showFooter: (__VLS_ctx.liveOk),
    titleClickable: (__VLS_ctx.pickerMode || __VLS_ctx.liveOk),
}));
const __VLS_2 = __VLS_1({
    ...{ 'onTitleClick': {} },
    focusZone: (__VLS_ctx.focusZone),
    title: (__VLS_ctx.$t('chat.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    connectionState: (__VLS_ctx.connectionState),
    fullHeight: (true),
    focusModel: "auto",
    showSidebar: (__VLS_ctx.pickerMode),
    showRightPanel: (__VLS_ctx.liveOk),
    showFooter: (__VLS_ctx.liveOk),
    titleClickable: (__VLS_ctx.pickerMode || __VLS_ctx.liveOk),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    onTitleClick: (__VLS_ctx.onTitleClick)
};
var __VLS_8 = {};
__VLS_3.slots.default;
{
    const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
    if (__VLS_ctx.pickerMode) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
            id: "vance-picker-projects-target",
            ...{ class: "h-full" },
        });
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "h-full min-h-0 flex flex-col" },
});
if (__VLS_ctx.errorMessage) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "px-6 pt-4" },
    });
    const __VLS_9 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
        variant: (__VLS_ctx.mode === 'occupied' ? 'warning' : 'error'),
    }));
    const __VLS_11 = __VLS_10({
        variant: (__VLS_ctx.mode === 'occupied' ? 'warning' : 'error'),
    }, ...__VLS_functionalComponentArgsRest(__VLS_10));
    __VLS_12.slots.default;
    (__VLS_ctx.errorMessage);
    if (__VLS_ctx.mode === 'occupied') {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-2 flex gap-2" },
        });
        const __VLS_13 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_14 = __VLS_asFunctionalComponent(__VLS_13, new __VLS_13({
            ...{ 'onClick': {} },
            variant: "secondary",
        }));
        const __VLS_15 = __VLS_14({
            ...{ 'onClick': {} },
            variant: "secondary",
        }, ...__VLS_functionalComponentArgsRest(__VLS_14));
        let __VLS_17;
        let __VLS_18;
        let __VLS_19;
        const __VLS_20 = {
            onClick: (__VLS_ctx.backToPicker)
        };
        __VLS_16.slots.default;
        (__VLS_ctx.$t('chat.pickAnotherSession'));
        var __VLS_16;
        const __VLS_21 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
            ...{ 'onClick': {} },
            variant: "ghost",
        }));
        const __VLS_23 = __VLS_22({
            ...{ 'onClick': {} },
            variant: "ghost",
        }, ...__VLS_functionalComponentArgsRest(__VLS_22));
        let __VLS_25;
        let __VLS_26;
        let __VLS_27;
        const __VLS_28 = {
            onClick: (...[$event]) => {
                if (!(__VLS_ctx.errorMessage))
                    return;
                if (!(__VLS_ctx.mode === 'occupied'))
                    return;
                __VLS_ctx.openAndBind(__VLS_ctx.activeSessionId ?? '');
            }
        };
        __VLS_24.slots.default;
        (__VLS_ctx.$t('chat.tryAgain'));
        var __VLS_24;
    }
    else if (__VLS_ctx.mode === 'failed') {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-2" },
        });
        const __VLS_29 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_30 = __VLS_asFunctionalComponent(__VLS_29, new __VLS_29({
            ...{ 'onClick': {} },
            variant: "secondary",
        }));
        const __VLS_31 = __VLS_30({
            ...{ 'onClick': {} },
            variant: "secondary",
        }, ...__VLS_functionalComponentArgsRest(__VLS_30));
        let __VLS_33;
        let __VLS_34;
        let __VLS_35;
        const __VLS_36 = {
            onClick: (__VLS_ctx.backToPicker)
        };
        __VLS_32.slots.default;
        (__VLS_ctx.$t('chat.backToPicker'));
        var __VLS_32;
    }
    var __VLS_12;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1 min-h-0" },
});
if (__VLS_ctx.mode === 'picker' && __VLS_ctx.socket) {
    /** @type {[typeof PickerView, ]} */ ;
    // @ts-ignore
    const __VLS_37 = __VLS_asFunctionalComponent(PickerView, new PickerView({
        ...{ 'onSessionPicked': {} },
        ...{ 'onSessionBootstrapped': {} },
        ...{ 'onFocusMain': {} },
        ...{ 'onProjectPick': {} },
        ...{ 'onProjectResolved': {} },
        selectedProject: (__VLS_ctx.pickerProjectName),
        socket: (__VLS_ctx.socket),
        username: (__VLS_ctx.username),
    }));
    const __VLS_38 = __VLS_37({
        ...{ 'onSessionPicked': {} },
        ...{ 'onSessionBootstrapped': {} },
        ...{ 'onFocusMain': {} },
        ...{ 'onProjectPick': {} },
        ...{ 'onProjectResolved': {} },
        selectedProject: (__VLS_ctx.pickerProjectName),
        socket: (__VLS_ctx.socket),
        username: (__VLS_ctx.username),
    }, ...__VLS_functionalComponentArgsRest(__VLS_37));
    let __VLS_40;
    let __VLS_41;
    let __VLS_42;
    const __VLS_43 = {
        onSessionPicked: (__VLS_ctx.onSessionPicked)
    };
    const __VLS_44 = {
        onSessionBootstrapped: (__VLS_ctx.onSessionBootstrapped)
    };
    const __VLS_45 = {
        onFocusMain: (...[$event]) => {
            if (!(__VLS_ctx.mode === 'picker' && __VLS_ctx.socket))
                return;
            __VLS_ctx.focusZone = 'main';
        }
    };
    const __VLS_46 = {
        onProjectPick: (__VLS_ctx.onPickerProjectPick)
    };
    const __VLS_47 = {
        onProjectResolved: (__VLS_ctx.onPickerProjectResolved)
    };
    var __VLS_39;
}
else if (__VLS_ctx.liveOk) {
    /** @type {[typeof ChatView, ]} */ ;
    // @ts-ignore
    const __VLS_48 = __VLS_asFunctionalComponent(ChatView, new ChatView({
        ...{ 'onLeave': {} },
        ...{ 'onHub': {} },
        ...{ 'onSpeakMessage': {} },
        ...{ 'onNoteActivity': {} },
        ...{ 'onHistoryLoaded': {} },
        ...{ 'onAskUserPick': {} },
        ...{ 'onWizardDeepLink': {} },
        ...{ 'onProjectResolved': {} },
        ...{ 'onLastAssistantChanged': {} },
        ...{ 'onAcceptFollowUp': {} },
        ref: "chatViewRef",
        key: (__VLS_ctx.activeSessionId ?? ''),
        socket: (__VLS_ctx.socket),
        sessionId: (__VLS_ctx.activeSessionId),
        mediation: (__VLS_ctx.mediation),
        chatProcessName: (__VLS_ctx.chatProcessName),
        chatProjectId: (__VLS_ctx.chatProjectId),
        followUpSuggestion: (__VLS_ctx.followUpSuggestion),
    }));
    const __VLS_49 = __VLS_48({
        ...{ 'onLeave': {} },
        ...{ 'onHub': {} },
        ...{ 'onSpeakMessage': {} },
        ...{ 'onNoteActivity': {} },
        ...{ 'onHistoryLoaded': {} },
        ...{ 'onAskUserPick': {} },
        ...{ 'onWizardDeepLink': {} },
        ...{ 'onProjectResolved': {} },
        ...{ 'onLastAssistantChanged': {} },
        ...{ 'onAcceptFollowUp': {} },
        ref: "chatViewRef",
        key: (__VLS_ctx.activeSessionId ?? ''),
        socket: (__VLS_ctx.socket),
        sessionId: (__VLS_ctx.activeSessionId),
        mediation: (__VLS_ctx.mediation),
        chatProcessName: (__VLS_ctx.chatProcessName),
        chatProjectId: (__VLS_ctx.chatProjectId),
        followUpSuggestion: (__VLS_ctx.followUpSuggestion),
    }, ...__VLS_functionalComponentArgsRest(__VLS_48));
    let __VLS_51;
    let __VLS_52;
    let __VLS_53;
    const __VLS_54 = {
        onLeave: (__VLS_ctx.leaveLive)
    };
    const __VLS_55 = {
        onHub: (__VLS_ctx.backToHub)
    };
    const __VLS_56 = {
        onSpeakMessage: (__VLS_ctx.onSpeakMessageFromView)
    };
    const __VLS_57 = {
        onNoteActivity: (__VLS_ctx.onNoteActivityFromView)
    };
    const __VLS_58 = {
        onHistoryLoaded: (__VLS_ctx.onHistoryLoadedFromView)
    };
    const __VLS_59 = {
        onAskUserPick: (__VLS_ctx.onAskUserPickFromView)
    };
    const __VLS_60 = {
        onWizardDeepLink: (__VLS_ctx.onWizardDeepLinkFromView)
    };
    const __VLS_61 = {
        onProjectResolved: (__VLS_ctx.onChatViewProjectResolved)
    };
    const __VLS_62 = {
        onLastAssistantChanged: (__VLS_ctx.onLastAssistantChangedFromView)
    };
    const __VLS_63 = {
        onAcceptFollowUp: (__VLS_ctx.onAcceptFollowUpFromView)
    };
    /** @type {typeof __VLS_ctx.chatViewRef} */ ;
    var __VLS_64 = {};
    var __VLS_50;
}
else if (__VLS_ctx.mode === 'connecting') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-6 text-sm opacity-60" },
    });
    (__VLS_ctx.$t('chat.connecting'));
}
{
    const { 'right-panel': __VLS_thisSlot } = __VLS_3.slots;
    if (__VLS_ctx.liveOk) {
        /** @type {[typeof ChatRightPanel, ]} */ ;
        // @ts-ignore
        const __VLS_66 = __VLS_asFunctionalComponent(ChatRightPanel, new ChatRightPanel({
            ...{ 'onPromptReady': {} },
            ref: "rightPanelRef",
            events: (__VLS_ctx.progressEvents),
            projectId: (__VLS_ctx.chatProjectId || undefined),
            sessionKey: (__VLS_ctx.chatProcessName ?? undefined),
        }));
        const __VLS_67 = __VLS_66({
            ...{ 'onPromptReady': {} },
            ref: "rightPanelRef",
            events: (__VLS_ctx.progressEvents),
            projectId: (__VLS_ctx.chatProjectId || undefined),
            sessionKey: (__VLS_ctx.chatProcessName ?? undefined),
        }, ...__VLS_functionalComponentArgsRest(__VLS_66));
        let __VLS_69;
        let __VLS_70;
        let __VLS_71;
        const __VLS_72 = {
            onPromptReady: (__VLS_ctx.onPromptReadyFromRightPanel)
        };
        /** @type {typeof __VLS_ctx.rightPanelRef} */ ;
        var __VLS_73 = {};
        var __VLS_68;
    }
}
{
    const { footer: __VLS_thisSlot } = __VLS_3.slots;
    if (__VLS_ctx.liveOk) {
        /** @type {[typeof ChatComposer, ]} */ ;
        // @ts-ignore
        const __VLS_75 = __VLS_asFunctionalComponent(ChatComposer, new ChatComposer({
            ...{ 'onHub': {} },
            ...{ 'onLocalEcho': {} },
            ...{ 'onRollbackEcho': {} },
            ...{ 'onTextChanged': {} },
            ...{ 'onFollowUpAccepted': {} },
            ...{ 'onFocusChanged': {} },
            ref: "composerRef",
            key: (__VLS_ctx.activeSessionId ?? ''),
            socket: (__VLS_ctx.socket),
            chatProcessName: (__VLS_ctx.chatProcessName),
            chatProjectId: (__VLS_ctx.chatProjectId),
            mediation: (__VLS_ctx.mediation),
            followUpSuggestion: (__VLS_ctx.followUpSuggestion),
        }));
        const __VLS_76 = __VLS_75({
            ...{ 'onHub': {} },
            ...{ 'onLocalEcho': {} },
            ...{ 'onRollbackEcho': {} },
            ...{ 'onTextChanged': {} },
            ...{ 'onFollowUpAccepted': {} },
            ...{ 'onFocusChanged': {} },
            ref: "composerRef",
            key: (__VLS_ctx.activeSessionId ?? ''),
            socket: (__VLS_ctx.socket),
            chatProcessName: (__VLS_ctx.chatProcessName),
            chatProjectId: (__VLS_ctx.chatProjectId),
            mediation: (__VLS_ctx.mediation),
            followUpSuggestion: (__VLS_ctx.followUpSuggestion),
        }, ...__VLS_functionalComponentArgsRest(__VLS_75));
        let __VLS_78;
        let __VLS_79;
        let __VLS_80;
        const __VLS_81 = {
            onHub: (__VLS_ctx.backToHub)
        };
        const __VLS_82 = {
            onLocalEcho: (__VLS_ctx.onLocalEchoFromComposer)
        };
        const __VLS_83 = {
            onRollbackEcho: (__VLS_ctx.onRollbackEchoFromComposer)
        };
        const __VLS_84 = {
            onTextChanged: (__VLS_ctx.onComposerTextChanged)
        };
        const __VLS_85 = {
            onFollowUpAccepted: (__VLS_ctx.onFollowUpAcceptedFromComposer)
        };
        const __VLS_86 = {
            onFocusChanged: (__VLS_ctx.onComposerFocusChanged)
        };
        /** @type {typeof __VLS_ctx.composerRef} */ ;
        var __VLS_87 = {};
        var __VLS_77;
    }
}
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-4']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['p-6']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
// @ts-ignore
var __VLS_65 = __VLS_64, __VLS_74 = __VLS_73, __VLS_88 = __VLS_87;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            VAlert: VAlert,
            VButton: VButton,
            PickerView: PickerView,
            ChatView: ChatView,
            ChatComposer: ChatComposer,
            ChatRightPanel: ChatRightPanel,
            mode: mode,
            errorMessage: errorMessage,
            socket: socket,
            activeSessionId: activeSessionId,
            mediation: mediation,
            username: username,
            connectionState: connectionState,
            chatProcessName: chatProcessName,
            chatProjectId: chatProjectId,
            pickerProjectName: pickerProjectName,
            onPickerProjectPick: onPickerProjectPick,
            onPickerProjectResolved: onPickerProjectResolved,
            onChatViewProjectResolved: onChatViewProjectResolved,
            breadcrumbs: breadcrumbs,
            progressEvents: progressEvents,
            focusZone: focusZone,
            chatViewRef: chatViewRef,
            composerRef: composerRef,
            rightPanelRef: rightPanelRef,
            onLocalEchoFromComposer: onLocalEchoFromComposer,
            onRollbackEchoFromComposer: onRollbackEchoFromComposer,
            onSpeakMessageFromView: onSpeakMessageFromView,
            onNoteActivityFromView: onNoteActivityFromView,
            onHistoryLoadedFromView: onHistoryLoadedFromView,
            onAskUserPickFromView: onAskUserPickFromView,
            onWizardDeepLinkFromView: onWizardDeepLinkFromView,
            onPromptReadyFromRightPanel: onPromptReadyFromRightPanel,
            followUpSuggestion: followUpSuggestion,
            onLastAssistantChangedFromView: onLastAssistantChangedFromView,
            onComposerTextChanged: onComposerTextChanged,
            onComposerFocusChanged: onComposerFocusChanged,
            onAcceptFollowUpFromView: onAcceptFollowUpFromView,
            onFollowUpAcceptedFromComposer: onFollowUpAcceptedFromComposer,
            openAndBind: openAndBind,
            backToHub: backToHub,
            onSessionPicked: onSessionPicked,
            onSessionBootstrapped: onSessionBootstrapped,
            leaveLive: leaveLive,
            backToPicker: backToPicker,
            liveOk: liveOk,
            pickerMode: pickerMode,
            onTitleClick: onTitleClick,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ChatApp.vue.js.map