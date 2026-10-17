import type { FormSuggestionDto } from '@vance/generated';
import { brainFetch } from './restClient';

/**
 * Live suggestions for a form field with `suggestionsFrom` —
 * `GET /brain/{tenant}/suggestions/{source}`. An unknown source answers an
 * empty list (the addon behind it is not installed), never an error.
 */
export async function fetchFieldSuggestions(
  source: string,
  query: string,
  projectId?: string,
): Promise<FormSuggestionDto[]> {
  const params = new URLSearchParams({ q: query });
  if (projectId) params.set('projectId', projectId);
  return brainFetch<FormSuggestionDto[]>('GET', `suggestions/${encodeURIComponent(source)}?${params.toString()}`);
}
