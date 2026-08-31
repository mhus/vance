import { describe, expect, it } from 'vitest';
import {
  connectionChecksum,
  decodeConnectionBlob,
  encodeConnectionBlob,
  type ConnectionBlob,
} from './integrationConnectionCodec';

const blob: ConnectionBlob = {
  brainUrl: 'https://eddie.example',
  tenant: 'acme',
  projectId: 'reading',
  target: 'links',
  profile: 'links-capture',
  token: 'eyJhbGciOiJFUzI1NiJ9.payload.signature',
  expiresAt: 1_800_000_000_000,
};

describe('connection blob', () => {
  it('round-trips every field', () => {
    expect(decodeConnectionBlob(encodeConnectionBlob(blob))).toEqual(blob);
  });

  it('is recognisable by its prefix', () => {
    expect(encodeConnectionBlob(blob).startsWith('vancetope1.')).toBe(true);
  });

  it('survives the whitespace a paste picks up', () => {
    const encoded = encodeConnectionBlob(blob);
    expect(decodeConnectionBlob(`\n  ${encoded}  \n`)).toEqual(blob);
  });

  /**
   * The reason the checksum exists: everything except the token is unsigned,
   * so a truncated paste of the brain URL or the project would otherwise only
   * show up much later as a 404 that looks nothing like a bad paste.
   */
  it('rejects a truncated string', () => {
    const encoded = encodeConnectionBlob(blob);
    expect(decodeConnectionBlob(encoded.slice(0, encoded.length - 12))).toBeNull();
  });

  it('rejects a payload that was edited under its checksum', () => {
    const [prefix, payload, checksum] = encodeConnectionBlob(blob).split('.');
    const tampered = `${prefix}.${payload.slice(0, -2)}XY.${checksum}`;
    expect(decodeConnectionBlob(tampered)).toBeNull();
  });

  it('rejects something that is not one of ours', () => {
    expect(decodeConnectionBlob('hello')).toBeNull();
    expect(decodeConnectionBlob('other1.abc.def')).toBeNull();
    expect(decodeConnectionBlob('')).toBeNull();
  });

  /** A blob missing a field the far end needs is as useless as a broken one. */
  it('rejects a well-formed blob with nothing to connect to', () => {
    const empty = encodeConnectionBlob({ ...blob, brainUrl: '', token: '' });
    expect(decodeConnectionBlob(empty)).toBeNull();
  });

  it('keeps non-ASCII intact', () => {
    const accented = { ...blob, target: 'lesen/über-uns' };
    expect(decodeConnectionBlob(encodeConnectionBlob(accented))?.target)
      .toBe('lesen/über-uns');
  });

  it('produces a stable eight-hex-digit checksum', () => {
    const sum = connectionChecksum('anything');
    expect(sum).toMatch(/^[0-9a-f]{8}$/);
    expect(connectionChecksum('anything')).toBe(sum);
    expect(connectionChecksum('anythinh')).not.toBe(sum);
  });
});
