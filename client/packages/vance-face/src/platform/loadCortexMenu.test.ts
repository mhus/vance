// @vitest-environment jsdom
//
// The loader asks `getActiveUiLevel()` for the reader's UI level before it
// registers anything, and that reads a cookie — no DOM, no level, no test.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const registerRemotes = vi.fn();
const loadRemote = vi.fn();

vi.mock('@module-federation/runtime', () => ({
  registerRemotes: (...args: unknown[]) => registerRemotes(...args),
  loadRemote: (...args: unknown[]) => loadRemote(...args),
}));

const originalFetch = globalThis.fetch;

function manifest(body: unknown): void {
  globalThis.fetch = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => body,
  }) as unknown as typeof fetch;
}

/** The loader and the registry both keep module state, so each case is fresh. */
async function freshModules() {
  vi.resetModules();
  const menu = await import('./cortexMenu');
  const loader = await import('./loadCortexMenu');
  return { menu, loader };
}

function ctx(document: unknown = null) {
  return {
    projectId: 'p',
    document,
    selection: null,
    openDocument: async () => {},
    revealPath: async () => {},
  } as unknown as import('./cortexMenu').CortexMenuContext;
}

const MARKDOWN_DOC = {
  id: 'd', path: 'documents/a.md', name: 'a.md',
  mimeType: 'text/markdown', kind: null, text: '', dirty: false,
};

describe('loadCortexMenuContributions', () => {
  beforeEach(() => {
    registerRemotes.mockReset();
    loadRemote.mockReset();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('registers declared entries without loading a single remote', async () => {
    // The whole point: the menu is rendered from the manifest, and an addon's
    // bundle is fetched only when its entry is clicked.
    manifest([{
      name: 'tex',
      path: 'bundled:tex',
      menu: [{ id: 'compile', slot: 'extras', label: 'Compile to PDF…' }],
    }]);
    const { menu, loader } = await freshModules();

    await loader.loadCortexMenuContributions();

    expect(menu.cortexMenuItemsFor('extras', ctx()).map((i) => i.id)).toEqual(['tex:compile']);
    expect(loadRemote).not.toHaveBeenCalled();
  });

  it('namespaces the id with the addon, so two addons may share one', async () => {
    manifest([
      { name: 'wiki', path: 'bundled:wiki', menu: [{ id: 'index', slot: 'view', label: 'A' }] },
      { name: 'kanban', path: 'bundled:kanban', menu: [{ id: 'index', slot: 'view', label: 'B' }] },
    ]);
    const { menu, loader } = await freshModules();

    await loader.loadCortexMenuContributions();

    expect(menu.cortexMenuItemsFor('view', ctx()).map((i) => i.id))
      .toEqual(['wiki:index', 'kanban:index']);
  });

  it('skips an entry that names no renderable slot', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    manifest([{
      name: 'kanban',
      path: 'bundled:kanban',
      menu: [{ id: 'sweep', slot: 'tools', label: 'Sweep' }],
    }]);
    const { menu, loader } = await freshModules();

    await loader.loadCortexMenuContributions();

    expect(menu.allCortexMenuItems()).toHaveLength(0);
  });

  it('loads the declared expose on activation and calls its handler', async () => {
    const run = vi.fn();
    loadRemote.mockResolvedValue({ run });
    manifest([{
      name: 'tex',
      path: 'bundled:tex',
      menu: [{ id: 'compile', slot: 'extras', label: 'Compile' }],
    }]);
    const { menu, loader } = await freshModules();
    await loader.loadCortexMenuContributions();

    const context = ctx(MARKDOWN_DOC);
    await menu.cortexMenuItemsFor('extras', context)[0].run(context);

    expect(loadRemote).toHaveBeenCalledWith('vance_addon_tex/menu');
    expect(run).toHaveBeenCalledWith(context);
  });

  it('honours a custom expose and handler name', async () => {
    const compile = vi.fn();
    loadRemote.mockResolvedValue({ default: { compile } });
    manifest([{
      name: 'tex',
      path: 'bundled:tex',
      menu: [{
        id: 'compile', slot: 'extras', label: 'Compile',
        expose: './actions', handler: 'compile',
      }],
    }]);
    const { menu, loader } = await freshModules();
    await loader.loadCortexMenuContributions();

    const context = ctx(MARKDOWN_DOC);
    await menu.cortexMenuItemsFor('extras', context)[0].run(context);

    expect(loadRemote).toHaveBeenCalledWith('vance_addon_tex/actions');
    expect(compile).toHaveBeenCalledOnce();
  });

  it('throws a named error when the expose carries no such handler', async () => {
    // The reader clicked something; nothing happening is the one outcome that
    // cannot be explained.
    loadRemote.mockResolvedValue({ somethingElse: () => {} });
    manifest([{
      name: 'tex',
      path: 'bundled:tex',
      menu: [{ id: 'compile', slot: 'extras', label: 'Compile' }],
    }]);
    const { menu, loader } = await freshModules();
    await loader.loadCortexMenuContributions();

    const context = ctx(MARKDOWN_DOC);
    await expect(menu.cortexMenuItemsFor('extras', context)[0].run(context))
      .rejects.toThrow(/exposes no 'run'/);
  });

  it('survives an unreachable manifest with no entries', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new Error('offline')) as unknown as typeof fetch;
    const { menu, loader } = await freshModules();

    await loader.loadCortexMenuContributions();

    expect(menu.allCortexMenuItems()).toHaveLength(0);
  });
});

describe('matchesDocument', () => {
  it('shows an entry that declares neither list, with or without a document', async () => {
    const { loader } = await freshModules();
    expect(loader.matchesDocument({}, null)).toBe(true);
    expect(loader.matchesDocument({}, MARKDOWN_DOC)).toBe(true);
  });

  it('requires a document once either list is declared', async () => {
    const { loader } = await freshModules();
    expect(loader.matchesDocument({ kinds: ['canvas'] }, null)).toBe(false);
    expect(loader.matchesDocument({ mimes: ['text/'] }, null)).toBe(false);
  });

  it('matches a kind exactly and a mime by prefix', async () => {
    const { loader } = await freshModules();
    expect(loader.matchesDocument({ mimes: ['text/'] }, MARKDOWN_DOC)).toBe(true);
    expect(loader.matchesDocument({ mimes: ['image/'] }, MARKDOWN_DOC)).toBe(false);
    expect(loader.matchesDocument(
      { kinds: ['canvas'] },
      { ...MARKDOWN_DOC, kind: 'canvas' },
    )).toBe(true);
    // Prefix matching would make `canvasbook` match `canvas`; kinds are names,
    // not namespaces.
    expect(loader.matchesDocument(
      { kinds: ['canvas'] },
      { ...MARKDOWN_DOC, kind: 'canvasbook' },
    )).toBe(false);
  });

  it('treats two declared lists as alternatives', async () => {
    const { loader } = await freshModules();
    // A plain Markdown file carries no kind at all, so a kind list alone can
    // never address it — which is why the mime list exists beside it.
    expect(loader.matchesDocument(
      { kinds: ['canvas'], mimes: ['text/markdown'] },
      MARKDOWN_DOC,
    )).toBe(true);
  });

  it('reads an empty list as "matches nothing", not as "not declared"', async () => {
    const { loader } = await freshModules();
    expect(loader.matchesDocument({ kinds: [] }, MARKDOWN_DOC)).toBe(false);
  });
});
