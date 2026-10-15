import '@/platform/bootWeb';
import { createApp } from 'vue';
import { createPinia } from 'pinia';
import LoginApp from './LoginApp.vue';
import { i18n, i18nReady } from '@/i18n';
import '@/style/app.css';
// Fonts outside the Tailwind entry: Vite has to see the file at its own
// path to resolve the relative font URLs.
import '@vance/components/fonts.css';

// No `ensureAuthenticated()` here, obviously — this IS the authentication.
// And no addon manifest, no Kind registry, no WebSocket: the login screen is
// the surface that has to boot when the workspace bundle cannot.
// First paint in the user's language: the locale's messages load before mount.
await i18nReady;
createApp(LoginApp).use(i18n).use(createPinia()).mount('#app');
