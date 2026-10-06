import { brainFetch } from '@vance/shared';
import type {
  DocumentCreateRequest,
  DocumentDto,
  DocumentFolderListResponse,
  DocumentSummary,
} from '@vance/generated';
import type { SettingsDocRow, SettingsProvider, SettingsScope } from '@vance/kind-registry';

/**
 * Settings contributions of the two CSS theme kinds — chat themes
 * (`_vance/chat-themes/`, spec {@code chat-themes.md}: browser CSS over the
 * web-chat transcript, dark mode, fail-open) and report themes
 * (`_vance/report-themes/`, spec {@code report-themes.md}: the
 * openhtmltopdf print subset, layered after the default theme). One
 * factory, because the mechanics are identical in both systems — one named
 * CSS file per theme, flat folder, {@code [a-z0-9-]+} names, first-match
 * cascade — and only the vocabulary differs.
 *
 * <p><b>Raw text, like the recipe area.</b> The kind entries register no
 * view and no codec; the settings host falls back to its CodeEditor, which
 * highlights CSS through the document's mime type. A theme is authored CSS —
 * anything else would be a style editor the product does not have.
 *
 * <p><b>No server-side KindHandler, deliberately.</b> Both pipelines are
 * fail-open by design — an invalid or missing theme falls back to
 * {@code default.css} plus WARN (chat) or is skipped (report); there is no
 * finding a validator could report that the runtime would not already
 * absorb gracefully. The name grammar is enforced at create, matching the
 * resolvers' regex. Theme files carry no kind marker — CSS has no place
 * for one — so the kind identity lives in the area rows, which assign it
 * by location; the documents themselves stay plain CSS everywhere.
 *
 * <p><b>Location-based inventory</b>, like the model and recipe areas: the
 * resolvers scan the folder by name and never look at kind markers. Served
 * for the tenant and project layers only — both cascades
 * (project → {@code _tenant} → classpath) have no user layer.
 */
interface CssThemeConfig {
  /** Kind id of the theme documents (also the {@code $meta.kind} marker). */
  kindId: string;
  /** i18n key for the area title. */
  titleKey: string;
  /** i18n key for the add-dialog hint. */
  createHintKey: string;
  /** Document folder, with trailing slash ({@code _vance/chat-themes/}). */
  folder: string;
  /** Seeded body of a new theme — the vocabulary comment differs per system. */
  seed: (name: string) => string;
}

const NAME_PATTERN = /^[a-z0-9-]+$/;

function cssThemeSettingsProvider(config: CssThemeConfig): SettingsProvider {
  return {
    titleKey: config.titleKey,
    createHintKey: config.createHintKey,
    list: async (scope: SettingsScope): Promise<SettingsDocRow[]> => {
      if (scope.kind === 'user') return [];
      return listThemeFiles(scope.projectId, config);
    },
    create: async (scope: SettingsScope, name: string): Promise<SettingsDocRow> => {
      if (scope.kind === 'user') {
        throw new Error('Themes are not available in the user scope.');
      }
      const trimmed = name.trim();
      if (!NAME_PATTERN.test(trimmed)) {
        throw new Error(`theme names are [a-z0-9-] — '${trimmed}' does not match`);
      }
      const path = `${config.folder}${trimmed}.css`;
      const body: DocumentCreateRequest = {
        path,
        inlineText: config.seed(trimmed),
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
        kindId: config.kindId,
        documentId: doc.id,
        projectId: scope.projectId,
        path,
      };
    },
  };
}

async function listThemeFiles(
  projectId: string,
  config: CssThemeConfig,
): Promise<SettingsDocRow[]> {
  const params = new URLSearchParams({
    projectId,
    path: config.folder,
    pageSize: '200',
  });
  const data = await brainFetch<DocumentFolderListResponse>(
    'GET',
    `documents/folder?${params.toString()}`,
  );
  return (data.files ?? [])
    .filter((doc) => (doc.path ?? '').endsWith('.css'))
    .map((doc: DocumentSummary) => {
      const stem = doc.path!.substring(config.folder.length).replace(/\.css$/, '');
      return {
        name: stem,
        title: doc.title || stem,
        kindId: config.kindId,
        documentId: doc.id,
        projectId: doc.projectId,
        path: doc.path,
      };
    });
}

/** Area for the web-chat transcript themes ({@code _vance/chat-themes/}). */
export const chatThemeSettingsProvider = cssThemeSettingsProvider({
  kindId: 'vance-chat-theme',
  titleKey: 'settings.areas.chatThemes.title',
  createHintKey: 'settings.areas.chatThemes.createHint',
  folder: '_vance/chat-themes/',
  seed: (name) =>
    `/* ${name} — chat theme: styles the web-chat transcript of a session.
 * Chosen by a recipe's webTheme field; scoping and dark-mode handling are
 * the server pipeline's business (chat-themes.md). Invalid CSS falls back
 * to default.css — fail-open, like the whole pipeline.
 */

/* Example accent — delete and write your own. */
.vance-transcript .message-role-assistant {
    border-left: 3px solid #7aa2c4;
}
`,
});

/** Area for the PDF report themes ({@code _vance/report-themes/}). */
export const reportThemeSettingsProvider = cssThemeSettingsProvider({
  kindId: 'vance-report-theme',
  titleKey: 'settings.areas.reportThemes.title',
  createHintKey: 'settings.areas.reportThemes.createHint',
  folder: '_vance/report-themes/',
  seed: (name) =>
    `/* ${name} — report theme: styles the PDF export of a markdown report.
 * Chosen by the theme frontmatter key; loads AFTER the default theme, so
 * any rule here wins the CSS cascade (report-themes.md). Openhtmltopdf is
 * an XHTML renderer, not a browser — stay in its subset and keep angle
 * brackets out of CSS comments, they break the XML parser.
 */

/* Example accent — delete and write your own. */
pre {
    border: 1px solid #c9a227;
    border-radius: 8px;
}
`,
});
