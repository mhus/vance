import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ensureAuthenticated } from '@vance/shared';
import ChatApp from './ChatApp.vue';
import '@/style/app.css';

await ensureAuthenticated();
createApp(ChatApp).use(createPinia()).mount('#app');
