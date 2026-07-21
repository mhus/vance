import { beforeEach, describe, it, expect, vi } from 'vitest';
import type { Component } from 'vue';

// resolveBinding stores view components but never renders them here, so stub the
// eagerly-imported SFCs — keeps their (heavy) dependency graphs out of the test.
// The defineAsyncComponent(() => import(...)) views never fire in resolveBinding,
// so they need no mock. Codecs are pure TS and load for real via the `@` alias.
vi.mock('@/document/ListView.vue', () => ({ default: {} }));
vi.mock('@/document/TreeView.vue', () => ({ default: {} }));
vi.mock('@/document/RecordsView.vue', () => ({ default: {} }));
vi.mock('@/document/SheetView.vue', () => ({ default: {} }));
vi.mock('@/document/MindmapView.vue', () => ({ default: {} }));

import { registerKind } from '@vance/kind-registry';
import { resolveBinding } from './docTypeRegistry';
import type { CortexDocument } from './types';

const CMP = {} as Component;

function doc(p: { kind?: string | null; mimeType?: string | null; app?: string }): CortexDocument {
  return {
    kind: p.kind ?? null,
    mimeType: p.mimeType ?? null,
    path: 'doc.txt', // real CortexDocument always has a path (hand-rolled image match reads it)
    headers: p.app ? { app: p.app } : {},
  } as unknown as CortexDocument;
}

// resolveBinding reads the shared globalThis kind-registry — reset per test.
beforeEach(() => {
  (globalThis as Record<string, unknown>).__VANCE_KIND_REGISTRY__ = undefined;
});

describe('resolveBinding — cortex document → renderer dispatch', () => {
  it('application manifest routes to its registered application:<app> kind (client-memory when it serializes)', () => {
    registerKind({ id: 'application:workbook', matches: () => false, view: CMP, serialize: () => '' });
    const b = resolveBinding(doc({ kind: 'application', app: 'workbook' }));
    expect(b.mode).toBe('kind-registry');
    expect(b.id).toBe('kind-registry:application:workbook');
    expect(b.editLocation).toBe('client-memory');
  });

  it('an app kind with a view but no serialize is edited server-side', () => {
    registerKind({ id: 'application:canvasbook', matches: () => false, view: CMP });
    const b = resolveBinding(doc({ kind: 'application', app: 'canvasbook' }));
    expect(b.mode).toBe('kind-registry');
    expect(b.editLocation).toBe('server-side');
  });

  it('application manifest with no registered app kind falls through (not kind-registry)', () => {
    const b = resolveBinding(doc({ kind: 'application', app: 'ghost', mimeType: 'application/yaml' }));
    expect(b.mode).not.toBe('kind-registry');
    expect(b.id).not.toContain('kind-registry');
  });

  it('a registered kind whose matcher accepts and has a view wins via resolveKindFor', () => {
    registerKind({ id: 'x', matches: (k) => k === 'xkind', view: CMP, serialize: () => '' });
    const b = resolveBinding(doc({ kind: 'xkind', mimeType: 'text/plain' }));
    expect(b.id).toBe('kind-registry:x');
    expect(b.mode).toBe('kind-registry');
    expect(b.editLocation).toBe('client-memory');
  });

  it('a registered kind with only codePreview (no view) does NOT take the kind-registry path', () => {
    registerKind({ id: 'y', matches: (k) => k === 'ykind', codePreview: CMP });
    const b = resolveBinding(doc({ kind: 'ykind', mimeType: 'text/plain' }));
    expect(b.mode).not.toBe('kind-registry');
  });
});
