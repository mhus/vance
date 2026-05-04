import '@/platform/bootWeb';
import { createApp } from 'vue';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import ScopesApp from './ScopesApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';
await ensureAuthenticated();
createApp(ScopesApp).use(i18n).mount('#app');
//# sourceMappingURL=main.js.map