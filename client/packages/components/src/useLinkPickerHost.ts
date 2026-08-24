/**
 * The host half of {@link VLinkPicker}: open it, hand the pick to the editor,
 * close it.
 *
 * The block editor has no server access and no Vance dependency, so inserting a
 * link is a *host* callback (`openLinkPicker`) — the editor asks, the host puts
 * the dialog on screen and applies the result to the editor's current selection.
 * Without a host it falls back to a bare `window.prompt`.
 *
 * That handshake was copied verbatim into six hosts (workbook, wiki, gtd,
 * issues, journal, kanban), comment included — and only one of them carried the
 * `<dialog>` note below, because only there was it learned. This is the same
 * argument that put `useApplicationPicker` here rather than in each picker, one
 * layer up.
 *
 * **Where to render the picker.** Inside whatever native `<dialog>` the host
 * already has open, if it has one. A `<dialog>` opened with `showModal()` lives
 * in the browser's top layer and paints over any fixed-position sibling outside
 * it — a picker mounted next to the dialog is simply invisible (kanban's card
 * detail is the case that found this).
 *
 * **Usage** — destructure, so the template keeps unwrapped names:
 *
 * ```ts
 * const {
 *   isOpen: linkPickerOpen,
 *   initialHref: linkPickerInitialHref,
 *   open: openLinkPicker,
 *   close: closeLinkPicker,
 *   onPicked: onLinkPicked,
 *   onClear: onLinkClear,
 * } = useLinkPickerHost(() => editorRef.value);
 * ```
 */
import { ref, toValue, type MaybeRefOrGetter, type Ref } from 'vue';

/** What this composable needs of a mounted block editor. */
export interface LinkPickerEditor {
  /** Apply a link mark to the current selection. */
  applyLink: (href: string, openInNewTab?: boolean) => void;
  /** Remove the link mark under the cursor. */
  clearLink: () => void;
  /** The href of the link under the cursor, for editing an existing one. */
  currentLinkHref: () => string | null;
}

export interface LinkPickerHost {
  /** Whether the dialog is on screen. */
  isOpen: Ref<boolean>;
  /**
   * The href to pre-fill, when the cursor sits in an existing link. `null` for
   * a fresh one — the picker then opens on its search tab instead of the URL
   * box.
   */
  initialHref: Ref<string | null>;
  /** The editor's `openLinkPicker` callback. */
  open: () => void;
  /** Cancel, from the dialog's close/backdrop. */
  close: () => void;
  /** The dialog's `pick` handler. */
  onPicked: (href: string, openInNewTab: boolean) => void;
  /** The dialog's `clear` handler — "remove this link". */
  onClear: () => void;
}

export function useLinkPickerHost(
  editor: MaybeRefOrGetter<LinkPickerEditor | null | undefined>,
): LinkPickerHost {
  const isOpen = ref(false);
  const initialHref = ref<string | null>(null);

  // Free functions, not methods on the returned object: every host destructures
  // this so the template keeps plain names, and a `this.close()` would then be
  // called with `this === undefined`.
  function open(): void {
    // Read the current link *before* showing the dialog: opening it moves focus
    // out of the editor, and some hosts suppress the floating toolbar while it
    // is up.
    initialHref.value = toValue(editor)?.currentLinkHref() ?? null;
    isOpen.value = true;
  }

  function close(): void {
    isOpen.value = false;
    initialHref.value = null;
  }

  function onPicked(href: string, openInNewTab: boolean): void {
    toValue(editor)?.applyLink(href, openInNewTab);
    close();
  }

  function onClear(): void {
    toValue(editor)?.clearLink();
    close();
  }

  return { isOpen, initialHref, open, close, onPicked, onClear };
}
