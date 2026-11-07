import { isIconName, type IconName } from '@vance/components';
import type { ToolServiceDto } from '@vance/generated';

/** What stands in front of a state's type: an icon of the UI vocabulary, or an image the server serves (a logo). */
export type NodeIcon = { kind: 'ui'; name: IconName } | { kind: 'image'; src: string };

/** A web UI asset path: plain segments, an image ending — what `webui-assets.md` §2 allows, in the short form. */
const ASSET_PATH = /^[a-z0-9][a-z0-9._-]*(\/[a-z0-9][a-z0-9._-]*)*\.(svg|png|jpg|jpeg|webp|gif|ico)$/;

/**
 * The icon a catalog entry names: a name of the UI vocabulary, or the path of a web UI asset (turned into an address
 * by {@code assetUrl}). Anything else — no icon, a name the vocabulary does not know — has none.
 */
export function resolveNodeIcon(
  icon: string | null | undefined,
  assetUrl: (path: string) => string,
): NodeIcon | null {
  if (!icon) return null;
  if (isIconName(icon)) return { kind: 'ui', name: icon };
  if (!ASSET_PATH.test(icon)) return null;
  const src = assetUrl(icon);
  return src === '' ? null : { kind: 'image', src };
}

/**
 * The icon of the service that owns {@code tool}: its catalog entry's icon. A tool no service lists, or a service whose
 * entry names no icon, has none — the state keeps the icon of its task type.
 */
export function toolNodeIcon(
  tool: string | undefined,
  services: readonly ToolServiceDto[],
  assetUrl: (path: string) => string,
): NodeIcon | null {
  if (!tool) return null;
  const owner = services.find((svc) => svc.tools.some((t) => t.name === tool));
  return resolveNodeIcon(owner?.catalog?.icon, assetUrl);
}
