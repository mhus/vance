package de.mhus.vance.facelift.accountwebview;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Android port of the per-account isolated WebView host. Keeps one
 * cached {@link WebView} per {@code accountId} (a UUID string), each
 * bound to its own androidx.webkit {@code Profile} — the Android analog
 * of iOS' {@code WKWebsiteDataStore(forIdentifier:)}. That gives full
 * cookie / LocalStorage / IndexedDB / Service-Worker isolation even when
 * two accounts point at the same Brain origin.
 *
 * <p>Isolation requires Android System WebView &ge; 114
 * ({@code WebViewFeature.MULTI_PROFILE}). When that is unavailable we do
 * NOT silently share one cookie jar across accounts (that would mix
 * identities) — a {@code present} for a second, different account is
 * rejected so the Vue shell can surface a "update WebView" hint. See
 * planning/vance-facelift-android.md §2.
 *
 * <p>Bounds arrive in CSS pixels (matching the iOS points contract);
 * Android view layout is in physical pixels, so every bound is scaled by
 * display density in {@link #px(double)}.
 */
@CapacitorPlugin(name = "VanceAccountWebView")
public class VanceAccountWebViewPlugin extends Plugin {

    private static final String TAG = "VanceFacelift";

    /** User-Agent suffix appended to the default WebView UA. The website
     *  + Brain detect this to adapt behaviour. Mirrors the iOS
     *  {@code applicationNameForUserAgent} value. */
    private static final String USER_AGENT_SUFFIX = "VanceFacelift/0.1.0";

    /** Custom scheme the website navigates to in order to ask the
     *  wrapper to act (e.g. {@code vance-facelift://back-to-picker}). */
    private static final String URL_SCHEME = "vance-facelift";

    /** JS object name injected by {@code addWebMessageListener}. The
     *  document-start shim posts stringified JSON here so the website can
     *  call into native code without a Capacitor bridge (it lives in a
     *  plain WebView, not the Capacitor host WebView). */
    private static final String BRIDGE_JS_OBJECT_NAME = "vanceFaceliftBridge";

    private static final Set<String> ORIGIN_RULES = Collections.singleton("*");

    /** Injected at document-start into every per-account WebView.
     *  {@code __ACCOUNT_ID__} is substituted per-WebView so
     *  {@code window.vanceFacelift.accountId} is the wrapper's UUID for
     *  the owning account. Dependency-free by design. Unlike iOS'
     *  {@code messageHandlers.postMessage} (which takes an object), the
     *  androidx WebMessageListener channel is string-only, so payloads
     *  are JSON-stringified before posting. */
    private static final String BRIDGE_SCRIPT_TEMPLATE =
        "(function () {\n" +
        "  if (window.vanceFacelift) return;\n" +
        "  var ACCOUNT_ID = __ACCOUNT_ID__;\n" +
        "  function post(payload) {\n" +
        "    try {\n" +
        "      window." + BRIDGE_JS_OBJECT_NAME + ".postMessage(JSON.stringify(payload));\n" +
        "    } catch (e) {\n" +
        "      console.error('[vanceFacelift] bridge post failed', e);\n" +
        "    }\n" +
        "  }\n" +
        "  window.vanceFacelift = {\n" +
        "    accountId: ACCOUNT_ID,\n" +
        "    exportFile: function (opts) {\n" +
        "      opts = opts || {};\n" +
        "      post({ action: 'exportFile', name: String(opts.name || 'document'), mime: String(opts.mime || 'application/octet-stream'), base64: String(opts.base64 || '') });\n" +
        "    },\n" +
        "    setShareCredentials: function (opts) {\n" +
        "      post({ action: 'setShareCredentials', accountId: ACCOUNT_ID, credentialsJson: JSON.stringify(opts || {}) });\n" +
        "    },\n" +
        "    setProjectSnapshot: function (projects) {\n" +
        "      post({ action: 'setProjectSnapshot', accountId: ACCOUNT_ID, projectsJson: JSON.stringify(projects || []) });\n" +
        "    }\n" +
        "  };\n" +
        "})();";

    private final Map<String, WebView> webViews = new HashMap<>();
    /** Original host for each cached WebView, for the external-link
     *  guard. Any navigation to a different host is handed to the system
     *  browser instead of turning the wrapper into a general browser. */
    private final Map<String, String> webViewHomeHosts = new HashMap<>();
    @Nullable private WebView activeWebView;
    @Nullable private String activeAccountId;

    private static String profileName(String accountId) {
        return "vance_" + accountId;
    }

    private int px(double cssPx) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return Math.round((float) (cssPx * density));
    }

    @Nullable
    private ViewGroup hostContainer() {
        if (getActivity() == null) return null;
        return getActivity().findViewById(android.R.id.content);
    }

    // MARK: - present / dismiss / bounds / reload / navigateHome / remove

    @PluginMethod
    public void present(final PluginCall call) {
        final String accountId = call.getString("accountId");
        if (accountId == null || accountId.isEmpty()) {
            call.reject("accountId required");
            return;
        }
        final String urlString = call.getString("url");
        if (urlString == null || urlString.isEmpty()) {
            call.reject("url required and valid");
            return;
        }
        final Uri url = Uri.parse(urlString);
        final double top = call.getDouble("top", 0.0);
        final double left = call.getDouble("left", 0.0);
        final double width = call.getDouble("width", 0.0);
        final double height = call.getDouble("height", 0.0);

        getActivity().runOnUiThread(() -> {
            final ViewGroup parent = hostContainer();
            if (parent == null) {
                call.reject("no parent view available");
                return;
            }

            // Hide whichever WebView is on screen so we can swap in this
            // account's one.
            WebView cached = webViews.get(accountId);
            if (activeWebView != null && activeWebView != cached) {
                activeWebView.setVisibility(View.GONE);
            }

            WebView webView = cached;
            if (webView == null) {
                final boolean multiProfile =
                    WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
                Logger.debug(TAG, "present: creating WebView for account " + accountId
                    + " (multiProfile=" + multiProfile + ")");

                // Honest fallback: without MULTI_PROFILE a second, distinct
                // account cannot be isolated — refuse rather than mix
                // cookie jars. A single account still works on the default
                // profile.
                if (!multiProfile && !webViews.isEmpty()) {
                    call.reject("multi-account isolation requires Android System WebView 114+ "
                        + "(update Android System WebView / Chrome)");
                    return;
                }

                webView = new WebView(getActivity());

                // Bind the isolated profile BEFORE any load or other use.
                if (multiProfile) {
                    try {
                        ProfileStore.getInstance().getOrCreateProfile(profileName(accountId));
                        WebViewCompat.setProfile(webView, profileName(accountId));
                    } catch (Exception e) {
                        Logger.error(TAG, "present: setProfile failed for " + accountId, e);
                        call.reject("failed to create isolated profile: " + e.getMessage());
                        return;
                    }
                }

                configureSettings(webView);
                webView.setWebViewClient(navigationClient());
                webView.setWebChromeClient(mediaCaptureChromeClient());
                installJsBridge(webView, accountId);
                try {
                    WebView.setWebContentsDebuggingEnabled(true);
                } catch (Exception ignored) {
                    // Non-fatal — debugging just won't be available.
                }

                webViews.put(accountId, webView);
                if (url.getHost() != null) {
                    webViewHomeHosts.put(accountId, url.getHost().toLowerCase());
                }
                parent.addView(webView, boundsToLayoutParams(top, left, width, height));
                webView.loadUrl(url.toString());
            } else {
                webView.setLayoutParams(boundsToLayoutParams(top, left, width, height));
            }

            webView.setVisibility(View.VISIBLE);
            webView.bringToFront();
            webView.requestLayout();

            activeWebView = webView;
            activeAccountId = accountId;
            call.resolve();
        });
    }

    @PluginMethod
    public void dismiss(final PluginCall call) {
        getActivity().runOnUiThread(() -> {
            if (activeWebView != null) {
                activeWebView.setVisibility(View.GONE);
            }
            call.resolve();
        });
    }

    @PluginMethod
    public void setBounds(final PluginCall call) {
        final double top = call.getDouble("top", 0.0);
        final double left = call.getDouble("left", 0.0);
        final double width = call.getDouble("width", 0.0);
        final double height = call.getDouble("height", 0.0);
        getActivity().runOnUiThread(() -> {
            if (activeWebView != null) {
                activeWebView.setLayoutParams(boundsToLayoutParams(top, left, width, height));
                activeWebView.requestLayout();
            }
            call.resolve();
        });
    }

    @PluginMethod
    public void reload(final PluginCall call) {
        getActivity().runOnUiThread(() -> {
            if (activeWebView != null) {
                activeWebView.reload();
            }
            call.resolve();
        });
    }

    @PluginMethod
    public void navigateHome(final PluginCall call) {
        final String accountId = call.getString("accountId");
        if (accountId == null || accountId.isEmpty()) {
            call.reject("accountId required");
            return;
        }
        final String urlString = call.getString("url");
        if (urlString == null || urlString.isEmpty()) {
            call.reject("url required and valid");
            return;
        }
        getActivity().runOnUiThread(() -> {
            // Re-navigate the cached WebView without tearing down its
            // profile, so cookies / login survive. No-op if the WebView
            // for this account was never created — the next present(...)
            // will create + load it.
            WebView webView = webViews.get(accountId);
            if (webView != null) {
                webView.loadUrl(urlString);
            }
            call.resolve();
        });
    }

    @PluginMethod
    public void remove(final PluginCall call) {
        final String accountId = call.getString("accountId");
        if (accountId == null || accountId.isEmpty()) {
            call.reject("accountId required");
            return;
        }
        getActivity().runOnUiThread(() -> {
            WebView webView = webViews.remove(accountId);
            if (webView != null) {
                webView.stopLoading();
                ViewGroup parent = (ViewGroup) webView.getParent();
                if (parent != null) parent.removeView(webView);
                webView.destroy();
                if (accountId.equals(activeAccountId)) {
                    activeWebView = null;
                    activeAccountId = null;
                }
            }
            webViewHomeHosts.remove(accountId);
            // Wipe the isolated profile so a future re-add of the same
            // UUID starts clean. Best-effort — matches iOS semantics; a
            // deletion failure (e.g. still-attached) must not fail the JS
            // call.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                try {
                    ProfileStore.getInstance().deleteProfile(profileName(accountId));
                } catch (Exception e) {
                    Logger.debug(TAG, "remove: deleteProfile skipped for " + accountId
                        + ": " + e.getMessage());
                }
            }
            call.resolve();
        });
    }

    // MARK: - WebView configuration

    private FrameLayout.LayoutParams boundsToLayoutParams(double top, double left,
                                                          double width, double height) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(width), px(height));
        lp.leftMargin = px(left);
        lp.topMargin = px(top);
        return lp;
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureSettings(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // target="_blank" / window.open() should load in the same WebView
        // (the wrapper hosts one WebView per account; multi-tab belongs in
        // the website). With multiple-windows off, such navigations fall
        // through to shouldOverrideUrlLoading.
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        // Website drives media capture (voice STT, photo attach) via
        // getUserMedia; don't gate it behind a user gesture at the WebView
        // layer (the OS still owns the actual permission).
        settings.setMediaPlaybackRequiresUserGesture(false);
        // Tell the website + Brain this WebView lives inside Facelift.
        settings.setUserAgentString(settings.getUserAgentString() + " " + USER_AGENT_SUFFIX);
    }

    private void installJsBridge(WebView webView, String accountId) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, BRIDGE_JS_OBJECT_NAME, ORIGIN_RULES,
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> handleBridgeMessage(message));
        } else {
            Logger.warn(TAG, "WEB_MESSAGE_LISTENER unsupported — window.vanceFacelift bridge "
                + "(export/share) disabled on this WebView provider");
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, buildBridgeScript(accountId), ORIGIN_RULES);
        } else {
            Logger.warn(TAG, "DOCUMENT_START_SCRIPT unsupported — window.vanceFacelift shim "
                + "not injected on this WebView provider");
        }
    }

    private String buildBridgeScript(String accountId) {
        // accountId is a UUID; escape single quotes defensively in case
        // the source format ever loosens.
        String escaped = accountId.replace("'", "\\'");
        return BRIDGE_SCRIPT_TEMPLATE.replace("__ACCOUNT_ID__", "'" + escaped + "'");
    }

    private WebViewClient navigationClient() {
        return new WebViewClient() {
            // API 24+ path.
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(view, request.getUrl());
            }

            // Deprecated String path for API 23 (our minSdk).
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(view, Uri.parse(url));
            }
        };
    }

    /** @return true to cancel the navigation (we handled it), false to
     *  let the WebView proceed. */
    private boolean handleNavigation(WebView view, Uri url) {
        if (url == null) return false;
        // vance-facelift://* → forward to JS as urlOpen event.
        if (URL_SCHEME.equals(url.getScheme())) {
            JSObject data = new JSObject();
            data.put("url", url.toString());
            notifyListeners("urlOpen", data);
            return true;
        }
        // External-link guard — a navigation to a host other than the
        // account's home host is opened in the system browser instead.
        // No whitelist (would break self-hosted users); OAuth bounces to
        // external IdPs leave the app — an accepted v1 trade-off.
        String homeHost = homeHostFor(view);
        String nextHost = url.getHost() != null ? url.getHost().toLowerCase() : null;
        if (homeHost != null && nextHost != null && !nextHost.equals(homeHost)) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, url);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            } catch (Exception e) {
                Logger.warn(TAG, "external open failed for " + url + ": " + e.getMessage());
            }
            return true;
        }
        return false;
    }

    @Nullable
    private String homeHostFor(WebView view) {
        for (Map.Entry<String, WebView> entry : webViews.entrySet()) {
            if (entry.getValue() == view) {
                return webViewHomeHosts.get(entry.getKey());
            }
        }
        return null;
    }

    private WebChromeClient mediaCaptureChromeClient() {
        return new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                getActivity().runOnUiThread(() -> {
                    // Grant only what the app already holds at the OS level.
                    // The full runtime-permission request flow (asking the
                    // user when the app lacks CAMERA/RECORD_AUDIO) is a
                    // follow-up — see planning doc §6.
                    List<String> granted = new ArrayList<>();
                    for (String res : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)
                            && hasOsPermission(Manifest.permission.CAMERA)) {
                            granted.add(res);
                        } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)
                            && hasOsPermission(Manifest.permission.RECORD_AUDIO)) {
                            granted.add(res);
                        }
                    }
                    if (granted.isEmpty()) {
                        request.deny();
                    } else {
                        request.grant(granted.toArray(new String[0]));
                    }
                });
            }
        };
    }

    private boolean hasOsPermission(String permission) {
        return ContextCompat.checkSelfPermission(getContext(), permission)
            == PackageManager.PERMISSION_GRANTED;
    }

    // MARK: - JS bridge dispatch (window.vanceFacelift.*)

    private void handleBridgeMessage(WebMessageCompat message) {
        String data = message.getData();
        if (data == null) return;
        try {
            JSONObject body = new JSONObject(data);
            String action = body.optString("action");
            switch (action) {
                case "exportFile":
                    // Save-to-Files is a Phase-4 item on Android — it needs
                    // Storage Access Framework + an ActivityResult round-trip
                    // (Capacitor startActivityForResult). Intentionally not
                    // wired yet; see planning/vance-facelift-android.md §6.
                    Logger.warn(TAG, "bridge exportFile received but Save-to-Files is not yet "
                        + "implemented on Android (Phase 4, SAF) — ignoring");
                    break;
                case "setShareCredentials":
                    mergeShareCredentials(body.getString("accountId"), body.getString("credentialsJson"));
                    break;
                case "setProjectSnapshot":
                    String safeId = body.getString("accountId").replace("/", "_");
                    writeStringToFile(new File(shareDir(), "projects-" + safeId + ".json"),
                        body.getString("projectsJson"));
                    break;
                default:
                    Logger.debug(TAG, "bridge: unknown action '" + action + "'");
            }
        } catch (JSONException | IOException e) {
            Logger.error(TAG, "bridge message handling failed", e);
        }
    }

    // MARK: - Biometric

    @PluginMethod
    public void isBiometricAvailable(final PluginCall call) {
        BiometricManager manager = BiometricManager.from(getContext());
        int status = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
        JSObject result = new JSObject();
        result.put("available", status == BiometricManager.BIOMETRIC_SUCCESS);
        // Android's BiometricManager does not expose the modality (face vs.
        // fingerprint) cleanly, and the JS lock screen only reads
        // `available`, so report a neutral type rather than a guessed value.
        result.put("biometryType", "none");
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            result.put("errorCode", status);
            result.put("errorMessage", biometricStatusMessage(status));
        }
        call.resolve(result);
    }

    private String biometricStatusMessage(int status) {
        switch (status) {
            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
                return "no biometric hardware";
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                return "biometric hardware unavailable";
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                return "no biometrics enrolled";
            default:
                return "biometrics unavailable (" + status + ")";
        }
    }

    @PluginMethod
    public void authenticateBiometric(final PluginCall call) {
        final String reason = call.getString("reason", "Unlock Vancetope");
        getActivity().runOnUiThread(() -> {
            FragmentActivity activity = (FragmentActivity) getActivity();
            Executor executor = ContextCompat.getMainExecutor(getContext());
            BiometricPrompt prompt = new BiometricPrompt(activity, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        JSObject r = new JSObject();
                        r.put("success", true);
                        call.resolve(r);
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        JSObject r = new JSObject();
                        r.put("success", false);
                        r.put("errorCode", errorCode);
                        r.put("errorMessage", errString.toString());
                        call.resolve(r);
                    }
                    // onAuthenticationFailed (a single rejected attempt) is
                    // non-terminal; BiometricPrompt keeps the sheet open, so
                    // we deliberately don't resolve there.
                });
            // JS owns the PIN fallback, so we use biometrics only and give
            // the system sheet a plain Cancel button (mirrors iOS clearing
            // localizedFallbackTitle).
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(reason)
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build();
            prompt.authenticate(info);
        });
    }

    // MARK: - Share snapshot bridge (app-internal storage)

    private File shareDir() {
        File dir = new File(getContext().getFilesDir(), "share");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    @PluginMethod
    public void setAccountSnapshot(final PluginCall call) {
        String json = call.getString("accountsJson");
        if (json == null) {
            call.reject("accountsJson required");
            return;
        }
        try {
            writeStringToFile(new File(shareDir(), "accounts.json"), json);
            call.resolve();
        } catch (IOException e) {
            call.reject("write accounts.json failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void setShareCredentials(final PluginCall call) {
        String accountId = call.getString("accountId");
        String credentialsJson = call.getString("credentialsJson");
        if (accountId == null || credentialsJson == null) {
            call.reject("accountId + credentialsJson required");
            return;
        }
        try {
            mergeShareCredentials(accountId, credentialsJson);
            call.resolve();
        } catch (JSONException | IOException e) {
            call.reject("setShareCredentials write failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void setProjectSnapshot(final PluginCall call) {
        String accountId = call.getString("accountId");
        String projectsJson = call.getString("projectsJson");
        if (accountId == null || projectsJson == null) {
            call.reject("accountId + projectsJson required");
            return;
        }
        try {
            String safeId = accountId.replace("/", "_");
            writeStringToFile(new File(shareDir(), "projects-" + safeId + ".json"), projectsJson);
            call.resolve();
        } catch (IOException e) {
            call.reject("write projects failed: " + e.getMessage());
        }
    }

    /** Read-merge-write credentials.json keyed by accountId, so multiple
     *  accounts' credentials coexist and a write replaces only its own
     *  entry. Shared by the plugin method and the JS-bridge action. */
    private void mergeShareCredentials(String accountId, String credentialsJson)
        throws JSONException, IOException {
        File file = new File(shareDir(), "credentials.json");
        JSONObject merged;
        String existing = readFileOrNull(file);
        if (existing != null) {
            try {
                merged = new JSONObject(existing);
            } catch (JSONException malformed) {
                // A corrupt file must not wedge future writes — start fresh.
                merged = new JSONObject();
            }
        } else {
            merged = new JSONObject();
        }
        merged.put(accountId, new JSONObject(credentialsJson));
        writeStringToFile(file, merged.toString(2));
    }

    private void writeStringToFile(File file, String contents) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(contents.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nullable
    private String readFileOrNull(File file) {
        if (!file.exists()) return null;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int read = fis.read(buf);
            return new String(buf, 0, Math.max(read, 0), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
