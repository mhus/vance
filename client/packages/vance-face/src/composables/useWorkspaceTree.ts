import { reactive, ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type { WorkspaceTreeNodeDto } from '@vance/generated';
import { WorkspaceNodeType } from '@vance/generated';

/**
 * Lazy workspace-tree loader. The Brain's `/projects/{project}/workspace/tree`
 * endpoint accepts an optional `path` and a `depth`; we hit it with
 * {@code depth=1} for the initial root and refetch the same way every
 * time the user expands a folder. Two state buckets:
 *
 * - {@link expanded}: paths the user has clicked open. The recursive
 *   tree component reads this to decide whether to render `children`.
 * - {@link loading}: paths whose child fetch is currently in flight.
 *   The tree shows a spinner badge for those.
 *
 * Reactivity: `root.value` is replaced wholesale on each `loadRoot`;
 * within an expanded subtree, {@link expand} mutates the matching
 * node's `children` array in place. Vue's tree-walking renderers
 * pick that up via the deep-reactive `root` ref.
 */
export interface UseWorkspaceTree {
  root: Ref<WorkspaceTreeNodeDto | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  expanded: Set<string>;
  loadingPaths: Set<string>;
  loadRoot: (projectId: string) => Promise<void>;
  expand: (projectId: string, path: string) => Promise<void>;
  collapse: (path: string) => void;
  toggle: (projectId: string, path: string, isDir: boolean) => Promise<void>;
}

export function useWorkspaceTree(): UseWorkspaceTree {
  const root = ref<WorkspaceTreeNodeDto | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const expanded = reactive(new Set<string>());
  const loadingPaths = reactive(new Set<string>());

  async function loadRoot(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const node = await fetchTree(projectId, '', 1);
      root.value = node;
      expanded.add(node.path);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load workspace tree.';
      root.value = null;
    } finally {
      loading.value = false;
    }
  }

  async function expand(projectId: string, path: string): Promise<void> {
    if (loadingPaths.has(path)) return;
    expanded.add(path);
    if (root.value === null) return;
    const node = findNode(root.value, path);
    if (!node || node.type !== WorkspaceNodeType.DIR) return;
    // Children present? trust them. Re-fetch only when undefined — the
    // initial root call only populates one level deep.
    if (node.children !== undefined) return;
    loadingPaths.add(path);
    try {
      const fresh = await fetchTree(projectId, path, 1);
      node.children = fresh.children ?? [];
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load folder.';
    } finally {
      loadingPaths.delete(path);
    }
  }

  function collapse(path: string): void {
    expanded.delete(path);
  }

  async function toggle(projectId: string, path: string, isDir: boolean): Promise<void> {
    if (!isDir) return;
    if (expanded.has(path)) collapse(path);
    else await expand(projectId, path);
  }

  return { root, loading, error, expanded, loadingPaths, loadRoot, expand, collapse, toggle };
}

async function fetchTree(
  projectId: string,
  path: string,
  depth: number,
): Promise<WorkspaceTreeNodeDto> {
  const params = new URLSearchParams();
  if (path) params.set('path', path);
  params.set('depth', String(depth));
  return brainFetch<WorkspaceTreeNodeDto>(
    'GET',
    `projects/${encodeURIComponent(projectId)}/workspace/tree?${params}`,
  );
}

/**
 * Walk the tree to find a node by path. Paths are stable identifiers
 * across reloads, so DFS by `path` is reliable.
 */
function findNode(
  node: WorkspaceTreeNodeDto,
  path: string,
): WorkspaceTreeNodeDto | null {
  if (node.path === path) return node;
  if (!node.children) return null;
  for (const child of node.children) {
    const hit = findNode(child, path);
    if (hit) return hit;
  }
  return null;
}
