/**
 * MCP pack connections — Stufe 1: stdio servers
 * (planning/desktop-tool-packs.md §7/§8). Owns per-account connections,
 * the trust dialog and the materialization lifecycle; the pure config
 * logic lives in {@code packs.ts}.
 *
 * Lifecycle contract (like the exec jobs): server processes start with
 * the account materialization (they must answer `initialize` +
 * `tools/list` before the bind can register their tools) and die with
 * the app (`killAll`) — no orphaned subprocesses.
 */
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { BrowserWindow, dialog } from 'electron';

import { accountToolsDir, getAccountConfig } from './accountConfig';
import {
  applySelection,
  buildPackToolEntry,
  filterSubTools,
  isTrusted,
  mergeServers,
  parseMcpJson,
  parseToolPacksSelection,
  parseTrustedPacks,
  reachOf,
  serializeTrustedPacks,
  packSpawnEnv,
  type MergedServer,
  type PackToolEntry,
} from './packs';
import { SHELL_IPC, type AccountToolsConfig } from './types';

/** Same constant as the permission gate — a trust question nobody answers
 *  is a rejection, never a silent load (fail-closed, §5). */
const TRUST_TIMEOUT_MS = 25_000;

interface RunningServer {
  server: MergedServer;
  client: Client;
  tools: { name: string; description?: string; inputSchema?: unknown }[];
}

interface PackError {
  pack: string;
  error: string;
}

interface AccountPacks {
  tools: PackToolEntry[];
  errors: PackError[];
  servers: Map<string, RunningServer>;
  /** In-flight materialization — concurrent callers await the same run. */
  materializing: Promise<void> | null;
  /** The HOME the servers were spawned under (change detection for
   *  setProject). Empty until the first materialization. */
  home: string;
}

/**
 * Trust answers for one project pack (§5). Mirrors foot's dialog:
 * load once / always for this workdir / no.
 */
type TrustAnswer = 'once' | 'always' | 'no';

export class PackService {
  private readonly accounts = new Map<string, AccountPacks>();
  private readonly windowProvider: () => BrowserWindow | null;

  constructor(windowProvider: () => BrowserWindow | null) {
    this.windowProvider = windowProvider;
  }

  /** Drop the cached materialization and rebuild it — the connect
   *  option: mcp.json edits, revoked trust and restarted servers become
   *  visible without an app restart. Running servers die first; a new
   *  project pack asks its trust question again (the dialog waits,
   *  bounded — this call can take as long). The next bind re-registers
   *  the fresh tool set. */
  async reload(accountId: string): Promise<{ servers: number; tools: number; errors: number }> {
    const cached = this.accounts.get(accountId);
    if (cached !== undefined) {
      for (const running of cached.servers.values()) {
        running.client.close().catch(() => {
          // best effort — the process dies with the transport anyway
        });
      }
      this.accounts.delete(accountId);
    }
    const fresh = await this.materialize(accountId);
    return {
      servers: fresh.servers.size,
      tools: fresh.tools.length,
      errors: fresh.errors.length,
    };
  }

  /** The account's pack tools and per-pack failures — the UI shows both:
   *  a pack that fails to start (missing npx, broken command) must be
   *  visible, not silently absent. Triggers a materialization lazily. */
  async listTools(accountId: string): Promise<{
    tools: PackToolEntry[];
    errors: PackError[];
  }> {
    const packs = await this.materialize(accountId);
    return { tools: packs.tools, errors: packs.errors };
  }

  /** One `pack_invoke` (ungated in v1 — foot parity, §9). */
  async invoke(
    accountId: string,
    pack: string,
    tool: string,
    args: Record<string, unknown>,
  ): Promise<Record<string, unknown>> {
    const packs = await this.materialize(accountId);
    const server = packs.servers.get(pack);
    if (server === undefined) {
      throw new Error(`Unknown MCP pack: ${pack}`);
    }
    if (!server.tools.some((t) => t.name === tool)) {
      throw new Error(`Unknown tool ${tool} in MCP pack ${pack}`);
    }
    const result = await server.client.callTool({ name: tool, arguments: args });
    const content = (result.content ?? []) as Record<string, unknown>[];
    const text = content
      .filter((c) => c.type === 'text')
      .map((c) => String(c.text ?? ''))
      .join('\n');
    if (result.isError) {
      throw new Error(text || `MCP pack ${pack} reported an error for tool ${tool}`);
    }
    return { content, ...(text !== '' ? { text } : {}) };
  }

  /** The last reported project per account — `tenant/project` keyed at
   *  resolution time. Survives reload (the reported context outlives a
   *  respawn); the face re-reports on every editor mount anyway.
   *  planning/desktop-pack-home-per-project.md §3. */
  private readonly projects = new Map<string, { tenant: string | null; project: string }>();

  /** Face-side project report. Resolves the effective pack home
   *  (`packHomeByProject['tenant/project'] ?? packHome ?? real home`) and
   *  respawns the running servers when it CHANGED — a project switch
   *  without its own home entry costs nothing. Pushes the new context to
   *  the shell (tooltip). */
  async setProject(accountId: string, tenant: string | null, project: string | null): Promise<void> {
    if (project === null || project === '') {
      this.projects.delete(accountId);
    } else {
      this.projects.set(accountId, { tenant, project });
    }
    const config = await getAccountConfig(accountId);
    const home = this.effectiveHome(accountId, config);
    const cached = this.accounts.get(accountId);
    if (cached !== undefined && cached.home !== '' && cached.home !== home) {
      await this.reload(accountId);
    } else if (cached !== undefined && cached.home === '') {
      // not materialized yet — stamp the home so the first spawn uses it
      cached.home = home;
    }
    this.pushProject(accountId, home);
  }

  /** Re-resolve the home after a config write (default or per-project
   *  changed) — keeps the reported project, respawns when the effective
   *  home moved. The face is not involved; the shell UI wrote the config. */
  async reResolve(accountId: string): Promise<void> {
    const config = await getAccountConfig(accountId);
    const home = this.effectiveHome(accountId, config);
    const cached = this.accounts.get(accountId);
    if (cached !== undefined && cached.home !== '' && cached.home !== home) {
      await this.reload(accountId);
    }
    this.pushProject(accountId, home);
  }

  /** Tooltip context push — per account, the shell shows the one on screen. */
  private pushProject(accountId: string, home: string): void {
    this.windowProvider()?.webContents.send(SHELL_IPC.packsProject, {
      accountId,
      project: this.projects.get(accountId)?.project ?? null,
      tenant: this.projects.get(accountId)?.tenant ?? null,
      home,
    });
  }

  /** Effective pack home for the account's current project report. */
  private effectiveHome(accountId: string, config: AccountToolsConfig): string {
    const reported = this.projects.get(accountId);
    const key = reported ? `${reported.tenant ?? ''}/${reported.project}` : null;
    return (key !== null ? config.packHomeByProject[key] : undefined) ?? config.packHome ?? os.homedir();
  }

  /** The context the shell tooltip shows (last report + effective home). */
  currentProject(accountId: string): { project: string | null; tenant: string | null; home: string | null } {
    const reported = this.projects.get(accountId);
    const cached = this.accounts.get(accountId);
    return {
      project: reported?.project ?? null,
      tenant: reported?.tenant ?? null,
      home: cached && cached.home !== '' ? cached.home : null,
    };
  }

  /** Read config, merge, select, trust, connect, list tools. Idempotent —
   *  concurrent calls share one run. A failing server never blocks the
   *  others; a missing config is simply an empty toolbox. */
  async materialize(accountId: string): Promise<AccountPacks> {
    const existing = this.accounts.get(accountId);
    if (existing !== undefined) return existing;
    let packs = this.accounts.get(accountId);
    if (packs === undefined) {
      packs = { tools: [], errors: [], servers: new Map(), materializing: null, home: '' };
      this.accounts.set(accountId, packs);
    }
    if (packs.materializing !== null) {
      await packs.materializing;
      return packs;
    }
    packs.materializing = this.doMaterialize(accountId, packs).finally(() => {
      packs!.materializing = null;
    });
    await packs.materializing;
    return packs;
  }

  private async doMaterialize(accountId: string, packs: AccountPacks): Promise<void> {
    const config = await getAccountConfig(accountId);
    const workdir = config.workdir;
    // The spawn home: per-project override, default override, or the
    // real user home (planning §5/§6 — the npm/npx cache stays pinned to
    // the real home inside packSpawnEnv, so npx never re-downloads per
    // project).
    packs.home = this.effectiveHome(accountId, config);
    // A deleted home dir would fail the spawn with a misleading
    // "spawn <command> ENOENT" — the chooser only picks existing dirs, but
    // the directory can vanish between config write and respawn. Best
    // effort; a real failure surfaces as a visible pack error either way.
    await mkdir(packs.home, { recursive: true }).catch(() => {});
    const spawnEnv = packSpawnEnv(packs.home, os.homedir(), process.env);

    const [globalRaw, projectRaw, configRaw, trustedRaw] = await Promise.all([
      readTextOrNull(path.join(os.homedir(), '.vancetope', 'mcp.json')),
      readTextOrNull(path.join(workdir, '.vancetope', 'mcp.json')),
      readTextOrNull(path.join(workdir, '.vancetope', 'config.yaml')),
      readTextOrNull(this.trustedFile(accountId)),
    ]);

    const servers = applySelection(
      mergeServers(parseMcpJson(globalRaw ?? ''), parseMcpJson(projectRaw ?? '')),
      parseToolPacksSelection(configRaw),
    );

    const trusted = parseTrustedPacks(trustedRaw);
    const tools: PackToolEntry[] = [];
    const errors: PackError[] = [];
    for (const server of servers) {
      const command = server.config.command ?? [];
      if (command.length === 0) continue; // no transport fields yet (http = Stufe 2)
      if (server.origin === 'project') {
        const reach = reachOf(server.config);
        if (!isTrusted(trusted, workdir, server.name, reach)) {
          const answer = await this.askTrust(server, workdir);
          if (answer === 'no') continue;
          if (answer === 'always') {
            await this.persistTrust(accountId, workdir, server.name, reach, trusted);
          }
        }
      }
      try {
        const client = new Client(
          { name: 'vancetope-desktop', version: '1' },
          {},
        );
        const transport = new StdioClientTransport({
          command: command[0],
          args: command.slice(1),
          env: spawnEnv,
          cwd: packs.home,
        });
        await client.connect(transport);
        const listed = await client.listTools();
        const subTools = filterSubTools(
          listed.tools.map((t) => ({
            name: t.name,
            description: t.description,
            inputSchema: t.inputSchema,
          })),
          server.name,
          server.config.disabledSubTools,
        );
        packs.servers.set(server.name, { server, client, tools: subTools });
        for (const tool of subTools) {
          tools.push(buildPackToolEntry(server.name, server.config.labels ?? [], tool));
        }
      } catch (e) {
        // One broken pack never blocks the toolbox — but it must be
        // VISIBLE that it is missing (the log alone is invisible in a
        // Finder-launched app).
        const message = e instanceof Error ? e.message : String(e);
        console.warn(`[packs] MCP server '${server.name}' failed to start:`, message);
        errors.push({ pack: server.name, error: message });
      }
    }
    packs.tools = tools;
    packs.errors = errors;
  }

  /** Native trust dialog (§5) — Load once / Always for this workdir / No.
   *  No window or a timeout is a rejection: fail-closed. */
  private async askTrust(server: MergedServer, workdir: string): Promise<TrustAnswer> {
    const win = this.windowProvider();
    if (win === null) return 'no';
    const reach = reachOf(server.config);
    const timer = new Promise<'timeout'>((resolve) => {
      setTimeout(() => resolve('timeout'), TRUST_TIMEOUT_MS);
    });
    const choice = await Promise.race([
      dialog.showMessageBox(win, {
        type: 'warning',
        title: 'Project tool pack',
        message: `Project tool pack '${server.name}' from ${path.join(workdir, '.vancetope', 'mcp.json')}`,
        detail: `wants to run: ${reach}\n\nProject packs can run commands from the working directory. Only load packs from repositories you trust.`,
        buttons: ['Load once', 'Always for this workdir', "Don't load"],
        defaultId: 2,
        cancelId: 2,
        noLink: true,
      }),
      timer,
    ]);
    if (typeof choice === 'string') return 'no'; // timeout — fail-closed
    if (choice.response === 0) return 'once';
    if (choice.response === 1) return 'always';
    return 'no';
  }

  private async persistTrust(
    accountId: string,
    workdir: string,
    name: string,
    reach: string,
    trusted: Record<string, { name: string; reach: string }[]>,
  ): Promise<void> {
    const entries = trusted[workdir] ?? [];
    if (!entries.some((e) => e.name === name && e.reach === reach)) {
      entries.push({ name, reach });
      trusted[workdir] = entries;
    }
    const file = this.trustedFile(accountId);
    await mkdir(path.dirname(file), { recursive: true });
    await writeFile(file, serializeTrustedPacks(trusted), 'utf-8');
  }

  /** Per-account trust store — in the desktop config, NEVER in the workdir
   *  (a repo must not authorize itself, §5). */
  private trustedFile(accountId: string): string {
    return path.join(accountToolsDir(accountId), 'trusted-packs.yaml');
  }

  /** App-quit path: every MCP server process dies with the app (§7). */
  killAll(): void {
    for (const packs of this.accounts.values()) {
      for (const running of packs.servers.values()) {
        running.client.close().catch(() => {
          // best effort — close errors at shutdown are noise
        });
      }
    }
    this.accounts.clear();
  }
}

async function readTextOrNull(file: string): Promise<string | null> {
  try {
    return await readFile(file, 'utf-8');
  } catch {
    return null;
  }
}
