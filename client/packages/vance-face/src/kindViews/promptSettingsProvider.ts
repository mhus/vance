import { brainFetch } from '@vance/shared';
import type {
  DocumentCreateRequest,
  DocumentDto,
  DocumentFolderListResponse,
  DocumentSummary,
} from '@vance/generated';
import type { SettingsDocRow, SettingsProvider, SettingsScope } from '@vance/kind-registry';

/**
 * Settings contribution of the engine-prompt kind: the tiered prompt
 * overrides under {@code _vance/prompts/} (one Markdown file per engine
 * prompt, e.g. {@code arthur-prompt.md} plus the Arthur plan-mode variants —
 * see {@code EnginePromptResolver}). The Settings page's "Bereiche" tab shows
 * this as the Prompts area: which overrides exist in this scope, add,
 * delete; an entry opens in the raw Markdown editor right there (inline).
 *
 * <p><b>Raw text, like the recipe and theme areas.</b> A prompt is a Pebble
 * template in Markdown clothing, rendered per turn — anything but a text
 * editor would be a prompt designer the product does not have.
 *
 * <p><b>Create is the override workflow.</b> The bundled prompts live on the
 * classpath; what this area inventories are the documents that shadow them
 * by name. A new entry with an engine's prompt name ({@code arthur-prompt},
 * {@code eddie-prompt}, a plan-mode variant) takes effect for every spawn of
 * the scope from the next turn — the hint says so. The seed carries the
 * naming convention as a comment; the server's {@code PromptDocKindHandler}
 * validates that the template compiles (a syntax error fails the turn, not
 * a fallback).
 *
 * <p><b>Flat, location-based inventory</b>: the resolver looks documents up by
 * exact path under the prefix, so a single-level folder listing shows the
 * override candidates. Served for the tenant and project layers — those are
 * the two document cascade layers the resolver walks
 * (project → {@code _vance} → classpath).
 */
export const promptSettingsProvider: SettingsProvider = {
  titleKey: 'settings.areas.prompts.title',
  createHintKey: 'settings.areas.prompts.createHint',
  list: async (scope: SettingsScope): Promise<SettingsDocRow[]> => {
    if (scope.kind === 'user') return [];
    return listPromptDocs(scope.projectId);
  },
  create: async (scope: SettingsScope, name: string): Promise<SettingsDocRow> => {
    if (scope.kind === 'user') {
      throw new Error('Prompt overrides are not available in the user scope.');
    }
    const trimmed = name.trim().replace(/\.md$/, '');
    if (!trimmed) throw new Error('A name is required.');
    const path = `_vance/prompts/${trimmed}.md`;
    const body: DocumentCreateRequest = {
      path,
      inlineText:
        `<!-- ${trimmed} — engine prompt override. Same name as the bundled\n`
        + '     prompt (EnginePromptResolver): this document shadows it for every\n'
        + '     spawn of this scope. The body is a Pebble template rendered per\n'
        + '     turn (prompts-and-manuals.md). Empty the file to fall back to the\n'
        + '     bundled prompt. -->\n'
        + 'You are …\n',
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
      kindId: 'vance-prompt',
      documentId: doc.id,
      projectId: scope.projectId,
      path,
    };
  },
};

async function listPromptDocs(projectId: string): Promise<SettingsDocRow[]> {
  const params = new URLSearchParams({
    projectId,
    path: '_vance/prompts/',
    pageSize: '200',
  });
  const data = await brainFetch<DocumentFolderListResponse>(
    'GET',
    `documents/folder?${params.toString()}`,
  );
  return (data.files ?? [])
    .filter((doc) => (doc.path ?? '').endsWith('.md'))
    .map((doc: DocumentSummary) => {
      const stem = doc.path!.substring('_vance/prompts/'.length).replace(/\.md$/, '');
      return {
        name: stem,
        title: doc.title || stem,
        kindId: 'vance-prompt',
        documentId: doc.id,
        projectId: doc.projectId,
        path: doc.path,
      };
    });
}
