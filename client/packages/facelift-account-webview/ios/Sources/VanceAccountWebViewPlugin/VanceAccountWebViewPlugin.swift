import Capacitor
import LocalAuthentication
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
public class VanceAccountWebViewPlugin: CAPPlugin, CAPBridgedPlugin, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler, UIDocumentPickerDelegate {
    public let identifier = "VanceAccountWebViewPlugin"
    public let jsName = "VanceAccountWebView"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "present", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "dismiss", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setBounds", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "reload", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "navigateHome", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "remove", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setAccountSnapshot", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setShareCredentials", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setProjectSnapshot", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isBiometricAvailable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "authenticateBiometric", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "addListener", returnType: CAPPluginReturnCallback),
        CAPPluginMethod(name: "removeAllListeners", returnType: CAPPluginReturnPromise),
    ]

    /// User-Agent suffix appended to the default Safari UA via
    /// `WKWebViewConfiguration.applicationNameForUserAgent`. The
    /// website + Brain server can detect this with
    /// `navigator.userAgent` / `User-Agent` header and adapt
    /// behaviour (Mobile-Photos uploader, deep-link back to picker,
    /// etc.). See `facelift-bridge/README.md` for the JS-side
    /// detection convention.
    private let userAgentSuffix = "VanceFacelift/0.1.0"

    /// Custom URL scheme the website navigates to in order to ask
    /// the wrapper to perform an action (e.g.
    /// `vance-facelift://back-to-picker`). The WebView's navigation
    /// delegate cancels the request and forwards it to JS via the
    /// `urlOpen` plugin event so Vue can act on it.
    private let urlScheme = "vance-facelift"

    /// Name of the `WKScriptMessageHandler` channel exposed to the
    /// website via `window.webkit.messageHandlers.<name>`. The
    /// `vanceFacelift` JS facade injected at document-start posts
    /// messages here so the website can call into native code
    /// without going through Capacitor (which it can't — the
    /// website lives in a plain WKWebView, not the Capacitor host
    /// WebView).
    private let bridgeMessageName = "vanceFacelift"

    /// Template for the JS shim that gets injected at document-start
    /// into every per-account WebView. Substituted per-WebView so
    /// `window.vanceFacelift.accountId` is the wrapper's UUID for
    /// the account that owns this WebView — the website doesn't
    /// know that ID otherwise. Keep dependency-free — the website's
    /// bundle must not have to import anything to use it.
    private let bridgeUserScriptTemplate = """
    (function () {
      if (window.vanceFacelift) return;
      var ACCOUNT_ID = __ACCOUNT_ID__;
      function post(payload) {
        try {
          window.webkit.messageHandlers.vanceFacelift.postMessage(payload);
        } catch (e) {
          console.error('[vanceFacelift] bridge post failed', e);
        }
      }
      window.vanceFacelift = {
        accountId: ACCOUNT_ID,
        exportFile: function (opts) {
          opts = opts || {};
          post({
            action: 'exportFile',
            name: String(opts.name || 'document'),
            mime: String(opts.mime || 'application/octet-stream'),
            base64: String(opts.base64 || '')
          });
        },
        setShareCredentials: function (opts) {
          post({
            action: 'setShareCredentials',
            accountId: ACCOUNT_ID,
            credentialsJson: JSON.stringify(opts || {})
          });
        },
        setProjectSnapshot: function (projects) {
          post({
            action: 'setProjectSnapshot',
            accountId: ACCOUNT_ID,
            projectsJson: JSON.stringify(projects || [])
          });
        }
      };
    })();
    """

    private func buildBridgeUserScript(accountId: String) -> String {
        // accountId is a UUID string — safe to embed as a JS literal,
        // but escape single quotes defensively in case the source
        // ever loosens the format.
        let escaped = accountId.replacingOccurrences(of: "'", with: "\\'")
        return bridgeUserScriptTemplate.replacingOccurrences(
            of: "__ACCOUNT_ID__", with: "'\(escaped)'")
    }

    private var webViews: [String: WKWebView] = [:]
    /// Original host (without port) for each cached WebView. Used by
    /// the external-link guard in `WKNavigationDelegate` — any
    /// navigation to a different host gets cancelled + handed to
    /// Safari rather than turning the wrapper into a general-purpose
    /// browser.
    private var webViewHomeHosts: [String: String] = [:]
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
                // Tells the website + Brain that this WebView lives
                // inside Facelift. Appended to the default Safari UA.
                config.applicationNameForUserAgent = self.userAgentSuffix
                // Inject `window.vanceFacelift.*` bridge + register
                // ourselves as the message handler so the website can
                // call into native (export-to-Files etc.) without a
                // Capacitor bridge.
                let contentController = WKUserContentController()
                contentController.add(self, name: self.bridgeMessageName)
                contentController.addUserScript(WKUserScript(
                    source: self.buildBridgeUserScript(accountId: accountId),
                    injectionTime: .atDocumentStart,
                    forMainFrameOnly: false))
                config.userContentController = contentController
                NSLog("[VanceFacelift] bridge installed on new WebView for account \(accountId)")
                let created = WKWebView(frame: frame, configuration: config)
                created.allowsBackForwardNavigationGestures = true
                created.translatesAutoresizingMaskIntoConstraints = true
                created.autoresizingMask = []
                // Defensive — render content strictly within the
                // requested frame, regardless of iOS' default
                // safe-area inset adjustment.
                created.clipsToBounds = true
                created.scrollView.contentInsetAdjustmentBehavior = .never
                // Catch `vance-facelift://*` URLs and forward them to
                // JS as `urlOpen` events. All other navigations pass
                // through unchanged.
                created.navigationDelegate = self
                // Handle `target="_blank"` / `window.open(...)` —
                // WKWebView returns nil by default, so the click
                // silently no-ops. We redirect the navigation into
                // the same WebView so chat-message "open" buttons
                // and other internal links actually go somewhere.
                created.uiDelegate = self
                if #available(iOS 16.4, *) {
                    created.isInspectable = true
                }
                self.webViews[accountId] = created
                if let host = url.host {
                    self.webViewHomeHosts[accountId] = host.lowercased()
                }
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

    @objc func navigateHome(_ call: CAPPluginCall) {
        guard let accountId = call.getString("accountId"), !accountId.isEmpty else {
            call.reject("accountId required")
            return
        }
        guard let urlString = call.getString("url"),
              let url = URL(string: urlString) else {
            call.reject("url required and valid")
            return
        }
        DispatchQueue.main.async { [weak self] in
            guard let self = self else {
                call.resolve()
                return
            }
            // Re-navigates the cached WebView to its account's home
            // URL without tearing down the WKWebsiteDataStore, so
            // cookies and login state survive. No-op when the WebView
            // for this account hasn't been created yet — the next
            // `present(...)` will create one and load the URL then.
            if let webView = self.webViews[accountId] {
                webView.load(URLRequest(url: url))
            }
            call.resolve()
        }
    }

    // MARK: - WKScriptMessageHandler

    public func userContentController(_ userContentController: WKUserContentController,
                                      didReceive message: WKScriptMessage) {
        NSLog("[VanceFacelift] bridge: message received name=\(message.name)")
        guard message.name == self.bridgeMessageName else { return }
        guard let body = message.body as? [String: Any],
              let action = body["action"] as? String else {
            NSLog("[VanceFacelift] bridge: malformed message body")
            return
        }
        NSLog("[VanceFacelift] bridge: action=\(action)")
        switch action {
        case "exportFile":
            self.handleExportFile(body)
        case "setShareCredentials":
            self.handleBridgeSetShareCredentials(body)
        case "setProjectSnapshot":
            self.handleBridgeSetProjectSnapshot(body)
        default:
            NSLog("[VanceFacelift] bridge: unknown action '\(action)'")
        }
    }

    private func handleBridgeSetShareCredentials(_ body: [String: Any]) {
        guard let accountId = body["accountId"] as? String, !accountId.isEmpty,
              let credentialsJson = body["credentialsJson"] as? String else {
            NSLog("[VanceFacelift] bridge setShareCredentials: invalid payload")
            return
        }
        writeShareCredentialsMerged(accountId: accountId, credentialsJson: credentialsJson)
    }

    private func handleBridgeSetProjectSnapshot(_ body: [String: Any]) {
        guard let accountId = body["accountId"] as? String, !accountId.isEmpty,
              let projectsJson = body["projectsJson"] as? String else {
            NSLog("[VanceFacelift] bridge setProjectSnapshot: invalid payload")
            return
        }
        let safeId = accountId.replacingOccurrences(of: "/", with: "_")
        writeShareFileBackground(name: "projects-\(safeId).json", contents: projectsJson)
    }

    private func handleExportFile(_ body: [String: Any]) {
        guard let name = body["name"] as? String,
              let base64 = body["base64"] as? String,
              let data = Data(base64Encoded: base64) else {
            NSLog("[VanceFacelift] exportFile: invalid payload")
            return
        }
        // Sanitise filename — slashes / control chars would confuse
        // either the temp-file path or the picker's suggested name.
        var safeName = name
        for ch in ["/", "\\", "\0"] {
            safeName = safeName.replacingOccurrences(of: ch, with: "_")
        }
        if safeName.isEmpty { safeName = "document" }
        let tmpDir = FileManager.default.temporaryDirectory
        let fileURL = tmpDir.appendingPathComponent(safeName)
        do {
            try data.write(to: fileURL, options: [.atomic])
        } catch {
            NSLog("[VanceFacelift] exportFile: failed to write temp file: \(error)")
            return
        }
        NSLog("[VanceFacelift] exportFile: temp file written at \(fileURL.path), size=\(data.count)")
        DispatchQueue.main.async { [weak self] in
            guard let self = self,
                  let vc = self.bridge?.viewController else {
                NSLog("[VanceFacelift] exportFile: no host view controller")
                return
            }
            // Direct iOS Files picker (export mode) instead of the
            // UIActivityViewController share sheet. The "Save to
            // Files" entry of the share sheet silently fails in the
            // simulator; calling the picker directly side-steps that
            // path and lets the user pick a destination immediately.
            let picker = UIDocumentPickerViewController(forExporting: [fileURL])
            picker.delegate = self
            picker.modalPresentationStyle = .formSheet
            vc.present(picker, animated: true) {
                NSLog("[VanceFacelift] exportFile: document picker presented")
            }
        }
    }

    // MARK: - UIDocumentPickerDelegate

    public func documentPicker(_ controller: UIDocumentPickerViewController,
                               didPickDocumentsAt urls: [URL]) {
        NSLog("[VanceFacelift] exportFile: saved to \(urls.map { $0.path })")
    }

    public func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        NSLog("[VanceFacelift] exportFile: picker cancelled")
    }

    // MARK: - WKUIDelegate

    /// Catch `target="_blank"` / `window.open(...)` from the website
    /// and load the URL in the same WebView instead of letting the
    /// click silently no-op. The Facelift shell intentionally hosts
    /// only one WebView per account; multi-tab behaviour belongs in
    /// the website, not the wrapper.
    public func webView(_ webView: WKWebView,
                        createWebViewWith configuration: WKWebViewConfiguration,
                        for navigationAction: WKNavigationAction,
                        windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil, let url = navigationAction.request.url {
            webView.load(URLRequest(url: url))
        }
        return nil
    }

    /// Grant the website microphone / camera access without an
    /// in-WebView second prompt. iOS still shows its own system-
    /// level prompt (driven by `NSMicrophoneUsageDescription` /
    /// `NSCameraUsageDescription`), and the user's answer there is
    /// the actual authority — this handler only stops WKWebView
    /// from rejecting the JS API call before iOS gets a chance to
    /// ask.
    @available(iOS 15.0, *)
    public func webView(_ webView: WKWebView,
                        requestMediaCapturePermissionFor origin: WKSecurityOrigin,
                        initiatedByFrame frame: WKFrameInfo,
                        type: WKMediaCaptureType,
                        decisionHandler: @escaping (WKPermissionDecision) -> Void) {
        decisionHandler(.grant)
    }

    // MARK: - WKNavigationDelegate

    public func webView(_ webView: WKWebView,
                        decidePolicyFor navigationAction: WKNavigationAction,
                        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }
        // `vance-facelift://*` → forward to JS as urlOpen event.
        if url.scheme == self.urlScheme {
            self.notifyListeners("urlOpen", data: ["url": url.absoluteString])
            decisionHandler(.cancel)
            return
        }
        // External-link guard — any navigation to a host other than
        // the account's home host gets cancelled + opened in Safari
        // instead. Keeps the wrapper focused on the user's Vance
        // deployment rather than turning into a general browser. No
        // whitelist (would break self-hosted users); OAuth flows
        // that bounce through external IdPs leave the app — that's
        // an accepted v1 trade-off.
        if let homeHost = self.homeHost(for: webView),
           let nextHost = url.host?.lowercased(),
           nextHost != homeHost {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    private func homeHost(for webView: WKWebView) -> String? {
        for (accountId, cached) in self.webViews where cached === webView {
            return self.webViewHomeHosts[accountId]
        }
        return nil
    }

    // MARK: - Biometric (Face-ID / Touch-ID)

    /// Reports whether the device can run a biometric check at all.
    /// Resolves with `{ available: bool, biometryType: 'faceID' |
    /// 'touchID' | 'none', errorCode?: number }`. The JS lock screen
    /// uses {@code available} to decide whether to surface the
    /// "Use Face ID / Touch ID" toggle.
    @objc func isBiometricAvailable(_ call: CAPPluginCall) {
        let context = LAContext()
        var nsError: NSError?
        let canEvaluate = context.canEvaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics, error: &nsError)
        var biometryType = "none"
        if #available(iOS 11.0, *) {
            switch context.biometryType {
            case .faceID:  biometryType = "faceID"
            case .touchID: biometryType = "touchID"
            default:       biometryType = "none"
            }
        }
        var result: [String: Any] = [
            "available": canEvaluate,
            "biometryType": biometryType,
        ]
        if let err = nsError {
            result["errorCode"] = err.code
            result["errorMessage"] = err.localizedDescription
        }
        call.resolve(result)
    }

    /// Trigger the system biometric prompt. Resolves with
    /// `{ success: bool, errorCode?: number, errorMessage?: string }`.
    /// `errorCode` follows `LAError.Code` — `userCancel` (-2) is the
    /// most common non-success outcome and should not be treated as
    /// a fatal error by the caller.
    @objc func authenticateBiometric(_ call: CAPPluginCall) {
        let reason = call.getString("reason") ?? "Unlock Vance"
        let context = LAContext()
        // Suppress the Apple-side "fallback to passcode" affordance —
        // our JS layer owns the PIN fallback so the two paths don't
        // conflict.
        context.localizedFallbackTitle = ""
        var nsError: NSError?
        guard context.canEvaluatePolicy(
                .deviceOwnerAuthenticationWithBiometrics,
                error: &nsError) else {
            var result: [String: Any] = ["success": false]
            if let err = nsError {
                result["errorCode"] = err.code
                result["errorMessage"] = err.localizedDescription
            }
            call.resolve(result)
            return
        }
        context.evaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            localizedReason: reason
        ) { success, error in
            DispatchQueue.main.async {
                var result: [String: Any] = ["success": success]
                if let err = error as NSError? {
                    result["errorCode"] = err.code
                    result["errorMessage"] = err.localizedDescription
                }
                call.resolve(result)
            }
        }
    }

    // MARK: - Share-Extension snapshot bridge

    /// App-Group identifier shared between the wrapper and the
    /// Share-Extension target. Must match the `com.apple.security.
    /// application-groups` entitlement on both. Update both places
    /// together if you ever rename it.
    private let appGroupId = "group.de.mhus.vance.facelift"

    private func appGroupContainerURL() -> URL? {
        return FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: self.appGroupId)
    }

    /// Wrapper writes the account-list snapshot on every accountStore
    /// mutation. Extension reads `accounts.json` to populate its
    /// account picker.
    @objc func setAccountSnapshot(_ call: CAPPluginCall) {
        guard let json = call.getString("accountsJson") else {
            call.reject("accountsJson required")
            return
        }
        writeShareFile(call: call, name: "accounts.json", contents: json)
    }

    /// Vance-face writes a long-lived bearer token + identity for the
    /// active account once the user has signed in. Stored under
    /// `credentials.json` keyed by `accountId` so multiple accounts'
    /// credentials coexist; subsequent writes replace just their own
    /// entry rather than overwriting the whole file.
    @objc func setShareCredentials(_ call: CAPPluginCall) {
        guard let accountId = call.getString("accountId"),
              let credentialsJson = call.getString("credentialsJson") else {
            call.reject("accountId + credentialsJson required")
            return
        }
        writeShareCredentialsMerged(
            accountId: accountId,
            credentialsJson: credentialsJson,
            onResolve: { call.resolve() },
            onReject: { msg in call.reject(msg) })
    }

    /// Shared body for both the Capacitor plugin entry-point
    /// ({@link setShareCredentials}) and the WKScriptMessageHandler
    /// bridge case ({@code setShareCredentials}). The two callers
    /// differ in how they report results — the Capacitor plugin has
    /// a {@code CAPPluginCall} to resolve/reject, the bridge has no
    /// reply channel and just logs failures.
    private func writeShareCredentialsMerged(
        accountId: String,
        credentialsJson: String,
        onResolve: @escaping () -> Void = {},
        onReject: @escaping (String) -> Void = { msg in
            NSLog("[VanceFacelift] setShareCredentials: \(msg)")
        }
    ) {
        guard let containerURL = appGroupContainerURL() else {
            onReject("App Group '\(appGroupId)' is not configured — add the capability in Xcode → Signing & Capabilities → App Groups")
            return
        }
        DispatchQueue.global(qos: .userInitiated).async {
            let file = containerURL.appendingPathComponent("credentials.json")
            do {
                var merged: [String: Any] = [:]
                if let existing = try? Data(contentsOf: file),
                   let parsed = try JSONSerialization.jsonObject(with: existing) as? [String: Any] {
                    merged = parsed
                }
                guard let incomingData = credentialsJson.data(using: .utf8),
                      let incoming = try JSONSerialization.jsonObject(with: incomingData) as? [String: Any] else {
                    throw NSError(domain: "VanceFacelift", code: 1,
                                  userInfo: [NSLocalizedDescriptionKey: "credentialsJson is not a JSON object"])
                }
                merged[accountId] = incoming
                let out = try JSONSerialization.data(withJSONObject: merged,
                                                     options: [.prettyPrinted, .sortedKeys])
                try out.write(to: file, options: .atomic)
                DispatchQueue.main.async { onResolve() }
            } catch {
                DispatchQueue.main.async {
                    onReject("setShareCredentials write failed: \(error.localizedDescription)")
                }
            }
        }
    }

    /// Vance-face writes the per-account project list after fetching
    /// `/projects`. Extension reads `projects-<accountId>.json` to
    /// populate its project picker once the account is chosen.
    @objc func setProjectSnapshot(_ call: CAPPluginCall) {
        guard let accountId = call.getString("accountId"),
              let projectsJson = call.getString("projectsJson") else {
            call.reject("accountId + projectsJson required")
            return
        }
        let safeId = accountId.replacingOccurrences(of: "/", with: "_")
        writeShareFile(call: call,
                       name: "projects-\(safeId).json",
                       contents: projectsJson)
    }

    private func writeShareFile(call: CAPPluginCall, name: String, contents: String) {
        writeShareFileBackground(
            name: name,
            contents: contents,
            onResolve: { call.resolve() },
            onReject: { msg in call.reject(msg) })
    }

    private func writeShareFileBackground(
        name: String,
        contents: String,
        onResolve: @escaping () -> Void = {},
        onReject: @escaping (String) -> Void = { msg in
            NSLog("[VanceFacelift] writeShareFile: \(msg)")
        }
    ) {
        guard let containerURL = appGroupContainerURL() else {
            onReject("App Group '\(appGroupId)' is not configured — add the capability in Xcode → Signing & Capabilities → App Groups")
            return
        }
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let file = containerURL.appendingPathComponent(name)
                try contents.write(to: file, atomically: true, encoding: .utf8)
                DispatchQueue.main.async { onResolve() }
            } catch {
                DispatchQueue.main.async {
                    onReject("write '\(name)' failed: \(error.localizedDescription)")
                }
            }
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
            self.webViewHomeHosts.removeValue(forKey: accountId)
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
