import { brainFetch } from '@vance/shared';
import type { SlideshowRebuildResponse } from './generated/slideshow/SlideshowRebuildResponse';
import type { SlideshowView } from './generated/slideshow/SlideshowView';

function qs(params: Record<string, string>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) u.set(k, v);
  return u.toString();
}

export async function getSlideshow(
  projectId: string,
  folder: string,
): Promise<SlideshowView> {
  return brainFetch<SlideshowView>(
    'GET',
    `slideshow/show?${qs({ projectId, folder })}`,
  );
}

export async function rebuildSlideshow(
  projectId: string,
  folder: string,
): Promise<SlideshowRebuildResponse> {
  return brainFetch<SlideshowRebuildResponse>(
    'POST',
    `slideshow/rebuild?${qs({ projectId, folder })}`,
  );
}
