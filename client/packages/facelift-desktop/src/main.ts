import { app, BrowserWindow, protocol } from 'electron';
import { readFile } from 'node:fs/promises';
import path from 'node:path';

import { AccountViewManager } from './account-view-manager';
import { registerIpc } from './ipc';

let manager: AccountViewManager | null = null;

/**
 * Custom scheme for the shell renderer. The facelift-bridge Vite build
 * references its assets with absolute paths (`/assets/…`) — correct under
 * Capacitor's https origin on mobile, but broken under `file://` (a blank
 * window). Serving the build over a privileged standard+secure scheme
 * makes `/assets/…` resolve to the bundle root again, without touching the
 * shared bridge build config.
 */
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

function createWindow(): void {
  const win = new BrowserWindow({
    width: 420,
    height: 900,
    backgroundColor: '#1e3a8a',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      sandbox: true,
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  manager = new AccountViewManager(win);
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

app.whenReady().then(() => {
  registerRendererProtocol();
  // IPC handlers are global; register once. They resolve the current
  // window's manager lazily via the getter.
  registerIpc(() => manager);
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
