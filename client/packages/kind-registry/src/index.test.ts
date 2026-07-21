import { beforeEach, describe, it, expect } from 'vitest';
import {
  registerKind,
  resolveKind,
  resolveKindFor,
  listKinds,
  type KindEntry,
} from './index';

/**
 * The kind-registry is the addon → host dispatch interface: addons call
 * registerKind() from their federation `./register` expose; the host resolves
 * by id (application:<app>) or by the matches() predicate (document kind + mime).
 * State lives on globalThis so every bundle shares one Map — reset it per test.
 */
beforeEach(() => {
  (globalThis as Record<string, unknown>).__VANCE_KIND_REGISTRY__ = undefined;
});

function entry(id: string, matches: KindEntry['matches'] = () => false): KindEntry {
  return { id, matches };
}

describe('kind-registry', () => {
  it('registers and resolves an entry by id', () => {
    const e = entry('calendar');
    registerKind(e);
    expect(resolveKind('calendar')).toBe(e);
  });

  it('resolveKind returns undefined for an unknown id', () => {
    expect(resolveKind('nope')).toBeUndefined();
  });

  it('re-registering the same id replaces the previous entry (last-write-wins)', () => {
    registerKind(entry('canvas'));
    const second = entry('canvas');
    registerKind(second);
    expect(resolveKind('canvas')).toBe(second);
    expect(listKinds().filter((k) => k.id === 'canvas')).toHaveLength(1);
  });

  it('resolveKindFor returns the first matcher that accepts — insertion order (built-in wins ties)', () => {
    registerKind(entry('builtin', (kind) => kind === 'md'));
    registerKind(entry('addon', (kind) => kind === 'md'));
    expect(resolveKindFor('md', null)?.id).toBe('builtin');
  });

  it('resolveKindFor passes kind + mime to the matcher and tolerates null', () => {
    registerKind(entry('pdf', (_kind, mime) => mime === 'application/pdf'));
    expect(resolveKindFor(null, 'application/pdf')?.id).toBe('pdf');
    expect(resolveKindFor(null, null)).toBeUndefined();
  });

  it('an id-only entry (matches:()=>false) is reachable via resolveKind but not resolveKindFor', () => {
    // application:<app> kinds register with matches:()=>false and are opened by
    // explicit id lookup, never by the kind/mime predicate.
    registerKind(entry('application:workbook'));
    expect(resolveKind('application:workbook')?.id).toBe('application:workbook');
    expect(resolveKindFor('workbook', null)).toBeUndefined();
  });

  it('listKinds reflects registrations in insertion order', () => {
    registerKind(entry('a'));
    registerKind(entry('b'));
    expect(listKinds().map((k) => k.id)).toEqual(['a', 'b']);
  });
});
