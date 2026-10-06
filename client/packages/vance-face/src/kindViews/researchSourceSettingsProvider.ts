import { brainFetch } from '@vance/shared';
import type {
  DocumentCreateRequest,
  DocumentDto,
  DocumentFolderListResponse,
  DocumentSummary,
} from '@vance/generated';
import type { SettingsDocRow, SettingsProvider, SettingsScope } from '@vance/kind-registry';
import { RESEARCH_SOURCE_KIND } from './researchSourceCodec';

/**
 * Settings contribution of the research-source kind: the Zarniwoop search
 * sources under {@code _vance/config/research/} (one YAML per instance,
 * see {@code SourceConfigPaths.RESEARCH}). The Settings page's "Bereiche"
 * tab shows this as the Research area: entry inventory, add, delete; an
 * entry opens in the kind's own form view right there (inline), not as a
 * Cortex link.
 *
 * <p>Served for the tenant and project layers only. The source-config
 * cascade is {@code project → _tenant → classpath} — it has no user layer,
 * so a user-scope listing would always be empty and asking for it would
 * just burn a request the permission layer may reject anyway.
 */
export const researchSourceSettingsProvider: SettingsProvider = {
  titleKey: 'settings.areas.research.title',
  list: async (scope: SettingsScope): Promise<SettingsDocRow[]> => {
    if (scope.kind === 'user') return [];
    const rows = await listResearchSources(scope.projectId);
    return rows;
  },
  create: async (scope: SettingsScope, name: string): Promise<SettingsDocRow> => {
    if (scope.kind === 'user') {
      throw new Error('Research sources are not available in the user scope.');
    }
    const path = `_vance/config/research/${name}.yaml`;
    const body: DocumentCreateRequest = {
      path,
      inlineText:
        '# New search source — inert until configured and enabled.\n'
        + '$meta:\n  kind: vance-research-source\n'
        + 'protocol: searxng\nenabled: false\n',
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
      kindId: RESEARCH_SOURCE_KIND,
      documentId: doc.id,
      projectId: scope.projectId,
      path,
    };
  },
};

async function listResearchSources(projectId: string): Promise<SettingsDocRow[]> {
  const params = new URLSearchParams({
    projectId,
    path: '_vance/config/research/',
    pageSize: '200',
  });
  const data = await brainFetch<DocumentFolderListResponse>(
    'GET',
    `documents/folder?${params.toString()}`,
  );
  return (data.files ?? [])
    .filter((doc) => doc.kind === RESEARCH_SOURCE_KIND)
    .map((doc: DocumentSummary) => ({
      name: doc.name,
      title: doc.title || doc.name,
      kindId: RESEARCH_SOURCE_KIND,
      documentId: doc.id,
      projectId: doc.projectId,
      path: doc.path,
    }));
}
