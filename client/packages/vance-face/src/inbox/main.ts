import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ensureAuthenticated } from '@vance/shared';
import InboxApp from './InboxApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';

await ensureAuthenticated();
createApp(InboxApp).use(i18n).use(createPinia()).mount('#app');
