import { afterEach, describe, expect, it, vi } from 'vitest';
import { DocumentAccess } from './api';

/**
 * The listing and the reading have to agree on what a path is.
 *
 * <p>This is not a hypothetical: the first browser run of `documents.list`
 * asked for `apps/demo/apps/demo/invoices/acme-corp.yaml`. Paths a program
 * writes are relative to the **app folder**; a listing comes back from the
 * server as a **project** path. Handing that back bare resolved it a second
 * time. Both tests below fail against that version.
 */

vi.mock('@vance/shared', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@vance/shared');
  return { ...actual, brainFetch: vi.fn() };
});

const { brainFetch } = await import('@vance/shared');
const fetchMock = brainFetch as unknown as ReturnType<typeof vi.fn>;

afterEach(() => {
  fetchMock.mockReset();
});

describe('documents.list', () => {
  it('returns a path a program can hand straight to read', async () => {
    fetchMock.mockResolvedValue({
      files: [{ id: '1', path: 'apps/demo/invoices/acme-corp.yaml', mimeType: 'application/yaml' }],
    });

    const entries = await new DocumentAccess('test1').list('apps/demo/invoices/');

    // A leading slash is the grammar's "from the project root" — which is
    // exactly what the server just told us this document is.
    expect(entries[0].path).toBe('/apps/demo/invoices/acme-corp.yaml');
    expect(entries[0].key).toBe('acme-corp');
  });

  /**
   * A query parameterises the *content* of a mounted document. Forwarding it to
   * a folder listing would be a question nobody answers; dropping it would
   * return the whole folder wearing the shape of a filtered one.
   */
  it('refuses a query instead of quietly dropping it', async () => {
    await expect(new DocumentAccess('test1').list('invoices/?from=2026-01-01')).rejects.toThrow(
      /carries a query/,
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
