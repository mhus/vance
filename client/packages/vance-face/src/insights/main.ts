import { createApp } from 'vue';
import { ensureAuthenticated } from '@vance/shared';
import InsightsApp from './InsightsApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';

await ensureAuthenticated();
createApp(InsightsApp).use(i18n).mount('#app');
