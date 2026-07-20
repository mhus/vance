// Default body <-> attrs codec for addon-contributed blocks
// (block-extension-registry). Kept OUT of blockRegistry.ts so an addon
// importing `@vance/block-editor/blockRegistry` (just to call
// registerBlock) does NOT pull js-yaml into its bundle — only the
// editor's codec paths (parser / proseMirror) use these.

import yaml from 'js-yaml';
import type { BlockExtension } from '../blockRegistry';

/**
 * Fence body → node attrs. Uses the extension's {@link BlockExtension.toAttrs}
 * when provided; otherwise parses the body as a YAML map (empty / non-map
 * bodies yield {@code {}} — fine for atom blocks).
 */
export function attrsFromBody(ext: BlockExtension, body: string): Record<string, unknown> {
  if (ext.toAttrs) return ext.toAttrs(body);
  try {
    const loaded = yaml.load(body);
    return loaded && typeof loaded === 'object' && !Array.isArray(loaded)
      ? (loaded as Record<string, unknown>)
      : {};
  } catch {
    return {};
  }
}

/**
 * Node attrs → fence body. Uses the extension's {@link BlockExtension.toBody}
 * when provided; otherwise dumps the attrs as YAML (empty attrs → empty
 * body, so an atom block round-trips to a bare fence).
 */
export function bodyFromAttrs(ext: BlockExtension, attrs: Record<string, unknown>): string {
  if (ext.toBody) return ext.toBody(attrs);
  if (!attrs || Object.keys(attrs).length === 0) return '';
  return yaml.dump(attrs, {
    lineWidth: -1,
    noCompatMode: true,
    quotingType: '"',
    forceQuotes: false,
  });
}
