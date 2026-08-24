import type { CortexDocument, FolderNode } from './types';

/**
 * What is known about one folder after asking the server for it.
 *
 * `folders` are the direct subfolder *names* as the folder endpoint reports
 * them — including the synthetic `_ext` entries, which exist in no document
 * path and can therefore not be derived from the file rows.
 */
export interface FolderState {
  folders: string[];
  loaded: boolean;
  loading: boolean;
  error: string | null;
  /** Files this folder holds in total, as counted by the server. */
  totalFiles: number;
  /** Files actually fetched — one page. The difference is what is missing. */
  loadedFiles: number;
}

export interface TreeInput {
  /** Keyed by folder path; `''` is the project root. */
  folderStates: ReadonlyMap<string, FolderState>;
  /** Every document row the session knows about, from any loaded folder. */
  files: readonly CortexDocument[];
  /** Folders the user staged that hold no document yet. */
  virtualFolders: ReadonlySet<string>;
}

/** The folder a path lives in — `''` for a document at the project root. */
export function parentFolderOf(path: string): string {
  const slash = path.lastIndexOf('/');
  return slash < 0 ? '' : path.slice(0, slash);
}

/** The last segment of a path. */
export function leafNameOf(path: string): string {
  const slash = path.lastIndexOf('/');
  return slash < 0 ? path : path.slice(slash + 1);
}

/**
 * Builds the folder tree from what has been loaded so far.
 *
 * <p>The structure comes from the <b>server's folder listings</b>, not from
 * aggregating document paths. That is the whole point of the lazy tree: a
 * folder can be known to exist and hold nothing that has been fetched yet, and
 * a path-prefix aggregation cannot express that — it can only show folders it
 * has already seen a file in, which is exactly what made the tree depend on
 * loading every document up front.
 *
 * <p>A folder that has not been loaded becomes a node with no children and
 * `loaded: false`. The renderer shows it as collapsed-and-expandable; opening
 * it triggers the fetch. Nothing here reaches the network.
 */
export function buildFolderTree(input: TreeInput): FolderNode {
  const { folderStates, files, virtualFolders } = input;

  // Files grouped by their folder — one pass instead of a scan per node.
  const byFolder = new Map<string, CortexDocument[]>();
  for (const f of files) {
    const parent = parentFolderOf(f.path);
    const list = byFolder.get(parent);
    const row = { ...f, name: leafNameOf(f.path) };
    if (list) list.push(row);
    else byFolder.set(parent, [row]);
  }

  // Subfolder paths per folder: what the server reported, plus the staged
  // virtual folders, plus any folder we happen to have state for (a reveal
  // loads a chain of folders directly, without their parents being expanded).
  const childPaths = new Map<string, Set<string>>();
  const addChild = (parent: string, child: string): void => {
    const set = childPaths.get(parent);
    if (set) set.add(child);
    else childPaths.set(parent, new Set([child]));
  };
  for (const [path, state] of folderStates) {
    for (const name of state.folders) {
      addChild(path, path ? `${path}/${name}` : name);
    }
  }
  for (const path of folderStates.keys()) {
    if (path) addChild(parentFolderOf(path), path);
  }
  for (const vpath of virtualFolders) {
    // Every ancestor of a staged folder has to exist as a node too, or the
    // folder the user just created is unreachable.
    const segments = vpath.split('/');
    let prefix = '';
    for (const seg of segments) {
      const next = prefix ? `${prefix}/${seg}` : seg;
      addChild(prefix, next);
      prefix = next;
    }
  }

  const build = (path: string): FolderNode => {
    const state = folderStates.get(path);
    const children = [...(childPaths.get(path) ?? [])]
      .sort((a, b) => leafNameOf(a).localeCompare(leafNameOf(b)))
      .map(build);
    const nodeFiles = [...(byFolder.get(path) ?? [])]
      .sort((a, b) => (a.name ?? '').localeCompare(b.name ?? ''));
    return {
      path,
      name: leafNameOf(path),
      children,
      files: nodeFiles,
      loaded: state?.loaded ?? false,
      loading: state?.loading ?? false,
      error: state?.error ?? null,
      moreFiles: state ? Math.max(0, state.totalFiles - state.loadedFiles) : 0,
    };
  };

  return build('');
}
