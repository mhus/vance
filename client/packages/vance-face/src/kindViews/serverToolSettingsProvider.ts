import { brainFetch, RestError } from '@vance/shared';
import type {
  DocumentCreateRequest,
  DocumentDto,
  DocumentFolderListResponse,
  DocumentSummary,
  ServerToolDto,
} from '@vance/generated';
import type {
  SettingsAreaRow,
  SettingsDocRow,
  SettingsInheritedFrom,
  SettingsInheritedRow,
  SettingsProvider,
  SettingsScope,
} from '@vance/kind-registry';
import {
  MCP_SERVER_TYPE,
  SERVER_TOOL_PATH_PREFIX,
  TOOL_NAME_PATTERN,
  fieldsFromDoc,
  serializeServerToolDoc,
  serverToolDocFor,
  toolNameFromPath,
  toolPath,
} from './serverToolCodec';

export const SERVER_TOOL_KIND = 'vance-server-tool';

/**
 * Settings contribution of the server-tool kind: the server tools under
 * {@code _vance/server-tools/} (one YAML per tool, resolved by file name —
 * see {@code ServerToolLoader}). The Settings page's "Bereiche" tab shows
 * this as the Server tools area; an entry opens the ServerToolFormView
 * inline, with the MCP login and connection test.
 *
 * <p><b>Cascade.</b> Tools resolve {@code project → _tenant → classpath}.
 * The area lists the scope's own documents and, below them, what the
 * scope only reads through — tenant tools seen from a project and the
 * tools the server ships. Those come from the admin listing, which
 * resolves the cascade and stamps each tool with its tier; overriding one
 * copies its resolved configuration into the scope.
 *
 * <p>Served for the tenant and project layers only — the cascade has no
 * user layer.
 */
export const serverToolSettingsProvider: SettingsProvider = {
  titleKey: 'settings.areas.serverTools.title',
  createHintKey: 'settings.areas.serverTools.createHint',
  list: async (scope: SettingsScope): Promise<SettingsAreaRow[]> => {
    if (scope.kind === 'user') return [];
    const [owned, resolved] = await Promise.all([
      listOwnedTools(scope.projectId),
      listResolvedTools(scope.projectId),
    ]);
    const ownedNames = new Set(owned.map((row) => row.name));
    const descriptions = new Map(resolved.map((dto) => [dto.name, dto.description]));
    const inherited: SettingsInheritedRow[] = [];
    for (const dto of resolved) {
      const origin = inheritedFrom(dto.source);
      if (!origin || ownedNames.has(dto.name)) continue;
      inherited.push({
        name: dto.name,
        title: dto.name,
        description: dto.description,
        kindId: SERVER_TOOL_KIND,
        projectId: origin === 'tenant' ? TENANT_PROJECT : scope.projectId,
        inherited: origin,
      });
    }
    return [
      ...owned.map((row) => ({ ...row, description: descriptions.get(row.name) })),
      ...inherited.sort((a, b) => a.name.localeCompare(b.name)),
    ];
  },
  create: async (scope: SettingsScope, name: string): Promise<SettingsDocRow> => {
    const trimmed = await requireToolName(scope, name);
    // Starts disabled: an MCP server without its URL would fail on first
    // lookup. Fill in the server, then switch it on.
    const doc = serverToolDocFor(trimmed, {
      ...fieldsFromDoc({}),
      type: MCP_SERVER_TYPE,
      description: `What ${trimmed} offers the model.`,
      parameters: { transport: 'http', url: '' },
      enabled: false,
    });
    return createToolDocument(scope.projectId, trimmed, serializeServerToolDoc(doc));
  },
  override: async (scope: SettingsScope, row: SettingsInheritedRow): Promise<SettingsDocRow> => {
    const name = await requireToolName(scope, row.name);
    const dto = await brainFetch<ServerToolDto>(
      'GET',
      `admin/projects/${encodeURIComponent(scope.projectId)}/server-tools/${encodeURIComponent(name)}`,
    );
    const doc = serverToolDocFor(name, {
      type: dto.type,
      description: dto.description,
      parameters: dto.parameters ?? {},
      labels: dto.labels ?? [],
      enabled: dto.enabled,
      primary: dto.primary,
      disabledSubTools: dto.disabledSubTools ?? [],
      defaultDeferred: dto.defaultDeferred,
      promptHint: dto.promptHint ?? '',
    });
    return createToolDocument(scope.projectId, name, serializeServerToolDoc(doc));
  },
};

const TENANT_PROJECT = '_tenant';

/** The admin listing's tier names, as the area's inheritance origins. */
function inheritedFrom(source: string | undefined): SettingsInheritedFrom | null {
  if (source === 'TENANT') return 'tenant';
  if (source === 'BUNDLED') return 'bundled';
  return null;
}

/**
 * The validated tool name, or an error with a translated message for the
 * host to show. The i18n instance is loaded on demand: this module is
 * part of the kind registry, which loads in places that never boot i18n.
 */
async function requireToolName(scope: SettingsScope, name: string): Promise<string> {
  const trimmed = name.trim();
  if (scope.kind !== 'user' && TOOL_NAME_PATTERN.test(trimmed)) return trimmed;
  const { i18n } = await import('@/i18n');
  throw new Error(i18n.global.t(scope.kind === 'user'
    ? 'settings.areas.serverTools.errors.userScope'
    : 'settings.areas.serverTools.errors.invalidName'));
}

async function createToolDocument(projectId: string, name: string, inlineText: string): Promise<SettingsDocRow> {
  const path = toolPath(name);
  const body: DocumentCreateRequest = { path, inlineText };
  const params = new URLSearchParams({ projectId });
  const doc = await brainFetch<DocumentDto>('POST', `documents?${params.toString()}`, { body });
  return { name, title: name, kindId: SERVER_TOOL_KIND, documentId: doc.id, projectId, path };
}

/** The scope's own tool documents — exactly what it can open and delete. */
async function listOwnedTools(projectId: string): Promise<SettingsDocRow[]> {
  const params = new URLSearchParams({ projectId, path: SERVER_TOOL_PATH_PREFIX, pageSize: '200' });
  const data = await brainFetch<DocumentFolderListResponse>(
    'GET',
    `documents/folder?${params.toString()}`,
  );
  const rows: SettingsDocRow[] = [];
  for (const doc of (data.files ?? []) as DocumentSummary[]) {
    const name = toolNameFromPath(doc.path);
    if (!name) continue;
    rows.push({
      name,
      title: name,
      kindId: SERVER_TOOL_KIND,
      documentId: doc.id,
      projectId: doc.projectId,
      path: doc.path,
    });
  }
  return rows.sort((a, b) => a.name.localeCompare(b.name));
}

/**
 * The cascade-resolved tools with their tier. Needs project admin, like
 * writing a tool does; without it (403) the area shows the owned documents
 * only. Any other failure propagates — the host shows it as the area error
 * instead of silently hiding every inherited tool.
 */
async function listResolvedTools(projectId: string): Promise<ServerToolDto[]> {
  try {
    return await brainFetch<ServerToolDto[]>(
      'GET',
      `admin/projects/${encodeURIComponent(projectId)}/server-tools`,
    );
  } catch (e) {
    if (e instanceof RestError && e.status === 403) return [];
    throw e;
  }
}
