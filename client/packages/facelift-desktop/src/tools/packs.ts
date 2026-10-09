/**
 * Tool packs for the desktop agent tools — the MCP passthrough
 * (planning/desktop-tool-packs.md). Pure logic, no Electron, no SDK:
 * parsing, merging, selection, trust matching and spec mapping are
 * unit-testable here; connections live in {@code packService.ts}.
 *
 * One config file for all clients (§3): {@code ~/.vancetope/mcp.json}
 * (global default) and {@code <workdir>/.vancetope/mcp.json} (project
 * level, comes with the repo, trust-gated). Format: the known
 * {@code mcpServers} map — the key is the pack name, our extensions
 * (labels, enabled, disabledSubTools) are per-server fields.
 */
import { load as yamlLoad, dump as yamlDump } from 'js-yaml';

/** One MCP server from the mcpServers map. v1: stdio (command array). */
export interface McpServerConfig {
  command?: string[];
  labels?: string[];
  enabled?: boolean;
  disabledSubTools?: string[];
}

export interface McpConfigFile {
  mcpServers?: Record<string, McpServerConfig>;
}

/** A server after the two-level merge — project wins by name. */
export interface MergedServer {
  name: string;
  config: McpServerConfig;
  /** Where the effective definition came from — project packs are trust-gated. */
  origin: 'global' | 'project';
}

/** The `toolPacks:` block of `<workdir>/.vancetope/config.yaml` (§4). */
export interface ToolPacksSelection {
  enabled?: boolean;
  packs?: string[];
  disabledPacks?: string[];
}

export interface TrustedPackEntry {
  name: string;
  reach: string;
}

/** One pack tool as the face registers it: the spec plus the routing pair. */
export interface PackToolEntry {
  spec: {
    name: string;
    description: string;
    primary: boolean;
    source: string;
    paramsSchema: Record<string, unknown>;
    labels: string[];
    allowedProfiles: string[];
    deferred: boolean;
    searchHint: string;
    safety: 'SAFE_PROBE' | 'MUTATING';
    requiresEngineRoles: string[];
  };
  pack: string;
  tool: string;
}

const AUTOMATIC_LABELS = (pack: string): string[] => ['mcp', `mcp:${pack}`, 'side-effect'];

/** Parse an mcp.json — tolerant: missing/corrupt yields an empty file. */
export function parseMcpJson(text: string): McpConfigFile {
  try {
    const parsed = JSON.parse(text) as McpConfigFile;
    if (typeof parsed === 'object' && parsed !== null) return parsed;
  } catch {
    // corrupt — behave like an empty toolbox, the log at the caller says so
  }
  return {};
}

/**
 * Merge the two levels (§3): union over the server name, project wins —
 * contribute, redirect (same name, other config) or silence
 * (`enabled: false`). Servers absent everywhere fall away.
 */
export function mergeServers(
  globalFile: McpConfigFile,
  projectFile: McpConfigFile,
): MergedServer[] {
  const merged = new Map<string, MergedServer>();
  for (const [name, config] of Object.entries(globalFile.mcpServers ?? {})) {
    if (typeof config === 'object' && config !== null) {
      merged.set(name, { name, config, origin: 'global' });
    }
  }
  for (const [name, config] of Object.entries(projectFile.mcpServers ?? {})) {
    if (typeof config === 'object' && config !== null) {
      merged.set(name, { name, config, origin: 'project' });
    }
  }
  return [...merged.values()];
}

/** Parse the `toolPacks:` selection — absent block = no steering (§4). */
export function parseToolPacksSelection(configYaml: string | null): ToolPacksSelection | null {
  if (configYaml === null) return null;
  try {
    const parsed = yamlLoad(configYaml) as { toolPacks?: ToolPacksSelection } | null;
    if (typeof parsed === 'object' && parsed !== null && typeof parsed.toolPacks === 'object') {
      return parsed.toolPacks;
    }
  } catch {
    // corrupt config — no steering rather than guessing
  }
  return null;
}

/**
 * Apply the selection to the merged servers (§4 order): the pack file's
 * own `enabled` → the `toolPacks:` selection. Exact name comparison; every
 * field individually optional.
 */
export function applySelection(
  servers: MergedServer[],
  selection: ToolPacksSelection | null,
): MergedServer[] {
  let out = servers.filter((s) => s.config.enabled !== false);
  if (selection === null) return out;
  if (selection.enabled === false) return [];
  if (Array.isArray(selection.packs) && selection.packs.length > 0) {
    const allow = new Set(selection.packs);
    out = out.filter((s) => allow.has(s.name));
  }
  if (Array.isArray(selection.disabledPacks)) {
    const deny = new Set(selection.disabledPacks);
    out = out.filter((s) => !deny.has(s.name));
  }
  return out;
}

/** What the server wants to run — the trust identity (§5): joined command,
 *  endpoint later. A project pack that swaps the command re-asks. */
export function reachOf(config: McpServerConfig): string {
  return (config.command ?? []).join(' ');
}

export function parseTrustedPacks(yamlText: string | null): Record<string, TrustedPackEntry[]> {
  if (yamlText === null) return {};
  try {
    const parsed = yamlLoad(yamlText) as { trustedPacks?: Record<string, TrustedPackEntry[]> } | null;
    if (typeof parsed === 'object' && parsed !== null && typeof parsed.trustedPacks === 'object') {
      return parsed.trustedPacks;
    }
  } catch {
    // corrupt — treat as nothing trusted, fail-closed
  }
  return {};
}

export function serializeTrustedPacks(trusted: Record<string, TrustedPackEntry[]>): string {
  return yamlDump({ trustedPacks: trusted });
}

/** Trust matches on workdir + name + reach (§5) — a swapped command
 *  invalidates a prior "always". */
export function isTrusted(
  trusted: Record<string, TrustedPackEntry[]>,
  workdir: string,
  name: string,
  reach: string,
): boolean {
  const entries = trusted[workdir];
  return (
    Array.isArray(entries) && entries.some((e) => e.name === name && e.reach === reach)
  );
}

/** Wire name: `<pack>__<subtool>` — identical to foot (foot-tool-packs §1).
 *  The budget family is the pack name (ToolFamily: prefix before `__`). */
export function packToolName(pack: string, tool: string): string {
  return `${pack}__${tool}`;
}

/** Map one MCP tool to the client-tool spec the face registers (§6):
 *  labels = pack labels + the automatic set, deferred (manifest-flood
 *  guard), desktop profile. Safety is conservatively MUTATING — MCP
 *  tools carry no safety metadata. */
export function buildPackToolEntry(
  pack: string,
  packLabels: string[],
  tool: { name: string; description?: string; inputSchema?: unknown },
): PackToolEntry {
  const description = (tool.description ?? `MCP tool ${tool.name} of pack ${pack}.`).trim();
  const schema =
    typeof tool.inputSchema === 'object' && tool.inputSchema !== null
      ? (tool.inputSchema as Record<string, unknown>)
      : { type: 'object', properties: {} };
  const labels = [...new Set([...(packLabels ?? []), ...AUTOMATIC_LABELS(pack)])];
  return {
    spec: {
      name: packToolName(pack, tool.name),
      description,
      primary: false,
      source: 'desktop',
      paramsSchema: schema,
      labels,
      allowedProfiles: ['desktop'],
      deferred: true,
      searchHint: `${pack} MCP pack: ${description}`,
      safety: 'MUTATING',
      requiresEngineRoles: [],
    },
    pack,
    tool: tool.name,
  };
}

/** Drop `disabledSubTools` (the pack file's per-tool switch, §1). */
export function filterSubTools<T extends { name: string }>(tools: T[], pack: string, disabled?: string[]): T[] {
  if (!Array.isArray(disabled) || disabled.length === 0) return tools;
  const deny = new Set(disabled.map((t) => packToolName(pack, t)).concat(disabled));
  return tools.filter((t) => !deny.has(t.name));
}
