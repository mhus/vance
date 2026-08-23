import { beforeEach, describe, expect, it, vi } from 'vitest';

const brainFetch = vi.fn();

class RestError extends Error {
  constructor(
    public readonly status: number,
    public readonly path: string,
    message: string,
  ) {
    super(message);
    this.name = 'RestError';
  }
}

vi.mock('@vance/shared', () => ({
  brainFetch: (...args: unknown[]) => brainFetch(...args),
  RestError,
}));

const { resolveDocumentIdByPath } = await import('./resolveDocumentPath');

describe('resolveDocumentIdByPath', () => {
  beforeEach(() => {
    brainFetch.mockReset();
  });

  it('answers from the loaded page without asking the server', async () => {
    const id = await resolveDocumentIdByPath('proj', 'notes/a.md', [
      { id: 'doc-1', path: 'notes/a.md' },
    ]);

    expect(id).toBe('doc-1');
    expect(brainFetch).not.toHaveBeenCalled();
  });

  it('resolves a document beyond the first page of the file list', async () => {
    // The regression: the loaded list is one page of 500, so a miss there is
    // not proof of absence. Anything past it has to come from the server.
    brainFetch.mockResolvedValue({ id: 'doc-501', path: 'notes/z.md' });

    const id = await resolveDocumentIdByPath('proj', 'notes/z.md', [
      { id: 'doc-1', path: 'notes/a.md' },
    ]);

    expect(id).toBe('doc-501');
    expect(brainFetch).toHaveBeenCalledWith(
      'GET',
      'documents/by-path?projectId=proj&path=notes%2Fz.md',
    );
  });

  it('reports absence only on 404', async () => {
    brainFetch.mockRejectedValue(new RestError(404, 'documents/by-path', 'Not Found'));

    await expect(resolveDocumentIdByPath('proj', 'gone.md')).resolves.toBeNull();
  });

  it('rethrows an unreachable server instead of claiming the document is gone', async () => {
    brainFetch.mockRejectedValue(new RestError(503, 'documents/by-path', 'Service Unavailable'));

    await expect(resolveDocumentIdByPath('proj', 'notes/a.md')).rejects.toThrow(
      'Service Unavailable',
    );
  });
});
