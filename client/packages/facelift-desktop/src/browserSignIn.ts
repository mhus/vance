/**
 * SSO sign-in for the account views (specification/public/sso-login.md §8).
 *
 * Google refuses sign-ins inside embedded browsers, and a sign-in in the
 * system browser would leave its cookies there. So the page asks for
 * `window.vanceFacelift.browserSignIn(startUrl)`: the main process opens the
 * start URL in the default browser with a loopback `app_redirect`
 * (RFC 8252 §7.3), waits for the server to send the browser back with a
 * one-time code, and resolves with that URL. The page then redeems the code
 * with the PKCE verifier only it holds — the cookies land in the account's
 * own partition.
 *
 * A loopback listener needs no OS registration (no fight between a dev and a
 * release install over a URL scheme), and a process that catches the code on
 * the port still lacks the verifier.
 */
import { BrowserWindow, app, ipcMain, shell, type IpcMainInvokeEvent } from 'electron';
import http from 'node:http';
import type { AddressInfo } from 'node:net';

/** IPC channel — inlined as a literal in preload-tools.ts (sandboxed preloads cannot import). */
export const IPC_BROWSER_SIGN_IN = 'facelift-auth:browser-sign-in';

/** How long the user may take in the browser. */
const TIMEOUT_MS = 5 * 60_000;

const CALLBACK_PATH = '/sso-done';

const DONE_PAGE = `<!doctype html><html><head><meta charset="utf-8"><title>Vancetope</title></head>
<body style="font-family:system-ui,sans-serif;text-align:center;padding-top:20vh">
<p>Sign-in finished. You can close this window and return to Vancetope.</p></body></html>`;

/**
 * The start URL to open, with our loopback redirect — or `null` when the page
 * asked for anything but its own origin's `/brain/_sso/start`. The page must
 * not be able to turn this into "open any URL in the browser".
 */
export function prepareStartUrl(startUrl: string, pageUrl: string, port: number): string | null {
  let start: URL;
  let page: URL;
  try {
    start = new URL(startUrl);
    page = new URL(pageUrl);
  } catch {
    return null;
  }
  if (start.origin !== page.origin || start.pathname !== '/brain/_sso/start') return null;
  if (start.protocol !== 'https:' && start.protocol !== 'http:') return null;
  start.searchParams.set('app_redirect', `http://127.0.0.1:${port}${CALLBACK_PATH}`);
  return start.toString();
}

export function registerBrowserSignIn(): void {
  ipcMain.handle(IPC_BROWSER_SIGN_IN, (event: IpcMainInvokeEvent, startUrl: unknown) =>
    browserSignIn(event, startUrl),
  );
}

async function browserSignIn(event: IpcMainInvokeEvent, startUrl: unknown): Promise<string> {
  if (typeof startUrl !== 'string') throw new Error('invalid start URL');
  const server = http.createServer();
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve());
  });
  const port = (server.address() as AddressInfo).port;
  const url = prepareStartUrl(startUrl, event.sender.getURL(), port);
  if (url === null) {
    server.close();
    throw new Error('invalid start URL');
  }
  return new Promise<string>((resolve, reject) => {
    const timer = setTimeout(() => {
      server.close();
      reject(new Error('timeout'));
    }, TIMEOUT_MS);
    server.on('request', (req, res) => {
      const requested = new URL(req.url ?? '/', `http://127.0.0.1:${port}`);
      // Anything else on the port (a stray probe) is not the answer — keep waiting.
      if (req.method !== 'GET' || requested.pathname !== CALLBACK_PATH) {
        res.statusCode = 404;
        res.end();
        return;
      }
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(DONE_PAGE);
      clearTimeout(timer);
      server.close();
      bringToFront(event);
      resolve(requested.toString());
    });
    void shell.openExternal(url);
  });
}

function bringToFront(event: IpcMainInvokeEvent): void {
  const win = BrowserWindow.fromWebContents(event.sender) ?? BrowserWindow.getAllWindows()[0];
  if (win) {
    if (win.isMinimized()) win.restore();
    win.show();
    win.focus();
  }
  if (process.platform === 'darwin') app.focus({ steal: true });
}
