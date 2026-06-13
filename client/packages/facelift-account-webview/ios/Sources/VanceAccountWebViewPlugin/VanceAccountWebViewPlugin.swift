import Capacitor
import UIKit
import WebKit

/// Per-account isolated WKWebView host. The plugin keeps one cached
/// `WKWebView` per `accountId` (a UUID string), each configured with
/// its own `WKWebsiteDataStore(forIdentifier:)` on iOS 17+. That
/// gives full cookie / IndexedDB / Service-Worker isolation even when
/// two accounts point at the same Brain origin.
///
/// Lifecycle:
///   - `present(accountId, url, bounds)` shows the matching WebView,
///     creating it on first call. Other cached WebViews are hidden
///     but kept alive so switching back is instant (scroll position,
///     form state, video playback all survive).
///   - `dismiss()` hides whichever WebView is currently on screen.
///   - `setBounds(...)` re-positions the visible WebView (e.g. on
///     header resize or rotation).
///   - `reload()` reloads the visible WebView's page.
///   - `remove(accountId)` tears down both the cached WebView and
///     its persistent data store — the next `present` for the same
///     id starts fresh.
@objc(VanceAccountWebViewPlugin)
public class VanceAccountWebViewPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "VanceAccountWebViewPlugin"
    public let jsName = "VanceAccountWebView"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "present", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "dismiss", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setBounds", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "reload", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "remove", returnType: CAPPluginReturnPromise),
    ]

    private var webViews: [String: WKWebView] = [:]
    private var activeWebView: WKWebView?
    private var activeAccountId: String?

    @objc func present(_ call: CAPPluginCall) {
        guard let accountId = call.getString("accountId"), !accountId.isEmpty else {
            call.reject("accountId required")
            return
        }
        guard let urlString = call.getString("url"),
              let url = URL(string: urlString) else {
            call.reject("url required and valid")
            return
        }
        let top = CGFloat(call.getDouble("top") ?? 0)
        let left = CGFloat(call.getDouble("left") ?? 0)
        let width = CGFloat(call.getDouble("width") ?? 0)
        let height = CGFloat(call.getDouble("height") ?? 0)
        let frame = CGRect(x: left, y: top, width: width, height: height)

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            guard let parentView = self.bridge?.viewController?.view else {
                call.reject("no parent view available")
                return
            }

            // Hide whichever WebView is on screen so we can swap in
            // the one for this account.
            if let active = self.activeWebView, active !== self.webViews[accountId] {
                active.isHidden = true
            }

            let webView: WKWebView
            if let cached = self.webViews[accountId] {
                webView = cached
            } else {
                // Per-account isolated data store. `forIdentifier:`
                // is iOS 17+ only.
                guard #available(iOS 17.0, *) else {
                    call.reject("iOS 17.0 or newer is required for isolated per-account WebViews")
                    return
                }
                guard let uuid = UUID(uuidString: accountId) else {
                    call.reject("accountId must be a UUID")
                    return
                }
                let config = WKWebViewConfiguration()
                config.websiteDataStore = WKWebsiteDataStore(forIdentifier: uuid)
                let created = WKWebView(frame: frame, configuration: config)
                created.allowsBackForwardNavigationGestures = true
                created.translatesAutoresizingMaskIntoConstraints = true
                created.autoresizingMask = []
                // Defensive — render content strictly within the
                // requested frame, regardless of iOS' default
                // safe-area inset adjustment.
                created.clipsToBounds = true
                created.scrollView.contentInsetAdjustmentBehavior = .never
                if #available(iOS 16.4, *) {
                    created.isInspectable = true
                }
                self.webViews[accountId] = created
                parentView.addSubview(created)
                created.load(URLRequest(url: url))
                webView = created
            }

            webView.frame = frame
            webView.isHidden = false
            parentView.bringSubviewToFront(webView)

            self.activeWebView = webView
            self.activeAccountId = accountId
            call.resolve()
        }
    }

    @objc func dismiss(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            self?.activeWebView?.isHidden = true
            call.resolve()
        }
    }

    @objc func setBounds(_ call: CAPPluginCall) {
        let top = CGFloat(call.getDouble("top") ?? 0)
        let left = CGFloat(call.getDouble("left") ?? 0)
        let width = CGFloat(call.getDouble("width") ?? 0)
        let height = CGFloat(call.getDouble("height") ?? 0)
        let frame = CGRect(x: left, y: top, width: width, height: height)
        DispatchQueue.main.async { [weak self] in
            self?.activeWebView?.frame = frame
            call.resolve()
        }
    }

    @objc func reload(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            self?.activeWebView?.reload()
            call.resolve()
        }
    }

    @objc func remove(_ call: CAPPluginCall) {
        guard let accountId = call.getString("accountId"), !accountId.isEmpty else {
            call.reject("accountId required")
            return
        }
        DispatchQueue.main.async { [weak self] in
            guard let self = self else {
                call.resolve()
                return
            }
            if let webView = self.webViews.removeValue(forKey: accountId) {
                webView.stopLoading()
                webView.removeFromSuperview()
                if self.activeAccountId == accountId {
                    self.activeWebView = nil
                    self.activeAccountId = nil
                }
            }
            // Wipe the persistent data store so a future re-add of
            // the same UUID starts with no cookies. Best-effort —
            // failure of the removal call doesn't fail the JS call.
            if #available(iOS 17.0, *), let uuid = UUID(uuidString: accountId) {
                WKWebsiteDataStore.remove(forIdentifier: uuid) { _ in
                    call.resolve()
                }
            } else {
                call.resolve()
            }
        }
    }
}
