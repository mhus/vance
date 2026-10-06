import { brainFetch } from '@vance/shared';
import type {
  DocumentCreateRequest,
  DocumentDto,
  DocumentFolderListResponse,
  DocumentSummary,
} from '@vance/generated';
import type { SettingsDocRow, SettingsProvider, SettingsScope } from '@vance/kind-registry';
import { MOUNT_SOURCE_KIND } from './mountSourceCodec';

/**
 * Settings contribution of the mount-source kind: the Jaglan mounts under
 * {@code _vance/config/mounts/} (one YAML per instance, see
 * {@code SourceConfigPaths.MOUNTS}). The Settings page's "Bereiche" tab shows
 * this as the Mounts area: entry inventory, add, delete; an entry opens in
 * the kind's own form view right there (inline), not as a Cortex link.
 *
 * <p>Served for the tenant and project layers only, like the research area:
 * the source-config cascade is {@code project → _tenant → classpath} — it
 * has no user layer, so a user-scope listing would always be empty.
 */
export const mountSourceSettingsProvider: SettingsProvider = {
  titleKey: 'settings.areas.mounts.title',
  list: async (scope: SettingsScope): Promise<SettingsDocRow[]> => {
    if (scope.kind === 'user') return [];
    return listMountSources(scope.projectId);
  },
  create: async (scope: SettingsScope, name: string): Promise<SettingsDocRow> => {
    if (scope.kind === 'user') {
      throw new Error('Mount sources are not available in the user scope.');
    }
    const path = `_vance/config/mounts/${name}.yaml`;
    const body: DocumentCreateRequest = {
      path,
      inlineText:
        '# New mount — inert until configured and enabled.\n'
        + '$meta:\n  kind: vance-mount-source\n'
        + 'protocol: local\nenabled: false\n',
    };
    const params = new URLSearchParams({ projectId: scope.projectId });
    const doc = await brainFetch<DocumentDto>(
      'POST',
      `documents?${params.toString()}`,
      { body },
    );
    return {
      name,
      title: name,
      kindId: MOUNT_SOURCE_KIND,
      documentId: doc.id,
      projectId: scope.projectId,
      path,
    };
  },
};

async function listMountSources(projectId: string): Promise<SettingsDocRow[]> {
  const params = new URLSearchParams({
    projectId,
    path: '_vance/config/mounts/',
    pageSize: '200',
  });
  const data = await brainFetch<DocumentFolderListResponse>(
    'GET',
    `documents/folder?${params.toString()}`,
  );
  return (data.files ?? [])
    .filter((doc) => doc.kind === MOUNT_SOURCE_KIND)
    .map((doc: DocumentSummary) => ({
      name: doc.name,
      title: doc.title || doc.name,
      kindId: MOUNT_SOURCE_KIND,
      documentId: doc.id,
      projectId: doc.projectId,
      path: doc.path,
    }));
}
