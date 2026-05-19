import '@/platform/bootWeb';
import { createApp } from 'vue';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import OAuthProvidersApp from './OAuthProvidersApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';
await ensureAuthenticated();
createApp(OAuthProvidersApp).use(i18n).mount('#app');
//# sourceMappingURL=main.js.map