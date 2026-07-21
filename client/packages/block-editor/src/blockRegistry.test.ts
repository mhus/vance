import { describe, it, expect, vi } from 'vitest';
import type { Node } from '@tiptap/core';
import type { BlockExtension } from './blockRegistry';

/**
 * blockRegistry is the addon → editor block-contribution interface: an addon's
 * register() calls registerBlock() so its custom fence renders/round-trips in
 * that bundle's editor. The Map is module-scoped (per-bundle, no clear()), so
 * each test loads a fresh copy of the module via vi.resetModules() for isolation.
 */
async function freshRegistry() {
  vi.resetModules();
  return import('./blockRegistry');
}

// Only `.name` is read off the node (findBlockByNodeName); a minimal fake keeps
// the test free of any Tiptap/ProseMirror runtime.
function fakeNode(name: string): Node {
  return { name } as unknown as Node;
}
function ext(fence: string, nodeName = fence): BlockExtension {
  return { fence, node: fakeNode(nodeName) };
}

describe('blockRegistry', () => {
  it('registers and finds a block by fence', async () => {
    const r = await freshRegistry();
    const e = ext('vance-sprint');
    r.registerBlock(e);
    expect(r.findBlockByFence('vance-sprint')).toBe(e);
  });

  it('findBlockByFence returns undefined for an unknown fence', async () => {
    const r = await freshRegistry();
    expect(r.findBlockByFence('nope')).toBeUndefined();
  });

  it('re-registering the same fence replaces the entry and warns (last-write-wins)', async () => {
    const r = await freshRegistry();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    r.registerBlock(ext('vance-x', 'n1'));
    const second = ext('vance-x', 'n2');
    r.registerBlock(second);
    expect(r.findBlockByFence('vance-x')).toBe(second);
    expect(r.registeredBlocks()).toHaveLength(1);
    expect(warn).toHaveBeenCalledOnce();
    warn.mockRestore();
  });

  it('findBlockByNodeName resolves via the tiptap node name', async () => {
    const r = await freshRegistry();
    r.registerBlock({ fence: 'vance-y', node: fakeNode('yNode') });
    expect(r.findBlockByNodeName('yNode')?.fence).toBe('vance-y');
    expect(r.findBlockByNodeName('missing')).toBeUndefined();
  });

  it('registeredBlocks preserves insertion order', async () => {
    const r = await freshRegistry();
    r.registerBlock(ext('a'));
    r.registerBlock(ext('b'));
    r.registerBlock(ext('c'));
    expect(r.registeredBlocks().map((b) => b.fence)).toEqual(['a', 'b', 'c']);
  });
});
