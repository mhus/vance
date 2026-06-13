/**
 * Native file bridges, scoped to the Facelift Capacitor wrapper.
 *
 * The website runs inside a plain `WKWebView` that the wrapper's
 * Swift plugin creates per-account — there is *no* Capacitor bridge
 * in this WebView, so the `@capacitor/*` plugin APIs are unreachable
 * from the website JS. Instead, the wrapper injects a tiny shim at
 * document-start that exposes `window.vanceFacelift.*` and forwards
 * the calls to native code via `WKScriptMessageHandler`.
 *
 * Every helper here checks {@link isFacelift} and throws when called
 * from a plain browser context — UI code should guard the
 * entry-point with the same check before showing the native-only
 * affordance. See `instructions/web-ui.md` and
 * `repos/vance/client/packages/facelift-bridge/README.md` for the
 * detection convention.
 */
import { isFacelift } from '@vance/shared';
function getBridge() {
    if (!isFacelift()) {
        throw new Error('Facelift file APIs are only available inside the Facelift wrapper.');
    }
    const bridge = window
        .vanceFacelift;
    if (bridge?.exportFile === undefined) {
        throw new Error('vanceFacelift bridge is missing — the WebView userScript did not inject.');
    }
    return bridge;
}
/**
 * Open the iOS share-sheet with a temporary file, letting the user
 * choose where to save it ("Save to Files", "Save Image", AirDrop,
 * mail, third-party apps). The file is written into the app's
 * temporary directory on the native side and is best-effort cleaned
 * up by iOS later.
 */
export async function exportToFiles(opts) {
    const bridge = getBridge();
    const base64 = await blobToBase64(opts.data);
    bridge.exportFile({ name: opts.name, mime: opts.mime, base64 });
}
function blobToBase64(blob) {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onloadend = () => {
            const result = typeof reader.result === 'string' ? reader.result : '';
            const comma = result.indexOf(',');
            resolve(comma === -1 ? result : result.slice(comma + 1));
        };
        reader.onerror = () => reject(reader.error ?? new Error('FileReader failed'));
        reader.readAsDataURL(blob);
    });
}
//# sourceMappingURL=faceliftFiles.js.map