/**
 * Registers the desktop-only `client_file_*` tool family on the session
 * when the Facelift desktop app is the host (planning/desktop-agent-tools.md).
 *
 * Two gates, both green or nothing happens:
 * 1. **Capability** — `getDesktopTools()` returns a bridge. Only the real
 *    Electron preload injects it; a spoofed User-Agent creates no bridge.
 * 2. **Release** — the per-account `toolsEnabled` switch, read through the
 *    bridge (machine-local config; there is deliberately no brain setting).
 *
 * The registration itself rides the existing `client-tool-register`
 * protocol — same shape as the Cortex/Chat UI-state tools, only a
 * different family. Tool specs keep parity with the foot CLI's
 * `tools/file` package (name, paramsSchema, description semantics) so
 * the brain's generic `file_*` wrappers behave identically behind
 * either CLIENT backend.
 *
 * Handlers are thin forwarders onto the bridge; execution and the
 * permission gate live entirely in the Electron main process. A denied
 * call surfaces as a tool error with `denyReason`, never a silent no-op.
 */
import { ref, type Ref } from 'vue';

import {
  getDesktopTools,
  type BrainWsApi,
  type DesktopToolInvokeResult,
} from '@vance/shared';

type ToolSafety = 'SAFE_PROBE' | 'MUTATING';

interface ToolSpec {
  name: string;
  description: string;
  primary: boolean;
  source?: string;
  paramsSchema: Record<string, unknown>;
  labels: string[];
  allowedProfiles: string[];
  deferred: boolean;
  searchHint: string;
  safety: ToolSafety;
  requiresEngineRoles: string[];
}

interface ClientToolInvokeRequest {
  correlationId: string;
  name: string;
  params: Record<string, unknown>;
}

interface ClientToolInvokeResponse {
  correlationId: string;
  result: Record<string, unknown>;
  error?: string | null;
}

interface DesktopTool {
  op: string;
  spec: ToolSpec;
  /** Optional param remap — pack tools all ride `pack_invoke` with
   *  {pack, tool, args} built from the request params. */
  paramsAdapter?: (params: Record<string, unknown>) => Record<string, unknown>;
}

const READ_ONLY = ['read-only', 'filesystem', 'client'];
const WRITE_LABELS = ['filesystem', 'client'];
const EXEC_LABELS = ['executive', 'side-effect', 'execution', 'shell', 'client'];
const EXEC_INSPECT_LABELS = ['execution', 'shell', 'client'];

/** The exec family, wire-compatible with foot's `client_exec_*`: run is
 *  gated (command domain), status/tail/kill/stat are inspection ops. */
function desktopExecTools(): DesktopTool[] {
  return [
    {
      op: 'exec.run',
      spec: spec(
        'client_exec_run',
        "Run a shell command on the user's machine (desktop app) and "
          + 'wait up to ~15s. If it finishes in time you get status + stdout '
          + '+ stderr; otherwise the response carries a job id and '
          + 'status=RUNNING — follow up with client_exec_status. Logs '
          + 'live at stdoutPath/stderrPath; page through them with bounded '
          + "follow-ups (head, tail, grep -m, sed -n 'A,Bp').",
        {
          command: {
            type: 'string',
            description:
              "Shell command to run on the user's machine "
              + '(bash via /bin/sh -c on Linux/macOS, cmd.exe /c on Windows). '
              + 'Full shell syntax allowed; CWD is the account working dir.',
          },
          waitMs: {
            type: 'integer',
            description:
              'Milliseconds to wait INLINE for completion before returning. '
              + 'Default 15 000, capped at ~20 000. This is only the inline '
              + 'wait — a longer command (build, test suite) is NOT cut off: it keeps '
              + 'running in the background and the call returns status=RUNNING with a '
              + 'job id. Poll client_exec_status(id) until it finishes. Do NOT pass a '
              + "huge waitMs to 'wait out' a long command.",
          },
          deadlineSeconds: {
            type: 'integer',
            description:
              'Optional hard-kill deadline (seconds from now). If the subprocess '
              + 'is still running when the deadline passes, it is KILLED and the '
              + 'command does NOT finish, so its output stays partial. Size this to '
              + 'your WORST-CASE runtime estimate with generous headroom. For '
              + 'open-ended jobs prefer to OMIT it entirely — the job then runs to '
              + 'completion in the background and you poll client_exec_status.',
          },
        },
        ['command'],
        true,
        'MUTATING',
        "Run a shell command on the user's machine via the desktop app.",
        EXEC_LABELS,
      ),
    },
    {
      op: 'exec.status',
      spec: spec(
        'client_exec_status',
        'Check status and inline output of a previously started client-exec job '
          + 'by id. Returns the same shape as client_exec_run.',
        { id: { type: 'string', description: 'Job id returned by client_exec_run.' } },
        ['id'],
        true,
        'SAFE_PROBE',
        'Poll a running local command via the desktop app.',
        EXEC_INSPECT_LABELS,
      ),
    },
    {
      op: 'exec.tail',
      spec: spec(
        'client_exec_tail',
        "Return the last N lines (default 10, max 500) of a client-exec job's "
          + 'stdout or stderr log file. Lines come back oldest-first.',
        {
          id: { type: 'string', description: 'Job id returned by client_exec_run.' },
          n: { type: 'integer', description: 'Number of lines to return (default 10, max 500).' },
          stream: {
            type: 'string',
            description: 'Which stream to tail; default stdout.',
          },
        },
        ['id'],
        true,
        'SAFE_PROBE',
        'Tail a local command output via the desktop app.',
        EXEC_INSPECT_LABELS,
      ),
    },
    {
      op: 'exec.kill',
      spec: spec(
        'client_exec_kill',
        'Force-kill a still-running client-exec job. Returns killed=false '
          + "if it's already terminal or not found.",
        { id: { type: 'string', description: 'Job id to kill.' } },
        ['id'],
        true,
        'MUTATING',
        'Stop a running local command via the desktop app.',
        EXEC_INSPECT_LABELS,
      ),
    },
    {
      op: 'exec.stat',
      spec: spec(
        'client_exec_stat',
        'Compact status of a client-exec job: status, startedAt, lastOutputAt, '
          + 'finishedAt, exitCode, runtime, log sizes/mtimes. No stdout/stderr '
          + 'bodies — use client_exec_tail for those.',
        { id: { type: 'string', description: 'Job id returned by client_exec_run.' } },
        ['id'],
        true,
        'SAFE_PROBE',
        'Compact stats of a local command via the desktop app.',
        EXEC_INSPECT_LABELS,
      ),
    },
  ];
}


/** The file family, wire-compatible with foot's `client_file_*`. */
function desktopFileTools(): DesktopTool[] {
  return [
    {
      op: 'file.read',
      spec: spec(
        'client_file_read',
        'Read a text file on the user\'s machine (desktop app). '
          + 'Use startLine + maxLines to page large files; the result '
          + 'is capped at maxChars (default ~8 000).',
        {
          path: { type: 'string', description: 'Absolute or working-dir relative path on the user\'s machine.' },
          startLine: { type: 'integer', description: '1-based start line. Omit to start from the beginning.' },
          maxLines: { type: 'integer', description: 'Maximum lines to return. Omit for the default char cap.' },
          maxChars: {
            type: 'integer',
            description: 'Maximum characters to return. 0 or negative means the default cap of 8000.',
          },
        },
        ['path'],
        true,
        'SAFE_PROBE',
        'Read a local file on the user\'s machine via the desktop app.',
        READ_ONLY,
      ),
    },
    {
      op: 'file.write',
      spec: spec(
        'client_file_write',
        "Create or overwrite a UTF-8 file on the USER'S OWN MACHINE "
          + '(via the desktop app). Use this only when the user explicitly '
          + 'asks to write to their local disk — e.g. a code project '
          + "they're editing outside Vance, a lab notebook, downloads they "
          + 'want to keep. NOT for: research notes the user wants to find '
          + 'later inside Vance (use doc_write), or scriptable data for '
          + 'project-side processing (use work_file_write). Parent '
          + 'directories are created as needed. Pass the contentHash from '
          + 'your last client_file_read as expectedContentHash to refuse '
          + 'the write when the file changed since that read.',
        {
          path: { type: 'string', description: 'Absolute or working-dir relative file path.' },
          content: { type: 'string', description: 'Full file content. Replaces any existing content.' },
          expectedContentHash: {
            type: 'string',
            description:
              'Optional If-Match guard: the contentHash from your last read of this file. '
              + 'The write is refused when the file changed meanwhile.',
          },
        },
        ['path', 'content'],
        true,
        'MUTATING',
        'Write a local file on the user\'s machine via the desktop app.',
        WRITE_LABELS,
      ),
    },
    {
      op: 'file.edit',
      spec: spec(
        'client_file_edit',
        "Replace one occurrence of oldText with newText inside a file on "
          + "the user's machine. Fails if oldText is not found or appears "
          + 'more than once — add surrounding context until the match is '
          + 'unique. Preferred over rewriting the whole file. Pass the '
          + 'contentHash from your last file_read as expectedContentHash to '
          + 'refuse the edit when the file changed since that read.',
        {
          path: { type: 'string', description: 'Absolute or working-dir relative file path.' },
          oldText: { type: 'string', description: 'Exact snippet to replace. Whitespace-sensitive.' },
          newText: { type: 'string', description: 'Replacement text.' },
          expectedContentHash: {
            type: 'string',
            description:
              'Optional If-Match guard: the contentHash from your last read of this file. '
              + 'The edit is refused when the file changed meanwhile.',
          },
        },
        ['path', 'oldText', 'newText'],
        true,
        'MUTATING',
        'Edit a local file on the user\'s machine via the desktop app.',
        WRITE_LABELS,
      ),
    },
    {
      op: 'file.list',
      spec: spec(
        'client_file_list',
        'List the entries of a directory on the user\'s machine '
          + "(non-recursive). Directories are returned with a trailing "
          + "'/' suffix.",
        { path: { type: 'string', description: 'Directory path. Default: working directory.' } },
        [],
        true,
        'SAFE_PROBE',
        'List a local directory via the desktop app.',
        READ_ONLY,
      ),
    },
    {
      op: 'file.grep',
      spec: spec(
        'client_file_grep',
        "Recursively grep regex patterns across files on the user's "
          + 'machine. Returns matching lines with a 1-based line number '
          + 'and a path that can be passed straight to the other file '
          + 'tools, optionally with context. Binary / oversized files and '
          + 'common build output (node_modules, target, .git, dist, …) '
          + 'are skipped.',
        {
          pattern: { type: 'string', description: 'Regular expression. Plain substrings are fine.' },
          path: {
            type: 'string',
            description:
              "Directory to search (recursive) OR a single regular file. "
              + "Default: current working directory. Supports a leading '~/' for the user's home.",
          },
          pathGlob: {
            type: 'string',
            description: "Optional glob filter on file paths relative to 'path', e.g. '**/*.java'. Default: all files.",
          },
          caseInsensitive: { type: 'boolean', description: 'Match case-insensitively. Default: false.' },
          contextBefore: { type: 'integer', description: 'Number of lines before each match. Default: 0.' },
          contextAfter: { type: 'integer', description: 'Number of lines after each match. Default: 0.' },
          maxDepth: {
            type: 'integer',
            description: 'Recursion depth cap. Default: 12. Use 1 to scan a flat directory.',
          },
          limit: { type: 'integer', description: 'Cap on total match rows. Default: 200, max: 1000.' },
          includeGenerated: {
            type: 'boolean',
            description:
              'Also walk dependency and build directories (node_modules, target, dist, .git, …), '
              + 'which are skipped by default.',
          },
        },
        ['pattern'],
        true,
        'SAFE_PROBE',
        'Search local files for a pattern via the desktop app.',
        READ_ONLY,
      ),
    },
    {
      op: 'file.find',
      spec: spec(
        'client_file_find',
        "Find files on the user's machine by path glob, size range, and "
          + "modification-time range. Recursive walk under 'path'. Returns "
          + 'size + mtime per hit, with paths that can be passed straight '
          + 'to the other file tools.',
        {
          path: { type: 'string', description: 'Directory to walk. Default: current working directory.' },
          pathGlob: {
            type: 'string',
            description:
              "Glob pattern matched against the relative path under 'path', e.g. '**/*.md'. Default: all files.",
          },
          minSizeBytes: { type: 'integer', description: 'Skip files smaller than this. Default: no lower bound.' },
          maxSizeBytes: { type: 'integer', description: 'Skip files larger than this. Default: no upper bound.' },
          modifiedAfter: {
            type: 'string',
            description: 'ISO-8601 instant — only files modified strictly after. Default: no lower bound.',
          },
          modifiedBefore: {
            type: 'string',
            description: 'ISO-8601 instant — only files modified strictly before. Default: no upper bound.',
          },
          sortBy: {
            type: 'string',
            description: "Sort key. 'path' (default), 'mtime' (descending), 'size' (descending).",
          },
          maxDepth: {
            type: 'integer',
            description: 'Recursion depth cap. Default: 12. Use 1 to scan a flat directory.',
          },
          limit: { type: 'integer', description: 'Cap on entries returned. Default: 200, max: 2000.' },
          includeGenerated: {
            type: 'boolean',
            description:
              'Also walk dependency and build directories (node_modules, target, dist, .git, …), '
              + 'which are skipped by default.',
          },
        },
        [],
        true,
        'SAFE_PROBE',
        'Find local files by name/size/mtime via the desktop app.',
        READ_ONLY,
      ),
    },
    {
      op: 'file.count',
      spec: spec(
        'client_file_count',
        "Count lines, characters, and bytes for a file or — when 'path' is "
          + "a directory — across every file matching a glob. Optional regex "
          + 'narrows the line-count to matches (wc-style line/char/byte stats).',
        {
          path: {
            type: 'string',
            description:
              "File or directory on the user's machine. Directories are walked "
              + "recursively. Default: current working directory. Supports a leading '~/' for home.",
          },
          pathGlob: {
            type: 'string',
            description: "Glob filter on file paths under 'path' (directories only). Default: all files.",
          },
          pattern: {
            type: 'string',
            description:
              "Optional regex. When set, 'lines' counts only matching lines and 'chars' "
              + 'aggregates the matched line text.',
          },
          caseInsensitive: { type: 'boolean', description: 'Match the regex case-insensitively. Default: false.' },
          maxDepth: { type: 'integer', description: "Recursion depth cap when 'path' is a directory. Default: 12." },
          includeGenerated: {
            type: 'boolean',
            description:
              'Also walk dependency and build directories (node_modules, target, dist, .git, …), '
              + 'which are skipped by default.',
          },
        },
        [],
        true,
        'SAFE_PROBE',
        'Count lines/chars/bytes in local files via the desktop app.',
        READ_ONLY,
      ),
    },
    {
      op: 'file.head_tail',
      spec: spec(
        'client_file_head_tail',
        'Return the first N lines (head) and / or last N lines (tail) of '
          + "a file on the user's machine. At least one of head / tail "
          + 'must be > 0. Lines carry 1-based numbers.',
        {
          path: { type: 'string', description: "File path. Supports a leading '~/' for home." },
          head: { type: 'integer', description: 'Lines from the top. 0 / omitted = none. Capped at 200.' },
          tail: { type: 'integer', description: 'Lines from the bottom. Capped at 200.' },
        },
        ['path'],
        true,
        'SAFE_PROBE',
        'Peek at the start/end of a local file via the desktop app.',
        READ_ONLY,
      ),
    },
    {
      op: 'file.delete',
      spec: spec(
        'client_file_delete',
        "Delete a single file from the USER'S OWN MACHINE. Safe to call on "
          + "a path that doesn't exist — returns deleted=false. Directories "
          + 'are refused. The sandbox policy must permit deletion of that '
          + 'path specifically; permission to read or write it is not '
          + 'enough, so expect an interactive confirmation. Deleting a '
          + 'Vance document is doc_delete — this tool is for files on the '
          + "user's disk.",
        { path: { type: 'string', description: 'Absolute or working-dir relative file path.' } },
        ['path'],
        true,
        'MUTATING',
        'Delete a local file on the user\'s machine via the desktop app.',
        WRITE_LABELS,
      ),
    },
  ];
}

function spec(
  name: string,
  description: string,
  properties: Record<string, unknown>,
  required: string[],
  deferred: boolean,
  safety: ToolSafety,
  searchHint: string,
  labels: string[],
): ToolSpec {
  return {
    name,
    description,
    primary: false,
    // Provider discriminator for the server-side registration merge: one
    // WebView registers these AND the web UI's state tools (source
    // "chat") over the same socket — a re-registration replaces only
    // the tools of the sources it declares, so both groups coexist.
    source: 'desktop',
    paramsSchema: { type: 'object', properties, required },
    labels,
    allowedProfiles: ['desktop'],
    deferred,
    searchHint,
    safety,
    requiresEngineRoles: [],
  };
}

export class DesktopAgentToolService {
  /** True while at least one invoke is in flight — drives the toggle's
   *  activity indicator (same counted-ref pattern as the Cortex service). */
  readonly isExecuting: Ref<boolean> = ref(false);

  /** Per-account release switch, mirrored from the desktop config. */
  readonly enabled: Ref<boolean> = ref(false);

  readonly available: boolean;

  private invokeUnsub: (() => void) | null = null;
  private inflight = 0;

  constructor(private readonly bridge: ReturnType<typeof getDesktopTools>) {
    this.available = bridge !== null;
  }

  /** Create the service when the bridge exists, else an inert placeholder
   *  that never registers anything. */
  static create(): DesktopAgentToolService {
    return new DesktopAgentToolService(getDesktopTools());
  }

  /** Refresh the release switch from the machine-local config. */
  async refreshEnabled(): Promise<void> {
    if (!this.bridge) return;
    this.enabled.value = await this.bridge.toolsEnabled.get();
  }

  /** Refresh the release switch, then attach — the exact lifecycle the
   *  chat panels drive, so a toggle flip lands on the next bind. */
  async refreshAndAttach(ws: BrainWsApi): Promise<void> {
    await this.refreshEnabled();
    await this.attach(ws);
  }

  /** Push the registration and subscribe to invocations. Call on every
   *  WS open; the brain drops the surface when the socket closes. */
  async attach(ws: BrainWsApi): Promise<void> {
    if (!this.bridge || !this.enabled.value) return;
    const tools = [...desktopFileTools(), ...desktopExecTools()];
    // Tool packs (MCP): the specs come from the main process — the face
    // never sees pack files or servers, only the mapped tool surface.
    // A missing `packs` member (older app build) means an empty toolbox.
    const packEntries = (await this.bridge.packs?.list().catch((): [] => [])) ?? [];
    for (const entry of packEntries) {
      tools.push({
        op: 'pack_invoke',
        spec: entry.spec as ToolSpec,
        paramsAdapter: (params) => ({
          pack: entry.pack,
          tool: entry.tool,
          args: params,
        }),
      });
    }
    await ws.send('client-tool-register', { tools: tools.map((t) => t.spec) });
    this.invokeUnsub = ws.on<ClientToolInvokeRequest>('client-tool-invoke', (req) => {
      void this.onInvoke(ws, req, tools);
    });
  }

  detach(): void {
    this.invokeUnsub?.();
    this.invokeUnsub = null;
  }

  private async onInvoke(
    ws: BrainWsApi,
    req: ClientToolInvokeRequest,
    tools: DesktopTool[],
  ): Promise<void> {
    const correlationId = req.correlationId;
    const tool = tools.find((t) => t.spec.name === req.name);
    // One connection can host several tool providers (the desktop app:
    // this service AND the agent tools). Answering for a tool this
    // service did not register races the real owner's reply — the
    // brain completes the invocation on the first result. Stay
    // silent; a tool no service owns surfaces through the brain-side
    // invocation timeout.
    if (!tool) {
      console.warn('Ignoring client-tool-invoke for an unowned tool:', req.name);
      return;
    }
    let response: ClientToolInvokeResponse;
    this.beginExecuting();
    try {
      const outcome: DesktopToolInvokeResult = await this.bridge!.invoke(
        tool.op,
        tool.paramsAdapter ? tool.paramsAdapter(req.params ?? {}) : (req.params ?? {}),
      );
      response = outcome.ok
        ? { correlationId, result: outcome.result }
        : { correlationId, result: {}, error: outcome.error };
    } catch (e) {
      response = {
        correlationId,
        result: {},
        error: e instanceof Error ? e.message : String(e),
      };
    } finally {
      this.endExecuting();
    }
    await ws.send('client-tool-result', response);
  }

  private beginExecuting(): void {
    this.inflight++;
    this.isExecuting.value = true;
  }

  private endExecuting(): void {
    this.inflight--;
    if (this.inflight <= 0) {
      this.inflight = 0;
      this.isExecuting.value = false;
    }
  }
}
