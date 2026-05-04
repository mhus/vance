import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ensureAuthenticated } from '@vance/shared';
import ChatApp from './ChatApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';

await ensureAuthenticated();
createApp(ChatApp).use(i18n).use(createPinia()).mount('#app');
