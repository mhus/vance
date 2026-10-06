import { brainFetch } from '@vance/shared';
import type {
  DocumentCreateRequest,
  DocumentDto,
  DocumentFolderListResponse,
  DocumentSummary,
} from '@vance/generated';
import type { SettingsDocRow, SettingsProvider, SettingsScope } from '@vance/kind-registry';

/**
 * Settings contribution of the wizard kind: the wizard definitions under
 * {@code _vance/wizards/} (one YAML per wizard, resolved by filename —
 * see {@code WizardLoader}, spec {@code specification/wizards.md}). The
 * Settings page's "Bereiche" tab shows this as the Wizards area.
 *
 * <p><b>The first area with a user layer.</b> The wizard cascade is
 * {@code project → _user → _vance → classpath} — and the user scope's
 * {@code projectId} is exactly the loader's {@code _user_<login>} tier.
 * The provider therefore serves all three scopes unchanged: the same
 * folder listing and create call write to whichever tier the panel has
 * selected. A wizard saved in the user scope shadows the tenant's
 * same-name wizard, the same way a project one does.
 *
 * <p><b>Raw text, deliberately.</b> A wizard body is localized
 * title/description, a field list (the shared form-field grammar),
 * a Pebble {@code promptTemplate} and optional
 * {@code validatorPrompt}/follow-ups — richer than any single form
 * should own. The kind registers no view and no codec; the settings host
 * falls back to its raw YAML editor.
 *
 * <p><b>Location-based inventory</b>: the loader scans the path and never
 * looks at kind markers. Server truth: {@link WizardLoader} — a wizard
 * whose YAML does not parse is skipped with a WARN and silently
 * disappears from every surface — and {@code WizardDocKindHandler},
 * which runs the same parse: a finding means exactly what the loader
 * does.
 */
export const wizardSettingsProvider: SettingsProvider = {
  titleKey: 'settings.areas.wizards.title',
  createHintKey: 'settings.areas.wizards.createHint',
  list: (scope: SettingsScope) => listWizards(scope.projectId),
  create: async (scope: SettingsScope, name: string): Promise<SettingsDocRow> => {
    const trimmed = name.trim();
    if (!trimmed) throw new Error('A name is required.');
    const path = `_vance/wizards/${trimmed}.yaml`;
    const body: DocumentCreateRequest = {
      path,
      // Minimal body the loader accepts: localized title + description,
      // at least one field, a Pebble promptTemplate. The name is the
      // filename — same-name shadows the outer cascade tiers.
      inlineText:
        '# New wizard — the loader needs title, description, at least one field\n'
        + '# and a promptTemplate; same name in an inner cascade tier shadows\n'
        + '# this one. See specification/wizards.md.\n'
        + '$meta:\n  kind: vance-wizard\n'
        + 'title:\n'
        + `  de: "${trimmed}"\n`
        + `  en: "${trimmed}"\n`
        + 'description: |\n'
        + '  What this wizard does — one sentence.\n'
        + 'icon: wand\n'
        + 'category: general\n'
        + 'fields:\n'
        + '  - name: subject\n'
        + '    type: string\n'
        + '    required: true\n'
        + '    label:\n'
        + '      de: "Thema"\n'
        + '      en: "Subject"\n'
        + '    help:\n'
        + '      de: "Kurz beschreiben, worum es geht."\n'
        + '      en: "Briefly describe what this is about."\n'
        + 'promptTemplate: |\n'
        + '  Do something useful with: {{ subject }}\n',
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
      kindId: 'vance-wizard',
      documentId: doc.id,
      projectId: scope.projectId,
      path,
    };
  },
};

async function listWizards(projectId: string): Promise<SettingsDocRow[]> {
  const params = new URLSearchParams({
    projectId,
    path: '_vance/wizards/',
    pageSize: '200',
  });
  const data = await brainFetch<DocumentFolderListResponse>(
    'GET',
    `documents/folder?${params.toString()}`,
  );
  return (data.files ?? [])
    .filter((doc) => (doc.path ?? '').endsWith('.yaml'))
    .map((doc: DocumentSummary) => {
      const stem = doc.path!.substring('_vance/wizards/'.length).replace(/\.yaml$/, '');
      return {
        name: stem,
        title: doc.title || stem,
        kindId: 'vance-wizard',
        documentId: doc.id,
        projectId: doc.projectId,
        path: doc.path,
      };
    });
}
