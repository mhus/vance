import { brainFetch } from '@vance/shared';
import { cortexDeepLink } from '@vance/components';
import type { SettingsDocRow, SettingsProvider, SettingsScope } from '@vance/kind-registry';
import type { DocumentFolderListResponse, DocumentSummary } from '@vance/generated';
import { RESEARCH_SOURCE_KIND } from './researchSourceCodec';

/**
 * Settings contribution of the research-source kind: the Zarniwoop search
 * sources under {@code _vance/config/research/} (one YAML per instance,
 * see {@code SourceConfigPaths.RESEARCH}). The Settings panel shows this
 * inventory next to the Setting Forms; a row opens the source in its normal
 * document editor (Cortex deep link) — no settings-specific renderer.
 *
 * <p>Served for the tenant and project layers only. The source-config
 * cascade is {@code project → _tenant → classpath} — it has no user layer,
 * so a user-scope listing would always be empty and asking for it would
 * just burn a request the permission layer may reject anyway.
 */
export const researchSourceSettingsProvider: SettingsProvider = {
  category: 'research',
  list: async (scope: SettingsScope): Promise<SettingsDocRow[]> => {
    if (scope.kind === 'user') return [];
    const params = new URLSearchParams({
      projectId: scope.projectId,
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
        category: 'research',
        kindId: RESEARCH_SOURCE_KIND,
        href: cortexDeepLink({ project: scope.projectId, documentId: doc.id }),
      }));
  },
};
