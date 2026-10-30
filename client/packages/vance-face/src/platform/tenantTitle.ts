// The tenant's own title for the header of the Web-UI.
//
// A tenant admin sets `ui.title` in the `_tenant` project; it replaces the
// instance title the pod writes into `/config.json` (which is the same for
// every tenant on the deployment). Read through the settings cascade without
// a project, so it is the tenant value — see `SettingsCascadeController`.
//
// Fail-open: no setting, an empty value or a failed request all read as
// "no tenant title", and the header keeps the instance title. A label is
// not worth a banner.

import { brainFetch } from '@vance/shared';

const KEY_TITLE = 'ui.title';

/** The tenant's title, or `null` when none is set or it could not be read. */
export async function loadTenantTitle(): Promise<string | null> {
  const params = new URLSearchParams();
  params.append('key', KEY_TITLE);
  try {
    const values = await brainFetch<Record<string, string>>(
      'GET', `settings/cascade?${params.toString()}`);
    const title = values[KEY_TITLE]?.trim();
    return title ? title : null;
  } catch (e) {
    console.warn('loadTenantTitle failed, keeping the instance title', e);
    return null;
  }
}
