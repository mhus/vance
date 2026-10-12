/**
 * Per-account secret store for MCP pack credentials
 * (planning/desktop-pack-secrets.md §3): the VALUES behind
 * `${secret:NAME}` references in a pack's `env` block — tokens, API keys.
 *
 * <p>Storage: one YAML file per account in the app's userData
 * directory. Values are encrypted with the OS keychain via Electron's
 * {@code safeStorage} (injected as {@link PackSecretCrypto} — the file
 * alone is worthless without the machine's keychain). The renderer never
 * sees a value: the shell UI only learns "set / not set"; entries are
 * written through the main-process IPC handlers.
 *
 * <p>Crypto adapter injected (not imported) so the store is unit-testable
 * without Electron — same discipline as the permission policy code.
 */
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

import { dump, load } from 'js-yaml';

/** Encrypt/decrypt adapter — the main wires Electron's safeStorage. */
export interface PackSecretCrypto {
  encrypt(value: string): Buffer;
  decrypt(blob: Buffer): string;
}

interface StoreShape {
  secrets?: Record<string, string>;
}

export class PackSecretStore {
  private readonly crypto: PackSecretCrypto;
  private readonly dirFor: (accountId: string) => string;

  constructor(crypto: PackSecretCrypto, dirFor: (accountId: string) => string) {
    this.crypto = crypto;
    this.dirFor = dirFor;
  }

  private file(accountId: string): string {
    const safeId = accountId.replace(/[/\\]/g, '_');
    return path.join(this.dirFor(accountId), `${safeId}.pack-secrets.yaml`);
  }

  private async read(accountId: string): Promise<StoreShape> {
    try {
      const shape = load(await readFile(this.file(accountId), 'utf-8')) as StoreShape | undefined;
      if (shape !== null && typeof shape === 'object' && shape !== undefined) return shape;
    } catch {
      // missing or corrupt — a fresh store; callers treat it as empty
    }
    return {};
  }

  private async write(accountId: string, shape: StoreShape): Promise<void> {
    const file = this.file(accountId);
    await mkdir(path.dirname(file), { recursive: true });
    await writeFile(file, dump(shape), 'utf-8');
  }

  /** The stored secret names (for the "set" chips in the UI). */
  async names(accountId: string): Promise<string[]> {
    const shape = await this.read(accountId);
    return Object.keys(shape.secrets ?? {});
  }

  /** A stored value, or undefined when absent or undecryptable (keychain
   * rotated, file moved from another machine — a broken entry behaves
   * like a missing one and is reported as such). */
  async get(accountId: string, name: string): Promise<string | undefined> {
    const raw = (await this.read(accountId)).secrets?.[name];
    if (raw === undefined || raw === null) return undefined;
    try {
      return this.crypto.decrypt(Buffer.from(raw, 'base64'));
    } catch {
      return undefined;
    }
  }

  async set(accountId: string, name: string, value: string): Promise<void> {
    const shape = await this.read(accountId);
    shape.secrets ??= {};
    shape.secrets[name] = this.crypto.encrypt(value).toString('base64');
    await this.write(accountId, shape);
  }

  async clear(accountId: string, name: string): Promise<void> {
    const shape = await this.read(accountId);
    if (shape.secrets === undefined) return;
    delete shape.secrets[name];
    await this.write(accountId, shape);
  }
}
