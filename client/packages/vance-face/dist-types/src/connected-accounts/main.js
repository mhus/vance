import '@/platform/bootWeb';
import { createApp } from 'vue';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import ConnectedAccountsApp from './ConnectedAccountsApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';
await ensureAuthenticated();
createApp(ConnectedAccountsApp).use(i18n).mount('#app');
//# sourceMappingURL=main.js.map