import { defineStore } from 'pinia';
import { ref, watch } from 'vue';
import { brainFetch } from '@vance/shared';
/**
 * How long {@link resolve} waits for the host editor to populate the
 * current project before giving up. Chat (WS session-list) and Cortex
 * (REST sessions) both resolve the project asynchronously after the
 * editor mounts — a vance:-link inside a historical message can fire
 * its EmbeddedKindBox onMounted well before either lookup returns.
 * Five seconds covers the realistic worst case (cold WS + first-paint
 * jitter on a slow client) without hanging the bubble forever when
 * something is genuinely broken upstream.
 */
const CURRENT_PROJECT_WAIT_MS = 5_000;
function keyFor(projectId, path) {
    return `${projectId ?? ''}::${path}`;
}
export const useDocumentRefStore = defineStore('documentRef', () => {
    const cache = ref(new Map());
    const pending = ref(new Map());
    /**
     * Current project name — used as the default when a {@code vance:/...}
     * URI omits the authority segment. Set by the host editor (chat,
     * inbox, …) on bind.
     */
    const currentProject = ref('');
    function setCurrentProject(projectName) {
        currentProject.value = projectName;
    }
    /**
     * Resolves once {@link currentProject} carries a non-empty value, or
     * returns the empty string after {@link CURRENT_PROJECT_WAIT_MS}.
     * The watcher is one-shot and stops itself either way, so no leaks
     * on unmount. Bypasses entirely when the value is already there —
     * the common path stays a single ref read.
     */
    function waitForCurrentProject() {
        if (currentProject.value)
            return Promise.resolve(currentProject.value);
        return new Promise((resolveP) => {
            let settled = false;
            const finish = (val) => {
                if (settled)
                    return;
                settled = true;
                stop();
                clearTimeout(timer);
                resolveP(val);
            };
            const stop = watch(currentProject, (val) => {
                if (val)
                    finish(val);
            });
            const timer = setTimeout(() => finish(''), CURRENT_PROJECT_WAIT_MS);
        });
    }
    async function resolve(embedRef) {
        let projectName = embedRef.project ?? currentProject.value;
        if (!projectName) {
            // Host editor hasn't populated the project context yet (typical
            // when a `vance:`-link mounts inside a freshly-restored chat
            // history before the session-list / sessions REST roundtrip
            // returns). Wait briefly — the project *is* on its way down the
            // pipe, we just need to let it land.
            projectName = await waitForCurrentProject();
        }
        if (!projectName) {
            throw new Error('No project context to resolve vance: URI');
        }
        const key = keyFor(projectName, embedRef.path);
        const hit = cache.value.get(key);
        if (hit)
            return hit;
        const inflight = pending.value.get(key);
        if (inflight)
            return inflight;
        const params = new URLSearchParams({
            projectId: projectName,
            path: embedRef.path,
        });
        const p = brainFetch('GET', `documents/by-path?${params}`)
            .then((doc) => {
            cache.value.set(key, doc);
            pending.value.delete(key);
            return doc;
        })
            .catch((err) => {
            pending.value.delete(key);
            throw err instanceof Error ? err : new Error(String(err));
        });
        pending.value.set(key, p);
        return p;
    }
    /** Drop a single cache entry (e.g. when a document was edited). */
    function invalidate(projectName, path) {
        cache.value.delete(keyFor(projectName, path));
    }
    /** Drop everything — page-reload-style reset for tenant/user switches. */
    function clear() {
        cache.value.clear();
        pending.value.clear();
    }
    return {
        currentProject,
        setCurrentProject,
        resolve,
        invalidate,
        clear,
    };
});
//# sourceMappingURL=documentRefStore.js.map