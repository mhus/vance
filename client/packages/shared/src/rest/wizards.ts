import type {
  WizardDto,
  WizardListResponseDto,
  WizardRenderResponseDto,
} from '@vance/generated';
import { brainFetch } from './restClient';

/**
 * Form value primitive. Wizards encode scalars as strings (matching the
 * Web-UI's `Record<string, string>` convention from tool-templates).
 * Multi-select carries a `string[]`. Nested `repeat` items are objects
 * keyed by field name, recursively.
 */
export type FormValue = string | string[] | FormValueObject[];
export type FormValueObject = Record<string, string | string[]>;

/**
 * GET /brain/{tenant}/wizards — listing of wizards available in the
 * current cascade (project → user → tenant → bundled).
 */
export async function listWizards(projectId?: string): Promise<WizardListResponseDto> {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return brainFetch<WizardListResponseDto>('GET', `wizards${qs}`);
}

/**
 * GET /brain/{tenant}/wizards/{name} — full wizard with field schema
 * for form rendering. Throws RestError on 404 when the wizard doesn't
 * exist in any cascade layer.
 */
export async function getWizard(name: string, projectId?: string): Promise<WizardDto> {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return brainFetch<WizardDto>('GET', `wizards/${encodeURIComponent(name)}${qs}`);
}

/**
 * POST /brain/{tenant}/wizards/{name}/render — submit form values,
 * receive the Pebble-rendered prompt ready for the chat input.
 *
 * `lang` overrides the resolver's tenant default — used by the Web-UI
 * to render with the active webui locale when it differs from chat lang.
 */
export async function renderWizard(
  name: string,
  values: Record<string, FormValue>,
  projectId?: string,
  lang?: string,
): Promise<WizardRenderResponseDto> {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return brainFetch<WizardRenderResponseDto>(
    'POST',
    `wizards/${encodeURIComponent(name)}/render${qs}`,
    { body: { values, lang } },
  );
}
