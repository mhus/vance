import { createApp } from 'vue';
import { Capacitor } from '@capacitor/core';
import { SplashScreen } from '@capacitor/splash-screen';
import { StatusBar, Style } from '@capacitor/status-bar';
import App from './App.vue';
import { createAppRouter } from './router';
import './style.css';

createApp(App).use(createAppRouter()).mount('#app');

// Native bridge configuration. All calls go through Capacitor's
// no-op stub when the bundle runs in a plain browser (e.g. via
// `pnpm dev`), so we wrap defensively but don't conditional-import.
if (Capacitor.isNativePlatform()) {
  void (async () => {
    try {
      // Light content (white text/icons) sits cleanly on the
      // bg-gray-900 header and on the dark splash background.
      await StatusBar.setStyle({ style: Style.Light });
    } catch (e) {
      console.warn('StatusBar.setStyle failed', e);
    }
    try {
      await SplashScreen.hide();
    } catch (e) {
      console.warn('SplashScreen.hide failed', e);
    }
  })();
}
