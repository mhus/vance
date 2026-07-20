// Runtime registry for addon-contributed block types.
//
// Mirrors the @vance/kind-registry + platform pattern: the backing Map
// lives on globalThis (`__VANCE_BLOCK_REGISTRY__`) so that a block
// registered from an addon's OWN bundled copy of @vance/block-editor is
// visible to the host editor's copy. Module Federation does NOT share
// workspace packages (see specification/public/addon-system.md §5.3 —
// TLA deadlock), so every consumer bundles its own copy; only globalThis
// bridges the bundle boundary.
//
// A registered block's Tiptap `node` must be built against the SHARED
// @tiptap/core singleton (declared in each addon's + the host's
// federation `shared` config) — otherwise the host Editor's
// ExtensionManager instanceof-checks reject the foreign Node. That is
// the exact property the block-extension-registry Phase-0 spike verifies.

import type { Editor, Node, Range } from '@tiptap/core';
import type { Component } from 'vue';

/** Slash-menu contribution for a registered block. */
export interface BlockExtensionSlashItem {
  /** Menu label. */
  title: string;
  /** One-line hint under the label. */
  hint: string;
  /** Insert the block; called after the `/` trigger range is known. */
  insert: (ctx: { editor: Editor; range: Range }) => void;
}

/** One addon-contributed block type. */
export interface BlockExtension {
  /**
   * Fence info-string WITHOUT backticks, e.g. {@code "vance-sprint"}.
   * Registry key — registering a second entry with the same fence
   * replaces the first (last-write-wins, with a console warning).
   * Core fences (`vance-callout`, `vance-toggle`, …) are privileged and
   * cannot be overridden — the core codec paths win.
   */
  fence: string;
  /**
   * Tiptap Node extension, built against the shared @tiptap/core. Its
   * {@code name} is the ProseMirror node type the codec maps to/from.
   * Declare the node's {@code addAttributes()} to match the keys
   * {@link toAttrs} produces.
   */
  node: Node;
  /**
   * Read-only Vue renderer for `BlockView` (non-Tiptap surfaces like the
   * read-only WorkPage view + workbook detail pane). Receives the block's
   * {@code attrs} as a prop. Optional — without it, read-only surfaces
   * show a generic fence card. Provide it for any block that should look
   * right outside the editor.
   */
  view?: Component;
  /**
   * Parse the fence body → ProseMirror node attrs. Optional — the codec
   * defaults to a YAML-map parse (or {@code {}} for empty/atom bodies).
   */
  toAttrs?: (body: string) => Record<string, unknown>;
  /**
   * Serialize node attrs → fence body. Optional — the codec defaults to
   * a YAML dump (or empty body when attrs is empty).
   */
  toBody?: (attrs: Record<string, unknown>) => string;
  /** Optional slash-menu entry. */
  slash?: BlockExtensionSlashItem;
}

const KEY = '__VANCE_BLOCK_REGISTRY__';

function store(): Map<string, BlockExtension> {
  const g = globalThis as unknown as Record<string, unknown>;
  let map = g[KEY] as Map<string, BlockExtension> | undefined;
  if (!map) {
    map = new Map<string, BlockExtension>();
    g[KEY] = map;
  }
  return map;
}

/** Register (or replace) an addon block. Call from the addon's `register()`. */
export function registerBlock(ext: BlockExtension): void {
  const map = store();
  if (map.has(ext.fence)) {
    // Last-write-wins, consistent with @vance/kind-registry. The
    // situation is meant to be avoided, not resolved elegantly.
    // eslint-disable-next-line no-console
    console.warn(`[block-registry] block "${ext.fence}" re-registered — replacing`);
  }
  map.set(ext.fence, ext);
}

/** All registered blocks, in insertion order. */
export function registeredBlocks(): BlockExtension[] {
  return [...store().values()];
}

/** Registered block for a fence info-string, or undefined. */
export function findBlockByFence(fence: string): BlockExtension | undefined {
  return store().get(fence);
}

/** Registered block whose Tiptap node has the given type name, or undefined. */
export function findBlockByNodeName(nodeName: string): BlockExtension | undefined {
  for (const b of store().values()) {
    if (b.node.name === nodeName) return b;
  }
  return undefined;
}
