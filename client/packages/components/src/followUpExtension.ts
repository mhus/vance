/**
 * Follow-up suggestion extension for {@code CodeEditor} (CodeMirror 6).
 *
 * <p>On-demand workflow:
 *
 * <ol>
 *   <li>User presses {@code Ctrl+.} (or {@code Cmd+.} on macOS) — the
 *       extension calls the host-provided {@link
 *       FollowUpExtensionOptions.fetch} callback with the current
 *       full document text and cursor offset. {@code Ctrl/Cmd+Space}
 *       was tried first but the OS captures it on macOS (Spotlight /
 *       IME switcher); {@code Mod-.} is free and matches the
 *       VS-Code-style "quick action" semantics.</li>
 *   <li>When the callback resolves with a non-empty string, a CodeMirror
 *       tooltip appears at the cursor showing the suggestion plus a
 *       small accept-hint label.</li>
 *   <li>While the tooltip is visible: {@code Tab} inserts the
 *       suggestion at the cursor and dismisses the tooltip;
 *       {@code Escape} dismisses without inserting. Any document or
 *       selection change also dismisses (the suggestion is anchored
 *       to a stale position then).</li>
 * </ol>
 *
 * <p>The extension is REST-agnostic — the host (e.g. the Web-UI's
 * document editor) injects a {@code fetch} callback that wraps the
 * actual transport. Keeps the shared components package free of
 * tenant / auth concerns.
 */

import { EditorView, keymap, showTooltip, tooltips, type Tooltip } from '@codemirror/view';
import { Prec, StateEffect, StateField, type Extension } from '@codemirror/state';

interface FollowUpState {
  /** Document offset where the suggestion was requested. */
  pos: number;
  /** The suggestion text to insert on accept. */
  suggestion: string;
}

/** Show / hide effect — value {@code null} dismisses the tooltip. */
const setFollowUp = StateEffect.define<FollowUpState | null>();

const followUpField = StateField.define<FollowUpState | null>({
  create: () => null,
  update(value, tr) {
    for (const eff of tr.effects) {
      if (eff.is(setFollowUp)) return eff.value;
    }
    // Any document or selection change invalidates the anchored
    // suggestion — the user moved on and we don't want to insert
    // text at a now-stale position.
    if (tr.docChanged || tr.selection) return null;
    return value;
  },
});

function buildTooltip(
  state: FollowUpState | null,
  acceptHint: string,
): Tooltip | null {
  if (!state) return null;
  return {
    pos: state.pos,
    above: false,
    strictSide: true,
    arrow: false,
    create() {
      const dom = document.createElement('div');
      dom.className = 'cm-followup-tooltip';
      const text = document.createElement('span');
      text.textContent = state.suggestion;
      dom.appendChild(text);
      const hint = document.createElement('span');
      hint.className = 'cm-followup-tooltip-hint';
      hint.textContent = '  ' + acceptHint;
      dom.appendChild(hint);
      return { dom };
    },
  };
}

/** Insert the active suggestion at its anchor and dismiss the tooltip. */
function acceptCommand(view: EditorView): boolean {
  const state = view.state.field(followUpField, false);
  if (!state) return false;
  view.dispatch({
    changes: { from: state.pos, insert: state.suggestion },
    selection: { anchor: state.pos + state.suggestion.length },
    effects: setFollowUp.of(null),
  });
  return true;
}

/** Hide the active tooltip without inserting. */
function dismissCommand(view: EditorView): boolean {
  if (!view.state.field(followUpField, false)) return false;
  view.dispatch({ effects: setFollowUp.of(null) });
  return true;
}

export interface FollowUpExtensionOptions {
  /**
   * Async callback that resolves to a suggestion string (or
   * {@code null} when there is nothing useful). The host wires this
   * to the {@code follow-up} REST endpoint. Errors should be caught
   * inside — the extension treats rejection as "no suggestion".
   *
   * @param text  the full document text at the moment of trigger
   * @param cursor character offset from start
   */
  fetch: (text: string, cursor: number) => Promise<string | null>;
  /**
   * Trailing label shown next to the suggestion (e.g. {@code "↹ Tab"}).
   * Localised by the host — defaults to {@code "Tab"}.
   */
  acceptHint?: string;
}

/**
 * Build the {@link Extension} bundle. The bundle is given high
 * precedence so the {@code Tab} keybinding wins over the default
 * indent / fold mappings while a suggestion is active; when no
 * suggestion is present, our handlers return {@code false} and
 * CodeMirror falls through to the default behaviour.
 */
export function followUpExtension(opts: FollowUpExtensionOptions): Extension {
  const acceptHint = opts.acceptHint ?? 'Tab';
  // Per-extension counter so stale fetch responses (slower than a
  // subsequent trigger or any doc edit) get dropped.
  let pendingSeq = 0;

  const triggerCommand = (view: EditorView): boolean => {
    const seq = ++pendingSeq;
    const text = view.state.doc.toString();
    const cursor = view.state.selection.main.head;
    Promise.resolve()
      .then(() => opts.fetch(text, cursor))
      .then((suggestion) => {
        if (seq !== pendingSeq) return;
        if (!suggestion) {
          // Clear any prior suggestion so the user sees the trigger
          // had no result rather than the stale value.
          view.dispatch({ effects: setFollowUp.of(null) });
          return;
        }
        view.dispatch({
          effects: setFollowUp.of({ pos: cursor, suggestion }),
        });
      })
      .catch(() => {
        if (seq !== pendingSeq) return;
        view.dispatch({ effects: setFollowUp.of(null) });
      });
    return true;
  };

  return [
    followUpField,
    // {@code position: "fixed"} renders the tooltip into the body's
    // top layer; without this the default {@code "absolute"} mode
    // glues it to the editor's offset parent, where overflow:hidden
    // / stacking-context surprises clip it on the documents page.
    tooltips({ position: 'fixed', parent: document.body }),
    showTooltip.compute([followUpField], (state) =>
      buildTooltip(state.field(followUpField), acceptHint),
    ),
    Prec.high(
      keymap.of([
        { key: 'Mod-.', run: triggerCommand, preventDefault: true },
        { key: 'Tab', run: acceptCommand },
        { key: 'Escape', run: dismissCommand },
      ]),
    ),
    // Hard-coded colours rather than {@code hsl(var(--b2))} et al.
    // — CodeMirror lifts the tooltip layer out of the editor's DOM
    // subtree (especially with {@code parent: document.body} above),
    // so DaisyUI's theme variables aren't guaranteed to resolve.
    // The combination of dark background + light foreground reads
    // on both light and dark Vance themes.
    EditorView.theme({
      '.cm-tooltip.cm-followup-tooltip': {
        padding: '6px 10px',
        background: 'rgba(33, 38, 45, 0.97)',
        color: '#f0f6fc',
        border: '1px solid rgba(240, 246, 252, 0.18)',
        borderRadius: '6px',
        boxShadow: '0 4px 14px rgba(0, 0, 0, 0.35)',
        fontFamily: 'system-ui, -apple-system, "Segoe UI", sans-serif',
        fontSize: '0.875rem',
        fontStyle: 'italic',
        maxWidth: '32rem',
        whiteSpace: 'normal',
        lineHeight: '1.4',
        zIndex: '9999',
        pointerEvents: 'none',
      },
      '.cm-followup-tooltip .cm-followup-tooltip-hint': {
        opacity: '0.65',
        fontSize: '0.72rem',
        fontStyle: 'normal',
        marginLeft: '0.5rem',
      },
    }),
  ];
}
