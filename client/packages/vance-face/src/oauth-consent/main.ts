import '@/platform/bootWeb';
import { createApp } from 'vue';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import OAuthConsentApp from './OAuthConsentApp.vue';
import { i18n, i18nReady } from '@/i18n';
import '@/style/app.css';
// Fonts outside the Tailwind entry: Vite has to see the file at its own
// path to resolve the relative font URLs.
import '@vance/components/fonts.css';

await ensureAuthenticated();
// First paint in the user's language: the locale's messages load before mount.
await i18nReady;
createApp(OAuthConsentApp).use(i18n).mount('#app');
