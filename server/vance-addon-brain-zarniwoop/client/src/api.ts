import { brainFetch, brainFetchBlob } from '@vance/shared';
import type { ZarniwoopInsightsDto } from '@vance/generated';
import type { ContentRequestView } from './generated/search/ContentRequestView';
import type { InvestigateResultView } from './generated/search/InvestigateResultView';
import type { SearchConfigView } from './generated/search/SearchConfigView';
import type { SearchRequestView } from './generated/search/SearchRequestView';
import type { SearchResultView } from './generated/search/SearchResultView';

function qs(params: Record<string, string>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) u.set(k, v);
  return u.toString();
}

/**
 * The provider endpoints of this project.
 *
 * <p>This is what the surface gates itself on — no Serper key means no image tab
 * at all, rather than an image tab that always fails. `refresh` makes the server
 * drop its five-minute factory cache first, which is what somebody needs right
 * after writing `research.endpoint.*`: until then the list is stale and looks
 * exactly like a wrong key.
 */
export async function listProviders(
  projectId: string,
  refresh = false,
): Promise<ZarniwoopInsightsDto[]> {
  return brainFetch<ZarniwoopInsightsDto[]>(
    'GET',
    `addon/search/providers?${qs({ projectId, refresh: String(refresh) })}`,
  );
}

export async function loadConfig(projectId: string, folder: string): Promise<SearchConfigView> {
  return brainFetch<SearchConfigView>('GET', `addon/search/config?${qs({ projectId, folder })}`);
}

export async function saveConfig(
  projectId: string,
  folder: string,
  config: SearchConfigView,
): Promise<SearchConfigView> {
  return brainFetch<SearchConfigView>('PUT', `addon/search/config?${qs({ projectId, folder })}`, {
    body: config,
  });
}

/**
 * One search, one modality. Costs provider quota, which is why the surface only
 * ever calls this on an explicit submit.
 */
export async function search(
  projectId: string,
  folder: string | null,
  request: SearchRequestView,
): Promise<SearchResultView> {
  const params: Record<string, string> = { projectId };
  if (folder) params.folder = folder;
  return brainFetch<SearchResultView>('POST', `addon/search/search?${qs(params)}`, {
    body: request,
  });
}

/**
 * The curated pipeline. Costs quota **and** LLM tokens and takes seconds — the
 * surface presents it as its own named action, never as the search button.
 */
export async function investigate(
  projectId: string,
  question: string,
): Promise<InvestigateResultView> {
  return brainFetch<InvestigateResultView>('POST', `addon/search/investigate?${qs({ projectId })}`, {
    body: { question },
  });
}

/**
 * The body behind a hit, for a source that serves one on request.
 *
 * <p>Bytes rather than JSON, because a body may be a PDF — hence `brainFetchBlob`
 * and not `brainFetch`. Only ever called for a hit whose `contentState` is
 * `on-demand`; anywhere else it would fail, which is the whole reason that field
 * exists.
 */
export async function loadContentBlob(
  projectId: string,
  request: ContentRequestView,
): Promise<Blob> {
  const { blob } = await brainFetchBlob(
    `addon/search/content?${qs({ projectId })}`,
    { body: request },
    'POST',
  );
  return blob;
}
