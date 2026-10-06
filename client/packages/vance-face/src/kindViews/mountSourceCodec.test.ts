import { describe, expect, it } from 'vitest';

import {
  MOUNT_SOURCE_KIND,
  MountSourceParseError,
  applyForm,
  formFromDoc,
  parseMountSourceDoc,
  preservedKeyCount,
  serializeMountSourceDoc,
  showsLocalFields,
  type MountSourceForm,
} from './mountSourceCodec';

/**
 * The contract behind `kind: vance-mount-source`: the form owns its fields
 * and nothing else, `$meta.kind` is guaranteed on save, a protocol the form
 * does not know is kept verbatim rather than snapped to a known one, and
 * the local extras are rendered only for the local protocol.
 */

function form(over: Partial<MountSourceForm> = {}): MountSourceForm {
  return {
    protocol: 'local',
    baseUrl: '',
    apiKey: '',
    enabled: true,
    rootDir: '/mnt/vault',
    writable: false,
    ...over,
  };
}

describe('mountSourceCodec', () => {
  it('parses a YAML mapping and rejects anything else', () => {
    expect(parseMountSourceDoc('protocol: local\n')).toEqual({ protocol: 'local' });
    expect(parseMountSourceDoc('')).toEqual({});
    expect(() => parseMountSourceDoc('- a\n- b\n')).toThrow(MountSourceParseError);
    expect(() => parseMountSourceDoc('a: [\n')).toThrow(MountSourceParseError);
  });

  it('round-trips through serialize without losing values', () => {
    const doc = parseMountSourceDoc('$meta:\n  kind: vance-mount-source\nprotocol: local\n');
    expect(parseMountSourceDoc(serializeMountSourceDoc(doc))).toEqual(doc);
  });

  it('keeps unknown keys and $meta through a form round-trip', () => {
    const doc = parseMountSourceDoc(
      '$meta:\n  kind: vance-mount-source\nprotocol: local\nmetadataTtlSeconds: 300\nsomeFutureField: 7\n',
    );

    const out = applyForm(doc, form({ rootDir: '/mnt/other' }));

    expect(out.metadataTtlSeconds).toBe(300);
    expect(out.someFutureField).toBe(7);
    expect(out.$meta).toEqual({ kind: MOUNT_SOURCE_KIND });
    expect(out.rootDir).toBe('/mnt/other');
  });

  it('guarantees $meta.kind even when the document never had a $meta block', () => {
    const doc = parseMountSourceDoc('protocol: local\n');

    const out = applyForm(doc, form());

    expect(out.$meta).toEqual({ kind: MOUNT_SOURCE_KIND });
    // …and it comes first, like the templates write it.
    expect(Object.keys(out)[0]).toBe('$meta');
  });

  it('keeps other $meta entries when stamping the kind', () => {
    const doc = parseMountSourceDoc('$meta:\n  title: Vault\nprotocol: local\n');

    const out = applyForm(doc, form());

    expect(out.$meta).toEqual({ title: 'Vault', kind: MOUNT_SOURCE_KIND });
  });

  it('keeps a protocol id the form does not know', () => {
    const doc = parseMountSourceDoc('protocol: webdav\n');

    expect(formFromDoc(doc).protocol).toBe('webdav');
    expect(applyForm(doc, form({ protocol: 'webdav' })).protocol).toBe('webdav');
  });

  it('drops empty optional keys instead of writing blanks', () => {
    const doc = parseMountSourceDoc('protocol: ode\napiKey: "{noop}old"\nbaseUrl: https://x.test\n');

    const out = applyForm(doc, form({ protocol: 'ode', apiKey: '', rootDir: '' }));

    expect(out.apiKey).toBeUndefined();
    expect(out.rootDir).toBeUndefined();
  });

  it('writes enabled and writable explicitly in both directions', () => {
    const doc = parseMountSourceDoc('protocol: local\n');

    expect(applyForm(doc, form({ enabled: false })).enabled).toBe(false);
    expect(applyForm(doc, form({ enabled: true })).enabled).toBe(true);
    expect(applyForm(doc, form({ writable: true })).writable).toBe(true);
    expect(applyForm(doc, form({ writable: false })).writable).toBe(false);
  });

  it('counts only keys the form does not own', () => {
    const doc = parseMountSourceDoc(
      '$meta:\n  kind: vance-mount-source\nprotocol: local\nmetadataTtlSeconds: 60\nother: 1\n',
    );

    expect(preservedKeyCount(doc)).toBe(2);
  });

  it('shows the local-directory fields only for the local protocol', () => {
    expect(showsLocalFields('local')).toBe(true);
    expect(showsLocalFields('ode')).toBe(false);
    expect(showsLocalFields('demo')).toBe(false);
  });
});
