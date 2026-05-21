import { defineStore } from 'pinia';
import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
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
    async function resolve(embedRef) {
        const projectName = embedRef.project ?? currentProject.value;
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