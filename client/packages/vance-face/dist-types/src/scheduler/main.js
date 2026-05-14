import '@/platform/bootWeb';
import { createApp } from 'vue';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import SchedulerApp from './SchedulerApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';
await ensureAuthenticated();
createApp(SchedulerApp).use(i18n).mount('#app');
//# sourceMappingURL=main.js.map