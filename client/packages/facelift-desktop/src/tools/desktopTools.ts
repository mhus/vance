/**
 * Main-process service for the desktop agent tools. Owns the per-account
 * tool execution: policy load, permission gate, op dispatch.
 *
 * Trust model (planning/desktop-agent-tools.md §2.1): the renderer can
 * only *request* ({@code desktop-tools:invoke}), never *decide* — the
 * verdict comes from this module. The webContents a request arrives on
 * identifies the account; a renderer can never execute on behalf of
 * another account's WebView.
 *
 * The ops form a closed namespace (`file.*` today); unknown ops are
 * rejected with an error, never a fallthrough. Wire-level tool names
 * map 1:1 (`file.read` ↔ `client_file_read`).
 */
import { BrowserWindow, dialog, ipcMain } from 'electron';

import {
  getAccountConfig,
  setConfineToWorkdir,
  setToolsEnabled,
  setWorkdir,
  accountToolsDir,
} from './accountConfig';
import { ExecJobs, execJobsRoot } from './execOps';
import {
  fileCount,
  fileDelete,
  fileEdit,
  fileFind,
  fileGrep,
  fileHeadTail,
  fileList,
  fileRead,
  fileWrite,
} from './fileOps';
import { PermissionGate } from './permissionGate';
import {
  canonicalize,
  addRule,
  loadPolicy,
  readRules,
  removeRule,
  resetPolicy,
  denyFloor,
  type PermissionDomain,
  type AskSubject,
  type PermissionPolicy,
  type Verdict,
} from './permissionPolicy';
import { IPC, SHELL_IPC, type DesktopClientContext, type InvokeResult } from './types';

import os from 'node:os';
import path from 'node:path';

type FileOp = (params: Record<string, unknown>, workdir: string) => Promise<Record<string, unknown>>;

/** The closed op namespace. Every entry is gated on its `path` param
 *  against the account policy before it runs. */
const FILE_OPS: Record<string, FileOp> = {
  'file.read': fileRead,
  'file.write': fileWrite,
  'file.edit': fileEdit,
  'file.list': fileList,
  'file.grep': fileGrep,
  'file.find': fileFind,
  'file.count': fileCount,
  'file.head_tail': fileHeadTail,
};

/** file.delete is a separate entry, not in FILE_OPS: it runs through the
 *  gate's own `delete` domain (foot-sandbox.md §3.1). */
const DELETE_OP = 'file.delete';

/** The ungated exec-job inspection ops — same scope as foot's gate
 *  (job inspection is always permitted; only {@code exec.run} is checked). */
const EXEC_OPS: Record<
  string,
  (jobs: ExecJobs, params: Record<string, unknown>) => Promise<Record<string, unknown>>
> = {
  'exec.status': (jobs, p) => jobs.status(p),
  'exec.tail': (jobs, p) => jobs.tail(p),
  'exec.kill': (jobs, p) => jobs.kill(p),
  'exec.stat': (jobs, p) => jobs.stat(p),
};

const EXEC_RUN_OP = 'exec.run';

/** Per-account runtime: config + cached policy + exec jobs + ask label. */
interface AccountRuntime {
  accountId: string;
  displayName: string;
  policy: PermissionPolicy | null;
  exec: ExecJobs | null;
}

export class DesktopToolsService {
  /** webContents.id → account. Filled by registerView, cleared on close. */
  private readonly viewAccounts = new Map<number, AccountRuntime>();
  private readonly gate: PermissionGate;
  /** In-flight invoke count per account — drives the shell's activity push. */
  private readonly inflight = new Map<string, number>();
  private readonly windowProvider: () => BrowserWindow | null;

  constructor(windowProvider: () => BrowserWindow | null) {
    this.windowProvider = windowProvider;
    this.gate = new PermissionGate(windowProvider);
  }

  /** Register a newly created account WebView. The webContents id is the
   *  isolation key every invoke is resolved through. */
  registerView(accountId: string, displayName: string, webContentsId: number): void {
    this.viewAccounts.set(webContentsId, { accountId, displayName, policy: null, exec: null });
  }

  unregisterView(webContentsId: number): void {
    this.viewAccounts.delete(webContentsId);
  }

  /** Install the IPC handlers. Invoke handlers resolve the sender's
   *  account — a renderer cannot address another account's slot. */
  registerIpc(): void {
    ipcMain.handle(IPC.getContext, (event) => {
      const rt = this.viewAccounts.get(event.sender.id);
      return this.contextFor(rt);
    });
    ipcMain.handle(IPC.toolsEnabledGet, (event) => {
      const rt = this.viewAccounts.get(event.sender.id);
      if (!rt) return false;
      return getAccountConfig(rt.accountId).then((c) => c.toolsEnabled);
    });
    // Shell-only write path: the release button in the shell topbar
    // (planning/desktop-agent-tools.md §6.2). The account WebViews have
    // no set handler at all — remote content can never flip the release.
    ipcMain.handle(SHELL_IPC.toolsEnabledGet, (_e, o: unknown) => {
      const accountId = accountIdOf(o);
      return accountId === null ? false : getAccountConfig(accountId).then((c) => c.toolsEnabled);
    });
    ipcMain.handle(SHELL_IPC.toolsEnabledSet, (_e, o: unknown) => {
      const accountId = accountIdOf(o);
      if (accountId === null) return;
      if (typeof (o as { enabled?: unknown }).enabled === 'boolean') {
        void setToolsEnabled(accountId, (o as { enabled: boolean }).enabled);
      }
    });
    ipcMain.handle(SHELL_IPC.workdirGet, (_e, o: unknown) => {
      const accountId = accountIdOf(o);
      return accountId === null
        ? null
        : getAccountConfig(accountId).then((c) => c.workdir);
    });
    ipcMain.handle(SHELL_IPC.workdirSet, (_e, o: unknown) => {
      const accountId = accountIdOf(o);
      const workdir =
        typeof (o as { workdir?: unknown }).workdir === 'string'
          ? ((o as { workdir: string }).workdir).trim()
          : '';
      // Only a non-empty absolute path — the picker guarantees a real
      // directory; anything else would silently break relative resolution.
      if (accountId === null || workdir.length === 0 || !path.isAbsolute(workdir)) {
        return;
      }
      void setWorkdir(accountId, workdir);
    });
    ipcMain.handle(SHELL_IPC.workdirPick, async (_e, o: unknown) => {
      const accountId = accountIdOf(o);
      const win = this.windowProvider();
      if (accountId === null || !win) return null;
      const current = await getAccountConfig(accountId);
      const result = await dialog.showOpenDialog(win, {
        title: 'Choose the working directory for this account',
        defaultPath: current.workdir,
        properties: ['openDirectory', 'dontAddToRecent'],
      });
      return result.canceled ? null : (result.filePaths[0] ?? null);
    });
    ipcMain.handle(SHELL_IPC.confineGet, (_e, o: unknown) => {
      const accountId = accountIdOf(o);
      return accountId === null
        ? false
        : getAccountConfig(accountId).then((c) => c.confineToWorkdir);
    });
    ipcMain.handle(SHELL_IPC.confineSet, (_e, o: unknown) => {
      const accountId = accountIdOf(o);
      if (accountId !== null && typeof (o as { confined?: unknown }).confined === 'boolean') {
        void setConfineToWorkdir(accountId, (o as { confined: boolean }).confined);
      }
    });
    ipcMain.handle(SHELL_IPC.policyGet, (_e, o: unknown) => {
      const accountId = accountIdOf(o);
      if (accountId === null) return null;
      return readRules(this.policyFileOf(accountId)).then((rules) => ({
        ...rules,
        denyFloor: denyFloor(),
      }));
    });
    ipcMain.handle(SHELL_IPC.policyAddRule, (_e, o: unknown) => {
      const request = policyRuleOf(o);
      if (request === null) return null;
      return addRule(
        this.policyFileOf(request.accountId),
        request.domain,
        request.list,
        request.rule,
      ).then((rules) => ({ ...rules, denyFloor: denyFloor() }));
    });
    ipcMain.handle(SHELL_IPC.policyRemoveRule, (_e, o: unknown) => {
      const request = policyRuleOf(o);
      if (request === null) return null;
      return removeRule(
        this.policyFileOf(request.accountId),
        request.domain,
        request.list,
        request.rule,
      ).then((rules) => ({ ...rules, denyFloor: denyFloor() }));
    });
    ipcMain.handle(SHELL_IPC.policyReset, (_e, o: unknown) => {
      const accountId = accountIdOf(o);
      if (accountId === null) return;
      void resetPolicy(this.policyFileOf(accountId));
    });
    ipcMain.handle(IPC.invoke, (event, op: unknown, params: unknown) => {
      if (typeof op !== 'string' || typeof params !== 'object' || params === null) {
        return fail('Malformed invoke: op must be a string, params an object');
      }
      return this.invoke(event.sender.id, op, params as Record<string, unknown>);
    });
  }

/** The account's policy file path — same location the gate reads. */
  private policyFileOf(accountId: string): string {
    return path.join(accountToolsDir(accountId), 'permissions.yaml');
  }

  /** One operation: resolve the account, gate it, run it. */
  private async invoke(
    webContentsId: number,
    op: string,
    params: Record<string, unknown>,
  ): Promise<InvokeResult> {
    const rt = this.viewAccounts.get(webContentsId);
    if (!rt) {
      return fail('Desktop tools are not available for this view');
    }
    const config = await getAccountConfig(rt.accountId);
    if (!config.toolsEnabled) {
      return fail(
        'Desktop agent tools are disabled for this account — enable them with the release button in the app toolbar',
      );
    }
    const isFileOp = op in FILE_OPS || op === DELETE_OP;
    const isExecRun = op === EXEC_RUN_OP;
    const isExecInspection = op in EXEC_OPS;
    if (!isFileOp && !isExecRun && !isExecInspection) {
      return fail(`Unknown desktop tool op: ${op}`);
    }

    // Activity: the whole gated execution counts as "the agent is
    // working on this machine" — including a pending ASK dialog —
    // and every exit path (unknown op, deny, error, success) must drop
    // the counter, hence the try/finally.
    this.activityBegin(rt);
    try {
      const workdir = config.workdir;

      // Gate (foot-sandbox.md §3): file ops by their canonical path
      // (delete through its own domain), exec_run by its command string;
      // exec job inspection is always permitted.
      if (isFileOp) {
        const rawPath = typeof params.path === 'string' && params.path.length > 0 ? params.path : '.';
        const policy = await this.policyFor(rt, workdir, config.confineToWorkdir);
        const canonical = await canonicalize(rawPath, workdir);
        const isDelete = op === DELETE_OP;
        const verdict = policy.evaluatePath(canonical, isDelete);
        const allowed = await this.resolveVerdict(
          policy,
          { toolName: op, domain: isDelete ? 'delete' : 'paths', subject: canonical },
          rt,
          verdict,
        );
        if (!allowed) {
          return fail(
            `Permission denied: ${op} on ${canonical} was not granted`,
            'denied by policy or user',
          );
        }
      }
      if (isExecRun) {
        const command = typeof params.command === 'string' ? params.command : '';
        const policy = await this.policyFor(rt, workdir, config.confineToWorkdir);
        const verdict = policy.evaluateCommand(command);
        const allowed = await this.resolveVerdict(
          policy,
          { toolName: op, domain: 'commands', subject: command },
          rt,
          verdict,
        );
        if (!allowed) {
          return fail(
            `Permission denied: command '${command}' was not granted`,
            'denied by policy or user',
          );
        }
      }

      let result: Record<string, unknown>;
      if (isExecRun) {
        result = await this.execFor(rt, workdir).run(params);
      } else if (isExecInspection) {
        result = await EXEC_OPS[op](this.execFor(rt, workdir), params);
      } else if (op === DELETE_OP) {
        result = await fileDelete(params, workdir);
      } else {
        result = await FILE_OPS[op](params, workdir);
      }
      return { ok: true, result };
    } catch (e) {
      return fail(e instanceof Error ? e.message : String(e));
    } finally {
      this.activityEnd(rt);
    }
  }

  /** DENY never asks; ASK goes through the native dialog. */
  private async resolveVerdict(
    policy: PermissionPolicy,
    subject: AskSubject,
    rt: AccountRuntime,
    verdict: Verdict,
  ): Promise<boolean> {
    if (verdict === 'ALLOW') return true;
    if (verdict === 'DENY') return false;
    return this.gate.resolve(subject, rt.displayName, policy);
  }

  // ─── Activity tracking (shell push, §6.2) ───────────────────────────

  private activityBegin(rt: AccountRuntime): void {
    const next = (this.inflight.get(rt.accountId) ?? 0) + 1;
    this.inflight.set(rt.accountId, next);
    if (next === 1) {
      this.pushActivity(rt.accountId, true);
    }
  }

  private activityEnd(rt: AccountRuntime): void {
    const next = (this.inflight.get(rt.accountId) ?? 1) - 1;
    this.inflight.set(rt.accountId, next);
    if (next <= 0) {
      this.inflight.set(rt.accountId, 0);
      this.pushActivity(rt.accountId, false);
    }
  }

  private pushActivity(accountId: string, active: boolean): void {
    const win = this.windowProvider();
    if (!win || win.isDestroyed()) return;
    win.webContents.send(SHELL_IPC.toolsActivity, { accountId, active });
  }

  /** Per-account exec job registry — created lazily against the
   *  account's workdir; job ids never cross accounts. */
  private execFor(rt: AccountRuntime, workdir: string): ExecJobs {
    if (rt.exec === null || rt.exec.workdir !== workdir) {
      rt.exec = new ExecJobs(execJobsRoot(accountToolsDir(rt.accountId)), workdir);
    }
    return rt.exec;
  }

  /** Kill all running jobs of every account — the app-quit hook. */
  killAll(): void {
    for (const rt of this.viewAccounts.values()) {
      rt.exec?.killAll();
    }
  }

  /** Policy per account, loaded lazily and reloaded after "always" writes
   *  (persistAlways mutates the in-memory copy; the next fresh load for
   *  the *other* account's or a future session's view picks it up). */
  private async policyFor(
    rt: AccountRuntime,
    workdir: string,
    confineToWorkdir: boolean,
  ): Promise<PermissionPolicy> {
    const policyFile = path.join(accountToolsDir(rt.accountId), 'permissions.yaml');
    const policy = await loadPolicy(policyFile, workdir, { confineToWorkdir });
    rt.policy = policy;
    return policy;
  }

  /** Platform context reported to the hosted web UI — mirrors foot's
   *  handshake payload (os/arch/shell/cwd/sandboxEnabled/timezone). */
  private async contextFor(rt: AccountRuntime | undefined): Promise<DesktopClientContext> {
    const config = rt ? await getAccountConfig(rt.accountId) : null;
    return {
      os: normalizeOs(),
      arch: process.arch,
      shell: process.platform === 'win32' ? 'cmd.exe' : '/bin/sh',
      cwd: config?.workdir ?? os.homedir(),
      sandboxEnabled: true,
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone ?? 'UTC',
    };
  }
}


/** Extract a non-empty accountId from a shell IPC payload. */
function accountIdOf(payload: unknown): string | null {
  if (typeof payload !== 'object' || payload === null) return null;
  const id = (payload as { accountId?: unknown }).accountId;
  return typeof id === 'string' && id.length > 0 ? id : null;
}

/** Validated policy-rule request for the shell management handlers.
 * Rejects anything the UI did not explicitly send as a known
 * domain/list — the policy file is never mutated with guessed keys. */
interface PolicyRuleRequest {
  accountId: string;
  domain: PermissionDomain;
  list: 'allow' | 'deny';
  rule: string;
}

function policyRuleOf(payload: unknown): PolicyRuleRequest | null {
  if (typeof payload !== 'object' || payload === null) return null;
  const accountId = accountIdOf(payload);
  const domain = (payload as { domain?: unknown }).domain;
  const list = (payload as { list?: unknown }).list;
  const rule = (payload as { rule?: unknown }).rule;
  if (accountId === null) return null;
  if (domain !== 'paths' && domain !== 'commands' && domain !== 'delete') return null;
  if (list !== 'allow' && list !== 'deny') return null;
  if (typeof rule !== 'string' || rule.trim().length === 0) return null;
  return { accountId, domain, list, rule };
}
function fail(error: string, denyReason?: string): InvokeResult {
  return { ok: false, error, ...(denyReason !== undefined ? { denyReason } : {}) };
}

/** Normalised OS family — matches foot's {@code os} normalisation so
 *  the brain's environment block is dialect-consistent. */
function normalizeOs(): string {
  switch (process.platform) {
    case 'darwin':
      return 'macos';
    case 'win32':
      return 'windows';
    case 'linux':
      return 'linux';
    default:
      return process.platform;
  }
}
