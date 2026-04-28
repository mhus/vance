import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ensureAuthenticated } from '@vance/shared';
import InboxApp from './InboxApp.vue';
import '@/style/app.css';

await ensureAuthenticated();
createApp(InboxApp).use(createPinia()).mount('#app');
