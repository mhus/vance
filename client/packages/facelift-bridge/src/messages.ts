import type { App } from 'vue';

/**
 * The few labels @vance/components reads from the host (useT → `$t`): the
 * close button of a dialog, the buttons of a confirmation. The shell has no
 * language switch — its own texts are English in the templates — so a plain
 * English table fulfils the contract instead of a vue-i18n install.
 */
const MESSAGES: Record<string, string> = {
  'common.close': 'Close',
  'common.cancel': 'Cancel',
  'common.confirm': 'Confirm',
  'common.delete': 'Delete',
  'common.remove': 'Remove',
};

/** Installs `$t` on the app so components rendered by the shell find their labels. */
export function installComponentMessages(app: App): void {
  app.config.globalProperties.$t = (key: string, named?: Record<string, unknown>): string => {
    const text = MESSAGES[key] ?? key;
    if (!named) return text;
    return text.replace(/\{(\w+)\}/g, (match, name: string) =>
      name in named ? String(named[name]) : match);
  };
}
