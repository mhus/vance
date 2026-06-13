import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'de.mhus.vance.facelift',
  appName: 'Vance',
  webDir: 'dist',
  ios: {
    // 'never' keeps the Capacitor WebView's CSS y=0 aligned with
    // UIKit y=0 of the host view. The Vue shell handles safe-area
    // top by setting `padding-top: env(safe-area-inset-top)` on the
    // header itself, so the plugin's per-account WebView at
    // y=headerOffsetHeight lands exactly below the header without
    // an iOS-side offset translation.
    contentInset: 'never',
    // iOS 17 is required for `WKWebsiteDataStore(forIdentifier:)` —
    // the per-account WebView isolation used by the
    // @vance/facelift-account-webview plugin.
    minVersion: '17.0',
    // Match the iOS launch background to the splash colour so the
    // status-bar area looks unified during the JS boot window.
    backgroundColor: '#1e3a8a',
  },
  server: {
    // No `url` field — release builds load bundled assets from `webDir`.
    // For livereload dev runs use `cap run ios --livereload --external`
    // which injects the dev-server URL at runtime, no config change here.
    androidScheme: 'https',
  },
  plugins: {
    SplashScreen: {
      // We hide the splash explicitly from main.ts as soon as the Vue
      // app has mounted, so set a generous auto-hide timeout as a
      // safety net for the case where the JS hide() never reaches us
      // (e.g. boot crash). Background matches `ios.backgroundColor`
      // and the splash PNG so any pre-fade is invisible.
      launchShowDuration: 3000,
      launchAutoHide: true,
      backgroundColor: '#1e3a8a',
      showSpinner: false,
    },
  },
};

export default config;
