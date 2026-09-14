import { describe, expect, it } from 'vitest';
import { applyFenceMeta, parseQrCodeDoc, QR_DEFAULTS } from './qrcodeCodec';

describe('parseQrCodeDoc', () => {
  it('reads the markdown front-matter form with options', () => {
    const doc = parseQrCodeDoc(
      '---\nkind: qrcode\nlabel: "Scan me"\nsize: 512\necc: high\n---\nhttps://example.com/invite\n',
    );
    expect(doc.payload).toBe('https://example.com/invite');
    expect(doc.label).toBe('Scan me');
    expect(doc.size).toBe(512);
    expect(doc.ecc).toBe('high');
  });

  it('treats a body without front matter as the bare payload', () => {
    const doc = parseQrCodeDoc('https://example.com');
    expect(doc.payload).toBe('https://example.com');
    expect(doc.label).toBe('');
    expect(doc.size).toBe(QR_DEFAULTS.size);
    expect(doc.ecc).toBe('medium');
  });

  it('keeps multi-line free text as one payload', () => {
    const doc = parseQrCodeDoc('BEGIN:VCARD\nFN:Jane Doe\nEND:VCARD\n');
    expect(doc.payload).toBe('BEGIN:VCARD\nFN:Jane Doe\nEND:VCARD');
  });

  it('reads the YAML form with $meta and a content key', () => {
    const doc = parseQrCodeDoc(
      '$meta:\n  kind: qrcode\ncontent: https://example.com\nmargin: 2\ndark: "#004488"\n',
    );
    expect(doc.payload).toBe('https://example.com');
    expect(doc.margin).toBe(2);
    expect(doc.dark).toBe('#004488');
  });

  it('reads the JSON form', () => {
    const doc = parseQrCodeDoc(
      '{ "$meta": { "kind": "qrcode" }, "content": "https://example.com", "size": 128 }\n',
    );
    expect(doc.payload).toBe('https://example.com');
    expect(doc.size).toBe(128);
  });

  it('falls back to defaults for unknown or malformed option values', () => {
    const doc = parseQrCodeDoc(
      '---\nkind: qrcode\nsize: huge\necc: ultra\ndark: blue\n---\nhttps://example.com\n',
    );
    expect(doc.size).toBe(QR_DEFAULTS.size);
    expect(doc.ecc).toBe(QR_DEFAULTS.ecc);
    expect(doc.dark).toBe(QR_DEFAULTS.dark);
  });

  it('clamps size and margin into their supported ranges', () => {
    const tooBig = parseQrCodeDoc('---\nsize: 99999\nmargin: 99\n---\nx\n');
    expect(tooBig.size).toBe(2048);
    expect(tooBig.margin).toBe(16);

    const tooSmall = parseQrCodeDoc('---\nsize: 1\nmargin: -5\n---\nx\n');
    expect(tooSmall.size).toBe(64);
    expect(tooSmall.margin).toBe(0);
  });

  it('returns an empty payload for an empty body', () => {
    const doc = parseQrCodeDoc('   \n');
    expect(doc.payload).toBe('');
  });

  it('keeps free text with an indented colon line as payload', () => {
    // Not matching the YAML gate (no line-start `content:` / `$meta:`),
    // so the whole body stays payload text.
    const doc = parseQrCodeDoc('Visit\n  content: https://example.com\nfor details');
    expect(doc.payload).toBe('Visit\n  content: https://example.com\nfor details');
  });
});

describe('applyFenceMeta', () => {
  it('overlays fence params onto the parsed doc', () => {
    const doc = applyFenceMeta(parseQrCodeDoc('https://example.com'), {
      size: '1024',
      ecc: 'high',
      label: 'Invite',
    });
    expect(doc.payload).toBe('https://example.com');
    expect(doc.size).toBe(1024);
    expect(doc.ecc).toBe('high');
    expect(doc.label).toBe('Invite');
  });

  it('ignores unknown keys and malformed values', () => {
    const doc = applyFenceMeta(parseQrCodeDoc('x'), {
      bogus: '1',
      size: 'not-a-number',
    });
    expect(doc.size).toBe(QR_DEFAULTS.size);
  });
});
