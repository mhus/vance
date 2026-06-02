import { type Ref } from 'vue';
import type { WorkspaceTreeNodeDto } from '@vance/generated';
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
export declare function useWorkspaceTree(): UseWorkspaceTree;
//# sourceMappingURL=useWorkspaceTree.d.ts.map