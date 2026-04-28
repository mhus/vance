import { createApp } from 'vue';
import { ensureAuthenticated } from '@vance/shared';
import SkillsApp from './SkillsApp.vue';
import '@/style/app.css';

await ensureAuthenticated();
createApp(SkillsApp).mount('#app');
