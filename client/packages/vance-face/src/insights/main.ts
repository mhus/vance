import '@/platform/bootWeb';
import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import InsightsApp from './InsightsApp.vue';
import { i18n, i18nReady } from '@/i18n';
import '@/style/app.css';

await ensureAuthenticated();
// First paint in the user's language: the locale's messages load before mount.
await i18nReady;
createApp(InsightsApp).use(i18n).use(createPinia()).mount('#app');
