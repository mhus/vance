import { onBeforeUnmount, ref, watch } from 'vue';
import { useWsConnection } from '@/ws/wsConnectionStore';
export function useSessionRoster(sessionId) {
    const participants = ref([]);
    const conn = useWsConnection();
    let unsubscribeRoster = null;
    let baselineEstablished = false;
    const changeListeners = new Set();
    const initialListeners = new Set();
    function diff(prev, next) {
        const prevIds = new Set(prev.map((p) => p.editorId));
        const nextIds = new Set(next.map((p) => p.editorId));
        const joined = next.filter((p) => !prevIds.has(p.editorId));
        const left = prev.filter((p) => !nextIds.has(p.editorId));
        if (joined.length === 0 && left.length === 0)
            return null;
        return { joined, left, at: new Date() };
    }
    function attach() {
        detach();
        const socket = conn.socket.value;
        const sid = sessionId.value;
        if (!socket)
            return;
        unsubscribeRoster = socket.on('session-roster', (data) => {
            if (!data || data.sessionId !== sessionId.value)
                return;
            const next = data.participants ?? [];
            const isBaseline = !baselineEstablished;
            const change = isBaseline ? null : diff(participants.value, next);
            participants.value = next;
            baselineEstablished = true;
            if (isBaseline) {
                for (const listener of initialListeners) {
                    try {
                        listener(next);
                    }
                    catch (err) {
                        console.warn('[useSessionRoster] initial listener threw', err);
                    }
                }
            }
            if (change) {
                for (const listener of changeListeners) {
                    try {
                        listener(change);
                    }
                    catch (err) {
                        console.warn('[useSessionRoster] change listener threw', err);
                    }
                }
            }
        });
        // The server pushes a session-roster frame on join, but the
        // listener above only attaches once ChatView is mounted — by
        // then the server's initial push has already gone out and we
        // missed the baseline. Fetch it actively here so the composable
        // always knows the current roster, race-free.
        if (!sid)
            return;
        socket.send('session-who', {})
            .then((reply) => {
            if (!reply)
                return;
            if (reply.sessionId !== sid && reply.sessionId !== sessionId.value) {
                return;
            }
            if (baselineEstablished)
                return; // a push already landed first
            const next = reply.participants ?? [];
            participants.value = next;
            baselineEstablished = true;
            for (const listener of initialListeners) {
                try {
                    listener(next);
                }
                catch (err) {
                    console.warn('[useSessionRoster] initial listener threw', err);
                }
            }
        })
            .catch((err) => {
            console.warn('[useSessionRoster] session-who failed', err);
        });
    }
    function detach() {
        if (unsubscribeRoster) {
            unsubscribeRoster();
            unsubscribeRoster = null;
        }
    }
    /**
     * Subscribe to per-delta roster changes. Returns an unsubscribe
     * function. The first frame after attach is treated as a fresh
     * baseline (initialise participants without emitting "joined" for
     * everyone already present) because {@code participants} starts as
     * an empty array — every diff between {@code []} and the server's
     * snapshot would otherwise spam "X joined" on session-load.
     */
    function onChange(handler) {
        changeListeners.add(handler);
        return () => changeListeners.delete(handler);
    }
    /**
     * Subscribe to the initial roster baseline — fired exactly once
     * per attach, when the active session-who lookup resolves (or the
     * first server push lands, whichever arrives first). Lets a chat
     * view render a "currently here:" line right after the user enters
     * a shared session, without needing to type {@code /who} manually.
     */
    function onInitial(handler) {
        initialListeners.add(handler);
        return () => initialListeners.delete(handler);
    }
    watch(() => [conn.socket.value, sessionId.value], () => {
        participants.value = [];
        baselineEstablished = false;
        attach();
    }, { immediate: true });
    onBeforeUnmount(() => {
        detach();
        changeListeners.clear();
        initialListeners.clear();
    });
    return { participants, onChange, onInitial };
}
//# sourceMappingURL=useSessionRoster.js.map