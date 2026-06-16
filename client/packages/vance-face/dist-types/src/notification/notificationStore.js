import { defineStore } from 'pinia';
import { ref } from 'vue';
/**
 * In-app toast stack for the user-notification side-channel (the
 * {@code notify} WebSocket message). Toasts auto-dismiss; severity
 * drives the pitch of the WebAudio beep and the toast color.
 *
 * <p>Spec: specification/user-notification-channel.md
 *
 * <p>This store is intentionally side-effect-only on the UI surface:
 * it does not own the WebSocket subscription itself — host components
 * (ChatApp, CortexChatPanel) wire that via
 * {@code useNotificationSubscription(socket)} and call {@link push}
 * here when a frame arrives. Decoupling keeps the store free of WS
 * lifecycle concerns and reusable from non-Vue triggers (test stubs,
 * manual dispatch).
 *
 * <p>The browser-notification permission is requested lazily on the
 * first incoming notification — never up-front, so users that never
 * receive one never see a permission prompt.
 */
const TOAST_TTL_MS = 4500;
const MAX_TOASTS = 5;
export const useNotificationStore = defineStore('notification', () => {
    const toasts = ref([]);
    let counter = 0;
    let permissionRequested = false;
    let audioContext = null;
    function push(notification) {
        if (!notification?.text)
            return;
        const id = `n-${Date.now()}-${++counter}`;
        const toast = {
            id,
            notification,
            addedAt: Date.now(),
        };
        toasts.value.push(toast);
        if (toasts.value.length > MAX_TOASTS) {
            // Drop the oldest — a flooding caller shouldn't push the user
            // out of the viewport.
            toasts.value.splice(0, toasts.value.length - MAX_TOASTS);
        }
        void beep(notification.severity);
        void surfaceBrowserNotification(notification);
        window.setTimeout(() => dismiss(id), TOAST_TTL_MS);
    }
    function dismiss(id) {
        const idx = toasts.value.findIndex((t) => t.id === id);
        if (idx >= 0)
            toasts.value.splice(idx, 1);
    }
    async function surfaceBrowserNotification(n) {
        if (typeof window === 'undefined' || !('Notification' in window))
            return;
        let permission = Notification.permission;
        // Ask for permission lazily on the first frame — never up front.
        // Permission survives across page loads; we still only request it
        // once per page to avoid pestering the user.
        if (permission === 'default' && !permissionRequested) {
            permissionRequested = true;
            try {
                permission = await Notification.requestPermission();
            }
            catch {
                return;
            }
        }
        if (permission !== 'granted')
            return;
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
            // fall back to the in-app toast only.
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
            // AudioContext can be denied in privacy-strict embeds — silent fallback.
        }
    }
    return { toasts, push, dismiss };
});
function pitchFor(severity) {
    switch (severity) {
        case 'WARN': return 900;
        case 'ERROR': return 1200;
        case 'INFO':
        default: return 600;
    }
}
//# sourceMappingURL=notificationStore.js.map