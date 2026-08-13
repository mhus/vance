import { beforeEach, describe, it, expect, vi } from 'vitest';
import type { Component } from 'vue';

// resolveHelpPath pulls in docTypeRegistry, which eagerly imports the
// hand-rolled view SFCs. Nothing renders here, so stub them — same set
// docTypeRegistry.test.ts stubs, for the same reason.
vi.mock('@/kindViews/ListView.vue', () => ({ default: {} }));
vi.mock('@/kindViews/TreeView.vue', () => ({ default: {} }));
vi.mock('@/kindViews/RecordsView.vue', () => ({ default: {} }));
vi.mock('@/kindViews/SheetView.vue', () => ({ default: {} }));
vi.mock('@/kindViews/MindmapView.vue', () => ({ default: {} }));

import { registerKind } from '@vance/kind-registry';
import { DEFAULT_HELP_PATH, resolveHelpPath } from './help';
import type { CortexDocument } from './types';

const CMP = {} as Component;

function doc(p: { kind?: string | null; mimeType?: string | null }): CortexDocument {
  return {
    kind: p.kind ?? null,
    mimeType: p.mimeType ?? null,
    path: 'doc.yaml',
    headers: {},
  } as unknown as CortexDocument;
}

// resolveHelpPath goes through resolveBinding, which reads the shared
// globalThis kind-registry — reset per test.
beforeEach(() => {
  (globalThis as Record<string, unknown>).__VANCE_KIND_REGISTRY__ = undefined;
});

describe('resolveHelpPath', () => {
  it('derives the help file from a kind-registry id by convention', () => {
    // The contract that lets a new kind ship help without touching any
    // mapping: register `vance-workflow`, drop
    // help/{lang}/doc-kind-vance-workflow.md into the brain, done.
    registerKind({
      id: 'vance-workflow',
      matches: (k) => k === 'vance-workflow',
      view: CMP,
      serialize: () => '',
    });
    expect(resolveHelpPath(doc({ kind: 'vance-workflow', mimeType: 'application/yaml' })))
      .toBe('doc-kind-vance-workflow.md');
  });

  it('falls back to the generic Cortex help for an unmapped document', () => {
    expect(resolveHelpPath(doc({ kind: null, mimeType: 'text/plain' })))
      .toBe(DEFAULT_HELP_PATH);
  });

  it('returns the generic help when there is no document', () => {
    expect(resolveHelpPath(null)).toBe(DEFAULT_HELP_PATH);
  });
});
