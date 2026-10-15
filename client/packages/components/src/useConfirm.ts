import { getCurrentInstance, h, render } from 'vue';
import VConfirmDialog from './VConfirmDialog.vue';

export interface ConfirmOptions {
  /** The question, short — "Delete scheduler?". Optional for messages that already ask. */
  title?: string;
  /** What happens — one or two sentences. */
  message: string;
  /** Label of the confirming button; default "Confirm". Name the action: "Delete", "Archive". */
  confirmLabel?: string;
  cancelLabel?: string;
  /**
   * The action destroys or discards something (default true): the button is
   * a filled danger button. False for reversible actions — a primary button.
   */
  danger?: boolean;
}

export type Confirm = (options: ConfirmOptions) => Promise<boolean>;

/**
 * The CI replacement for {@code window.confirm} (corporate-design.md §6.4).
 * Call in {@code setup()}: the dialog is rendered with the caller's app
 * context, so translations and the theme reach it — also from an addon
 * bundle, whose components live in the host's app.
 *
 * @example
 * const confirm = useConfirm();
 * if (!await confirm({ title: t('x.deleteTitle'), message: t('x.deleteBody'),
 *                      confirmLabel: t('common.delete') })) return;
 */
export function useConfirm(): Confirm {
  const appContext = getCurrentInstance()?.appContext ?? null;
  return (options) => new Promise<boolean>((resolve) => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const vnode = h(VConfirmDialog, {
      ...options,
      onResolve: (confirmed: boolean) => {
        resolve(confirmed);
        // Let the dialog close before it leaves the DOM.
        window.setTimeout(() => {
          render(null, container);
          container.remove();
        }, 0);
      },
    });
    vnode.appContext = appContext;
    render(vnode, container);
  });
}
