import '@/platform/bootWeb';
import { createApp } from 'vue';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import ConnectedAccountsApp from './ConnectedAccountsApp.vue';
import { i18n, i18nReady } from '@/i18n';
import '@/style/app.css';

await ensureAuthenticated();
// First paint in the user's language: the locale's messages load before mount.
await i18nReady;
createApp(ConnectedAccountsApp).use(i18n).mount('#app');
