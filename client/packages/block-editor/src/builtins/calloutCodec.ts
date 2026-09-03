// Pure body <-> attrs codec for the built-in `vance-callout` block.
// Deliberately Vue-free so round-trip tests can verify byte-equivalence
// with the former hard-coded core paths without pulling the NodeView.
// Mirrors exactly what WorkPageParser/Serializer did before callout moved
// onto the block-extension-registry.

import * as yaml from 'js-yaml';

function str(obj: Record<string, unknown>, key: string): string | null {
  const v = obj[key];
  if (v == null) return null;
  return String(v);
}

/** Fence body → callout node attrs (severity default `info`, title nullable). */
export function calloutToAttrs(body: string): Record<string, unknown> {
  let parsed: Record<string, unknown> = {};
  try {
    const loaded = yaml.load(body);
    if (loaded && typeof loaded === 'object' && !Array.isArray(loaded)) {
      parsed = loaded as Record<string, unknown>;
    }
  } catch {
    /* malformed YAML — treat as empty, matches old parser */
  }
  return {
    severity: str(parsed, 'severity') ?? 'info',
    title: str(parsed, 'title'),
    body: str(parsed, 'body') ?? '',
  };
}

/**
 * Callout node attrs → fence body. Reproduces the old serializer exactly:
 * always emit `severity`; omit empty `title`/`body`; same YAML dump options
 * as the former `renderFence`, so existing documents round-trip unchanged.
 * The one exception is a value that YAML 1.1 would read as a boolean (`yes`,
 * `on`, `y`, …) or as a sexagesimal (`1:30`): js-yaml quotes those, which the
 * Java serializer (SnakeYAML, YAML 1.1) has always done too.
 */
export function calloutToBody(attrs: Record<string, unknown>): string {
  const body: Record<string, unknown> = { severity: (attrs.severity as string) || 'info' };
  if (attrs.title) body.title = attrs.title;
  if (attrs.body) body.body = attrs.body;
  return yaml.dump(body, {
    lineWidth: -1,
    quoteStyle: 'double',
    forceQuotes: false,
  });
}
