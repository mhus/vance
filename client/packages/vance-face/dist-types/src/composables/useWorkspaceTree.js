import { reactive, ref } from 'vue';
import { brainFetch } from '@vance/shared';
import { WorkspaceNodeType } from '@vance/generated';
export function useWorkspaceTree() {
    const root = ref(null);
    const loading = ref(false);
    const error = ref(null);
    const expanded = reactive(new Set());
    const loadingPaths = reactive(new Set());
    async function loadRoot(projectId) {
        loading.value = true;
        error.value = null;
        try {
            const node = await fetchTree(projectId, '', 1);
            root.value = node;
            expanded.add(node.path);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load workspace tree.';
            root.value = null;
        }
        finally {
            loading.value = false;
        }
    }
    async function expand(projectId, path) {
        if (loadingPaths.has(path))
            return;
        expanded.add(path);
        if (root.value === null)
            return;
        const node = findNode(root.value, path);
        if (!node || node.type !== WorkspaceNodeType.DIR)
            return;
        // Children present? trust them. Re-fetch only when undefined — the
        // initial root call only populates one level deep.
        if (node.children !== undefined)
            return;
        loadingPaths.add(path);
        try {
            const fresh = await fetchTree(projectId, path, 1);
            node.children = fresh.children ?? [];
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load folder.';
        }
        finally {
            loadingPaths.delete(path);
        }
    }
    function collapse(path) {
        expanded.delete(path);
    }
    async function toggle(projectId, path, isDir) {
        if (!isDir)
            return;
        if (expanded.has(path))
            collapse(path);
        else
            await expand(projectId, path);
    }
    return { root, loading, error, expanded, loadingPaths, loadRoot, expand, collapse, toggle };
}
async function fetchTree(projectId, path, depth) {
    const params = new URLSearchParams();
    if (path)
        params.set('path', path);
    params.set('depth', String(depth));
    return brainFetch('GET', `projects/${encodeURIComponent(projectId)}/workspace/tree?${params}`);
}
/**
 * Walk the tree to find a node by path. Paths are stable identifiers
 * across reloads, so DFS by `path` is reliable.
 */
function findNode(node, path) {
    if (node.path === path)
        return node;
    if (!node.children)
        return null;
    for (const child of node.children) {
        const hit = findNode(child, path);
        if (hit)
            return hit;
    }
    return null;
}
//# sourceMappingURL=useWorkspaceTree.js.map