/** Base name for files created without a name: `note1.md`, `note2.md`, … */
export const DEFAULT_NOTE_BASE = 'note';

/**
 * Builds the full document path for the Cortex "New Markdown file" dialog
 * from a folder and a bare name. Leading/trailing slashes on the folder are
 * dropped, exactly one `/` separates folder and name, and `.md` is appended
 * unless the name already ends with it (case-insensitive). Returns
 * {@code null} when the name does not name a file: blank, ending with `/`
 * (a folder), or nothing but the `.md` suffix in its last segment.
 */
export function quickMarkdownPath(folder: string, name: string): string | null {
  const cleanFolder = folder.trim().replace(/^\/+|\/+$/g, '');
  const cleanName = name.trim().replace(/^\/+/, '');
  if (!cleanName || cleanName.endsWith('/')) return null;
  const lastSegment = cleanName.slice(cleanName.lastIndexOf('/') + 1);
  if (!lastSegment.replace(/\.md$/i, '')) return null;
  const fileName = /\.md$/i.test(cleanName) ? cleanName : `${cleanName}.md`;
  return cleanFolder ? `${cleanFolder}/${fileName}` : fileName;
}

/**
 * First `note<N>.md` path in {@code folder}, starting at {@code fromIndex},
 * that {@code isTaken} does not report. Only a hint from what the client has
 * loaded — the server still decides, and a 409 moves the caller to the next
 * index.
 */
export function nextNotePath(
  folder: string,
  isTaken: (path: string) => boolean,
  fromIndex = 1,
): { path: string; index: number } {
  for (let index = fromIndex; ; index++) {
    const path = quickMarkdownPath(folder, `${DEFAULT_NOTE_BASE}${index}`) as string;
    if (!isTaken(path)) return { path, index };
  }
}

/**
 * Creates the next free `note<N>.md` in {@code folder}. The client's view of
 * taken paths is only a hint, so a conflict reported by {@code create}
 * (as judged by {@code isConflict}) moves on to the next index — at most
 * {@code maxConflicts} times, then the last conflict is rethrown. Any other
 * error is rethrown at once. Resolves with the path that was created.
 */
export async function createNextNote(
  folder: string,
  isTaken: (path: string) => boolean,
  create: (fullPath: string) => Promise<void>,
  isConflict: (error: unknown) => boolean,
  maxConflicts: number,
): Promise<string> {
  let next = nextNotePath(folder, isTaken);
  for (let attempt = 0; ; attempt++) {
    try {
      await create(next.path);
      return next.path;
    } catch (e) {
      if (!isConflict(e) || attempt >= maxConflicts) throw e;
      next = nextNotePath(folder, isTaken, next.index + 1);
    }
  }
}
