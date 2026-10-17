import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as yaml from 'js-yaml';

const brainFetch = vi.fn();

class RestError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
}

vi.mock('@vance/shared', () => ({
  brainFetch: (...args: unknown[]) => brainFetch(...args),
  RestError,
}));

// The provider loads the i18n instance on demand for its validation errors.
vi.mock('@/i18n', () => ({ i18n: { global: { t: (key: string) => key } } }));

const { serverToolSettingsProvider } = await import('./serverToolSettingsProvider');

const PROJECT = { kind: 'project' as const, projectId: 'acme' };
const TENANT = { kind: 'tenant' as const, projectId: '_tenant' };

function ownedDoc(name: string, projectId = 'acme'): Record<string, unknown> {
  return { id: `id-${name}`, projectId, path: `_vance/server-tools/${name}.yaml` };
}

function resolved(name: string, source: string, description = `${name} does things`): Record<string, unknown> {
  return {
    name,
    type: 'mcp_server',
    description,
    parameters: {},
    labels: [],
    enabled: true,
    primary: false,
    disabledSubTools: [],
    defaultDeferred: false,
    promptHint: '',
    projectId: 'acme',
    source,
  };
}

/** Answers the folder listing and the resolved listing by path. */
function answer(files: unknown[], tools: unknown[] | Error): void {
  brainFetch.mockImplementation((_method: string, path: string) => {
    if (path.startsWith('documents/folder')) return Promise.resolve({ files });
    if (path.endsWith('/server-tools')) {
      return tools instanceof Error ? Promise.reject(tools) : Promise.resolve(tools);
    }
    return Promise.reject(new Error(`unexpected ${path}`));
  });
}

describe('serverToolSettingsProvider.list', () => {
  beforeEach(() => {
    brainFetch.mockReset();
  });

  it('lists nothing for the user scope', async () => {
    const rows = await serverToolSettingsProvider.list({ kind: 'user', projectId: '_user_mara', login: 'mara' });
    expect(rows).toEqual([]);
    expect(brainFetch).not.toHaveBeenCalled();
  });

  it('puts the own rows first, sorted, then the inherited ones, sorted', async () => {
    answer(
      [ownedDoc('zeta'), ownedDoc('alpha')],
      [resolved('zeta', 'PROJECT'), resolved('alpha', 'PROJECT'), resolved('web', 'BUNDLED'), resolved('jira', 'TENANT')],
    );
    const rows = await serverToolSettingsProvider.list(PROJECT);
    expect(rows.map((r) => r.name)).toEqual(['alpha', 'zeta', 'jira', 'web']);
    expect(rows.map((r) => r.inherited)).toEqual([undefined, undefined, 'tenant', 'bundled']);
  });

  it('maps the source tier to the inheritance origin and the owning project', async () => {
    answer([], [resolved('jira', 'TENANT'), resolved('web', 'BUNDLED')]);
    const rows = await serverToolSettingsProvider.list(PROJECT);
    expect(rows).toEqual([
      expect.objectContaining({ name: 'jira', inherited: 'tenant', projectId: '_tenant', kindId: 'vance-server-tool' }),
      expect.objectContaining({ name: 'web', inherited: 'bundled', projectId: 'acme' }),
    ]);
  });

  it('drops resolved tools the scope owns and tools without a known tier', async () => {
    answer([ownedDoc('jira')], [resolved('jira', 'TENANT'), resolved('other', 'PROJECT'), resolved('odd', undefined as unknown as string)]);
    const rows = await serverToolSettingsProvider.list(PROJECT);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toEqual(expect.objectContaining({ name: 'jira', documentId: 'id-jira' }));
    expect(rows[0].inherited).toBeUndefined();
  });

  it('decorates own rows with the resolved description', async () => {
    answer([ownedDoc('jira')], [resolved('jira', 'PROJECT', 'Issue tracker')]);
    const rows = await serverToolSettingsProvider.list(PROJECT);
    expect(rows[0].description).toBe('Issue tracker');
  });

  it('ignores documents outside the tool tree', async () => {
    answer([ownedDoc('jira'), { id: 'x', projectId: 'acme', path: '_vance/server-tools/sub/x.yaml' }], []);
    const rows = await serverToolSettingsProvider.list(PROJECT);
    expect(rows.map((r) => r.name)).toEqual(['jira']);
  });

  it('shows the owned rows only when the resolved listing is forbidden', async () => {
    answer([ownedDoc('jira')], new RestError(403, 'forbidden'));
    const rows = await serverToolSettingsProvider.list(PROJECT);
    expect(rows.map((r) => r.name)).toEqual(['jira']);
  });

  it('propagates any other failure of the resolved listing', async () => {
    answer([ownedDoc('jira')], new RestError(500, 'boom'));
    await expect(serverToolSettingsProvider.list(PROJECT)).rejects.toThrow('boom');
  });
});

describe('serverToolSettingsProvider.override', () => {
  beforeEach(() => {
    brainFetch.mockReset();
  });

  it('copies the resolved tool into the scope as a complete document', async () => {
    brainFetch.mockImplementation((method: string, path: string) => {
      if (method === 'GET') {
        return Promise.resolve({
          ...resolved('jira', 'TENANT', 'Issue tracker'),
          parameters: { transport: 'http', url: 'https://mcp.example.com' },
          labels: ['work'],
          disabledSubTools: ['delete_issue'],
          promptHint: 'Use for tickets.',
          oauth: { status: 'CONNECTED' },
        });
      }
      if (method === 'POST' && path.startsWith('documents?')) return Promise.resolve({ id: 'new-id' });
      return Promise.reject(new Error(`unexpected ${method} ${path}`));
    });

    const row = await serverToolSettingsProvider.override!(PROJECT, {
      name: 'jira',
      title: 'jira',
      kindId: 'vance-server-tool',
      projectId: '_tenant',
      inherited: 'tenant',
    });

    expect(brainFetch).toHaveBeenCalledWith('GET', 'admin/projects/acme/server-tools/jira');
    const [, postPath, options] = brainFetch.mock.calls[1] as [string, string, { body: { path: string; inlineText: string } }];
    expect(postPath).toBe('documents?projectId=acme');
    expect(options.body.path).toBe('_vance/server-tools/jira.yaml');
    expect(yaml.load(options.body.inlineText)).toEqual({
      name: 'jira',
      type: 'mcp_server',
      description: 'Issue tracker',
      parameters: { transport: 'http', url: 'https://mcp.example.com' },
      labels: ['work'],
      enabled: true,
      primary: false,
      disabledSubTools: ['delete_issue'],
      defaultDeferred: false,
      promptHint: 'Use for tickets.',
    });
    expect(row).toEqual({
      name: 'jira',
      title: 'jira',
      kindId: 'vance-server-tool',
      documentId: 'new-id',
      projectId: 'acme',
      path: '_vance/server-tools/jira.yaml',
    });
  });

  it('writes a tenant override into _tenant', async () => {
    brainFetch.mockImplementation((method: string) =>
      Promise.resolve(method === 'GET' ? resolved('web', 'BUNDLED') : { id: 'new-id' }));
    const row = await serverToolSettingsProvider.override!(TENANT, {
      name: 'web',
      title: 'web',
      kindId: 'vance-server-tool',
      projectId: '_tenant',
      inherited: 'bundled',
    });
    expect(brainFetch.mock.calls[0][1]).toBe('admin/projects/_tenant/server-tools/web');
    expect(brainFetch.mock.calls[1][1]).toBe('documents?projectId=_tenant');
    expect(row.projectId).toBe('_tenant');
  });
});

describe('serverToolSettingsProvider.create', () => {
  beforeEach(() => {
    brainFetch.mockReset();
  });

  it('rejects a name that is not snake_case with a translated message', async () => {
    await expect(serverToolSettingsProvider.create!(PROJECT, 'Bad Name'))
      .rejects.toThrow('settings.areas.serverTools.errors.invalidName');
    expect(brainFetch).not.toHaveBeenCalled();
  });

  it('rejects the user scope with a translated message', async () => {
    await expect(serverToolSettingsProvider.create!({ kind: 'user', projectId: '_user_mara' }, 'jira'))
      .rejects.toThrow('settings.areas.serverTools.errors.userScope');
  });

  it('creates a disabled MCP server document under the trimmed name', async () => {
    brainFetch.mockResolvedValue({ id: 'new-id' });
    const row = await serverToolSettingsProvider.create!(PROJECT, '  jira ');
    const [, , options] = brainFetch.mock.calls[0] as [string, string, { body: { inlineText: string } }];
    const doc = yaml.load(options.body.inlineText) as Record<string, unknown>;
    expect(doc).toEqual(expect.objectContaining({ name: 'jira', type: 'mcp_server', enabled: false }));
    expect(row.documentId).toBe('new-id');
  });
});
