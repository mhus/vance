import '@/platform/bootWeb';
import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import SettingsApp from './SettingsApp.vue';
import { registerBuiltInKinds } from '@/document/builtInKinds';
import { i18n } from '@/i18n';
import '@/style/app.css';

await ensureAuthenticated();
// The kind registry carries the settings-doc contributions (settingsProvider);
// the Settings page reads only that metadata — a provider's view components
// stay behind their async imports and are never fetched here.
registerBuiltInKinds();
// MarkdownView (the right-panel help renderer) resolves relative document
// references through a pinia store; without a pinia root its setup throws.
// The store stays empty here — help text belongs to no open document.
createApp(SettingsApp).use(i18n).use(createPinia()).mount('#app');
