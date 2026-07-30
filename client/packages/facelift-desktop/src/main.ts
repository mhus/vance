import { app, BrowserWindow } from 'electron';
import path from 'node:path';

import { AccountViewManager } from './account-view-manager';
import { registerIpc } from './ipc';

let manager: AccountViewManager | null = null;

function rendererIndexHtml(): string {
  if (app.isPackaged) {
    // Bundled by electron-builder's extraResources (electron-builder.yml).
    return path.join(process.resourcesPath, 'renderer', 'index.html');
  }
  // Dev: the sibling facelift-bridge web build. Build it first:
  // `pnpm --filter @vance/facelift-bridge build`.
  return path.join(
    __dirname,
    '..',
    '..',
    'facelift-bridge',
    'dist',
    'index.html',
  );
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

  void win.loadFile(rendererIndexHtml());
}

app.whenReady().then(() => {
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
