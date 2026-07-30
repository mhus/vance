import { app } from 'electron';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

// Desktop has no App-Group container like iOS; the share data just lives
// in the app's userData dir. Same merge semantics as the native plugins.

function shareDir(): string {
  return path.join(app.getPath('userData'), 'share');
}

async function writeShareFile(name: string, contents: string): Promise<void> {
  const dir = shareDir();
  await mkdir(dir, { recursive: true });
  await writeFile(path.join(dir, name), contents, 'utf8');
}

export function setAccountSnapshot(accountsJson: string): Promise<void> {
  return writeShareFile('accounts.json', accountsJson);
}

export function setProjectSnapshot(
  accountId: string,
  projectsJson: string,
): Promise<void> {
  const safeId = accountId.replace(/\//g, '_');
  return writeShareFile(`projects-${safeId}.json`, projectsJson);
}

/**
 * Read-merge-write credentials.json keyed by accountId so multiple
 * accounts' credentials coexist and a write replaces only its own entry.
 */
export async function setShareCredentials(
  accountId: string,
  credentialsJson: string,
): Promise<void> {
  const dir = shareDir();
  await mkdir(dir, { recursive: true });
  const file = path.join(dir, 'credentials.json');

  let merged: Record<string, unknown> = {};
  try {
    merged = JSON.parse(await readFile(file, 'utf8')) as Record<string, unknown>;
  } catch {
    // Missing or corrupt file — start fresh rather than wedge future writes.
    merged = {};
  }
  merged[accountId] = JSON.parse(credentialsJson);
  await writeFile(file, JSON.stringify(merged, null, 2), 'utf8');
}
