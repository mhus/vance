import '@/platform/bootWeb';
import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import { loadAddonRegistrations } from '@/platform/loadAddonRegistrations';
import { registerBuiltInKinds } from './builtInKinds';
import DocumentApp from './DocumentApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';

await ensureAuthenticated();
// Host built-ins register first so insertion order in the Kind
// registry puts them before any addon contribution — built-ins win
// ties when an addon claims the same id by accident.
registerBuiltInKinds();
// Populate the Kind registry from enabled addons' `./register` exposes
// before the first DocumentApp render — otherwise documents whose kind
// is contributed by an addon would briefly fall through to the "unknown
// kind" path. Best-effort: a failing addon (or unreachable brain) logs
// a warning but doesn't block the editor.
await loadAddonRegistrations();
createApp(DocumentApp).use(i18n).use(createPinia()).mount('#app');
