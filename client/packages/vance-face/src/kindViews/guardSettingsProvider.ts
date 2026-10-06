import { brainFetch } from '@vance/shared';
import type {
  DocumentCreateRequest,
  DocumentDto,
  DocumentFolderListResponse,
  DocumentSummary,
} from '@vance/generated';
import type { SettingsDocRow, SettingsProvider, SettingsScope } from '@vance/kind-registry';

/**
 * Settings contribution of the guard kind: the reusable Shooty guard
 * scripts under {@code _vance/guards/} — the library recipes reference by
 * cascade path from their {@code guard:} block (see
 * {@code GuardConfig}). The Settings page's "Bereiche" tab shows this as
 * the Guards area: the script library, not the wiring (which recipe
 * fires which guard at which point stays in the recipe).
 *
 * <p><b>Raw text, deliberately.</b> A guard script is imperative JS over
 * the {@code vance.guard.*} surface — there is nothing to parse into a
 * form. The kind registers no view and no codec, so the settings host
 * falls back to its raw editor (JavaScript via the mime type).
 *
 * <p><b>Location-based inventory</b>: recipes cite the path verbatim,
 * nothing looks at kind markers. Server truth: the guard points —
 * STOP/TERMINATE and START fail-open (a script error is absorbed),
 * COMMAND fails closed — and {@code GuardDocKindHandler}, which
 * delegates to the parse-only GraalJS validator: a finding is exactly
 * what the engine would refuse to evaluate. Top-level {@code return} is
 * the trap the bundled {@code llm-judge.js} shipped with — GraalJS
 * rejects it, the yield points swallow it, the guard silently never
 * fires. The seed says so in a comment.
 */
export const guardSettingsProvider: SettingsProvider = {
  titleKey: 'settings.areas.guards.title',
  createHintKey: 'settings.areas.guards.createHint',
  list: async (scope: SettingsScope): Promise<SettingsDocRow[]> => {
    if (scope.kind === 'user') return [];
    return listGuards(scope.projectId);
  },
  create: async (scope: SettingsScope, name: string): Promise<SettingsDocRow> => {
    if (scope.kind === 'user') {
      throw new Error('Guard scripts are not available in the user scope.');
    }
    const trimmed = name.trim();
    if (!trimmed) throw new Error('A name is required.');
    const path = `_vance/guards/${trimmed}.js`;
    const body: DocumentCreateRequest = {
      path,
      // Minimal valid guard body: no top-level return (GraalJS rejects it
      // as a statement — fail-open points would swallow the SyntaxError),
      // the surface documented in comments.
      inlineText:
        '// New guard script — wired from a recipe `guard:` block by cascade path:\n'
        + '//   guard:\n'
        + `//     - script: ${path}\n`
        + '//       trigger: stop   # stop | terminate | start | command\n'
        + '//       params: { ... } # readable as vance.params.*\n'
        + '//\n'
        + '// Points: stop/terminate/start fail-open, command fails closed.\n'
        + '// Surface: vance.guard.continueWith(prompt) — stop/terminate;\n'
        + '//          vance.guard.activateSkill(name) — any point;\n'
        + '//          vance.guard.setTurnPrompt(text) — start only;\n'
        + '//          vance.guard.deny(reason) — command only.\n'
        + '// No top-level `return` — use if/else; returning nothing = pass.\n'
        + 'const done = vance.guard.output.indexOf("DONE") >= 0;\n'
        + 'if (!done) {\n'
        + '  vance.guard.continueWith("Finish and say DONE.");\n'
        + '}\n',
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
      kindId: 'vance-guard',
      documentId: doc.id,
      projectId: scope.projectId,
      path,
    };
  },
};

async function listGuards(projectId: string): Promise<SettingsDocRow[]> {
  const params = new URLSearchParams({
    projectId,
    path: '_vance/guards/',
    pageSize: '200',
  });
  const data = await brainFetch<DocumentFolderListResponse>(
    'GET',
    `documents/folder?${params.toString()}`,
  );
  return (data.files ?? [])
    .filter((doc) => (doc.path ?? '').endsWith('.js'))
    .map((doc: DocumentSummary) => {
      const stem = doc.path!.substring('_vance/guards/'.length).replace(/\.js$/, '');
      return {
        name: stem,
        title: doc.title || stem,
        kindId: 'vance-guard',
        documentId: doc.id,
        projectId: doc.projectId,
        path: doc.path,
      };
    });
}
