import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { WebSocketClosedError, WebSocketRequestError, markdownToSpeech, MIN_RATE, MAX_RATE, MIN_VOLUME, MAX_VOLUME, } from '@vance/shared';
import { buildUtterance, isSpeechSynthesisSupported, } from '../platform/speechWeb';
import { getSpeakerEnabled, getSpeechRate, getSpeechVolume, resolveSpeechLanguage, saveSpeakerEnabled, } from '../platform/speechSettings';
import { uploadChatboxAttachments, ChatboxUploadError, } from '@composables/useChatboxUpload';
import { VAlert, VButton, VTextarea } from '@components/index';
import { OPTIMISTIC_PREFIX } from './optimisticEcho';
const props = defineProps();
const emit = defineEmits();
const { t } = useI18n();
// ──────────────── Composer state ────────────────
// Draft persistence. The composer lives behind a {@code v-if} on the
// WS-liveness flag, so a reconnect (laptop sleep/wake, idle close)
// destroys and recreates it — without persistence the user's typed
// text is gone. We mirror {@link composerText} into sessionStorage
// under a per-chat key so the same chat in the same tab restores it,
// and a different chat (or a fresh tab) gets a clean slate.
const DRAFT_STORAGE_PREFIX = 'vance.chat.composerDraft:';
function draftStorageKey() {
    return props.draftKey ? `${DRAFT_STORAGE_PREFIX}${props.draftKey}` : null;
}
function readDraft() {
    const key = draftStorageKey();
    if (!key)
        return '';
    try {
        return window.sessionStorage.getItem(key) ?? '';
    }
    catch {
        return '';
    }
}
function writeDraft(value) {
    const key = draftStorageKey();
    if (!key)
        return;
    try {
        if (value)
            window.sessionStorage.setItem(key, value);
        else
            window.sessionStorage.removeItem(key);
    }
    catch { /* quota / disabled — silently skip */ }
}
const composerText = ref(readDraft());
const sending = ref(false);
const uploading = ref(false);
const sendError = ref(null);
/**
 * Auto-AI mode — when on, every send prepends {@code @ai } to the
 * message so the agent reacts to every turn from this tab (typical
 * "pilot" role in a multi-user session). Starting a message with
 * {@code @no } escapes the prepend for that single turn. Toggle via
 * the {@code /aa} slash command. State is per-tab in-memory; a
 * reload resets it to off. See planning/multi-user-sessions.md §6.
 */
const autoAiOn = ref(false);
function handleAutoAiCmd(arg) {
    if (arg === 'status' || arg === null && false /* /aa toggles */) {
        // Will fall through to status display below.
    }
    if (arg === 'on')
        autoAiOn.value = true;
    else if (arg === 'off')
        autoAiOn.value = false;
    else if (arg === null)
        autoAiOn.value = !autoAiOn.value;
    // 'status' is read-only — no state change, just feedback.
    const label = autoAiOn.value
        ? t('chat.autoAi.statusOn')
        : t('chat.autoAi.statusOff');
    sendError.value = label;
    if (autoAiFeedbackTimer)
        clearTimeout(autoAiFeedbackTimer);
    autoAiFeedbackTimer = setTimeout(() => {
        if (sendError.value === label)
            sendError.value = null;
        autoAiFeedbackTimer = null;
    }, 2500);
}
let autoAiFeedbackTimer = null;
/**
 * Returns the effective text that goes onto the wire. Encodes the
 * auto-AI rules:
 * <ul>
 *   <li>Leading {@code @no} (space-terminated, case-insensitive) is
 *       stripped — the user wants this turn to NOT wake the agent,
 *       overrides auto-prepend.</li>
 *   <li>If auto is on and the result does not already start with a
 *       {@code @<word>} mention, {@code @ai } is prepended.</li>
 * </ul>
 */
function applyAutoAi(text) {
    const noPrefix = /^@no(?:\s+|$)/i;
    if (noPrefix.test(text)) {
        return text.replace(noPrefix, '').trim();
    }
    if (!autoAiOn.value)
        return text;
    // Already addressed manually — leave as-is.
    if (/^@\w/.test(text))
        return text;
    return text.length === 0 ? '@ai' : `@ai ${text}`;
}
// Mirror composer text upward so the parent can drive the follow-up
// ghost-bubble visibility (only shown while the composer is empty),
// and into sessionStorage so a WS reconnect doesn't lose the draft.
watch(composerText, (next) => {
    emit('text-changed', next);
    writeDraft(next);
}, { immediate: true });
const selectedFiles = ref([]);
const selectedDocs = ref([]);
const dragActive = ref(false);
function attachCurrentFile() {
    const src = props.currentFileSource;
    if (!src)
        return;
    // Same doc shouldn't appear twice in the same turn — silently
    // dedupe so a double-click on the menu item is harmless.
    if (selectedDocs.value.some((d) => d.documentId === src.documentId))
        return;
    selectedDocs.value = [...selectedDocs.value, { ...src }];
}
function removeAttachedDoc(idx) {
    const next = selectedDocs.value.slice();
    next.splice(idx, 1);
    selectedDocs.value = next;
}
/**
 * Collapse the DaisyUI attachment-picker dropdown by blurring the
 * currently-focused element. Daisy's CSS-only dropdown stays open as
 * long as the trigger or any child holds focus.
 */
function closeAttachmentMenu() {
    const ae = document.activeElement;
    if (ae && ae instanceof HTMLElement)
        ae.blur();
}
/**
 * Composer mode: single-line uses Enter to send (Shift+Enter for a hard
 * break), multi-line uses Ctrl/Cmd+Enter (because plain Enter is the
 * obvious gesture for newline once the user has multiple lines).
 */
const multiline = ref(false);
const composerRows = computed(() => (multiline.value ? 4 : 1));
const composerPlaceholder = computed(() => {
    if (autoAiOn.value)
        return t('chat.autoAi.placeholder');
    return multiline.value
        ? t('chat.composerPlaceholderMulti')
        : t('chat.composerPlaceholderSingle');
});
/**
 * Narrow-viewport composer toolbar — the multiline toggle, speech
 * cluster, and file-picker collapse into a single {@code ⋯} menu
 * button on phones. Toggled open by tapping the button; closed again
 * by re-tapping it.
 */
const composerToolsOpen = ref(false);
/** Sequence for optimistic temp message ids — never collides with server ids. */
let optimisticSeq = 0;
const speechSupported = ref(false);
const speechRecording = ref(false);
const speechError = ref(null);
let recognition = null;
function initSpeechRecognition() {
    const w = window;
    const Ctor = w.SpeechRecognition ?? w.webkitSpeechRecognition;
    if (!Ctor)
        return;
    speechSupported.value = true;
    const instance = new Ctor();
    instance.continuous = true;
    instance.interimResults = false;
    instance.lang = resolveSpeechLanguage();
    instance.onresult = (event) => {
        let gotFinal = false;
        for (let i = event.resultIndex; i < event.results.length; i++) {
            const r = event.results[i];
            if (!r.isFinal)
                continue;
            const text = (r[0].transcript ?? '').trim();
            if (!text)
                continue;
            composerText.value = composerText.value
                ? `${composerText.value} ${text}`
                : text;
            gotFinal = true;
        }
        if (gotFinal && talkMode.value) {
            noteTalkActivity();
            scheduleTalkAutoSend();
        }
    };
    instance.onerror = (event) => {
        speechError.value = t('chat.speech.microphoneError', { error: event.error });
        speechRecording.value = false;
    };
    instance.onend = () => {
        speechRecording.value = false;
        if (talkMode.value && !speakerSpeaking.value && !sending.value) {
            window.setTimeout(() => {
                if (talkMode.value && !speakerSpeaking.value && !sending.value
                    && !speechRecording.value) {
                    startMic();
                }
            }, 50);
        }
    };
    recognition = instance;
}
function startMic() {
    if (!recognition)
        return;
    if (speechRecording.value)
        return;
    speechError.value = null;
    recognition.lang = resolveSpeechLanguage();
    try {
        recognition.start();
        speechRecording.value = true;
    }
    catch (e) {
        speechRecording.value = false;
        speechError.value = e instanceof Error ? e.message : t('chat.speech.recordStartFailed');
    }
}
function stopMic() {
    if (!recognition)
        return;
    if (!speechRecording.value)
        return;
    try {
        recognition.stop();
    }
    catch { /* already stopped */ }
}
function toggleSpeech() {
    if (!recognition)
        return;
    if (talkMode.value) {
        disableTalkMode();
        return;
    }
    if (speechRecording.value)
        stopMic();
    else
        startMic();
}
// ──────────────── Speaker (text-to-speech queue) ────────────────
const speakerSupported = ref(false);
const speakerEnabled = ref(false);
const speakerSpeaking = ref(false);
/**
 * Set true by the parent once the REST history snapshot has finished
 * loading. Until then {@link speakMessage} no-ops so the speaker
 * never reads aloud backfill content.
 */
const speakerLiveReady = ref(false);
// Live rate / volume seeded from the persisted profile defaults at
// mount time. The composer exposes both as quick-adjust sliders that
// are intentionally session-only — moving them affects the running
// chat but does NOT write back to the server. The user's persistent
// defaults live on the profile page; reload the chat tab to pick up
// a fresh profile default here.
const speechRate = ref(getSpeechRate());
const speechVolume = ref(getSpeechVolume());
function initSpeechSynthesis() {
    if (!isSpeechSynthesisSupported())
        return;
    speakerSupported.value = true;
    speakerEnabled.value = getSpeakerEnabled();
}
function speakMessage(content) {
    if (!speakerEnabled.value || !speakerSupported.value)
        return;
    if (!speakerLiveReady.value)
        return;
    const text = markdownToSpeech(content);
    if (!text)
        return;
    const utter = buildUtterance(text, resolveSpeechLanguage(), {
        rate: speechRate.value,
        volume: speechVolume.value,
    });
    if (!utter)
        return;
    utter.onstart = () => {
        speakerSpeaking.value = true;
        if (talkMode.value && speechRecording.value) {
            stopMic();
        }
    };
    utter.onend = () => {
        if (window.speechSynthesis && !window.speechSynthesis.speaking) {
            speakerSpeaking.value = false;
            if (talkMode.value) {
                noteTalkActivity();
                if (!speechRecording.value && !sending.value)
                    startMic();
            }
        }
    };
    utter.onerror = () => {
        speakerSpeaking.value = false;
        if (talkMode.value && !speechRecording.value && !sending.value) {
            startMic();
        }
    };
    window.speechSynthesis.speak(utter);
}
function toggleSpeaker() {
    if (!speakerSupported.value)
        return;
    if (talkMode.value) {
        disableTalkMode();
        return;
    }
    const next = !speakerEnabled.value;
    speakerEnabled.value = next;
    void saveSpeakerEnabled(next);
    if (!next) {
        window.speechSynthesis.cancel();
        speakerSpeaking.value = false;
    }
}
function onRateInput(event) {
    const value = parseFloat(event.target.value);
    if (!Number.isFinite(value))
        return;
    // Session-only override — not persisted. See speechRate / speechVolume
    // definition above.
    speechRate.value = value;
}
function onVolumeInput(event) {
    const value = parseFloat(event.target.value);
    if (!Number.isFinite(value))
        return;
    speechVolume.value = value;
}
// ──────────────── Talk-Mode (hands-free phone-call UX) ────────────────
//
// Übermode that orchestrates mic + speaker:
//   • on activation: speaker forced on, mic started, idle-timer armed.
//   • each final STT phrase appends to the composer; a 2s pause without
//     follow-up commits via auto-send (no Enter / no click needed).
//   • while the speaker is reading the reply, the mic is paused.
//   • idle-timeout (120s without mic input AND without assistant
//     output) hard-disables the whole stack.
//   • manually clicking 🎤 or 🔊 while talk mode is on also hard-
//     disables — the user taking back control.
const TALK_MODE_STORAGE_KEY = 'vance.chat.talkMode';
const TALK_MODE_IDLE_MS = 120_000;
const TALK_MODE_AUTO_SEND_MS = 2_000;
const talkMode = ref(false);
let talkIdleTimer = null;
let talkAutoSendTimer = null;
function readTalkModeStored() {
    try {
        return window.sessionStorage.getItem(TALK_MODE_STORAGE_KEY) === '1';
    }
    catch {
        return false;
    }
}
function writeTalkModeStored(on) {
    try {
        if (on)
            window.sessionStorage.setItem(TALK_MODE_STORAGE_KEY, '1');
        else
            window.sessionStorage.removeItem(TALK_MODE_STORAGE_KEY);
    }
    catch { /* ignore */ }
}
function clearTalkIdle() {
    if (talkIdleTimer !== null) {
        window.clearTimeout(talkIdleTimer);
        talkIdleTimer = null;
    }
}
function clearTalkAutoSend() {
    if (talkAutoSendTimer !== null) {
        window.clearTimeout(talkAutoSendTimer);
        talkAutoSendTimer = null;
    }
}
function noteTalkActivity() {
    if (!talkMode.value)
        return;
    clearTalkIdle();
    talkIdleTimer = window.setTimeout(() => {
        speechError.value = t('chat.speech.talkModeIdleOff');
        disableTalkMode();
    }, TALK_MODE_IDLE_MS);
}
function scheduleTalkAutoSend() {
    clearTalkAutoSend();
    talkAutoSendTimer = window.setTimeout(() => {
        talkAutoSendTimer = null;
        if (!talkMode.value)
            return;
        if (!composerText.value.trim())
            return;
        if (sending.value || uploading.value)
            return;
        stopMic();
        void send();
    }, TALK_MODE_AUTO_SEND_MS);
}
const talkModeSupported = computed(() => speechSupported.value && speakerSupported.value);
function enableTalkMode() {
    if (talkMode.value)
        return;
    if (!talkModeSupported.value)
        return;
    talkMode.value = true;
    writeTalkModeStored(true);
    speechError.value = null;
    if (!speakerEnabled.value) {
        speakerEnabled.value = true;
        void saveSpeakerEnabled(true);
    }
    if (!speakerSpeaking.value && !speechRecording.value) {
        startMic();
    }
    noteTalkActivity();
}
function disableTalkMode() {
    if (!talkMode.value)
        return;
    talkMode.value = false;
    writeTalkModeStored(false);
    clearTalkIdle();
    clearTalkAutoSend();
    stopMic();
    if (window.speechSynthesis && window.speechSynthesis.speaking) {
        window.speechSynthesis.cancel();
    }
    speakerSpeaking.value = false;
    if (speakerEnabled.value) {
        speakerEnabled.value = false;
        void saveSpeakerEnabled(false);
    }
}
function toggleTalkMode() {
    if (talkMode.value)
        disableTalkMode();
    else
        enableTalkMode();
}
// ──────────────── Send / pause ────────────────
async function send() {
    const text = composerText.value.trim();
    const filesSnapshot = selectedFiles.value.slice();
    const docsSnapshot = selectedDocs.value.slice();
    // While bound to a worker via Eddie's MEDIATE handover, the user can
    // type {@code /hub} to bounce back to Eddie. We intercept it here
    // so the brain's MediationEndHandler picks up the control frame
    // instead of process-steer enqueueing "/hub" as a chat message.
    if (props.mediation && text === '/hub'
        && filesSnapshot.length === 0
        && docsSnapshot.length === 0) {
        composerText.value = '';
        emit('hub');
        return;
    }
    // `/who` — multi-user participant lookup (see
    // planning/multi-user-sessions.md §7). Frontend-only command: never
    // sent to the engine as chat input; bounced up to the parent which
    // round-trips a {@code session-who} WS request and renders the
    // reply as an ephemeral activity line.
    if (text === '/who'
        && filesSnapshot.length === 0
        && docsSnapshot.length === 0) {
        composerText.value = '';
        emit('who');
        return;
    }
    // `/aa` — auto-AI toggle (see planning/multi-user-sessions.md §6).
    // Frontend-only slash command: flips the prepend-@ai-to-every-message
    // mode on or off. Lets a pilot keep talking to the agent without
    // typing the mention on every turn, while leaving the navigator
    // free to comment without unintentionally waking the agent. `@no`
    // at the start of a message escapes the auto-prepend for that turn.
    if ((text === '/aa'
        || text === '/aa on'
        || text === '/aa off'
        || text === '/aa status')
        && filesSnapshot.length === 0
        && docsSnapshot.length === 0) {
        composerText.value = '';
        handleAutoAiCmd(text.length === 3 ? null : text.slice(4));
        return;
    }
    // Allow attachment-only sends so the user can drop a PDF and hit
    // send without typing — Arthur can then ask "what should I do with
    // this?" rather than the UI silently rejecting the click.
    if (sending.value || !props.chatProcessName)
        return;
    if (!text && filesSnapshot.length === 0 && docsSnapshot.length === 0)
        return;
    sending.value = true;
    sendError.value = null;
    clearTalkAutoSend();
    noteTalkActivity();
    // Apply auto-AI rewriting just before the wire-send — keeps the
    // user's typed text untouched in echoes / drafts / history.
    const wireText = applyAutoAi(text);
    // Stage 1 — upload attachments.
    let attachmentRefs = [];
    if (filesSnapshot.length > 0) {
        uploading.value = true;
        try {
            attachmentRefs = await uploadChatboxAttachments(props.chatProjectId, filesSnapshot);
        }
        catch (e) {
            if (e instanceof ChatboxUploadError) {
                sendError.value = e.message;
            }
            else {
                sendError.value = e instanceof Error
                    ? e.message
                    : 'Attachment upload failed.';
            }
            sending.value = false;
            uploading.value = false;
            return;
        }
        uploading.value = false;
    }
    // Merge the upload-derived AttachmentRefs with the already-existing
    // document references (Cortex "current file" path). Existing refs go
    // first so the agent sees the user's contextual pick before the
    // ad-hoc uploads.
    const allAttachments = [
        ...docsSnapshot.map((d) => ({ documentId: d.documentId })),
        ...attachmentRefs,
    ];
    // Stage 2 — emit the optimistic echo (the parent appends to its
    // message stream), clear the composer, and send the steer.
    // The echo carries the wire-text (auto-AI rewriting applied) so
    // what the user sees in the local stream matches what landed in
    // chat history and what other participants will see.
    const optimisticId = `${OPTIMISTIC_PREFIX}${++optimisticSeq}`;
    const echoText = filesSnapshot.length > 0 || docsSnapshot.length > 0
        ? attachmentEchoPrefix(filesSnapshot, docsSnapshot) + wireText
        : wireText;
    emit('local-echo', {
        messageId: optimisticId,
        thinkProcessId: '',
        role: 'USER',
        content: echoText,
        createdAt: new Date(),
        addressedToAgent: true,
    });
    composerText.value = '';
    selectedFiles.value = [];
    selectedDocs.value = [];
    try {
        // The WS may have been closed by the server during idle. Try a
        // transparent reconnect before failing the send — the user expects
        // their click to land, not to hit a "connection lost" banner just
        // because nothing happened on the connection for a while.
        if (props.socket.closed() && props.ensureConnected) {
            const reconnected = await props.ensureConnected();
            if (!reconnected) {
                throw new WebSocketClosedError('Reconnect failed');
            }
        }
        // Per-turn voice-mode signal — see specification/voice-mode.md.
        const voiceMode = speakerEnabled.value || talkMode.value;
        await props.socket.send('process-steer', {
            processName: props.chatProcessName,
            content: wireText,
            attachments: allAttachments.length > 0 ? allAttachments : undefined,
            voiceMode: voiceMode ? true : undefined,
            activeApp: props.activeApp ?? undefined,
        });
    }
    catch (e) {
        emit('rollback-echo', optimisticId);
        // Restore composer state so the user's input isn't lost (typical
        // case: WebSocket closed silently while idle, send fails on the
        // first frame). The textarea is not disabled during send, so the
        // user may have typed something new in the meantime — prepend the
        // failed text rather than overwrite, and dedupe re-added files/docs.
        if (text) {
            composerText.value = composerText.value
                ? `${text}\n\n${composerText.value}`
                : text;
        }
        if (filesSnapshot.length > 0) {
            const fileKey = (f) => `${f.name}|${f.size}|${f.lastModified}`;
            const existing = new Set(selectedFiles.value.map(fileKey));
            selectedFiles.value = [
                ...filesSnapshot.filter((f) => !existing.has(fileKey(f))),
                ...selectedFiles.value,
            ];
        }
        if (docsSnapshot.length > 0) {
            const existing = new Set(selectedDocs.value.map((d) => d.documentId));
            selectedDocs.value = [
                ...docsSnapshot.filter((d) => !existing.has(d.documentId)),
                ...selectedDocs.value,
            ];
        }
        if (e instanceof WebSocketRequestError) {
            sendError.value = `${e.message} (code ${e.errorCode})`;
        }
        else {
            sendError.value = e instanceof Error ? e.message : t('chat.failedToSend');
        }
    }
    finally {
        sending.value = false;
    }
}
/**
 * Fire-and-forget {@code process-pause} for the bound session. Pauses
 * everything alive in the session (chat + workers) and immediately
 * drops the local "sending" spinner so the composer is usable again.
 */
function pause() {
    if (!sending.value)
        return;
    try {
        props.socket.sendNoReply('process-pause', {});
    }
    catch (e) {
        sendError.value = e instanceof Error ? e.message : 'Pause failed.';
    }
    sending.value = false;
}
function onComposerFocusIn() {
    emit('focus-changed', true);
}
function onComposerFocusOut(event) {
    // {@code focusout} bubbles before the new {@code focusin} fires, so
    // {@code relatedTarget} tells us whether focus is moving to another
    // element inside the same wrapper (e.g. the speech-rate slider in
    // the toolbar) — in which case the composer is still "active".
    const next = event.relatedTarget;
    const root = event.currentTarget;
    if (next && root.contains(next))
        return;
    emit('focus-changed', false);
}
function onComposerKeydown(event) {
    // F4 — toggle auto-AI mode (see planning/multi-user-sessions.md §6).
    // Universal across browsers and platforms, no modifier needed, no
    // conflict with text-editing shortcuts.
    if (event.key === 'F4'
        && !event.ctrlKey && !event.metaKey && !event.altKey && !event.shiftKey) {
        event.preventDefault();
        handleAutoAiCmd(null);
        return;
    }
    // Follow-up acceptance: Space (or Tab) against an active suggestion
    // while the composer is empty writes the suggestion into the input
    // instead of inserting whitespace. Shell-autosuggestion style.
    if (!event.ctrlKey && !event.metaKey && !event.altKey &&
        composerText.value.length === 0 &&
        props.followUpSuggestion &&
        (event.key === ' ' || event.key === 'Tab')) {
        event.preventDefault();
        const appendSpace = event.key === ' ';
        composerText.value = props.followUpSuggestion + (appendSpace ? ' ' : '');
        emit('follow-up-accepted');
        return;
    }
    if (event.key !== 'Enter')
        return;
    if (multiline.value) {
        if (event.ctrlKey || event.metaKey) {
            event.preventDefault();
            void send();
        }
        return;
    }
    if (!event.shiftKey) {
        event.preventDefault();
        void send();
    }
}
// ──────────────── Drag-and-drop / picker ────────────────
const fileInputRef = ref(null);
function onComposerDragEnter(event) {
    if (sending.value || uploading.value)
        return;
    event.preventDefault();
    dragActive.value = true;
}
function onComposerDragOver(event) {
    if (sending.value || uploading.value)
        return;
    event.preventDefault();
    dragActive.value = true;
}
function onComposerDragLeave(event) {
    const related = event.relatedTarget;
    if (related && event.currentTarget.contains(related))
        return;
    dragActive.value = false;
}
function onComposerDrop(event) {
    event.preventDefault();
    dragActive.value = false;
    if (sending.value || uploading.value)
        return;
    const incoming = event.dataTransfer?.files;
    if (!incoming || incoming.length === 0)
        return;
    appendFiles(Array.from(incoming));
}
function onFilePickerChange(event) {
    const input = event.target;
    if (input.files) {
        appendFiles(Array.from(input.files));
    }
    input.value = '';
}
function appendFiles(incoming) {
    const merged = selectedFiles.value.slice();
    const fingerprint = (f) => `${f.name}|${f.size}|${f.lastModified}`;
    const seen = new Set(merged.map(fingerprint));
    for (const f of incoming) {
        const key = fingerprint(f);
        if (!seen.has(key)) {
            merged.push(f);
            seen.add(key);
        }
    }
    selectedFiles.value = merged;
}
function removeFile(index) {
    const next = selectedFiles.value.slice();
    next.splice(index, 1);
    selectedFiles.value = next;
}
function attachmentEchoPrefix(files, docs) {
    const lines = [];
    for (const d of docs) {
        lines.push(`📄 ${d.label}`);
    }
    for (const f of files) {
        lines.push(`📎 ${f.name} _(${formatBytes(f.size)})_`);
    }
    return lines.length > 0 ? lines.join('\n') + '\n\n' : '';
}
function formatBytes(bytes) {
    if (bytes < 1024)
        return `${bytes} B`;
    if (bytes < 1024 * 1024)
        return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}
// ──────────────── Imperative API ────────────────
/** Open the TTS gate. Called by the parent once the initial REST
 *  history snapshot has loaded — from that point on, every non-USER
 *  {@code chat-message-appended} frame gets spoken when the speaker
 *  is enabled. */
function markSpeakerLive() {
    speakerLiveReady.value = true;
}
/** Replace the composer text — used by the wizard prompt-ready
 *  handoff and by the ASK_USER picker click. */
function setText(text) {
    composerText.value = text;
}
/** Replace text AND send immediately. Used by the ASK_USER picker
 *  flow: the picker's option label is the canonical reply, no
 *  edit step expected. */
async function setTextAndSend(text) {
    if (sending.value)
        return;
    composerText.value = text;
    await send();
}
const __VLS_exposed = {
    speakMessage,
    noteTalkActivity,
    markSpeakerLive,
    setText,
    setTextAndSend,
};
defineExpose(__VLS_exposed);
// ──────────────── Lifecycle ────────────────
onMounted(() => {
    initSpeechRecognition();
    initSpeechSynthesis();
    // Restore Talk-Mode from sessionStorage. Tab-scoped: survives a
    // session switch (this composer remounts via :key="sessionId" on
    // the parent), but not a tab restart.
    if (readTalkModeStored()) {
        enableTalkMode();
    }
});
onBeforeUnmount(() => {
    // Tear down Talk-Mode timers but keep the persisted flag — a
    // session switch (component remount) is expected to resurrect it
    // from sessionStorage on the next mount.
    clearTalkIdle();
    clearTalkAutoSend();
    if (recognition && speechRecording.value) {
        try {
            recognition.stop();
        }
        catch { /* already stopped */ }
    }
    if (speakerSupported.value && window.speechSynthesis.speaking) {
        window.speechSynthesis.cancel();
    }
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['composer-tools-toggle']} */ ;
/** @type {__VLS_StyleScopedClasses['composer--compact']} */ ;
/** @type {__VLS_StyleScopedClasses['composer-tools']} */ ;
/** @type {__VLS_StyleScopedClasses['composer--compact']} */ ;
/** @type {__VLS_StyleScopedClasses['composer-tools']} */ ;
/** @type {__VLS_StyleScopedClasses['composer-tools-toggle']} */ ;
/** @type {__VLS_StyleScopedClasses['composer-tools']} */ ;
/** @type {__VLS_StyleScopedClasses['composer-tools']} */ ;
/** @type {__VLS_StyleScopedClasses['composer-tools--open']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ onDragenter: (__VLS_ctx.onComposerDragEnter) },
    ...{ onDragover: (__VLS_ctx.onComposerDragOver) },
    ...{ onDragleave: (__VLS_ctx.onComposerDragLeave) },
    ...{ onDrop: (__VLS_ctx.onComposerDrop) },
    ...{ class: "p-4 relative h-full" },
    ...{ class: ({ 'composer--compact': __VLS_ctx.compactTools }) },
});
if (__VLS_ctx.dragActive) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "\u0061\u0062\u0073\u006f\u006c\u0075\u0074\u0065\u0020\u0069\u006e\u0073\u0065\u0074\u002d\u0030\u0020\u0066\u006c\u0065\u0078\u0020\u0069\u0074\u0065\u006d\u0073\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u0020\u006a\u0075\u0073\u0074\u0069\u0066\u0079\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u002d\u0032\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u002d\u0064\u0061\u0073\u0068\u0065\u0064\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u002d\u0070\u0072\u0069\u006d\u0061\u0072\u0079\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0062\u0067\u002d\u0070\u0072\u0069\u006d\u0061\u0072\u0079\u002f\u0031\u0030\u0020\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u0020\u0070\u006f\u0069\u006e\u0074\u0065\u0072\u002d\u0065\u0076\u0065\u006e\u0074\u0073\u002d\u006e\u006f\u006e\u0065\u0020\u007a\u002d\u0031\u0030" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-sm font-semibold text-primary" },
    });
    (__VLS_ctx.$t('chat.attachments.dropToAttach'));
}
if (__VLS_ctx.sendError) {
    const __VLS_0 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        variant: "error",
        ...{ class: "mb-2" },
    }));
    const __VLS_2 = __VLS_1({
        variant: "error",
        ...{ class: "mb-2" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    __VLS_3.slots.default;
    (__VLS_ctx.sendError);
    var __VLS_3;
}
if (__VLS_ctx.speechError) {
    const __VLS_4 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
        variant: "warning",
        ...{ class: "mb-2" },
    }));
    const __VLS_6 = __VLS_5({
        variant: "warning",
        ...{ class: "mb-2" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_5));
    __VLS_7.slots.default;
    (__VLS_ctx.speechError);
    var __VLS_7;
}
if (__VLS_ctx.selectedFiles.length > 0 || __VLS_ctx.selectedDocs.length > 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "max-w-5xl mx-auto mb-2 flex flex-wrap gap-2" },
    });
    for (const [doc, idx] of __VLS_getVForSourceType((__VLS_ctx.selectedDocs))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: (`doc-${doc.documentId}`),
            ...{ class: "\u0066\u006c\u0065\u0078\u0020\u0069\u0074\u0065\u006d\u0073\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u0020\u0067\u0061\u0070\u002d\u0032\u0020\u0070\u0078\u002d\u0032\u0020\u0070\u0079\u002d\u0031\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u002d\u0070\u0072\u0069\u006d\u0061\u0072\u0079\u002f\u0034\u0030\u0020\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u0020\u0062\u0067\u002d\u0070\u0072\u0069\u006d\u0061\u0072\u0079\u002f\u0035\u0020\u0074\u0065\u0078\u0074\u002d\u0073\u006d" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            'aria-hidden': "true",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-mono truncate max-w-xs" },
        });
        (doc.label);
        const __VLS_8 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            disabled: (__VLS_ctx.sending || __VLS_ctx.uploading),
            title: (__VLS_ctx.$t('chat.attachments.remove')),
        }));
        const __VLS_10 = __VLS_9({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            disabled: (__VLS_ctx.sending || __VLS_ctx.uploading),
            title: (__VLS_ctx.$t('chat.attachments.remove')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_9));
        let __VLS_12;
        let __VLS_13;
        let __VLS_14;
        const __VLS_15 = {
            onClick: (...[$event]) => {
                if (!(__VLS_ctx.selectedFiles.length > 0 || __VLS_ctx.selectedDocs.length > 0))
                    return;
                __VLS_ctx.removeAttachedDoc(idx);
            }
        };
        __VLS_11.slots.default;
        var __VLS_11;
    }
    for (const [file, idx] of __VLS_getVForSourceType((__VLS_ctx.selectedFiles))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: (`att-${file.name}-${idx}`),
            ...{ class: "\u0066\u006c\u0065\u0078\u0020\u0069\u0074\u0065\u006d\u0073\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u0020\u0067\u0061\u0070\u002d\u0032\u0020\u0070\u0078\u002d\u0032\u0020\u0070\u0079\u002d\u0031\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u002d\u0062\u0061\u0073\u0065\u002d\u0033\u0030\u0030\u0020\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u0020\u0062\u0067\u002d\u0062\u0061\u0073\u0065\u002d\u0032\u0030\u0030\u0020\u0074\u0065\u0078\u0074\u002d\u0073\u006d" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            'aria-hidden': "true",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-mono truncate max-w-xs" },
        });
        (file.name);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs opacity-60" },
        });
        (__VLS_ctx.formatBytes(file.size));
        const __VLS_16 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            disabled: (__VLS_ctx.sending || __VLS_ctx.uploading),
            title: (__VLS_ctx.$t('chat.attachments.remove')),
        }));
        const __VLS_18 = __VLS_17({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            disabled: (__VLS_ctx.sending || __VLS_ctx.uploading),
            title: (__VLS_ctx.$t('chat.attachments.remove')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_17));
        let __VLS_20;
        let __VLS_21;
        let __VLS_22;
        const __VLS_23 = {
            onClick: (...[$event]) => {
                if (!(__VLS_ctx.selectedFiles.length > 0 || __VLS_ctx.selectedDocs.length > 0))
                    return;
                __VLS_ctx.removeFile(idx);
            }
        };
        __VLS_19.slots.default;
        var __VLS_19;
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "max-w-5xl mx-auto flex gap-2 items-end relative" },
});
const __VLS_24 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    ...{ class: "composer-tools-toggle" },
    title: (__VLS_ctx.composerToolsOpen ? 'Hide tools' : 'Show tools'),
}));
const __VLS_26 = __VLS_25({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    ...{ class: "composer-tools-toggle" },
    title: (__VLS_ctx.composerToolsOpen ? 'Hide tools' : 'Show tools'),
}, ...__VLS_functionalComponentArgsRest(__VLS_25));
let __VLS_28;
let __VLS_29;
let __VLS_30;
const __VLS_31 = {
    onClick: (...[$event]) => {
        __VLS_ctx.composerToolsOpen = !__VLS_ctx.composerToolsOpen;
    }
};
__VLS_27.slots.default;
var __VLS_27;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "composer-tools" },
    ...{ class: ({ 'composer-tools--open': __VLS_ctx.composerToolsOpen }) },
});
const __VLS_32 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_33 = __VLS_asFunctionalComponent(__VLS_32, new __VLS_32({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    title: (__VLS_ctx.multiline ? __VLS_ctx.$t('chat.multilineToggleSingle') : __VLS_ctx.$t('chat.multilineToggleMulti')),
}));
const __VLS_34 = __VLS_33({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    title: (__VLS_ctx.multiline ? __VLS_ctx.$t('chat.multilineToggleSingle') : __VLS_ctx.$t('chat.multilineToggleMulti')),
}, ...__VLS_functionalComponentArgsRest(__VLS_33));
let __VLS_36;
let __VLS_37;
let __VLS_38;
const __VLS_39 = {
    onClick: (...[$event]) => {
        __VLS_ctx.multiline = !__VLS_ctx.multiline;
    }
};
__VLS_35.slots.default;
(__VLS_ctx.multiline ? '▲' : '▼');
var __VLS_35;
if (__VLS_ctx.speechSupported || __VLS_ctx.speakerSupported) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex gap-1 items-center" },
    });
    if (__VLS_ctx.talkModeSupported) {
        const __VLS_40 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_41 = __VLS_asFunctionalComponent(__VLS_40, new __VLS_40({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            ...{ class: (__VLS_ctx.talkMode ? 'text-success animate-pulse' : '') },
            title: (__VLS_ctx.talkMode ? __VLS_ctx.$t('chat.speech.talkModeStop') : __VLS_ctx.$t('chat.speech.talkModeStart')),
        }));
        const __VLS_42 = __VLS_41({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            ...{ class: (__VLS_ctx.talkMode ? 'text-success animate-pulse' : '') },
            title: (__VLS_ctx.talkMode ? __VLS_ctx.$t('chat.speech.talkModeStop') : __VLS_ctx.$t('chat.speech.talkModeStart')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_41));
        let __VLS_44;
        let __VLS_45;
        let __VLS_46;
        const __VLS_47 = {
            onClick: (__VLS_ctx.toggleTalkMode)
        };
        __VLS_43.slots.default;
        var __VLS_43;
    }
    if (__VLS_ctx.speechSupported) {
        const __VLS_48 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_49 = __VLS_asFunctionalComponent(__VLS_48, new __VLS_48({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            ...{ class: (__VLS_ctx.speechRecording ? 'text-error animate-pulse' : '') },
            title: (__VLS_ctx.speechRecording ? __VLS_ctx.$t('chat.speech.stopSpeechToText') : __VLS_ctx.$t('chat.speech.startSpeechToText')),
        }));
        const __VLS_50 = __VLS_49({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            ...{ class: (__VLS_ctx.speechRecording ? 'text-error animate-pulse' : '') },
            title: (__VLS_ctx.speechRecording ? __VLS_ctx.$t('chat.speech.stopSpeechToText') : __VLS_ctx.$t('chat.speech.startSpeechToText')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_49));
        let __VLS_52;
        let __VLS_53;
        let __VLS_54;
        const __VLS_55 = {
            onClick: (__VLS_ctx.toggleSpeech)
        };
        __VLS_51.slots.default;
        var __VLS_51;
    }
    if (__VLS_ctx.speakerSupported) {
        const __VLS_56 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_57 = __VLS_asFunctionalComponent(__VLS_56, new __VLS_56({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            ...{ class: (__VLS_ctx.speakerEnabled ? (__VLS_ctx.speakerSpeaking ? 'text-success animate-pulse' : 'text-success') : '') },
            title: (__VLS_ctx.speakerEnabled ? __VLS_ctx.$t('chat.speech.muteIncoming') : __VLS_ctx.$t('chat.speech.readAloud')),
        }));
        const __VLS_58 = __VLS_57({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            ...{ class: (__VLS_ctx.speakerEnabled ? (__VLS_ctx.speakerSpeaking ? 'text-success animate-pulse' : 'text-success') : '') },
            title: (__VLS_ctx.speakerEnabled ? __VLS_ctx.$t('chat.speech.muteIncoming') : __VLS_ctx.$t('chat.speech.readAloud')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_57));
        let __VLS_60;
        let __VLS_61;
        let __VLS_62;
        const __VLS_63 = {
            onClick: (__VLS_ctx.toggleSpeaker)
        };
        __VLS_59.slots.default;
        (__VLS_ctx.speakerEnabled ? '🔊' : '🔇');
        var __VLS_59;
    }
    if (__VLS_ctx.speakerSupported) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
            ...{ onInput: (__VLS_ctx.onVolumeInput) },
            type: "range",
            ...{ class: "range range-xs w-16" },
            min: (__VLS_ctx.MIN_VOLUME),
            max: (__VLS_ctx.MAX_VOLUME),
            step: "0.05",
            value: (__VLS_ctx.speechVolume),
            title: (__VLS_ctx.$t('chat.speech.volume') + ': ' + Math.round(__VLS_ctx.speechVolume * 100) + '%'),
        });
    }
    if (__VLS_ctx.speakerSupported) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
            ...{ onInput: (__VLS_ctx.onRateInput) },
            type: "range",
            ...{ class: "range range-xs w-16" },
            min: (__VLS_ctx.MIN_RATE),
            max: (__VLS_ctx.MAX_RATE),
            step: "0.05",
            value: (__VLS_ctx.speechRate),
            title: (__VLS_ctx.$t('chat.speech.rate') + ': ' + __VLS_ctx.speechRate.toFixed(2) + '×'),
        });
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
    ...{ onChange: (__VLS_ctx.onFilePickerChange) },
    ref: "fileInputRef",
    type: "file",
    ...{ class: "hidden" },
    multiple: true,
});
/** @type {typeof __VLS_ctx.fileInputRef} */ ;
if (__VLS_ctx.currentFileSource) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "dropdown dropdown-top" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        tabindex: "0",
        role: "button",
        ...{ class: "btn btn-ghost btn-sm" },
        ...{ class: ({ 'btn-disabled': __VLS_ctx.sending || __VLS_ctx.uploading || !__VLS_ctx.chatProcessName }) },
        title: (__VLS_ctx.$t('chat.attachments.pickerTooltip')),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
        tabindex: "0",
        ...{ class: "\u0064\u0072\u006f\u0070\u0064\u006f\u0077\u006e\u002d\u0063\u006f\u006e\u0074\u0065\u006e\u0074\u0020\u006d\u0065\u006e\u0075\u0020\u006d\u0065\u006e\u0075\u002d\u0073\u006d\u0020\u0062\u0067\u002d\u0062\u0061\u0073\u0065\u002d\u0031\u0030\u0030\u0020\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u002d\u0062\u006f\u0078\u0020\u007a\u002d\u005b\u0033\u0030\u005d\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u006d\u0062\u002d\u0032\u0020\u0077\u002d\u0037\u0032\u0020\u0070\u002d\u0032\u0020\u0073\u0068\u0061\u0064\u006f\u0077\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u002d\u0062\u0061\u0073\u0065\u002d\u0033\u0030\u0030" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.currentFileSource))
                    return;
                __VLS_ctx.closeAttachmentMenu();
                __VLS_ctx.fileInputRef?.click();
            } },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        'aria-hidden': "true",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "flex-1" },
    });
    (__VLS_ctx.$t('chat.attachments.pickFromComputer'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.currentFileSource))
                    return;
                __VLS_ctx.closeAttachmentMenu();
                __VLS_ctx.attachCurrentFile();
            } },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        'aria-hidden': "true",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "flex-1 min-w-0" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "block text-xs opacity-60" },
    });
    (__VLS_ctx.$t('chat.attachments.attachCurrentFile'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "block truncate font-mono" },
    });
    (__VLS_ctx.currentFileSource.label);
}
else {
    const __VLS_64 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_65 = __VLS_asFunctionalComponent(__VLS_64, new __VLS_64({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        disabled: (__VLS_ctx.sending || __VLS_ctx.uploading || !__VLS_ctx.chatProcessName),
        title: (__VLS_ctx.$t('chat.attachments.pickerTooltip')),
    }));
    const __VLS_66 = __VLS_65({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        disabled: (__VLS_ctx.sending || __VLS_ctx.uploading || !__VLS_ctx.chatProcessName),
        title: (__VLS_ctx.$t('chat.attachments.pickerTooltip')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_65));
    let __VLS_68;
    let __VLS_69;
    let __VLS_70;
    const __VLS_71 = {
        onClick: (() => __VLS_ctx.fileInputRef?.click())
    };
    __VLS_67.slots.default;
    var __VLS_67;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ onFocusin: (__VLS_ctx.onComposerFocusIn) },
    ...{ onFocusout: (__VLS_ctx.onComposerFocusOut) },
    ...{ class: "flex-1" },
});
const __VLS_72 = {}.VTextarea;
/** @type {[typeof __VLS_components.VTextarea, ]} */ ;
// @ts-ignore
const __VLS_73 = __VLS_asFunctionalComponent(__VLS_72, new __VLS_72({
    ...{ 'onKeydown': {} },
    modelValue: (__VLS_ctx.composerText),
    placeholder: (__VLS_ctx.composerPlaceholder),
    rows: (__VLS_ctx.composerRows),
}));
const __VLS_74 = __VLS_73({
    ...{ 'onKeydown': {} },
    modelValue: (__VLS_ctx.composerText),
    placeholder: (__VLS_ctx.composerPlaceholder),
    rows: (__VLS_ctx.composerRows),
}, ...__VLS_functionalComponentArgsRest(__VLS_73));
let __VLS_76;
let __VLS_77;
let __VLS_78;
const __VLS_79 = {
    onKeydown: (__VLS_ctx.onComposerKeydown)
};
var __VLS_75;
const __VLS_80 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_81 = __VLS_asFunctionalComponent(__VLS_80, new __VLS_80({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: ((!__VLS_ctx.composerText.trim() && __VLS_ctx.selectedFiles.length === 0 && __VLS_ctx.selectedDocs.length === 0)
        || __VLS_ctx.sending || __VLS_ctx.uploading || !__VLS_ctx.chatProcessName),
    loading: (__VLS_ctx.sending || __VLS_ctx.uploading),
    title: (__VLS_ctx.$t('chat.send')),
}));
const __VLS_82 = __VLS_81({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: ((!__VLS_ctx.composerText.trim() && __VLS_ctx.selectedFiles.length === 0 && __VLS_ctx.selectedDocs.length === 0)
        || __VLS_ctx.sending || __VLS_ctx.uploading || !__VLS_ctx.chatProcessName),
    loading: (__VLS_ctx.sending || __VLS_ctx.uploading),
    title: (__VLS_ctx.$t('chat.send')),
}, ...__VLS_functionalComponentArgsRest(__VLS_81));
let __VLS_84;
let __VLS_85;
let __VLS_86;
const __VLS_87 = {
    onClick: (__VLS_ctx.send)
};
__VLS_83.slots.default;
var __VLS_83;
if (__VLS_ctx.sending) {
    const __VLS_88 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_89 = __VLS_asFunctionalComponent(__VLS_88, new __VLS_88({
        ...{ 'onClick': {} },
        variant: "danger",
        title: (__VLS_ctx.$t('chat.pauseTooltip')),
    }));
    const __VLS_90 = __VLS_89({
        ...{ 'onClick': {} },
        variant: "danger",
        title: (__VLS_ctx.$t('chat.pauseTooltip')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_89));
    let __VLS_92;
    let __VLS_93;
    let __VLS_94;
    const __VLS_95 = {
        onClick: (__VLS_ctx.pause)
    };
    __VLS_91.slots.default;
    var __VLS_91;
}
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['relative']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['composer--compact']} */ ;
/** @type {__VLS_StyleScopedClasses['absolute']} */ ;
/** @type {__VLS_StyleScopedClasses['inset-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['border-2']} */ ;
/** @type {__VLS_StyleScopedClasses['border-dashed']} */ ;
/** @type {__VLS_StyleScopedClasses['border-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-primary/10']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['pointer-events-none']} */ ;
/** @type {__VLS_StyleScopedClasses['z-10']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-5xl']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-primary/40']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-primary/5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-5xl']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['items-end']} */ ;
/** @type {__VLS_StyleScopedClasses['relative']} */ ;
/** @type {__VLS_StyleScopedClasses['composer-tools-toggle']} */ ;
/** @type {__VLS_StyleScopedClasses['composer-tools']} */ ;
/** @type {__VLS_StyleScopedClasses['composer-tools--open']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['range']} */ ;
/** @type {__VLS_StyleScopedClasses['range-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['w-16']} */ ;
/** @type {__VLS_StyleScopedClasses['range']} */ ;
/** @type {__VLS_StyleScopedClasses['range-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['w-16']} */ ;
/** @type {__VLS_StyleScopedClasses['hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['dropdown']} */ ;
/** @type {__VLS_StyleScopedClasses['dropdown-top']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-disabled']} */ ;
/** @type {__VLS_StyleScopedClasses['dropdown-content']} */ ;
/** @type {__VLS_StyleScopedClasses['menu']} */ ;
/** @type {__VLS_StyleScopedClasses['menu-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-box']} */ ;
/** @type {__VLS_StyleScopedClasses['z-[30]']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['w-72']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['shadow']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['block']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['block']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            MIN_RATE: MIN_RATE,
            MAX_RATE: MAX_RATE,
            MIN_VOLUME: MIN_VOLUME,
            MAX_VOLUME: MAX_VOLUME,
            VAlert: VAlert,
            VButton: VButton,
            VTextarea: VTextarea,
            composerText: composerText,
            sending: sending,
            uploading: uploading,
            sendError: sendError,
            selectedFiles: selectedFiles,
            selectedDocs: selectedDocs,
            dragActive: dragActive,
            attachCurrentFile: attachCurrentFile,
            removeAttachedDoc: removeAttachedDoc,
            closeAttachmentMenu: closeAttachmentMenu,
            multiline: multiline,
            composerRows: composerRows,
            composerPlaceholder: composerPlaceholder,
            composerToolsOpen: composerToolsOpen,
            speechSupported: speechSupported,
            speechRecording: speechRecording,
            speechError: speechError,
            toggleSpeech: toggleSpeech,
            speakerSupported: speakerSupported,
            speakerEnabled: speakerEnabled,
            speakerSpeaking: speakerSpeaking,
            speechRate: speechRate,
            speechVolume: speechVolume,
            toggleSpeaker: toggleSpeaker,
            onRateInput: onRateInput,
            onVolumeInput: onVolumeInput,
            talkMode: talkMode,
            talkModeSupported: talkModeSupported,
            toggleTalkMode: toggleTalkMode,
            send: send,
            pause: pause,
            onComposerFocusIn: onComposerFocusIn,
            onComposerFocusOut: onComposerFocusOut,
            onComposerKeydown: onComposerKeydown,
            fileInputRef: fileInputRef,
            onComposerDragEnter: onComposerDragEnter,
            onComposerDragOver: onComposerDragOver,
            onComposerDragLeave: onComposerDragLeave,
            onComposerDrop: onComposerDrop,
            onFilePickerChange: onFilePickerChange,
            removeFile: removeFile,
            formatBytes: formatBytes,
        };
    },
    __typeEmits: {},
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {
            ...__VLS_exposed,
        };
    },
    __typeEmits: {},
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ChatComposer.vue.js.map