import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ensureAuthenticated } from '@vance/shared';
import DocumentApp from './DocumentApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';

await ensureAuthenticated();
createApp(DocumentApp).use(i18n).use(createPinia()).mount('#app');
