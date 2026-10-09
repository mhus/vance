/**
 * The {@code file.*} operations of the desktop agent tools — the TS twin
 * of the foot CLI's {@code client_file_*} tools
 * (`vance-foot .../tools/file/`). Parity is the contract
 * (planning/desktop-agent-tools.md §4.1): names, params, result shapes
 * and error wording mirror the foot implementation; deviations are a
 * bug in one of the two.
 *
 * Shared walk defaults mirror {@code FileWalkDefaults} in vance-api —
 * the skip list must agree between CLIENT backends, or the generic
 * {@code file_*} wrapper lies about its behaviour.
 */
import { createHash } from 'node:crypto';
import {
  mkdir,
  readdir,
  readFile,
  stat,
  unlink,
  writeFile,
} from 'node:fs/promises';
import path from 'node:path';

import { expandHome } from './permissionPolicy';

/** FileWalkDefaults.SKIPPED_DIRS — directories stepped over by the
 *  recursive walks (generated content, tool state; never hand-written
 *  sources). Overridable per call via {@code includeGenerated}. */
const SKIPPED_DIRS = new Set([
  '.git', '.hg', '.svn',
  'node_modules', '.pnpm-store', 'bower_components',
  'target', 'build', 'dist', 'out',
  '.next', '.nuxt', '.svelte-kit', '.turbo', '.parcel-cache',
  '.gradle', '.m2',
  '__pycache__', '.venv', 'venv', '.tox', '.mypy_cache', '.pytest_cache', '.ruff_cache',
  '.idea', '.vscode',
  '.cache', 'coverage', '.nyc_output',
]);

const DEFAULT_CHAR_CAP = 8_000;
const GREP_DEFAULT_LIMIT = 200;
const GREP_MAX_LIMIT = 1_000;
const FIND_MAX_LIMIT = 2_000;
const DEFAULT_MAX_DEPTH = 12;
const GREP_MAX_FILE_BYTES = 2 * 1024 * 1024;
const MAX_LINE_CHARS = 600;
const TRUNCATE_MARK = '…[truncated]';
const FIND_MAX_FILE_BYTES = 10 * 1024 * 1024;

// ─── Param helpers ─────────────────────────────────────────────────────

export type Params = Record<string, unknown>;

function stringOrThrow(params: Params, key: string): string {
  const raw = params[key];
  if (typeof raw !== 'string' || raw.length === 0) {
    throw new Error(`'${key}' is required and must be a non-empty string`);
  }
  return raw;
}

function stringOrNull(params: Params, key: string): string | null {
  const raw = params[key];
  return typeof raw === 'string' ? raw : null;
}

function intOrNull(params: Params, key: string): number | null {
  const raw = params[key];
  if (typeof raw === 'number' && Number.isFinite(raw)) return Math.trunc(raw);
  return null;
}

// ─── Path helpers (ClientFilePaths twin) ──────────────────────────────

/** Expand {@code ~} and resolve against the account workdir. */
export function resolvePath(raw: string, workdir: string): string {
  const expanded = expandHome(raw);
  return path.isAbsolute(expanded) ? expanded : path.join(workdir, expanded);
}

/**
 * Render a path the way it must appear in tool output: workdir-relative
 * while the file lives under the workdir (what {@link resolvePath}
 * expects back), absolute otherwise — foot's {@code toToolPath} twin.
 */
export function toToolPath(file: string, workdir: string): string {
  const abs = path.resolve(file);
  const cwd = path.resolve(workdir);
  if (path.relative(cwd, abs).startsWith('..')) return abs;
  const rel = path.relative(cwd, abs);
  return rel === '' ? '.' : rel;
}

/** Describe a filesystem failure the way the foot tools do — the raw
 *  code + message plus the path, so the LLM can act on it. */
function describeFailure(p: string, e: unknown): string {
  const code = (e as NodeJS.ErrnoException | null)?.code;
  const message = e instanceof Error ? e.message : String(e);
  const abs = path.resolve(p);
  return code ? `${abs}: ${code} — ${message}` : `${abs}: ${message}`;
}

function wrapFailure(p: string, action: string, e: unknown): Error {
  if (e instanceof Error && e.message.startsWith(path.resolve(p))) return e;
  const cause = e instanceof Error ? e.message : String(e);
  return new Error(`${action} failed for ${path.resolve(p)}: ${cause}`, {
    cause: e,
  });
}

function sha256Hex(content: string | Buffer): string {
  return createHash('sha256').update(content).digest('hex');
}

function abbreviate(hash: string): string {
  return `${hash.slice(0, 8)}…${hash.slice(-4)}`;
}

// ─── Ops ───────────────────────────────────────────────────────────────

/** {@code client_file_read} — windowed, capped read with whole-file hash. */
export async function fileRead(params: Params, workdir: string): Promise<Record<string, unknown>> {
  const rawPath = stringOrThrow(params, 'path');
  const startLine = intOrNull(params, 'startLine');
  const maxLines = intOrNull(params, 'maxLines');
  const maxChars = intOrNull(params, 'maxChars');
  const cap = maxChars !== null && maxChars > 0 ? maxChars : DEFAULT_CHAR_CAP;
  const p = resolvePath(rawPath, workdir);
  let content: string;
  try {
    content = await readFile(p, 'utf-8');
  } catch (e) {
    throw wrapFailure(p, 'Read', e);
  }
  let region = content;
  if (startLine !== null || maxLines !== null) {
    const from = startLine === null ? 1 : Math.max(1, startLine);
    const count = maxLines === null ? Number.MAX_SAFE_INTEGER : Math.max(0, maxLines);
    const allLines = content.split('\n');
    region = allLines.slice(from - 1, from - 1 + count).join('\n');
  }
  const totalChars = region.length;
  const truncated = totalChars > cap;
  const served = truncated ? region.slice(0, cap) : region;
  return {
    path: toToolPath(p, workdir),
    content: served,
    truncated,
    totalChars,
    // Whole-file hash — identifies the file state for the edit/write
    // If-Match guard, not the served window.
    contentHash: sha256Hex(content),
  };
}

/** {@code client_file_write} — create/overwrite with optional If-Match. */
export async function fileWrite(params: Params, workdir: string): Promise<Record<string, unknown>> {
  const rawPath = stringOrThrow(params, 'path');
  const content = params.content;
  if (typeof content !== 'string') throw new Error("'content' is required");
  const expectedContentHash = stringOrNull(params, 'expectedContentHash');
  const p = resolvePath(rawPath, workdir);
  try {
    // If-Match before the write — a missing file counts as stale too.
    if (expectedContentHash !== null) {
      let actual: string;
      try {
        actual = sha256Hex(await readFile(p, 'utf-8'));
      } catch {
        throw new Error(
          'File changed since it was read (it no longer exists) — re-check with client_file_read before writing',
        );
      }
      if (expectedContentHash !== actual) {
        throw new Error(
          `File changed since it was read (contentHash mismatch: expected ${abbreviate(expectedContentHash)}, `
            + `found ${abbreviate(actual)}) — read the file again before overwriting it`,
        );
      }
    }
    await mkdir(path.dirname(p), { recursive: true });
    await writeFile(p, content, 'utf-8');
  } catch (e) {
    if (e instanceof Error && e.message.startsWith('File changed since')) throw e;
    throw wrapFailure(p, 'Write', e);
  }
  return { path: path.resolve(p), chars: content.length };
}

/** {@code client_file_edit} — unique-snippet replace with If-Match. */
export async function fileEdit(params: Params, workdir: string): Promise<Record<string, unknown>> {
  const rawPath = stringOrThrow(params, 'path');
  const oldText = params.oldText;
  if (typeof oldText !== 'string' || oldText.length === 0) throw new Error("'oldText' is required");
  const newText = params.newText;
  if (typeof newText !== 'string') throw new Error("'newText' is required");
  const expectedContentHash = stringOrNull(params, 'expectedContentHash');
  const p = resolvePath(rawPath, workdir);
  let content: string;
  try {
    content = await readFile(p, 'utf-8');
  } catch (e) {
    throw wrapFailure(p, 'Edit', e);
  }
  // If-Match before the snippet match — a stale file is the precondition
  // failure; telling the model to re-read is the right advice.
  if (expectedContentHash !== null) {
    const actual = sha256Hex(content);
    if (expectedContentHash !== actual) {
      throw new Error(
        `File changed since it was read (contentHash mismatch: expected ${abbreviate(expectedContentHash)}, `
          + `found ${abbreviate(actual)}) — read the file again and retry with the current contentHash`,
      );
    }
  }
  const first = content.indexOf(oldText);
  if (first < 0) {
    throw new Error(
      `oldText not found in ${path.resolve(p)} — read the file and copy the snippet verbatim `
        + '(whitespace and indentation included)',
    );
  }
  if (content.indexOf(oldText, first + oldText.length) >= 0) {
    throw new Error(
      `oldText appears multiple times in ${path.resolve(p)} — add surrounding context until the match is unique`,
    );
  }
  const updated = content.slice(0, first) + newText + content.slice(first + oldText.length);
  try {
    await writeFile(p, updated, 'utf-8');
  } catch (e) {
    throw wrapFailure(p, 'Edit', e);
  }
  return { path: path.resolve(p), replaced: 1 };
}

/** {@code client_file_list} — non-recursive directory listing with a
 *  trailing {@code /} for directories. */
export async function fileList(params: Params, workdir: string): Promise<Record<string, unknown>> {
  const rawPath = params.path;
  const p = typeof rawPath === 'string' && rawPath.length > 0 ? resolvePath(rawPath, workdir) : workdir;
  let info;
  try {
    info = await stat(p);
  } catch (e) {
    throw new Error(describeFailure(p, e), { cause: e });
  }
  if (!info.isDirectory()) {
    throw new Error(`Path is a file, not a directory: ${path.resolve(p)} — use client_file_read`);
  }
  const names: string[] = [];
  try {
    const dirents = await readdir(p, { withFileTypes: true });
    for (const e of dirents) names.push(e.name + (e.isDirectory() ? '/' : ''));
    names.sort();
  } catch (e) {
    throw wrapFailure(p, 'List', e);
  }
  return { path: path.resolve(p), entries: names, count: names.length };
}

/** {@code client_file_delete} — single file, refuses directories,
 *  {@code deleted=false} for missing targets. */
export async function fileDelete(params: Params, workdir: string): Promise<Record<string, unknown>> {
  const rawPath = stringOrThrow(params, 'path');
  const p = resolvePath(rawPath, workdir);
  const info = await stat(p).catch(() => null);
  if (info?.isDirectory()) {
    throw new Error(`'${path.resolve(p)}' is a directory — this tool deletes files only`);
  }
  let deleted: boolean;
  try {
    deleted = await unlink(p).then(
      () => true,
      (e: NodeJS.ErrnoException) => {
        if (e.code === 'ENOENT') return false;
        throw e;
      },
    );
  } catch (e) {
    throw wrapFailure(p, 'Delete', e);
  }
  return { path: path.resolve(p), deleted };
}

/** {@code client_file_grep} — recursive regex grep with relative-path
 *  glob filter, context lines, line clipping, noise-dir skipping, caps. */
export async function fileGrep(params: Params, workdir: string): Promise<Record<string, unknown>> {
  const patternStr = stringOrThrow(params, 'pattern');
  const ci = params.caseInsensitive === true;
  let pattern: RegExp;
  try {
    pattern = new RegExp(patternStr, ci ? 'i' : '');
  } catch (e) {
    throw new Error(`Invalid regex pattern: ${(e as Error).message}`, { cause: e });
  }
  const pathRaw = stringOrNull(params, 'path');
  const pathGlob = stringOrNull(params, 'pathGlob');
  const limitParam = intOrNull(params, 'limit');
  const limit = Math.min(limitParam !== null && limitParam > 0 ? limitParam : GREP_DEFAULT_LIMIT, GREP_MAX_LIMIT);
  const maxDepthParam = intOrNull(params, 'maxDepth');
  const maxDepth = maxDepthParam !== null && maxDepthParam > 0 ? maxDepthParam : DEFAULT_MAX_DEPTH;
  const before = Math.max(0, intOrNull(params, 'contextBefore') ?? 0);
  const after = Math.max(0, intOrNull(params, 'contextAfter') ?? 0);
  const includeGenerated = params.includeGenerated === true;

  const root = pathRaw !== null ? resolvePath(pathRaw, workdir) : workdir;
  const globRe = pathGlob !== null ? relativeGlobRegex(pathGlob) : null;
  const matches: Record<string, unknown>[] = [];
  let truncated = false;
  let filesScanned = 0;

  const consider = async (full: string): Promise<void> => {
    const size = await stat(full).then((s) => s.size).catch(() => -1);
    if (size < 0 || size > GREP_MAX_FILE_BYTES) return;
    const content = await readFile(full, 'utf-8').catch(() => null);
    if (content === null || content.includes('\0')) return;
    filesScanned++;
    const lines = content.split('\n');
    for (let i = 0; i < lines.length; i++) {
      if (!pattern.test(lines[i])) continue;
      if (matches.length >= limit) {
        truncated = true;
        return;
      }
      const m: Record<string, unknown> = {
        path: toToolPath(full, workdir),
        lineNumber: i + 1,
        line: clipLine(lines[i]),
      };
      if (before > 0 || after > 0) {
        const ctx: Record<string, unknown>[] = [];
        const from = Math.max(0, i - before);
        const to = Math.min(lines.length - 1, i + after);
        for (let j = from; j <= to; j++) {
          if (j === i) continue;
          ctx.push({ lineNumber: j + 1, line: clipLine(lines[j]) });
        }
        if (ctx.length > 0) m.context = ctx;
      }
      matches.push(m);
    }
  };

  const walk = async (dir: string, depth: number): Promise<void> => {
    if (depth > maxDepth) return;
    const dirents = await readdir(dir, { withFileTypes: true }).catch(() => []);
    for (const entry of dirents) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (!includeGenerated && SKIPPED_DIRS.has(entry.name)) continue;
        await walk(full, depth + 1);
        continue;
      }
      if (!entry.isFile()) continue;
      if (globRe !== null && !globRe.test(toPosix(path.relative(root, full)))) continue;
      await consider(full);
    }
  };

  const rootInfo = await stat(root).catch(() => null);
  if (rootInfo === null) throw new Error(describeFailure(root, Object.assign(new Error(), { code: 'ENOENT' })));
  if (rootInfo.isFile()) {
    await consider(root);
  } else {
    await walk(root, 1);
  }

  return {
    path: path.resolve(root),
    filesScanned,
    pattern: patternStr,
    matchCount: matches.length,
    truncated,
    matches,
  };
}

/** {@code client_file_find} — recursive walk with relative-path glob,
 *  size and mtime filters, sorting, caps. */
export async function fileFind(params: Params, workdir: string): Promise<Record<string, unknown>> {
  const pathRaw = stringOrNull(params, 'path');
  const pathGlob = stringOrNull(params, 'pathGlob');
  const minSizeBytes = intOrNull(params, 'minSizeBytes');
  const maxSizeBytes = intOrNull(params, 'maxSizeBytes');
  const modifiedAfter = stringOrNull(params, 'modifiedAfter');
  const modifiedBefore = stringOrNull(params, 'modifiedBefore');
  const sortBy = stringOrNull(params, 'sortBy') ?? 'path';
  const limitParam = intOrNull(params, 'limit');
  const limit = Math.min(limitParam !== null && limitParam > 0 ? limitParam : GREP_DEFAULT_LIMIT, FIND_MAX_LIMIT);
  const maxDepthParam = intOrNull(params, 'maxDepth');
  const maxDepth = maxDepthParam !== null && maxDepthParam > 0 ? maxDepthParam : DEFAULT_MAX_DEPTH;
  const includeGenerated = params.includeGenerated === true;

  const root = pathRaw !== null ? resolvePath(pathRaw, workdir) : workdir;
  const globRe = pathGlob !== null ? relativeGlobRegex(pathGlob) : null;
  const afterMs = parseInstant(modifiedAfter);
  const beforeMs = parseInstant(modifiedBefore);

  interface Row {
    path: string;
    size: number;
    modifiedAt: string;
    mtimeMs: number;
  }
  const rows: Row[] = [];
  let totalConsidered = 0;
  let generatedSkipped = 0;
  let truncated = false;

  const walk = async (dir: string, depth: number): Promise<void> => {
    if (depth > maxDepth) return;
    const dirents = await readdir(dir, { withFileTypes: true }).catch(() => []);
    for (const entry of dirents) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (!includeGenerated && SKIPPED_DIRS.has(entry.name)) {
          generatedSkipped++;
          continue;
        }
        await walk(full, depth + 1);
        continue;
      }
      if (!entry.isFile()) continue;
      if (globRe !== null && !globRe.test(toPosix(path.relative(root, full)))) continue;
      const info = await stat(full).catch(() => null);
      if (info === null) continue;
      if (info.size > FIND_MAX_FILE_BYTES) continue;
      if (minSizeBytes !== null && info.size < minSizeBytes) continue;
      if (maxSizeBytes !== null && info.size > maxSizeBytes) continue;
      if (afterMs !== null && info.mtimeMs <= afterMs) continue;
      if (beforeMs !== null && info.mtimeMs >= beforeMs) continue;
      totalConsidered++;
      if (rows.length >= limit) {
        truncated = true;
        return;
      }
      rows.push({
        path: toToolPath(full, workdir),
        size: info.size,
        modifiedAt: info.mtime.toISOString(),
        mtimeMs: info.mtimeMs,
      });
    }
  };

  await walk(root, 1);

  if (sortBy === 'mtime') {
    rows.sort((a, b) => b.mtimeMs - a.mtimeMs);
  } else if (sortBy === 'size') {
    rows.sort((a, b) => b.size - a.size);
  } else {
    rows.sort((a, b) => a.path.localeCompare(b.path));
  }

  const out: Record<string, unknown> = {
    path: path.resolve(root),
    totalConsidered,
    matchCount: rows.length,
    truncated,
    results: rows.map(({ path: p, size, modifiedAt }) => ({ path: p, size, modifiedAt })),
  };
  if (pathGlob !== null) out.pathGlob = pathGlob;
  if (generatedSkipped > 0) {
    out.generatedFilesSkipped = generatedSkipped;
    out.generatedFilesHint =
      'Dependency/build directories were skipped — pass includeGenerated=true to search them.';
  }
  return out;
}

/** {@code client_file_count} — wc-style stats for a file or, when the
 *  path is a directory, every matching file under it. */
export async function fileCount(params: Params, workdir: string): Promise<Record<string, unknown>> {
  const pathRaw = stringOrNull(params, 'path');
  const pathGlob = stringOrNull(params, 'pathGlob');
  const patternStr = stringOrNull(params, 'pattern');
  const ci = params.caseInsensitive === true;
  let pattern: RegExp | null = null;
  if (patternStr !== null) {
    try {
      pattern = new RegExp(patternStr, ci ? 'i' : '');
    } catch (e) {
      throw new Error(`Invalid regex pattern: ${(e as Error).message}`, { cause: e });
    }
  }
  const includeGenerated = params.includeGenerated === true;
  const root = pathRaw !== null ? resolvePath(pathRaw, workdir) : workdir;
  const rootInfo = await stat(root).catch(() => null);
  if (rootInfo === null) {
    throw new Error(describeFailure(root, Object.assign(new Error(), { code: 'ENOENT' })));
  }

  let filesCounted = 0;
  let filesSkipped = 0;
  let generatedSkipped = 0;
  let lines = 0;
  let matchingLines = 0;
  let chars = 0;
  let bytes = 0;

  const countFile = async (full: string): Promise<void> => {
    const info = await stat(full).catch(() => null);
    if (info === null || !info.isFile() || info.size > GREP_MAX_FILE_BYTES) {
      if (info !== null) filesSkipped++;
      return;
    }
    const content = await readFile(full, 'utf-8').catch(() => null);
    if (content === null) {
      filesSkipped++;
      return;
    }
    filesCounted++;
    bytes += info.size;
    chars += [...content].length;
    const contentLines = content.split('\n').length;
    lines += contentLines;
    if (pattern !== null) {
      for (const line of content.split('\n')) {
        if (pattern.test(line)) matchingLines++;
      }
    }
  };

  if (rootInfo.isFile()) {
    await countFile(root);
  } else {
    const walk = async (dir: string, depth: number): Promise<void> => {
      if (depth > DEFAULT_MAX_DEPTH) return;
      const dirents = await readdir(dir, { withFileTypes: true }).catch(() => []);
      for (const entry of dirents) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
          if (!includeGenerated && SKIPPED_DIRS.has(entry.name)) {
            generatedSkipped++;
            continue;
          }
          await walk(full, depth + 1);
          continue;
        }
        if (pathGlob !== null && !relativeGlobRegex(pathGlob).test(toPosix(path.relative(root, full)))) {
          continue;
        }
        await countFile(full);
      }
    };
    await walk(root, 1);
  }

  const out: Record<string, unknown> = {
    path: path.resolve(root),
    filesCounted,
    filesSkipped,
    lines: pattern === null ? lines : matchingLines,
    chars,
    bytes,
  };
  if (pathGlob !== null) out.pathGlob = pathGlob;
  if (patternStr !== null) {
    out.pattern = patternStr;
    out.totalLinesScanned = lines;
  }
  if (generatedSkipped > 0) {
    out.generatedFilesSkipped = generatedSkipped;
    out.generatedFilesHint =
      'Dependency/build directories were skipped — pass includeGenerated=true to count them.';
  }
  return out;
}

/** {@code client_file_head_tail} — numbered head and/or tail window. */
export async function fileHeadTail(params: Params, workdir: string): Promise<Record<string, unknown>> {
  const rawPath = stringOrThrow(params, 'path');
  const head = intOrNull(params, 'head') ?? 0;
  const tail = intOrNull(params, 'tail') ?? 0;
  if (head <= 0 && tail <= 0) {
    throw new Error('At least one of head / tail must be > 0');
  }
  const p = resolvePath(rawPath, workdir);
  let content: string;
  try {
    content = await readFile(p, 'utf-8');
  } catch (e) {
    throw wrapFailure(p, 'Read', e);
  }
  const all = content.split('\n');
  const total = all.length;
  const out: Record<string, unknown> = { path: path.resolve(p), totalLines: total };
  if (head > 0) {
    const n = Math.min(head, total);
    const rows: Record<string, unknown>[] = [];
    for (let i = 0; i < n; i++) rows.push({ lineNumber: i + 1, line: clipLine(all[i]) });
    out.head = rows;
  }
  if (tail > 0) {
    const n = Math.min(tail, total);
    const rows: Record<string, unknown>[] = [];
    const start = total - n;
    for (let i = 0; i < n; i++) rows.push({ lineNumber: start + i + 1, line: clipLine(all[start + i]) });
    out.tail = rows;
  }
  return out;
}

// ─── helpers ──────────────────────────────────────────────────────────

function clipLine(line: string): string {
  return line.length <= MAX_LINE_CHARS ? line : line.slice(0, MAX_LINE_CHARS) + TRUNCATE_MARK;
}

/**
 * A search-root-relative glob for the {@code pathGlob} params of
 * grep/find/count: a double asterisk crosses directories
 * ({@code **} followed by a slash), a single {@code *} stays within
 * one segment, and a trailing {@code dir/**} also matches the
 * directory itself — {@code GlobMatchers} twin of the foot side.
 */
export function relativeGlobRegex(glob: string): RegExp {
  let re = '';
  let i = 0;
  while (i < glob.length) {
    const c = glob[i];
    if (c === '*') {
      if (glob[i + 1] === '*') {
        i += 2;
        if (i >= glob.length && re.endsWith('/')) {
          re = `${re.slice(0, -1)}(/.*)?$`;
          return new RegExp(re);
        }
        re += '.*';
        if (glob[i] === '/') i++;
      } else {
        re += '[^/]*';
        i++;
      }
    } else if (c === '?') {
      re += '[^/]';
      i++;
    } else if ('\\^$.|+()[]{}'.includes(c)) {
      re += `\\${c}`;
      i++;
    } else {
      re += c;
      i++;
    }
  }
  return new RegExp(`^${re}$`);
}

/** Parse an ISO-8601 instant param; null for absent or malformed
 *  values (never fails the call). */
function toPosix(p: string): string {
  return p.replace(/\\/g, '/');
}

function parseInstant(raw: string | null): number | null {
  if (raw === null) return null;
  const ms = Date.parse(raw);
  return Number.isNaN(ms) ? null : ms;
}

