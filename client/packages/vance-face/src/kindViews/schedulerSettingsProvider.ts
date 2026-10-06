import { brainFetch } from '@vance/shared';
import type {
  DocumentCreateRequest,
  DocumentDto,
  DocumentFolderListResponse,
  DocumentSummary,
} from '@vance/generated';
import type { SettingsDocRow, SettingsProvider, SettingsScope } from '@vance/kind-registry';

/**
 * Settings contribution of the scheduler kind: the Ursa scheduler
 * definitions under {@code _vance/scheduler/} (one YAML per scheduler,
 * resolved by filename — see {@code UrsaSchedulerLoader}). The Settings
 * page's "Bereiche" tab shows this as the Scheduler area — the
 * configuration inventory; the operational side (manual fire, run events)
 * stays in the Insights scheduler tab, which reads the same documents.
 *
 * <p><b>Typed view.</b> The {@code vance-scheduler} kind carries the
 * SchedulerFormView + codec, so an entry opens in its own form right here —
 * the cron the form cannot express degrades to a raw input, same contract
 * as in the Cortex document tab. Server truth: {@code UrsaSchedulerLoader}
 * (exactly one of recipe/workflow/script, exactly one of cron/at) and
 * {@code UrsaSchedulerKindHandler} for the edit-time findings.
 *
 * <p>Served for the tenant and project layers only — the loader cascade
 * (project → {@code _vance}) has no user layer.
 */
export const schedulerSettingsProvider: SettingsProvider = {
  titleKey: 'settings.areas.schedulers.title',
  createHintKey: 'settings.areas.schedulers.createHint',
  list: async (scope: SettingsScope): Promise<SettingsDocRow[]> => {
    if (scope.kind === 'user') return [];
    return listSchedulers(scope.projectId);
  },
  create: async (scope: SettingsScope, name: string): Promise<SettingsDocRow> => {
    if (scope.kind === 'user') {
      throw new Error('Schedulers are not available in the user scope.');
    }
    const trimmed = name.trim();
    if (!trimmed) throw new Error('A name is required.');
    const path = `_vance/scheduler/${trimmed}.yaml`;
    const body: DocumentCreateRequest = {
      path,
      // The loader's two hard rules: exactly one trigger target
      // (recipe/workflow/script) and exactly one trigger (cron or at).
      inlineText:
        '# New scheduler — the loader demands exactly one target (recipe, workflow or\n'
        + '# script) and exactly one trigger (cron or at).\n'
        + '$meta:\n  kind: vance-scheduler\n'
        + `description: What ${trimmed} fires.\n`
        + 'recipe: arthur\ncron: "0 9 * * *"\nenabled: true\n',
    };
    const params = new URLSearchParams({ projectId: scope.projectId });
    const doc = await brainFetch<DocumentDto>(
      'POST',
      `documents?${params.toString()}`,
      { body },
    );
    return {
      name: trimmed,
      title: trimmed,
      kindId: 'vance-scheduler',
      documentId: doc.id,
      projectId: scope.projectId,
      path,
    };
  },
};

async function listSchedulers(projectId: string): Promise<SettingsDocRow[]> {
  const params = new URLSearchParams({
    projectId,
    path: '_vance/scheduler/',
    pageSize: '200',
  });
  const data = await brainFetch<DocumentFolderListResponse>(
    'GET',
    `documents/folder?${params.toString()}`,
  );
  return (data.files ?? [])
    .filter((doc) => (doc.path ?? '').endsWith('.yaml'))
    .map((doc: DocumentSummary) => {
      const stem = doc.path!.substring('_vance/scheduler/'.length).replace(/\.yaml$/, '');
      return {
        name: stem,
        title: doc.title || stem,
        kindId: 'vance-scheduler',
        documentId: doc.id,
        projectId: doc.projectId,
        path: doc.path,
      };
    });
}
