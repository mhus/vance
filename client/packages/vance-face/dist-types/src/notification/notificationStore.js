import { ref } from 'vue';
/**
 * In-app toast stack for the user-notification side-channel (the
 * {@code notify} WebSocket message). Toasts auto-dismiss; severity
 * drives the pitch of the WebAudio beep and the toast color.
 *
 * <p>Spec: specification/user-notification-channel.md
 *
 * <p>Implemented as a module-level reactive singleton — not a Pinia
 * store. The toast component is mounted globally inside EditorShell,
 * which means every MPA in the face workspace inherits it. Coupling
 * the global toast layer to Pinia would force every entry-point's
 * {@code main.ts} to register Pinia (multiple don't); a plain reactive
 * ref keeps the contract "import the shell, get toasts".
 *
 * <p>Side-effects (WebAudio, Browser-Notification) live in this module
 * too — the host component only consumes {@link toasts} and calls
 * {@link dismiss}. The subscription itself lives in
 * {@code useNotificationSubscription}.
 */
const TOAST_TTL_MS = 4500;
const MAX_TOASTS = 5;
const toastsRef = ref([]);
let counter = 0;
let permissionRequested = false;
let audioContext = null;
/**
 * Hybrid render policy:
 * <ul>
 *   <li>Tab visible → in-app toast (the OS banner would be suppressed
 *       by most browsers in a focused tab anyway, and the user is
 *       looking at the page).</li>
 *   <li>Tab hidden + permission granted → OS-native notification
 *       (Notification Center, system banner) — that's the whole point
 *       of going native.</li>
 *   <li>Tab hidden + permission missing/denied → fall back to the
 *       in-app toast so nothing is silently lost when the user
 *       eventually focuses the tab.</li>
 *   <li>Audio beep fires in every case (when AudioContext allows it).</li>
 * </ul>
 */
function push(notification) {
    if (!notification?.text)
        return;
    void beep(notification.severity);
    // We treat the page as "user not looking" when either the tab is
    // browser-hidden (visibilityState='hidden' — other tab or minimized)
    // OR the document doesn't have focus (other window / other app on
    // the same desktop). `visibilityState` alone only fires when the tab
    // is *in the same browser window* but not active; a different
    // browser window or a different app in the foreground still leaves
    // visibilityState='visible' — which is not what the user means by
    // "Ich schaue nicht hin".
    const userNotLooking = typeof document !== 'undefined'
        && (document.visibilityState === 'hidden' || !document.hasFocus());
    const perm = readPermission();
    // Lazy permission request — once per page session. We fire it
    // regardless of current visibility so the user is prepped for the
    // *next* hidden-tab notification, but never up-front before the
    // first frame arrives.
    if (perm === 'default' && !permissionRequested) {
        permissionRequested = true;
        void requestNotificationPermission();
    }
    if (userNotLooking && perm === 'granted') {
        showSystemNotification(notification);
        return;
    }
    showToast(notification);
}
function showToast(notification) {
    const id = `n-${Date.now()}-${++counter}`;
    toastsRef.value.push({ id, notification, addedAt: Date.now() });
    if (toastsRef.value.length > MAX_TOASTS) {
        // Drop the oldest — a flooding caller shouldn't push the user out
        // of the viewport.
        toastsRef.value.splice(0, toastsRef.value.length - MAX_TOASTS);
    }
    window.setTimeout(() => dismiss(id), TOAST_TTL_MS);
}
function dismiss(id) {
    const idx = toastsRef.value.findIndex((t) => t.id === id);
    if (idx >= 0)
        toastsRef.value.splice(idx, 1);
}
function readPermission() {
    if (typeof window === 'undefined' || !('Notification' in window))
        return 'denied';
    return Notification.permission;
}
async function requestNotificationPermission() {
    if (typeof window === 'undefined' || !('Notification' in window))
        return;
    try {
        await Notification.requestPermission();
    }
    catch {
        // Some embedders throw — treat as silent denial.
    }
}
function showSystemNotification(n) {
    try {
        const title = `Vance · ${n.severity}`;
        const opts = {
            body: n.text,
            // ERROR notifications stay until user dismisses them — INFO/WARN
            // follow the OS auto-dismiss.
            requireInteraction: n.severity === 'ERROR',
            tag: n.sourceProcessId ?? 'vance-notify',
        };
        const sys = new Notification(title, opts);
        sys.onclick = () => {
            window.focus();
            const sessionId = n.sessionId;
            if (sessionId) {
                // Same-tab navigation if we're already on chat.html; new tab
                // otherwise so we don't kill the editor the user has open.
                const onChat = window.location.pathname.endsWith('/chat.html')
                    || window.location.pathname === '/';
                const url = `/chat.html?sessionId=${encodeURIComponent(sessionId)}`;
                if (onChat)
                    window.location.href = url;
                else
                    window.open(url, '_blank', 'noopener');
            }
            sys.close();
        };
    }
    catch {
        // Some browsers (or restrictive embedders) throw on construction;
        // fall back to the in-app toast so the event isn't silently lost.
        showToast(n);
    }
}
async function beep(severity) {
    if (typeof window === 'undefined')
        return;
    try {
        if (!audioContext) {
            const Ctor = window.AudioContext
                ?? window
                    .webkitAudioContext;
            if (!Ctor)
                return;
            audioContext = new Ctor();
        }
        // Many browsers suspend the context until a user gesture — try to
        // resume best-effort; if it stays suspended the beep is silent
        // which is acceptable for a side-channel.
        if (audioContext.state === 'suspended') {
            try {
                await audioContext.resume();
            }
            catch { /* ignore */ }
        }
        const now = audioContext.currentTime;
        const pitch = pitchFor(severity);
        const osc = audioContext.createOscillator();
        const gain = audioContext.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(pitch, now);
        // Soft attack/release so it doesn't click.
        gain.gain.setValueAtTime(0, now);
        gain.gain.linearRampToValueAtTime(0.18, now + 0.015);
        gain.gain.linearRampToValueAtTime(0, now + 0.18);
        osc.connect(gain).connect(audioContext.destination);
        osc.start(now);
        osc.stop(now + 0.2);
    }
    catch {
        // AudioContext can be denied in privacy-strict embeds — silent
        // fallback.
    }
}
function pitchFor(severity) {
    switch (severity) {
        case 'WARN': return 900;
        case 'ERROR': return 1200;
        case 'INFO':
        default: return 600;
    }
}
/**
 * Composable-shaped accessor mirroring the Pinia call site so the
 * consumer code reads the same whether the backing store is Pinia or a
 * plain reactive singleton.
 */
export function useNotificationStore() {
    return { toasts: toastsRef, push, dismiss };
}
//# sourceMappingURL=notificationStore.js.map