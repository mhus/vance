/**
 * Per-account desktop tools config — the machine-local release state of
 * the agent tools (planning/desktop-agent-tools.md §6.1, §9).
 *
 * Stored in the app's {@code userData} directory, keyed by account id —
 * the same isolation boundary the account WebViews use. Main-process
 * owned: the renderer reaches this only through the bridge accessors,
 * never by reading files.
 */
import { app } from 'electron';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

import type { AccountToolsConfig } from './types';

const CONFIG_FILE = 'tools-config.json';

interface ConfigFileShape {
  accounts?: Record<string, Partial<AccountToolsConfig>>;
}

function defaultConfig(): AccountToolsConfig {
  return {
    toolsEnabled: false,
    workdir: app.getPath('home'),
    confineToWorkdir: false,
    packHome: null,
    packHomeByProject: {},
  };
}

async function configFile(): Promise<string> {
  const dir = path.join(app.getPath('userData'), 'tools');
  await mkdir(dir, { recursive: true });
  return path.join(dir, CONFIG_FILE);
}

/** Directory holding the per-account permission policy files. */
export function accountToolsDir(accountId: string): string {
  const safeId = accountId.replace(/[/\\]/g, '_');
  return path.join(app.getPath('userData'), 'tools', safeId);
}

async function readAll(): Promise<ConfigFileShape> {
  try {
    const file = await configFile();
    return JSON.parse(await readFile(file, 'utf-8')) as ConfigFileShape;
  } catch {
    // Missing or corrupt — start fresh rather than wedge future writes.
    return {};
  }
}

async function writeAll(shape: ConfigFileShape): Promise<void> {
  const file = await configFile();
  await writeFile(file, JSON.stringify(shape, null, 2), 'utf-8');
}

export async function getAccountConfig(accountId: string): Promise<AccountToolsConfig> {
  const all = await readAll();
  const stored = all.accounts?.[accountId] ?? {};
  const defaults = defaultConfig();
  return {
    toolsEnabled: stored.toolsEnabled ?? defaults.toolsEnabled,
    workdir: stored.workdir ?? defaults.workdir,
    confineToWorkdir: stored.confineToWorkdir ?? defaults.confineToWorkdir,
    packHome: stored.packHome ?? defaults.packHome,
    packHomeByProject: stored.packHomeByProject ?? defaults.packHomeByProject,
  };
}

/** Change the account's working directory (planning §9). Takes effect
 *  with the next invoke — the config is read per operation — and with the
 *  next session bind via the client context. */
export async function setWorkdir(accountId: string, workdir: string): Promise<void> {
  const all = await readAll();
  const accounts = all.accounts ?? {};
  const current = accounts[accountId] ?? {};
  accounts[accountId] = { ...current, workdir };
  all.accounts = accounts;
  await writeAll(all);
}

/** Confinement switch (§9): on = paths outside the working directory
 *  deny instead of asking. Read per invoke with the policy. */
export async function setConfineToWorkdir(
  accountId: string,
  confineToWorkdir: boolean,
): Promise<void> {
  const all = await readAll();
  const accounts = all.accounts ?? {};
  const current = accounts[accountId] ?? {};
  accounts[accountId] = { ...current, confineToWorkdir };
  all.accounts = accounts;
  await writeAll(all);
}

export async function setToolsEnabled(accountId: string, enabled: boolean): Promise<void> {
  const all = await readAll();
  const accounts = all.accounts ?? {};
  const current = accounts[accountId] ?? {};
  accounts[accountId] = { ...current, toolsEnabled: enabled };
  all.accounts = accounts;
  await writeAll(all);
}

/** Default pack home (null = the real user home). Takes effect with the
 *  next project report or packs reload. */
export async function setPackHome(accountId: string, home: string | null): Promise<void> {
  const all = await readAll();
  const accounts = all.accounts ?? {};
  const current = accounts[accountId] ?? {};
  accounts[accountId] = { ...current, packHome: home };
  all.accounts = accounts;
  await writeAll(all);
}

/** Per-project pack home. Key is `tenant/project` — project names are only
 *  unique per tenant. Takes effect with the next project report. */
export async function setPackHomeForProject(
  accountId: string,
  key: string,
  home: string,
): Promise<void> {
  const all = await readAll();
  const accounts = all.accounts ?? {};
  const current = accounts[accountId] ?? {};
  const byProject = current.packHomeByProject ?? {};
  byProject[key] = home;
  accounts[accountId] = { ...current, packHomeByProject: byProject };
  all.accounts = accounts;
  await writeAll(all);
}

export async function removePackHomeForProject(accountId: string, key: string): Promise<void> {
  const all = await readAll();
  const accounts = all.accounts ?? {};
  const current = accounts[accountId] ?? {};
  const byProject = current.packHomeByProject ?? {};
  delete byProject[key];
  accounts[accountId] = { ...current, packHomeByProject: byProject };
  all.accounts = accounts;
  await writeAll(all);
}
