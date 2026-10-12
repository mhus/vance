import {
  BrowserWindow,
  WebContentsView,
  session,
  shell,
  type Event as ElectronEvent,
} from 'electron';
import path from 'node:path';

import { DesktopToolsService } from './tools/desktopTools';
import {
  URL_SCHEME,
  USER_AGENT_SUFFIX,
  DESKTOP_USER_AGENT_TOKEN,
  type AccountWebViewBounds,
  type CloseTabOptions,
  type NavigateHomeOptions,
  type PresentOptions,
  type RemoveOptions,
} from './types';

/** Tab key in the views map — composite of account and tab id. */
const tabKey = (accountId: string, tabId: string): string => `${accountId}#${tabId}`;
/** The tab an account falls back to before the shell ever sent one. */
const DEFAULT_TAB = '1';
/**
 * Manages one isolated {@link WebContentsView} per account, layered over
 * the shell renderer inside the main window. Each view is bound to its own
 * `persist:vance-<accountId>` session partition — the Electron analog of
 * iOS `WKWebsiteDataStore(forIdentifier:)` / Android `Profile` — so two
 * accounts on the same Brain origin stay fully isolated.
 */
export class AccountViewManager {
  private readonly win: BrowserWindow;
  private readonly tools: DesktopToolsService;
  private readonly views = new Map<string, WebContentsView>();
  private readonly homeHosts = new Map<string, string>();
  /** Active tab per account — the shell owns the tab list; this is only
   *  "which one is on screen", so bounds/reload/home address the right
   *  view (planning/desktop-profile-tabs.md §2). */
  private readonly activeTab = new Map<string, string>();
  private activeAccountId: string | null = null;

  private viewsOf(accountId: string): [string, WebContentsView][] {
    const out: [string, WebContentsView][] = [];
    const prefix = `${accountId}#`;
    for (const [key, view] of this.views) {
      if (key.startsWith(prefix)) out.push([key.slice(prefix.length), view]);
    }
    return out;
  }

  private hideAll(exceptAccountId: string): void {
    for (const [key, view] of this.views) {
      if (!key.startsWith(`${exceptAccountId}#`)) {
        this.win.contentView.removeChildView(view);
      }
    }
  }

  constructor(win: BrowserWindow, tools: DesktopToolsService) {
    this.win = win;
    this.tools = tools;
    // Meta/Ctrl+1..0 while the SHELL has focus — the same interception the
    // account views get (below); one path, two focus homes.
    this.win.webContents.on('before-input-event', (event, input) => {
      this.onTabShortcutInput(event, input, () => this.activeAccountId, () => this.reload());
    });
  }

  /** Tab shortcuts (planning/desktop-profile-tabs.md): Meta/Ctrl+digit
   *  switches tabs, +R reloads the focused view, +W closes the active
   *  tab (the shell owns the list and enforces min-1). Runs in the main
   *  process on before-input-event — BEFORE any page-level hotkey — and
   *  works no matter which webContents has focus. The default Electron
   *  menu is gone (main.ts), so nothing else claims W/R. */
  private onTabShortcutInput(
    event: { preventDefault(): void },
    input: { type: string; key: string; control: boolean; meta: boolean },
    accountOf: () => string | null,
    reloadTarget: () => void,
  ): void {
    if (input.type !== 'keyDown') return;
    // Apple key on macOS, Ctrl elsewhere — the browser-tab convention.
    const modifier = process.platform === 'darwin' ? input.meta : input.control;
    if (!modifier) return;
    const accountId = accountOf();
    if (accountId === null) return;
    if (input.key.length === 1 && /^[0-9]$/.test(input.key)) {
      event.preventDefault();
      this.win.webContents.send('facelift:tab-shortcut', {
        accountId,
        digit: input.key as '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9',
      });
      return;
    }
    if (input.key.toLowerCase() === 'r') {
      event.preventDefault();
      reloadTarget();
      return;
    }
    if (input.key.toLowerCase() === 'w') {
      event.preventDefault();
      this.win.webContents.send('facelift:tab-close-request', { accountId });
    }
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
    const tabId = options.tabId ?? this.activeTab.get(accountId) ?? DEFAULT_TAB;
    const key = tabKey(accountId, tabId);

    let view = this.views.get(key);
    if (!view) {
      view = this.createView(accountId, tabId, url);
      this.views.set(key, view);
      if (!this.homeHosts.has(accountId)) {
        try {
          this.homeHosts.set(accountId, new URL(url).host.toLowerCase());
        } catch {
          // Malformed URL — leave homeHost unset; the external-link guard
          // then simply won't fire for this view.
        }
      }
      this.win.contentView.addChildView(view);
      view.webContents.loadURL(url);
    } else {
      // Cached view may have been detached by a previous dismiss/switch —
      // re-attach it (addChildView is a no-op if already a child, and also
      // raises it to the top of the z-order). Its page state survives:
      // that is the whole point of live tabs.
      this.win.contentView.addChildView(view);
    }

    // Hide every view of OTHER accounts (an account switch); sibling
    // tabs of this account are hidden below when another tab is active.
    this.hideAll(accountId);
    for (const [siblingId, sibling] of this.viewsOf(accountId)) {
      if (siblingId !== tabId) sibling.setVisible(false);
    }

    view.setBounds(rect);
    view.setVisible(true);
    this.activeAccountId = accountId;
    this.activeTab.set(accountId, tabId);
  }

  /** Trackpad swipe navigation (browser parity): back/forward in the
   *  ACTIVE TAB's page history — the account view, never the shell (its
   *  hash history is not what a swipe should move). No-ops when the
   *  history has nothing to go to, like a browser tab. */
  navigateBackward(): void {
    const view = this.activeView();
    if (view !== undefined && view.navigationHistory.canGoBack()) view.navigationHistory.goBack();
  }

  navigateForward(): void {
    const view = this.activeView();
    if (view !== undefined && view.navigationHistory.canGoForward()) view.navigationHistory.goForward();
  }

  private activeView(): Electron.WebContents | undefined {
    if (this.activeAccountId === null) return undefined;
    const tab = this.activeTab.get(this.activeAccountId) ?? DEFAULT_TAB;
    return this.views.get(tabKey(this.activeAccountId, tab))?.webContents;
  }

  /** Destroy one tab's view (planning §2). If it was on screen the shell
   *  immediately presents the next tab — the min-1 rule is the shell's */
  closeTab(options: CloseTabOptions): void {
    const key = tabKey(options.accountId, options.tabId);
    const view = this.views.get(key);
    if (view === undefined) return;
    this.win.contentView.removeChildView(view);
    view.webContents.close();
    this.views.delete(key);
    if (this.activeTab.get(options.accountId) === options.tabId) {
      this.activeTab.delete(options.accountId);
    }
  }

  dismiss(): void {
    if (!this.activeAccountId) return;
    for (const [, view] of this.viewsOf(this.activeAccountId)) {
      this.win.contentView.removeChildView(view);
    }
  }

  setBounds(bounds: AccountWebViewBounds): void {
    if (!this.activeAccountId) return;
    // All tabs of the active account — hidden ones included, so a
    // re-attached tab never shows stale bounds.
    for (const [, view] of this.viewsOf(this.activeAccountId)) {
      view.setBounds(this.boundsToRect(bounds));
    }
  }

  reload(): void {
    if (!this.activeAccountId) return;
    const tab = this.activeTab.get(this.activeAccountId) ?? DEFAULT_TAB;
    this.views.get(tabKey(this.activeAccountId, tab))?.webContents.reload();
  }

  navigateHome(options: NavigateHomeOptions): void {
    // Re-navigate the ACTIVE tab's view without dropping its partition
    // (cookies / login survive). No-op if the view was never created.
    const tab = options.tabId ?? this.activeTab.get(options.accountId) ?? DEFAULT_TAB;
    this.views.get(tabKey(options.accountId, tab))?.webContents.loadURL(options.url);
  }

  remove(options: RemoveOptions): void {
    const { accountId } = options;
    // Snapshot first — the loop deletes from the same map it reads.
    for (const [tabId, view] of this.viewsOf(accountId)) {
      this.win.contentView.removeChildView(view);
      view.webContents.close();
      this.tools.unregisterView(view.webContents.id);
      this.views.delete(tabKey(accountId, tabId));
    }
    this.activeTab.delete(accountId);
    if (this.activeAccountId === accountId) this.activeAccountId = null;
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

  private createView(accountId: string, tabId: string, _url: string): WebContentsView {
    const partition = this.partitionFor(accountId);

    // Remote content — lock it down: sandboxed, isolated, no Node.
    // Remote content — lock it down: sandboxed, isolated, no Node. The
    // tools preload is the only capability surface: a request-only
    // bridge into the main process (see preload-tools.ts).
    const view = new WebContentsView({
      webPreferences: {
        partition,
        sandbox: true,
        contextIsolation: true,
        nodeIntegration: false,
        preload: path.join(__dirname, 'preload-tools.js'),
      },
    });
    this.tools.registerView(accountId, accountId, view.webContents.id);
    const wc = view.webContents;
    wc.setUserAgent(`${wc.getUserAgent()} ${USER_AGENT_SUFFIX} ${DESKTOP_USER_AGENT_TOKEN}`);

    // Grant in-WebView permissions the web UI actually needs; everything
    // else stays denied (no silent prompt-free grants). The OS still owns
    // the actual device permission for media.
    //   media — voice STT / photo capture
    //   clipboard-sanitized-write — the copy buttons (navigator.clipboard
    //     .writeText); a browser auto-grants this, a deny-by-default
    //     handler made every copy button fail silently in the app
    //   clipboard-read — SheetView paste (navigator.clipboard.readText)
    session.fromPartition(partition).setPermissionRequestHandler(
      (_wc, permission, callback) => {
        callback(
          permission === 'media' ||
          permission === 'clipboard-sanitized-write' ||
          permission === 'clipboard-read',
        );
      },
    );

    // Tab titles (planning §4): the shell cannot read the webview DOM —
    // it derives the title from the URL this push carries.
    wc.on('did-navigate', (_event: ElectronEvent, navUrl: string) => {
      if (navUrl.startsWith('http')) {
        this.win.webContents.send('facelift:tab-url', { accountId, tabId, url: navUrl });
      }
    });
    // Tab shortcut while THIS view has focus (the normal case — the user
    // interacts with the web content, not the shell).
    wc.on('before-input-event', (event, input) => {
      this.onTabShortcutInput(event, input, () => accountId, () => wc.reload());
    });
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
