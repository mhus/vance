import { brainFetch } from '@vance/shared';
import type {
  DocumentCreateRequest,
  DocumentDto,
  DocumentFolderListResponse,
  DocumentSummary,
} from '@vance/generated';
import type { SettingsDocRow, SettingsProvider, SettingsScope } from '@vance/kind-registry';
import { FEED_SOURCE_KIND } from './feedSourceCodec';

/**
 * Settings contribution of the feed-source kind: the Centauri feed
 * endpoints under {@code _vance/config/feeds/} (one YAML per instance, see
 * {@code SourceConfigPaths.FEEDS}). The host Settings page's "Bereiche" tab
 * shows this as the Feeds area: entry inventory, add, delete; an entry
 * opens in the kind's own form view right there (inline), not as a Cortex
 * link.
 *
 * <p>This is the proof that an addon contributes a settings area without
 * touching host code: the provider rides along with the kind registration
 * ({@code register.ts}) over the same federation seam as every other addon
 * contribution.
 *
 * <p>Served for the tenant and project layers only, like the research area:
 * the source-config cascade is {@code project → _tenant → classpath} — it
 * has no user layer, so a user-scope listing would always be empty.
 */
export const feedSourceSettingsProvider: SettingsProvider = {
  titleKey: 'feeds.settingsArea.title',
  list: async (scope: SettingsScope): Promise<SettingsDocRow[]> => {
    if (scope.kind === 'user') return [];
    return listFeedSources(scope.projectId);
  },
  create: async (scope: SettingsScope, name: string): Promise<SettingsDocRow> => {
    if (scope.kind === 'user') {
      throw new Error('Feed sources are not available in the user scope.');
    }
    const path = `_vance/config/feeds/${name}.yaml`;
    const body: DocumentCreateRequest = {
      path,
      inlineText:
        '# New feed source — inert until configured and enabled.\n'
        + `$meta:\n  kind: ${FEED_SOURCE_KIND}\n`
        + 'protocol: usgs\nenabled: false\n',
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
      kindId: FEED_SOURCE_KIND,
      documentId: doc.id,
      projectId: scope.projectId,
      path,
    };
  },
};

async function listFeedSources(projectId: string): Promise<SettingsDocRow[]> {
  const params = new URLSearchParams({
    projectId,
    path: '_vance/config/feeds/',
    pageSize: '200',
  });
  const data = await brainFetch<DocumentFolderListResponse>(
    'GET',
    `documents/folder?${params.toString()}`,
  );
  return (data.files ?? [])
    .filter((doc) => doc.kind === FEED_SOURCE_KIND)
    .map((doc: DocumentSummary) => ({
      name: doc.name,
      title: doc.title || doc.name,
      kindId: FEED_SOURCE_KIND,
      documentId: doc.id,
      projectId: doc.projectId,
      path: doc.path,
    }));
}
