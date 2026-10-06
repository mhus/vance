import { brainFetch } from '@vance/shared';
import type {
  DocumentCreateRequest,
  DocumentDto,
  DocumentFolderListResponse,
  DocumentSummary,
} from '@vance/generated';
import type { SettingsDocRow, SettingsProvider, SettingsScope } from '@vance/kind-registry';

/**
 * Settings contribution of the workflow kind: the Magrathea workflow
 * definitions under {@code _vance/workflows/} (one YAML per workflow,
 * resolved by filename — see {@code MagratheaWorkflowLoader}). The
 * Settings page's "Bereiche" tab shows this as the Workflows area: the
 * first management surface workflows have — the Magrathea admin tab
 * inspects <em>runs</em>, not definitions.
 *
 * <p><b>Typed view, read-only by design.</b> The {@code vance-workflow}
 * kind registers the WorkflowFlowView (parse/serialize are identity —
 * the model is the YAML text), so an entry opens with its flow graph
 * rendered right here. The graph view never emits {@code update:doc}:
 * workflow YAML (states, transitions, parameters, bounds) is authored as
 * text — the flow graph is the map, not the road. The settings host falls
 * back to its raw editor whenever the typed view has no codec contract.
 *
 * <p><b>Location-based inventory</b>, like the recipe area: the loader
 * scans the path and never looks at kind markers. Server truth:
 * {@code MagratheaWorkflowLoader} ('start' must match a state, at least
 * one state, transition targets must exist) and
 * {@code WorkflowKindHandler} for the edit-time findings.
 *
 * <p>Served for the tenant and project layers only — the workflow
 * cascade (project → {@code _vance}) has no user layer.
 */
export const workflowSettingsProvider: SettingsProvider = {
  titleKey: 'settings.areas.workflows.title',
  createHintKey: 'settings.areas.workflows.createHint',
  list: async (scope: SettingsScope): Promise<SettingsDocRow[]> => {
    if (scope.kind === 'user') return [];
    return listWorkflows(scope.projectId);
  },
  create: async (scope: SettingsScope, name: string): Promise<SettingsDocRow> => {
    if (scope.kind === 'user') {
      throw new Error('Workflows are not available in the user scope.');
    }
    const trimmed = name.trim();
    if (!trimmed) throw new Error('A name is required.');
    const path = `_vance/workflows/${trimmed}.yaml`;
    const body: DocumentCreateRequest = {
      path,
      // Minimal body the loader accepts: 'start' plus one state. A state
      // without 'transitions:' is terminal, so this seed is a complete
      // single-state workflow until states grow.
      inlineText:
        '# New workflow — the loader needs start + at least one state; a state without\n'
        + '# transitions: is terminal. See specification/public/workflows.md.\n'
        + '$meta:\n  kind: vance-workflow\n'
        + 'description: |\n  What this workflow does.\n'
        + 'version: "1"\n'
        + 'start: work\n'
        + 'states:\n'
        + '  work:\n'
        + '    type: agent_task\n'
        + '    recipe: ford\n'
        + '    params:\n'
        + '      prompt: |\n'
        + '        Do the thing. This seed is the whole workflow until you add states.\n',
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
      kindId: 'vance-workflow',
      documentId: doc.id,
      projectId: scope.projectId,
      path,
    };
  },
};

async function listWorkflows(projectId: string): Promise<SettingsDocRow[]> {
  const params = new URLSearchParams({
    projectId,
    path: '_vance/workflows/',
    pageSize: '200',
  });
  const data = await brainFetch<DocumentFolderListResponse>(
    'GET',
    `documents/folder?${params.toString()}`,
  );
  return (data.files ?? [])
    .filter((doc) => (doc.path ?? '').endsWith('.yaml'))
    .map((doc: DocumentSummary) => {
      const stem = doc.path!.substring('_vance/workflows/'.length).replace(/\.yaml$/, '');
      return {
        name: stem,
        title: doc.title || stem,
        kindId: 'vance-workflow',
        documentId: doc.id,
        projectId: doc.projectId,
        path: doc.path,
      };
    });
}
