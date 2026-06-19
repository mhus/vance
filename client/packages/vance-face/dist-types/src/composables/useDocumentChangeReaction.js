import { onBeforeUnmount, ref, watch } from 'vue';
import { onDocumentChanged } from '@/ws/wsConnectionStore';
export function useDocumentChangeReaction(options) {
    const pendingChange = ref(null);
    let unsubscribe = null;
    watch(options.path, (path) => {
        pendingChange.value = null;
        if (unsubscribe) {
            try {
                unsubscribe();
            }
            catch { /* ignore */ }
            unsubscribe = null;
        }
        if (!path)
            return;
        unsubscribe = onDocumentChanged(path, async (kind) => {
            try {
                const handled = await options.tryApply(kind);
                if (!handled) {
                    pendingChange.value = kind;
                }
            }
            catch (e) {
                // Any error in the editor's silent path falls through to the
                // banner so the user can decide manually rather than losing
                // the change altogether.
                console.warn('[documents.changed] tryApply threw, falling back to banner:', e);
                pendingChange.value = kind;
            }
        });
    }, { immediate: true });
    onBeforeUnmount(() => {
        if (unsubscribe) {
            try {
                unsubscribe();
            }
            catch { /* ignore */ }
            unsubscribe = null;
        }
    });
    function keepLocal() {
        pendingChange.value = null;
    }
    async function acceptRemote() {
        const kind = pendingChange.value;
        pendingChange.value = null;
        if (!kind)
            return;
        try {
            await options.forceApply(kind);
        }
        catch (e) {
            // Same posture as tryApply — surface the banner again so the
            // user can retry.
            console.warn('[documents.changed] forceApply threw:', e);
            pendingChange.value = kind;
        }
    }
    return { pendingChange, keepLocal, acceptRemote };
}
/** True for {@code audio/*} or {@code video/*}. */
export function isAudioVideoMime(mime) {
    if (!mime)
        return false;
    return mime.startsWith('audio/') || mime.startsWith('video/');
}
//# sourceMappingURL=useDocumentChangeReaction.js.map