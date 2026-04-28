import { createApp } from 'vue';
import { ensureAuthenticated } from '@vance/shared';
import RecipesApp from './RecipesApp.vue';
import '@/style/app.css';
await ensureAuthenticated();
createApp(RecipesApp).mount('#app');
//# sourceMappingURL=main.js.map