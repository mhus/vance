import { registerPlugin } from '@capacitor/core';

import type { VanceAccountWebViewPlugin } from './definitions';

export * from './definitions';

/**
 * Per-account isolated WebView host.
 *
 * Native implementations: iOS (Swift, `WKWebsiteDataStore`), Android
 * (Java, androidx.webkit `Profile`). The `web` implementation targets
 * the Electron desktop shell (`@vance/facelift-desktop`) by bridging to
 * the `window.faceliftDesktop` object its preload injects; in a plain
 * browser it reports the plugin as unavailable.
 */
export const VanceAccountWebView = registerPlugin<VanceAccountWebViewPlugin>(
  'VanceAccountWebView',
  {
    web: () => import('./web').then((m) => new m.VanceAccountWebViewWeb()),
  },
);
