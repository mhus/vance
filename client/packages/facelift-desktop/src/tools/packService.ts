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
  type MergedServer,
  type PackToolEntry,
} from './packs';

/** Same constant as the permission gate — a trust question nobody answers
 *  is a rejection, never a silent load (fail-closed, §5). */
const TRUST_TIMEOUT_MS = 25_000;

interface RunningServer {
  server: MergedServer;
  client: Client;
  tools: { name: string; description?: string; inputSchema?: unknown }[];
}

interface AccountPacks {
  tools: PackToolEntry[];
  servers: Map<string, RunningServer>;
  /** In-flight materialization — concurrent callers await the same run. */
  materializing: Promise<void> | null;
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

  /** The account's pack tools for registration — empty until (and
   *  after a failed) materialization. Triggers one lazily. */
  async listTools(accountId: string): Promise<PackToolEntry[]> {
    const packs = await this.materialize(accountId);
    return packs.tools;
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

  /** Read config, merge, select, trust, connect, list tools. Idempotent —
   *  concurrent calls share one run. A failing server never blocks the
   *  others; a missing config is simply an empty toolbox. */
  async materialize(accountId: string): Promise<AccountPacks> {
    const existing = this.accounts.get(accountId);
    if (existing !== undefined) return existing;
    let packs = this.accounts.get(accountId);
    if (packs === undefined) {
      packs = { tools: [], servers: new Map(), materializing: null };
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
        // One broken pack never blocks the toolbox — the agent simply
        // does not see its tools (and the log says why).
        console.warn(
          `[packs] MCP server '${server.name}' failed to start:`,
          e instanceof Error ? e.message : String(e),
        );
      }
    }
    packs.tools = tools;
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
