import DiffMatchPatch from 'diff-match-patch';
import { onBeforeUnmount, ref, watch } from 'vue';
import { onDocumentChanged } from '@/ws/wsConnectionStore';
/** Auto-fade window for the {@code ⏺ name} awareness badge. */
const RECENT_EDITOR_TTL_MS = 2500;
export function useDocumentChangeReaction(options) {
    const pendingChange = ref(null);
    const recentEditor = ref(null);
    let unsubscribe = null;
    let recentEditorTimer = null;
    function flagRecentEditor(notification) {
        const name = notification.editorDisplayName
            ?? notification.editorUserId
            ?? null;
        if (!name)
            return;
        recentEditor.value = { displayName: name, setAt: Date.now() };
        if (recentEditorTimer)
            clearTimeout(recentEditorTimer);
        recentEditorTimer = setTimeout(() => {
            recentEditor.value = null;
            recentEditorTimer = null;
        }, RECENT_EDITOR_TTL_MS);
    }
    watch(options.path, (path) => {
        pendingChange.value = null;
        recentEditor.value = null;
        if (recentEditorTimer) {
            clearTimeout(recentEditorTimer);
            recentEditorTimer = null;
        }
        if (unsubscribe) {
            try {
                unsubscribe();
            }
            catch { /* ignore */ }
            unsubscribe = null;
        }
        if (!path)
            return;
        unsubscribe = onDocumentChanged(path, async (notification) => {
            const kind = notification.kind ?? 'upserted';
            console.debug(`[documents.changed] path='${path}' kind=${kind} writer='${notification.editorDisplayName ?? notification.editorUserId ?? '?'}' → tryApply`);
            try {
                const handled = await options.tryApply(notification);
                if (!handled) {
                    console.debug(`[documents.changed] path='${path}' → banner`);
                    pendingChange.value = kind;
                }
                else {
                    console.debug(`[documents.changed] path='${path}' → silent`);
                    flagRecentEditor(notification);
                }
            }
            catch (e) {
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
        if (recentEditorTimer) {
            clearTimeout(recentEditorTimer);
            recentEditorTimer = null;
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
            console.warn('[documents.changed] forceApply threw:', e);
            pendingChange.value = kind;
        }
    }
    return { pendingChange, recentEditor, keepLocal, acceptRemote };
}
/** True for {@code audio/*} or {@code video/*}. */
export function isAudioVideoMime(mime) {
    if (!mime)
        return false;
    return mime.startsWith('audio/') || mime.startsWith('video/');
}
/**
 * Three-way merge of a text document's {@code local} buffer against a
 * freshly-fetched {@code remote} body, using the {@code baseline}
 * (the version both sides started from) as the common ancestor.
 *
 * <p>Algorithm: {@code patch_make(baseline, local)} extracts the user's
 * edits as a patch list; {@code patch_apply(patches, remote)} replays
 * them on top of the remote snapshot. When every patch hunk applies
 * exactly (we keep the fuzzy-match threshold at 0 so context shifts
 * are treated as conflicts, not "close enough"), we have a clean
 * merge and return it; otherwise the caller falls back to a conflict
 * banner.
 *
 * <p>Trivial cases are short-circuited:
 * <ul>
 *   <li>{@code baseline === remote} — no remote change, return {@code local}.</li>
 *   <li>{@code baseline === local} — no local edit, return {@code remote}.</li>
 * </ul>
 *
 * <p>Cursor preservation is intentionally not handled here. The
 * non-conflict case typically only inserts/removes content well away
 * from the user's caret (otherwise it would have been flagged as a
 * conflict), so a fresh content set on the editor is acceptable for
 * Phase B; finer cursor adjustment can come later via the patch
 * offsets.
 */
export function tryThreeWayMerge(baseline, local, remote) {
    if (baseline === remote)
        return { ok: true, merged: local, remote };
    if (baseline === local)
        return { ok: true, merged: remote, remote };
    const dmp = new DiffMatchPatch.diff_match_patch();
    // Conservative tuning: any context mismatch counts as a conflict
    // rather than a fuzzy "best-guess" merge. The user can still recover
    // their work via the "Keep mine" banner action when this returns ok=false.
    dmp.Match_Threshold = 0;
    dmp.Patch_DeleteThreshold = 0;
    const patches = dmp.patch_make(baseline, local);
    const [merged, results] = dmp.patch_apply(patches, remote);
    const allOk = results.every(Boolean);
    if (!allOk)
        return { ok: false, remote };
    return { ok: true, merged, remote };
}
//# sourceMappingURL=useDocumentChangeReaction.js.map