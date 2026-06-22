import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useWsConnection } from '@/ws/wsConnectionStore';
export function useDocumentInvalidate(options) {
    const { socket } = useWsConnection();
    const debounceMs = options.debounceMs ?? 500;
    const activityWindowMs = options.activityWindowMs ?? 1500;
    // Wall-clock of the most recent invalidate (any docId).
    const lastInvalidateAt = ref(0);
    // Trailing-debounce timer per docId.
    const timers = new Map();
    // WS-listener unsubscribe handle, refreshed on socket replacement.
    let unsubscribeWs = null;
    function scheduleApply(docId, kind) {
        const existing = timers.get(docId);
        if (existing)
            clearTimeout(existing);
        const timer = setTimeout(async () => {
            timers.delete(docId);
            try {
                await options.apply(docId, kind);
            }
            catch (e) {
                console.warn(`[document-invalidate] apply for doc='${docId}' threw:`, e);
            }
        }, debounceMs);
        timers.set(docId, timer);
    }
    function attach() {
        detach();
        const sock = socket.value;
        if (!sock)
            return;
        unsubscribeWs = sock.on('document-invalidate', (data) => {
            if (!data || !data.documentId)
                return;
            lastInvalidateAt.value = Date.now();
            if (!options.openDocumentIds.value.includes(data.documentId))
                return;
            scheduleApply(data.documentId, data.kind ?? 'body');
        });
    }
    function detach() {
        if (unsubscribeWs) {
            try {
                unsubscribeWs();
            }
            catch { /* ignore */ }
            unsubscribeWs = null;
        }
    }
    watch(socket, () => attach(), { immediate: true });
    // Polling-based "is the agent actively editing" — re-evaluates every
    // 500ms so the pulse animation snaps off ~at the right time after the
    // activity window expires. Cheap, no WebSocket round-trip needed.
    const nowTick = ref(Date.now());
    const tickInterval = setInterval(() => { nowTick.value = Date.now(); }, 500);
    const isAgentEditing = computed(() => nowTick.value - lastInvalidateAt.value < activityWindowMs);
    onBeforeUnmount(() => {
        detach();
        for (const t of timers.values())
            clearTimeout(t);
        timers.clear();
        clearInterval(tickInterval);
    });
    return { isAgentEditing };
}
//# sourceMappingURL=useDocumentInvalidate.js.map