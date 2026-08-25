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
  return {
    ...actual,
    brainFetch: vi.fn(),
    brainFetchTextWithMeta: vi.fn(),
    brainSendRawWithMeta: vi.fn(),
  };
});

const shared = await import('@vance/shared');
const fetchMock = shared.brainFetch as unknown as ReturnType<typeof vi.fn>;
const textMock = shared.brainFetchTextWithMeta as unknown as ReturnType<typeof vi.fn>;
const sendMock = shared.brainSendRawWithMeta as unknown as ReturnType<typeof vi.fn>;

/** A response whose only interesting part is the ETag the version memory reads. */
function res(etag: string | null): Response {
  return { headers: new Headers(etag ? { ETag: etag } : {}) } as unknown as Response;
}

afterEach(() => {
  fetchMock.mockReset();
  textMock.mockReset();
  sendMock.mockReset();
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

/**
 * The four data kinds store their body in a markdown CSV-light grammar, so
 * reading one by mime type hands the program a wall of text. The host knows the
 * kind and decodes — which is what lets a program edit the same documents the
 * built-in editors and the `records_*` tools edit.
 */
describe('kind codecs', () => {
  // The real on-disk grammar: a front-matter header naming the schema, then one
  // bullet per row with comma-separated values. Not a pipe table.
  const RECORDS = '---\nkind: records\nschema: customer, amount\n---\n- Acme, 1250\n';

  it('reads a records document as a structure, not as markdown', async () => {
    fetchMock.mockResolvedValue({ id: '1', mimeType: 'text/markdown', kind: 'records' });
    textMock.mockResolvedValue({ text: RECORDS, response: res('"v1"') });

    const doc = (await new DocumentAccess('p').read('invoices.md')) as {
      schema: string[];
      items: { values: Record<string, string> }[];
    };

    expect(doc.schema).toEqual(['customer', 'amount']);
    expect(doc.items).toHaveLength(1);
    expect(doc.items[0].values).toMatchObject({ customer: 'Acme', amount: '1250' });
  });

  /**
   * Read → change → write has to come back out in the document's own grammar.
   * Falling through to YAML here would replace a table the built-in editor can
   * open with a mapping it cannot.
   */
  it('writes a records document back in its own format', async () => {
    fetchMock.mockResolvedValue({ id: '1', mimeType: 'text/markdown', kind: 'records' });
    textMock.mockResolvedValue({ text: RECORDS, response: res('"v1"') });
    sendMock.mockResolvedValue({ data: null, response: res('"v2"') });

    const docs = new DocumentAccess('p');
    const doc = (await docs.read('invoices.md')) as {
      items: { values: Record<string, string> }[];
    };
    doc.items.push({ values: { customer: 'Second', amount: '77' } } as never);
    await docs.write('invoices.md', doc);

    const body = sendMock.mock.calls[0][2] as string;
    expect(body).toBe(
      '---\nkind: records\nschema: customer, amount\n---\n- Acme, 1250\n- Second, 77\n',
    );
  });

  /**
   * A header claiming a kind its body does not match is a real state — somebody
   * hand-edited it. The useful answer is the raw text the author can look at,
   * not an error where the data should be.
   */
  it('falls back to the mime path when the body does not match the kind', async () => {
    fetchMock.mockResolvedValue({ id: '1', mimeType: 'text/plain', kind: 'sheet' });
    textMock.mockResolvedValue({ text: 'not a sheet at all', response: res(null) });

    await expect(new DocumentAccess('p').read('x.md')).resolves.toBe('not a sheet at all');
  });

  /**
   * The program said exactly what it wanted on disk. Re-serialising a string
   * through the codec would be the host overruling that.
   */
  it('writes a string verbatim even for a kind document', async () => {
    fetchMock.mockResolvedValue({ id: '1', mimeType: 'text/markdown', kind: 'records' });
    sendMock.mockResolvedValue({ data: null, response: res('"v2"') });

    await new DocumentAccess('p').write('invoices.md', '---\nkind: records\nschema: a\n---\n');

    expect(sendMock.mock.calls[0][2]).toBe('---\nkind: records\nschema: a\n---\n');
  });
});
