import { AGE_FILE_EXTENSION, AGE_KIND, AGE_MIME_TYPE } from './constants';

/**
 * Whether a document with this {@code kind} / MIME pair is age-encrypted.
 * Both markers are accepted so a row is still recognised when only one
 * of them survived — the client twin of the Java
 * {@code AgeDocumentKind.isAgeEncrypted}.
 */
export function isAgeDocument(
  kind: string | null | undefined,
  mimeType: string | null | undefined,
): boolean {
  if ((kind ?? '').trim().toLowerCase() === AGE_KIND) return true;
  const mime = (mimeType ?? '').trim().toLowerCase();
  if (!mime) return false;
  const base = mime.indexOf(';') >= 0 ? mime.slice(0, mime.indexOf(';')).trim() : mime;
  return base === AGE_MIME_TYPE;
}

export interface AgePathInfo {
  /** The file name without the trailing `.age` — `bericht.md.age` → `bericht.md`. */
  readonly innerName: string;
  /** Lower-cased inner extension — `bericht.md.age` → `md`. Empty when the inner name has none. */
  readonly innerExtension: string;
}

/**
 * Split a path with the age double-extension convention. `bericht.md.age`
 * yields the inner name + the inner type hint; a bare `secret.age` yields an
 * empty extension (sniffing has to come from the decrypted body). Returns
 * {@code null} for paths that do not end in `.age`.
 */
export function splitAgePath(path: string): AgePathInfo | null {
  const segment = path.split('/').pop() ?? '';
  if (!segment.toLowerCase().endsWith('.' + AGE_FILE_EXTENSION)) return null;
  const innerName = segment.slice(0, -(AGE_FILE_EXTENSION.length + 1));
  if (!innerName) return null;
  const dot = innerName.lastIndexOf('.');
  if (dot < 0 || dot === innerName.length - 1) {
    return { innerName, innerExtension: '' };
  }
  return { innerName, innerExtension: innerName.slice(dot + 1).toLowerCase() };
}

/**
 * Whether a path carries the age extension — the client-side mirror of the
 * Java `AgeDocumentKind.hasAgeExtension`.
 */
export function hasAgeExtension(path: string | null | undefined): boolean {
  if (!path) return false;
  const segment = path.split('/').pop() ?? '';
  return segment.toLowerCase().endsWith('.' + AGE_FILE_EXTENSION);
}
