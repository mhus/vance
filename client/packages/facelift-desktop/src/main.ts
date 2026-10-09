import { execSync } from 'node:child_process';
import { app, BrowserWindow, protocol } from 'electron';
import { readFile } from 'node:fs/promises';
import path from 'node:path';

import { AccountViewManager } from './account-view-manager';
import { DesktopToolsService } from './tools/desktopTools';
import { registerIpc } from './ipc';
let manager: AccountViewManager | null = null;
let tools: DesktopToolsService | null = null;

/**
 * Custom scheme for the shell renderer. The facelift-bridge Vite build
 * references its assets with absolute paths (`/assets/…`) — correct under
 * Capacitor's https origin on mobile, but broken under `file://` (a blank
 * window). Serving the build over a privileged standard+secure scheme
 * makes `/assets/…` resolve to the bundle root again, without touching the
 * shared bridge build config.
 */
/**
 * macOS apps launched from Finder/Dock get a minimal PATH
 * (/usr/bin:/bin:…) — npx, node, homebrew binaries are invisible to
 * spawned processes. That breaks MCP pack servers (npx …) and
 * exec_run commands with user binaries. Resolve the user's real PATH
 * from a login shell, exactly once at startup. No-op when the app
 * already inherits a rich PATH (terminal launch, dev runs).
 */
function extendPathForPackagedLaunch(): void {
  if (process.platform !== 'darwin') return;
  if ((process.env.PATH ?? '').includes('/opt/homebrew/bin')
      || (process.env.PATH ?? '').includes('/usr/local/bin')) {
    return;
  }
  try {
    // The inner command MUST be single-quoted: the outer /bin/sh expands
    // $PATH itself without them, and zsh would echo an empty line.
    const shellOutput = execSync("/bin/zsh -l -c 'echo $PATH'", {
      encoding: 'utf8',
      timeout: 3000,
    }).trim();
    // A login shell can print extra lines (zprofile hooks, banners) — the
    // PATH is the last colon-delimited line, not the whole output.
    const shellPath =
      shellOutput.split('\n').filter((line) => line.includes(':')).pop() ?? '';
    if (shellPath.includes('/bin')) {
      process.env.PATH = shellPath;
    } else {
      // Login shell unavailable — prepend the usual dev locations so
      // npx/node/homebrew stay reachable for MCP spawns and exec_run.
      process.env.PATH = [
        '/opt/homebrew/bin',
        '/usr/local/bin',
        process.env.PATH ?? '',
      ].join(':');
    }
  } catch {
    // Keep the default — a login shell is a best-effort convenience.
  }
}
extendPathForPackagedLaunch();

const RENDERER_SCHEME = 'vance-facelift-app';
const RENDERER_HOST = 'shell';

protocol.registerSchemesAsPrivileged([
  {
    scheme: RENDERER_SCHEME,
    privileges: {
      standard: true,
      secure: true,
      supportFetchAPI: true,
      stream: true,
    },
  },
]);

function rendererDir(): string {
  if (app.isPackaged) {
    // Bundled by electron-builder's extraResources (electron-builder.yml).
    return path.join(process.resourcesPath, 'renderer');
  }
  // Dev: the sibling facelift-bridge web build. Build it first:
  // `pnpm --filter @vance/facelift-bridge build`.
  return path.join(__dirname, '..', '..', 'facelift-bridge', 'dist');
}

const MIME: Record<string, string> = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.mjs': 'text/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.ico': 'image/x-icon',
  '.webp': 'image/webp',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.map': 'application/json',
};

function registerRendererProtocol(): void {
  const root = rendererDir();
  protocol.handle(RENDERER_SCHEME, async (request) => {
    const { pathname } = new URL(request.url);
    let rel = decodeURIComponent(pathname);
    if (rel === '/' || rel === '') rel = '/index.html';
    const filePath = path.normalize(path.join(root, rel));
    // Path-traversal guard — never serve outside the bundle root.
    if (filePath !== root && !filePath.startsWith(root + path.sep)) {
      return new Response('forbidden', { status: 403 });
    }
    try {
      const data = await readFile(filePath);
      const type = MIME[path.extname(filePath).toLowerCase()] ?? 'application/octet-stream';
      return new Response(data, { headers: { 'content-type': type } });
    } catch {
      return new Response('not found', { status: 404 });
    }
  });
}

function createWindow(toolService: DesktopToolsService): void {
  const win = new BrowserWindow({
    // Desktop-sized, not a phone frame. Sensible minimums so the shell +
    // account webview stay usable when the user shrinks the window.
    width: 1280,
    height: 832,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: '#1e3a8a',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      sandbox: true,
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  manager = new AccountViewManager(win, toolService);
  win.on('closed', () => {
    manager = null;
  });

  // Surface renderer load failures instead of a silent blank window.
  win.webContents.on('did-fail-load', (_e, code, desc, url) => {
    console.error(
      `[facelift-desktop] renderer failed to load: ${code} ${desc} ${url}`,
    );
  });
  void win.loadURL(`${RENDERER_SCHEME}://${RENDERER_HOST}/index.html`);
}

// Single instance: two processes on the same userData directory cannot
// share Chromium's profile lock — the second one comes up with an empty
// storage (looks like "no accounts"). A second launch focuses the running
// app instead of starting broken.
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    const [win] = BrowserWindow.getAllWindows();
    if (win) {
      if (win.isMinimized()) win.restore();
      win.show();
      win.focus();
    }
  });

  app.whenReady().then(() => {
    registerRendererProtocol();
    // IPC handlers are global; register once. They resolve the current
    // window's manager lazily via the getter.
    registerIpc(() => manager);
    tools = new DesktopToolsService(() => BrowserWindow.getAllWindows()[0] ?? null);
    tools.registerIpc();
    createWindow(tools);
    // No orphaned subprocesses: running exec jobs die with the app
    // (planning/desktop-agent-tools.md §4.2).
    app.on('will-quit', () => tools?.killAll());

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0 && tools !== null) {
        createWindow(tools);
      }
    });
  });
}

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
