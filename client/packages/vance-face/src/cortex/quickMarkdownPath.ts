/** Base name for files created without a name: `note1.md`, `note2.md`, … */
export const DEFAULT_NOTE_BASE = 'note';

/**
 * Builds the full document path for the Cortex "New Markdown file" dialog
 * from a folder and a bare name. Leading/trailing slashes on the folder are
 * dropped, exactly one `/` separates folder and name, and `.md` is appended
 * unless the name already ends with it (case-insensitive). Returns
 * {@code null} when the name is blank.
 */
export function quickMarkdownPath(folder: string, name: string): string | null {
  const cleanFolder = folder.trim().replace(/^\/+|\/+$/g, '');
  const cleanName = name.trim().replace(/^\/+/, '');
  if (!cleanName) return null;
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
