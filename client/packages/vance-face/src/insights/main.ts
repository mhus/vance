import { createApp } from 'vue';
import { ensureAuthenticated } from '@vance/shared';
import InsightsApp from './InsightsApp.vue';
import '@/style/app.css';

await ensureAuthenticated();
createApp(InsightsApp).mount('#app');
