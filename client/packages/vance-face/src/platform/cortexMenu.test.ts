import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  allCortexMenuItems,
  cortexMenuItemLabel,
  cortexMenuItemsFor,
  isCortexMenuItemEnabled,
  isCortexMenuSlot,
  registerCortexMenuItem,
  resetCortexMenu,
  unregisterCortexMenuItem,
  type CortexMenuContext,
  type CortexMenuItem,
} from './cortexMenu';

function ctx(overrides: Partial<CortexMenuContext> = {}): CortexMenuContext {
  return {
    projectId: 'p',
    document: null,
    selection: null,
    openDocument: async () => {},
    revealPath: async () => {},
    ...overrides,
  };
}

function item(partial: Partial<CortexMenuItem> & { id: string }): CortexMenuItem {
  return {
    slot: 'extras',
    label: partial.id,
    run: () => {},
    ...partial,
  };
}

describe('cortexMenu', () => {
  beforeEach(() => resetCortexMenu());

  it('replaces an entry registered twice under the same id', () => {
    // The workspace router mounts and unmounts the Cortex; a registry that
    // grew per mount would show translate three times.
    registerCortexMenuItem(item({ id: 'a', label: 'first' }));
    registerCortexMenuItem(item({ id: 'a', label: 'second' }));

    expect(allCortexMenuItems()).toHaveLength(1);
    expect(cortexMenuItemLabel(allCortexMenuItems()[0])).toBe('second');
  });

  it('unregisters by id', () => {
    registerCortexMenuItem(item({ id: 'a' }));
    unregisterCortexMenuItem('a');
    expect(allCortexMenuItems()).toHaveLength(0);
  });

  it('returns only the entries of the requested slot', () => {
    registerCortexMenuItem(item({ id: 'v', slot: 'view' }));
    registerCortexMenuItem(item({ id: 'a', slot: 'actions' }));
    registerCortexMenuItem(item({ id: 'x', slot: 'extras' }));

    expect(cortexMenuItemsFor('view', ctx()).map((i) => i.id)).toEqual(['v']);
    expect(cortexMenuItemsFor('extras', ctx()).map((i) => i.id)).toEqual(['x']);
  });

  it('sorts numbered entries first, then the rest by label', () => {
    registerCortexMenuItem(item({ id: 'zeta', label: 'Zeta' }));
    registerCortexMenuItem(item({ id: 'alpha', label: 'Alpha' }));
    registerCortexMenuItem(item({ id: 'second', label: 'Second', sortIndex: 20 }));
    registerCortexMenuItem(item({ id: 'first', label: 'First', sortIndex: 10 }));

    expect(cortexMenuItemsFor('extras', ctx()).map((i) => i.id))
      .toEqual(['first', 'second', 'alpha', 'zeta']);
  });

  it('applies the visibility predicate', () => {
    registerCortexMenuItem(item({ id: 'needsDoc', visible: (c) => c.document !== null }));

    expect(cortexMenuItemsFor('extras', ctx())).toHaveLength(0);
    expect(cortexMenuItemsFor('extras', ctx({
      document: {
        id: 'd', path: 'a.md', name: 'a.md', mimeType: 'text/markdown',
        kind: null, text: '', dirty: false,
      },
    }))).toHaveLength(1);
  });

  it('hides an entry whose predicate throws instead of taking the menu down', () => {
    // The predicate comes from an addon; the reader still needs the File menu.
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    registerCortexMenuItem(item({
      id: 'broken',
      visible: () => { throw new Error('boom'); },
    }));
    registerCortexMenuItem(item({ id: 'fine' }));

    expect(cortexMenuItemsFor('extras', ctx()).map((i) => i.id)).toEqual(['fine']);
  });

  it('treats an entry without an enablement rule as enabled', () => {
    const entry = item({ id: 'a' });
    expect(isCortexMenuItemEnabled(entry, ctx())).toBe(true);
  });

  it('disables an entry whose enablement rule throws', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const entry = item({ id: 'a', enabled: () => { throw new Error('boom'); } });
    expect(isCortexMenuItemEnabled(entry, ctx())).toBe(false);
  });

  it('resolves a deferred label, falling back to the id', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    expect(cortexMenuItemLabel(item({ id: 'a', label: () => 'Übersetzen…' }))).toBe('Übersetzen…');
    expect(cortexMenuItemLabel(item({
      id: 'broken',
      label: () => { throw new Error('no i18n'); },
    }))).toBe('broken');
  });

  it('recognises exactly the three slots that have a rendering place', () => {
    expect(isCortexMenuSlot('view')).toBe(true);
    expect(isCortexMenuSlot('actions')).toBe(true);
    expect(isCortexMenuSlot('extras')).toBe(true);
    expect(isCortexMenuSlot('file')).toBe(false);
    expect(isCortexMenuSlot(undefined)).toBe(false);
  });
});
