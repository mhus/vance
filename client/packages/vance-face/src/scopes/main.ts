import { createApp } from 'vue';
import { ensureAuthenticated } from '@vance/shared';
import ScopesApp from './ScopesApp.vue';
import '@/style/app.css';

await ensureAuthenticated();
createApp(ScopesApp).mount('#app');
