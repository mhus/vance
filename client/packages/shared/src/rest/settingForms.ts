import type {
  SettingFormApplyResponseDto,
  SettingFormDto,
  SettingFormListResponseDto,
} from '@vance/generated';
import { brainFetch } from './restClient';
import type { FormValue } from './wizards';

/**
 * Setting Forms REST client — wraps the
 * {@code /brain/{tenant}/setting-forms} endpoint surface (see
 * {@code specification/setting-forms.md §8}).
 *
 * <p>All endpoints accept an optional {@code projectId}: when omitted
 * the brain treats the request as tenant-scoped (system-project
 * cascade only).
 *
 * <p>The {@code FormValue} type is shared with the wizard module —
 * the same {@code Record<string, FormValue>} shape works for both
 * form subsystems because they share the underlying
 * {@code FormFieldDto} contract.
 */

/**
 * GET /brain/{tenant}/setting-forms — listing of forms available in
 * the current cascade after the form's {@code availableIn} glob
 * filter has been applied against {@code projectId}.
 */
export async function listSettingForms(
  projectId?: string,
): Promise<SettingFormListResponseDto> {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return brainFetch<SettingFormListResponseDto>('GET', `setting-forms${qs}`);
}

/**
 * GET /brain/{tenant}/setting-forms/{name} — full form definition
 * with live cascade values populated per direct-mapped field. The
 * response strips backend-only fields (Pebble templates for
 * {@code showIf}/{@code writeIf}/computed {@code value}); the UI
 * only sees the schema + the current effective values.
 */
export async function getSettingForm(
  name: string,
  projectId?: string,
): Promise<SettingFormDto> {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return brainFetch<SettingFormDto>(
    'GET',
    `setting-forms/${encodeURIComponent(name)}${qs}`,
  );
}

/**
 * POST /brain/{tenant}/setting-forms/{name}/apply — validate + render +
 * persist the form values. The response's {@code applied} list tells
 * the UI exactly which keys were written/deleted/skipped.
 *
 * <p>Password-typed fields submitted with an empty string mean
 * "do not modify" — the brain returns a SKIP entry for those.
 */
export async function applySettingForm(
  name: string,
  values: Record<string, FormValue>,
  projectId?: string,
  lang?: string,
): Promise<SettingFormApplyResponseDto> {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return brainFetch<SettingFormApplyResponseDto>(
    'POST',
    `setting-forms/${encodeURIComponent(name)}/apply${qs}`,
    { body: { values, lang } },
  );
}

/**
 * POST /brain/{tenant}/setting-forms/{name}/validate — dry-run of the
 * apply pipeline. Returns the same {@code applied} list that
 * {@link applySettingForm} would have produced, but without
 * persisting. Used by the "Preview" button in the UI.
 */
export async function validateSettingForm(
  name: string,
  values: Record<string, FormValue>,
  projectId?: string,
  lang?: string,
): Promise<SettingFormApplyResponseDto> {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return brainFetch<SettingFormApplyResponseDto>(
    'POST',
    `setting-forms/${encodeURIComponent(name)}/validate${qs}`,
    { body: { values, lang } },
  );
}

/**
 * POST /brain/{tenant}/setting-forms/{name}/reset — delete every key
 * the form references on its respective scope. Falls back to the
 * next outer cascade layer. Rejected with HTTP 400 when the form
 * declares {@code clearable: false}.
 */
export async function resetSettingForm(
  name: string,
  projectId?: string,
): Promise<SettingFormApplyResponseDto> {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return brainFetch<SettingFormApplyResponseDto>(
    'POST',
    `setting-forms/${encodeURIComponent(name)}/reset${qs}`,
  );
}
