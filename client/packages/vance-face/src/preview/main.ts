import '@/platform/bootWeb';
import { createApp } from 'vue';
import PreviewApp from './PreviewApp.vue';
import { i18n, i18nReady } from '@/i18n';
import '@/style/app.css';
// Fonts outside the Tailwind entry: Vite has to see the file at its own
// path to resolve the relative font URLs.
import '@vance/components/fonts.css';

// No ensureAuthenticated(): the page shows the building blocks only and talks
// to no server — it must render on a fresh dev checkout and after a theme or
// DaisyUI update (web-ui.md §7.4).
await i18nReady;
createApp(PreviewApp).use(i18n).mount('#app');
