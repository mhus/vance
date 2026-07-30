import {
  BrowserWindow,
  WebContentsView,
  session,
  shell,
  type Event as ElectronEvent,
} from 'electron';

import {
  URL_SCHEME,
  USER_AGENT_SUFFIX,
  type AccountWebViewBounds,
  type NavigateHomeOptions,
  type PresentOptions,
  type RemoveOptions,
} from './types';

/**
 * Manages one isolated {@link WebContentsView} per account, layered over
 * the shell renderer inside the main window. Each view is bound to its own
 * `persist:vance-<accountId>` session partition — the Electron analog of
 * iOS `WKWebsiteDataStore(forIdentifier:)` / Android `Profile` — so two
 * accounts on the same Brain origin stay fully isolated.
 */
export class AccountViewManager {
  private readonly win: BrowserWindow;
  private readonly views = new Map<string, WebContentsView>();
  private readonly homeHosts = new Map<string, string>();
  private activeAccountId: string | null = null;

  constructor(win: BrowserWindow) {
    this.win = win;
  }

  private partitionFor(accountId: string): string {
    return `persist:vance-${accountId}`;
  }

  private boundsToRect(b: AccountWebViewBounds): Electron.Rectangle {
    // CSS px map ~1:1 to Electron DIP; Electron handles the display scale.
    return {
      x: Math.round(b.left),
      y: Math.round(b.top),
      width: Math.round(b.width),
      height: Math.round(b.height),
    };
  }

  present(options: PresentOptions): void {
    const { accountId, url } = options;
    const rect = this.boundsToRect(options);

    let view = this.views.get(accountId);
    if (!view) {
      view = this.createView(accountId, url);
      this.views.set(accountId, view);
      try {
        this.homeHosts.set(accountId, new URL(url).host.toLowerCase());
      } catch {
        // Malformed URL — leave homeHost unset; the external-link guard
        // then simply won't fire for this view.
      }
      this.win.contentView.addChildView(view);
      view.webContents.loadURL(url);
    } else {
      // Cached view may have been detached by a previous dismiss/switch —
      // re-attach it (addChildView is a no-op if already a child, and also
      // raises it to the top of the z-order).
      this.win.contentView.addChildView(view);
    }

    // Hide whichever other view is currently on screen.
    if (this.activeAccountId && this.activeAccountId !== accountId) {
      const previous = this.views.get(this.activeAccountId);
      if (previous) this.win.contentView.removeChildView(previous);
    }

    view.setBounds(rect);
    view.setVisible(true);
    this.activeAccountId = accountId;
  }

  dismiss(): void {
    if (!this.activeAccountId) return;
    const view = this.views.get(this.activeAccountId);
    if (view) this.win.contentView.removeChildView(view);
  }

  setBounds(bounds: AccountWebViewBounds): void {
    if (!this.activeAccountId) return;
    const view = this.views.get(this.activeAccountId);
    if (view) view.setBounds(this.boundsToRect(bounds));
  }

  reload(): void {
    if (!this.activeAccountId) return;
    this.views.get(this.activeAccountId)?.webContents.reload();
  }

  navigateHome(options: NavigateHomeOptions): void {
    // Re-navigate the cached view without dropping its partition (cookies /
    // login survive). No-op if the view was never created.
    this.views.get(options.accountId)?.webContents.loadURL(options.url);
  }

  remove(options: RemoveOptions): void {
    const { accountId } = options;
    const view = this.views.get(accountId);
    if (view) {
      this.win.contentView.removeChildView(view);
      view.webContents.close();
      this.views.delete(accountId);
      if (this.activeAccountId === accountId) this.activeAccountId = null;
    }
    this.homeHosts.delete(accountId);
    // Wipe the persistent partition so a future re-add of the same UUID
    // starts clean. Best-effort — a failure must not reject the caller.
    session
      .fromPartition(this.partitionFor(accountId))
      .clearStorageData()
      .catch(() => {
        /* ignore */
      });
  }

  private createView(accountId: string, _url: string): WebContentsView {
    const partition = this.partitionFor(accountId);

    // Remote content — lock it down: sandboxed, isolated, no Node.
    const view = new WebContentsView({
      webPreferences: {
        partition,
        sandbox: true,
        contextIsolation: true,
        nodeIntegration: false,
      },
    });
    const wc = view.webContents;
    wc.setUserAgent(`${wc.getUserAgent()} ${USER_AGENT_SUFFIX}`);

    // Grant in-WebView media capture (voice STT / photo) at the partition
    // level; the OS still owns the actual device permission.
    session.fromPartition(partition).setPermissionRequestHandler(
      (_wc, permission, callback) => {
        callback(permission === 'media');
      },
    );

    wc.on('will-navigate', (event: ElectronEvent, navUrl: string) => {
      if (this.handleNavigation(accountId, navUrl)) event.preventDefault();
    });

    // target="_blank" / window.open(): never spawn a popup window — handle
    // it in-place, exactly like the iOS WKUIDelegate.
    wc.setWindowOpenHandler(({ url }) => {
      if (!this.handleNavigation(accountId, url)) {
        // Same-origin link that would have opened a new window → load it in
        // the same view instead of silently no-op'ing.
        wc.loadURL(url);
      }
      return { action: 'deny' };
    });

    return view;
  }

  /**
   * @returns true if the navigation was handled here (caller should cancel
   * it), false to let the view navigate normally.
   */
  private handleNavigation(accountId: string, rawUrl: string): boolean {
    let parsed: URL;
    try {
      parsed = new URL(rawUrl);
    } catch {
      return false;
    }

    // vance-facelift://* → forward to the shell as a urlOpen event.
    if (parsed.protocol === `${URL_SCHEME}:`) {
      this.win.webContents.send('facelift:urlOpen', { url: rawUrl });
      return true;
    }

    // External-link guard: a different host is opened in the system browser
    // rather than turning the wrapper into a general browser. OAuth bounces
    // to external IdPs leave the app — an accepted v1 trade-off.
    const homeHost = this.homeHosts.get(accountId);
    const nextHost = parsed.host.toLowerCase();
    if (homeHost && nextHost && nextHost !== homeHost) {
      void shell.openExternal(rawUrl);
      return true;
    }
    return false;
  }
}
