import type {
  TemplateApplyResponseDto,
  TemplateDto,
  TemplateListResponseDto,
} from '@vance/generated';
import { brainFetch } from './restClient';
import type { FormValue } from './wizards';

/**
 * GET /brain/{tenant}/templates — document templates available in the
 * current cascade (project → tenant → bundled). Optional {@code tag}
 * narrows the list server-side.
 */
export async function listTemplates(
  projectId?: string,
  tag?: string,
): Promise<TemplateListResponseDto> {
  const params = new URLSearchParams();
  if (projectId) params.set('projectId', projectId);
  if (tag) params.set('tag', tag);
  const qs = params.toString() ? `?${params}` : '';
  return brainFetch<TemplateListResponseDto>('GET', `templates${qs}`);
}

/**
 * GET /brain/{tenant}/templates/{name} — full definition (form fields +
 * name policy) for rendering the picker form.
 */
export async function getTemplate(name: string, projectId?: string): Promise<TemplateDto> {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return brainFetch<TemplateDto>('GET', `templates/${encodeURIComponent(name)}${qs}`);
}

/**
 * POST /brain/{tenant}/templates/{name}/apply — render the body and
 * create the document. Returns the created path + MIME. Throws
 * RestError with status 409 when the target document already exists.
 */
export async function applyTemplate(
  name: string,
  body: { folder: string; name?: string; values?: Record<string, FormValue>; lang?: string },
  projectId?: string,
): Promise<TemplateApplyResponseDto> {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return brainFetch<TemplateApplyResponseDto>(
    'POST',
    `templates/${encodeURIComponent(name)}/apply${qs}`,
    { body },
  );
}
