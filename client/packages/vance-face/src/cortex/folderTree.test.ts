import { describe, expect, it } from 'vitest';
import { buildFolderTree, leafNameOf, parentFolderOf, type FolderState } from './folderTree';
import type { CortexDocument } from './types';

function state(partial: Partial<FolderState> = {}): FolderState {
  return {
    folders: [],
    loaded: true,
    loading: false,
    error: null,
    totalFiles: 0,
    loadedFiles: 0,
    ...partial,
  };
}

function doc(path: string): CortexDocument {
  return {
    id: `id:${path}`,
    projectId: 'p',
    path,
    name: leafNameOf(path),
    title: null,
    mimeType: 'text/markdown',
    size: 1,
    tags: [],
    inline: true,
    dirty: false,
  } as unknown as CortexDocument;
}

function build(
  folderStates: Map<string, FolderState>,
  files: CortexDocument[] = [],
  virtualFolders = new Set<string>(),
) {
  return buildFolderTree({ folderStates, files, virtualFolders });
}

describe('parentFolderOf', () => {
  it('returns the empty string for a document at the project root', () => {
    expect(parentFolderOf('readme.md')).toBe('');
  });

  it('returns the folder for a nested document', () => {
    expect(parentFolderOf('apps/kanban/_app.yaml')).toBe('apps/kanban');
  });
});

describe('buildFolderTree', () => {
  it('takes the folder structure from the listing, not from file paths', () => {
    // The whole point of the lazy tree: `_ext` exists in no document path —
    // the server synthesises it — and a folder can be known while empty.
    const states = new Map([['', state({ folders: ['_ext', 'apps'] })]]);

    const root = build(states);

    expect(root.children.map((c) => c.path)).toEqual(['_ext', 'apps']);
  });

  it('marks a folder that has not been loaded', () => {
    const states = new Map([['', state({ folders: ['apps'] })]]);

    const apps = build(states).children[0];

    // Renders as expandable-but-unread; opening it is what fetches it.
    expect(apps.loaded).toBe(false);
    expect(apps.children).toEqual([]);
    expect(apps.files).toEqual([]);
  });

  it('nests a loaded subfolder under its parent', () => {
    const states = new Map([
      ['', state({ folders: ['apps'] })],
      ['apps', state({ folders: ['kanban'] })],
    ]);

    const root = build(states);

    const apps = root.children[0];
    expect(apps.loaded).toBe(true);
    expect(apps.children.map((c) => c.path)).toEqual(['apps/kanban']);
  });

  it('places files in the folder they belong to, by their own path', () => {
    const states = new Map([
      ['', state({ folders: ['apps'], totalFiles: 1, loadedFiles: 1 })],
      ['apps', state({ totalFiles: 1, loadedFiles: 1 })],
    ]);

    const root = build(states, [doc('readme.md'), doc('apps/_app.yaml')]);

    expect(root.files.map((f) => f.name)).toEqual(['readme.md']);
    expect(root.children[0].files.map((f) => f.name)).toEqual(['_app.yaml']);
  });

  it('does not show a file whose folder was never loaded', () => {
    // Such rows exist — an open tab, a deep link — but the tree must not
    // invent a folder for them: it would show a fragment of a folder as if
    // it were the whole thing.
    const states = new Map([['', state({ folders: ['deep'] })]]);

    const root = build(states, [doc('deep/inner/x.md')]);

    expect(root.children.map((c) => c.path)).toEqual(['deep']);
    expect(root.children[0].children).toEqual([]);
  });

  it('reports the files a page did not carry', () => {
    const states = new Map([['', state({ totalFiles: 605, loadedFiles: 200 })]]);

    expect(build(states).moreFiles).toBe(405);
  });

  it('reports no remainder for a folder it has not loaded', () => {
    const states = new Map([['', state({ folders: ['apps'] })]]);

    expect(build(states).children[0].moreFiles).toBe(0);
  });

  it('keeps a revealed folder reachable even when its parent is unread', () => {
    // expandTo() loads a chain directly. The intermediate folders are known
    // by their own state, so the chain has to appear even though nobody
    // listed the parent that names them.
    const states = new Map([
      ['', state({ loaded: false })],
      ['apps', state()],
      ['apps/kanban', state()],
    ]);

    const root = build(states);

    expect(root.children.map((c) => c.path)).toEqual(['apps']);
    expect(root.children[0].children.map((c) => c.path)).toEqual(['apps/kanban']);
  });

  it('merges staged virtual folders and their ancestors', () => {
    const root = build(new Map([['', state()]]), [], new Set(['new/sub']));

    const news = root.children[0];
    expect(news.path).toBe('new');
    expect(news.children.map((c) => c.path)).toEqual(['new/sub']);
  });

  it('sorts folders and files by name', () => {
    const states = new Map([
      ['', state({ folders: ['zeta', 'alpha'], totalFiles: 2, loadedFiles: 2 })],
    ]);

    const root = build(states, [doc('b.md'), doc('a.md')]);

    expect(root.children.map((c) => c.name)).toEqual(['alpha', 'zeta']);
    expect(root.files.map((f) => f.name)).toEqual(['a.md', 'b.md']);
  });

  it('carries the loading and error state of a folder', () => {
    const states = new Map([
      ['', state({ folders: ['a', 'b'] })],
      ['a', state({ loaded: false, loading: true })],
      ['b', state({ loaded: false, error: 'mount unreachable' })],
    ]);

    const [a, b] = build(states).children;
    expect(a.loading).toBe(true);
    expect(b.error).toBe('mount unreachable');
  });
});
