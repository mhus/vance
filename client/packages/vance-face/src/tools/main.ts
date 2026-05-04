import { createApp } from 'vue';
import { ensureAuthenticated } from '@vance/shared';
import ToolsApp from './ToolsApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';

await ensureAuthenticated();
createApp(ToolsApp).use(i18n).mount('#app');
