import { createApp } from 'vue';
import { ensureAuthenticated } from '@vance/shared';
import UsersApp from './UsersApp.vue';
import '@/style/app.css';
await ensureAuthenticated();
createApp(UsersApp).mount('#app');
//# sourceMappingURL=main.js.map