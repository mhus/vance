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

/**
 * The module memoises discovery and loads in module-level state, which is the
 * behaviour under test — so each case gets a fresh copy.
 */
async function freshModule() {
  vi.resetModules();
  return import('./addonRegistry');
}

function registeredNames(): string[] {
  const [remotes] = registerRemotes.mock.calls[0] as [{ name: string }[]];
  return remotes.map((r) => r.name);
}

function loadedExposes(): string[] {
  return loadRemote.mock.calls.map((c) => c[0] as string);
}

describe('addonRegistry', () => {
  beforeEach(() => {
    registerRemotes.mockReset();
    loadRemote.mockReset();
    loadRemote.mockResolvedValue({ register: vi.fn() });
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('registers every remote at boot without loading any of them', async () => {
    manifest([
      { name: 'calendar', path: 'bundled:calendar', kinds: ['calendar', 'timeline'] },
      { name: 'kanban', path: 'bundled:kanban', kinds: ['application:kanban'] },
    ]);
    const { initAddonRemotes } = await freshModule();

    await initAddonRemotes();

    expect(registeredNames()).toEqual(['vance_addon_calendar', 'vance_addon_kanban']);
    expect(loadRemote).not.toHaveBeenCalled();
  });

  it('loads an addon that declares no kinds at all, so forgetting to declare cannot break it', async () => {
    manifest([
      { name: 'calendar', path: 'bundled:calendar', kinds: ['calendar'] },
      { name: 'mystery', path: 'bundled:mystery' },
    ]);
    const { initAddonRemotes } = await freshModule();

    await initAddonRemotes();

    expect(loadedExposes()).toEqual(['vance_addon_mystery/register']);
  });

  it('loads an addon that asks to be eager, even though it declares kinds', async () => {
    // workbook: its registerBlock() has to land before any block editor is
    // constructed, and no kind can trigger that.
    manifest([
      { name: 'workbook', path: 'bundled:workbook', kinds: ['workpage'], eager: true },
      { name: 'kanban', path: 'bundled:kanban', kinds: ['application:kanban'] },
    ]);
    const { initAddonRemotes } = await freshModule();

    await initAddonRemotes();

    expect(loadedExposes()).toEqual(['vance_addon_workbook/register']);
  });

  it('still indexes the kinds of an eager addon, so it is not loaded twice', async () => {
    manifest([{ name: 'workbook', path: 'bundled:workbook', kinds: ['workpage'], eager: true }]);
    const { initAddonRemotes, ensureKindLoaded } = await freshModule();
    await initAddonRemotes();

    await expect(ensureKindLoaded('workpage')).resolves.toBe(false);
    expect(loadRemote).toHaveBeenCalledTimes(1);
  });

  it('does not load an addon that declares an empty kind list', async () => {
    manifest([{ name: 'store', path: 'bundled:store', kinds: [] }]);
    const { initAddonRemotes } = await freshModule();

    await initAddonRemotes();

    expect(loadRemote).not.toHaveBeenCalled();
  });

  it('loads the owning addon when its kind is first needed', async () => {
    manifest([
      { name: 'calendar', path: 'bundled:calendar', kinds: ['calendar', 'timeline'] },
      { name: 'kanban', path: 'bundled:kanban', kinds: ['application:kanban'] },
    ]);
    const { initAddonRemotes, ensureKindLoaded } = await freshModule();
    await initAddonRemotes();

    await expect(ensureKindLoaded('timeline')).resolves.toBe(true);

    expect(loadedExposes()).toEqual(['vance_addon_calendar/register']);
  });

  it('runs an addon register() exactly once across repeated needs', async () => {
    const register = vi.fn();
    loadRemote.mockResolvedValue({ register });
    manifest([{ name: 'calendar', path: 'bundled:calendar', kinds: ['calendar', 'timeline'] }]);
    const { initAddonRemotes, ensureKindLoaded } = await freshModule();
    await initAddonRemotes();

    await ensureKindLoaded('calendar');
    // Second call names the same addon through a different kind — still one load.
    await expect(ensureKindLoaded('timeline')).resolves.toBe(false);

    expect(loadRemote).toHaveBeenCalledTimes(1);
    expect(register).toHaveBeenCalledTimes(1);
  });

  it('leaves an unknown kind alone rather than guessing an addon', async () => {
    manifest([{ name: 'calendar', path: 'bundled:calendar', kinds: ['calendar'] }]);
    const { initAddonRemotes, ensureKindLoaded } = await freshModule();
    await initAddonRemotes();

    await expect(ensureKindLoaded('mindmap')).resolves.toBe(false);
    expect(loadRemote).not.toHaveBeenCalled();
  });

  it('gives a contested kind to the addon that comes first in the manifest', async () => {
    manifest([
      { name: 'calendar', path: 'bundled:calendar', kinds: ['timeline'] },
      { name: 'usurper', path: 'bundled:usurper', kinds: ['timeline'] },
    ]);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { initAddonRemotes, ensureKindLoaded } = await freshModule();
    await initAddonRemotes();

    await ensureKindLoaded('timeline');

    expect(loadedExposes()).toEqual(['vance_addon_calendar/register']);
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('survives an addon whose register expose is missing', async () => {
    loadRemote.mockRejectedValue(new Error('no such expose'));
    manifest([{ name: 'calendar', path: 'bundled:calendar', kinds: ['calendar'] }]);
    const { initAddonRemotes, ensureKindLoaded } = await freshModule();
    await initAddonRemotes();

    await expect(ensureKindLoaded('calendar')).resolves.toBe(true);
    // Memoised along with successes — a missing expose must not be retried
    // on every document that names the kind.
    await ensureKindLoaded('calendar');
    expect(loadRemote).toHaveBeenCalledTimes(1);
  });

  it('reads a matched kind case-insensitively, as the binding does', async () => {
    manifest([{ name: 'canvas', path: 'bundled:canvas', kinds: ['Canvas'] }]);
    const { initAddonRemotes, ensureKindLoaded } = await freshModule();
    await initAddonRemotes();

    await expect(ensureKindLoaded('CANVAS')).resolves.toBe(true);
  });
});

describe('candidateKindIds', () => {
  it('dispatches an application document on its app discriminator', async () => {
    const { candidateKindIds } = await freshModule();
    expect(candidateKindIds('application', 'Kanban')).toEqual(['application:kanban']);
  });

  it('dispatches every other document on its own kind', async () => {
    const { candidateKindIds } = await freshModule();
    expect(candidateKindIds('Calendar', null)).toEqual(['calendar']);
  });

  it('has nothing to load for an application manifest with no app set', async () => {
    const { candidateKindIds } = await freshModule();
    expect(candidateKindIds('application', null)).toEqual([]);
  });

  it('has nothing to load for a document without a kind', async () => {
    const { candidateKindIds } = await freshModule();
    expect(candidateKindIds(null, null)).toEqual([]);
  });
});
