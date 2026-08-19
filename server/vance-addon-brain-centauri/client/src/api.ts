import { brainFetch } from '@vance/shared';
import type { ClipRequest } from './generated/centauri/ClipRequest';
import type { ClipResponse } from './generated/centauri/ClipResponse';
import type { FeedConfigView } from './generated/centauri/FeedConfigView';
import type { FeedPageRequest } from './generated/centauri/FeedPageRequest';
import type { FeedPageView } from './generated/centauri/FeedPageView';
import type { FeedSourceView } from './generated/centauri/FeedSourceView';

function qs(params: Record<string, string>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) u.set(k, v);
  return u.toString();
}

export async function listSources(projectId: string): Promise<FeedSourceView[]> {
  return brainFetch<FeedSourceView[]>('GET', `addon/centauri/sources?${qs({ projectId })}`);
}

export async function loadConfig(projectId: string, folder: string): Promise<FeedConfigView> {
  return brainFetch<FeedConfigView>('GET', `addon/centauri/config?${qs({ projectId, folder })}`);
}

export async function saveConfig(
  projectId: string,
  folder: string,
  config: FeedConfigView,
): Promise<FeedConfigView> {
  return brainFetch<FeedConfigView>('PUT', `addon/centauri/config?${qs({ projectId, folder })}`, {
    body: config,
  });
}

/**
 * One page. The cursor is opaque — pass back exactly what the previous page
 * returned and never try to build one.
 */
export async function loadPage(
  projectId: string,
  request: FeedPageRequest,
): Promise<FeedPageView> {
  return brainFetch<FeedPageView>('POST', `addon/centauri/page?${qs({ projectId })}`, {
    body: request,
  });
}

export async function clipItem(projectId: string, request: ClipRequest): Promise<ClipResponse> {
  return brainFetch<ClipResponse>('POST', `addon/centauri/clip?${qs({ projectId })}`, {
    body: request,
  });
}
