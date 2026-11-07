import type { ToolServiceDto } from '@vance/generated';

/** One tool in the palette, as the list shows it. */
export interface PaletteTool {
  /** The full tool name a {@code tool_task} calls. */
  name: string;
  /** The part after the pack prefix — what the person recognises. */
  label: string;
  description: string;
  /** An example of the parameters the service's catalog entry gives, if it names one. */
  params?: Record<string, unknown>;
}

export interface PaletteGroup {
  service: string;
  title: string;
  description: string;
  /** The service wants a login the caller has not made. */
  needsLogin: boolean;
  /** What the catalog entry names as the service's icon: a UI icon name or a logo path; absent without one. */
  icon?: string;
  tools: PaletteTool[];
  /** How many tools of the service the query matched (more than {@link tools} when cut). */
  total: number;
}

/** Tools shown per service without a query when the catalog names no highlights. */
const FALLBACK_COUNT = 6;
/** Tools shown per service for a query. */
const QUERY_LIMIT = 20;

/** {@code slack_rest__conversations_history} → {@code conversations_history}. */
export function toolLabel(name: string): string {
  const cut = name.lastIndexOf('__');
  return cut >= 0 ? name.slice(cut + 2) : name;
}

/**
 * What the palette lists for {@code query}. Without one: per service the
 * operations its catalog entry points at (or the first few tools), so a long
 * pack does not drown the list. With one: whatever matches by name,
 * description or service, a bounded number per service.
 */
export function paletteGroups(services: ToolServiceDto[], query: string): PaletteGroup[] {
  const needle = query.trim().toLowerCase();
  const groups: PaletteGroup[] = [];

  for (const svc of services) {
    const title = svc.catalog?.title || svc.service;
    const description = svc.catalog?.description ?? '';
    const byName = new Map(svc.tools.map((t) => [t.name, t]));
    const example = new Map(svc.catalog?.highlights.map((h) => [h.tool, h]) ?? []);

    const asTool = (name: string): PaletteTool => {
      const spec = byName.get(name);
      const hl = example.get(name);
      return {
        name,
        label: toolLabel(name),
        description: hl?.description || spec?.description || '',
        params: hl?.params,
      };
    };

    let tools: PaletteTool[];
    let total: number;
    if (needle === '') {
      const featured = (svc.catalog?.highlights ?? []).filter((h) => byName.has(h.tool)).map((h) => asTool(h.tool));
      tools = featured.length > 0 ? featured : svc.tools.slice(0, FALLBACK_COUNT).map((t) => asTool(t.name));
      total = svc.tools.length;
    } else {
      const serviceHit = `${svc.service} ${title}`.toLowerCase().includes(needle);
      const matched = svc.tools.filter(
        (t) => serviceHit || `${t.name} ${t.description}`.toLowerCase().includes(needle),
      );
      tools = matched.slice(0, QUERY_LIMIT).map((t) => asTool(t.name));
      total = matched.length;
    }
    if (tools.length === 0) continue;

    groups.push({
      service: svc.service,
      title,
      description,
      needsLogin: svc.catalog?.loginProvider != null && svc.catalog.connected === false,
      icon: svc.catalog?.icon ?? undefined,
      tools,
      total,
    });
  }

  // Services the catalog knows come first, then the rest; each block by title.
  const known = new Set(services.filter((s) => s.catalog).map((s) => s.service));
  return groups.sort((a, b) => {
    const rank = Number(!known.has(a.service)) - Number(!known.has(b.service));
    return rank !== 0 ? rank : a.title.localeCompare(b.title);
  });
}
