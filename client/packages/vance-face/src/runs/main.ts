import '@/platform/bootWeb';
import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import RunsApp from './RunsApp.vue';
import { i18n, i18nReady } from '@/i18n';
import '@/style/app.css';

await ensureAuthenticated();
// First paint in the user's language: the locale's messages load before mount.
await i18nReady;
createApp(RunsApp).use(i18n).use(createPinia()).mount('#app');
