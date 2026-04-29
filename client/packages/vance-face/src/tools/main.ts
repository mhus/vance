import { createApp } from 'vue';
import { ensureAuthenticated } from '@vance/shared';
import ToolsApp from './ToolsApp.vue';
import '@/style/app.css';

await ensureAuthenticated();
createApp(ToolsApp).mount('#app');
